package com.palmerintech.firetube.player.cast

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.palmerintech.firetube.player.StreamResolver
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Streams FireTube tracks from the phone to a Chromecast.
 *
 * Cast queues need URLs up front, but YouTube stream URLs are resolved just in time, expire,
 * and are bound to the IP that resolved them — which a Chromecast won't share on dual-stack or
 * VPN setups. So the phone proxies: the queue holds `http://<phone>:<port>/<token>/track/<id>`,
 * and each request (Range requests included) is fetched from googlevideo by the phone and piped
 * through. Audio is ~128 kbps, so the cost is small.
 *
 * Live streams are HLS: `…/live/<id>.m3u8` serves the audio rendition's playlist with each
 * segment rewritten to `…/live/<id>/<sequence>.aac`, which the phone fetches in turn, so the
 * Chromecast never talks to googlevideo itself.
 *
 * Locked down for a shared network: a random per-run token in every URL, only ids that are in
 * the cast queue, a small connection limit, bounded request parsing, and the server only runs
 * while casting ([stop] when playback returns to the phone).
 */
class CastProxyServer(
    private val context: Context,
    private val resolver: StreamResolver,
    private val client: OkHttpClient,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(MAX_CONNECTIONS)
    private val scope = CoroutineScope(
        SupervisorJob() + io + CoroutineExceptionHandler { _, e -> Timber.w(e, "Cast proxy error") },
    )
    private val clients: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    /** The LAN address the server is bound to; re-checked only when the server (re)starts. */
    @Volatile private var boundAddress: InetAddress? = null
    private var lastAddressCheck = 0L

    /** Last value given to [setStreaming], so a server restart can restore the locks. */
    private var streaming = false
    private val active = AtomicInteger()
    private val allowed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Per live stream: the master playlist URL last resolved, and its audio rendition's playlist URL. */
    private val liveAudio = ConcurrentHashMap<String, Pair<String, String>>()

    /** Per live stream: googlevideo URLs of the segments in the playlist last served, by sequence number. */
    private val liveSegments = ConcurrentHashMap<String, Map<Long, String>>()

    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    @Volatile private var token = ""
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** URL the Chromecast should load for [trackId], starting the server if needed. Null when not on Wi-Fi/Ethernet. */
    @Synchronized
    fun urlFor(trackId: String): String? {
        // Reuse the running server unless it died or the phone's LAN address changed. The address
        // lookup is several binder calls, so it's re-checked at most every 30 s, not per queue item.
        val now = System.currentTimeMillis()
        if (server != null && now - lastAddressCheck > ADDRESS_RECHECK_MS) {
            lastAddressCheck = now
            // A null address is usually a momentary Wi-Fi blip, not a move; only restart on a real change.
            lanAddress()?.let { if (it != boundAddress) shutdown() }
        }
        val running = server?.takeIf { !it.isClosed }
        val socket = running ?: (lanAddress()?.let(::start) ?: return null)
        val address = boundAddress ?: return null
        allowed += trackId
        return "http://${address.hostAddress}:${socket.localPort}/$token/track/$trackId"
    }

    /** Like [urlFor], for a live stream: its audio as an HLS playlist. */
    fun liveUrlFor(trackId: String): String? =
        urlFor(trackId)?.replace("/track/$trackId", "/live/$trackId.m3u8")

    /**
     * Hold the CPU and Wi-Fi awake only while the Chromecast is actually playing, not for a
     * paused session left connected overnight.
     */
    @Synchronized
    @SuppressLint("WakelockTimeout") // released when playback pauses or casting ends
    fun setStreaming(streaming: Boolean) {
        this.streaming = streaming
        if (streaming && server != null) {
            wakeLock?.takeIf { !it.isHeld }?.acquire()
            wifiLock?.takeIf { !it.isHeld }?.acquire()
        } else {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        }
    }

    /** Stops serving (casting ended). Safe to call repeatedly. */
    @Synchronized
    fun stop() {
        streaming = false
        shutdown()
    }

    /** Tears the server down but remembers [streaming], for restarts. */
    private fun shutdown() {
        acceptJob?.cancel()
        runCatching { server?.close() }
        server = null
        boundAddress = null
        allowed.clear()
        liveAudio.clear()
        liveSegments.clear()
        // Unblock handlers stuck writing to a receiver that vanished without closing the connection.
        clients.toList().forEach { runCatching { it.close() } }
        clients.clear()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
    }

    fun release() {
        stop()
        scope.cancel()
    }

    @SuppressLint("WakelockTimeout") // released by setStreaming(false) / stop()
    private fun start(address: InetAddress): ServerSocket? {
        shutdown()
        return try {
            val socket = ServerSocket().apply { bind(InetSocketAddress(address, 0)) }
            server = socket
            boundAddress = address
            lastAddressCheck = System.currentTimeMillis()
            token = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
            // The phone keeps streaming to the Chromecast with its screen off.
            wakeLock = context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FireTube:cast")
            wifiLock = context.applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FireTube:cast")
            // Restarted mid-cast (address change / dead accept loop): keep the locks as they were.
            if (streaming) setStreaming(true)
            acceptJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val client = try {
                        socket.accept()
                    } catch (_: IOException) {
                        // Make isClosed tell the truth so urlFor starts a fresh server next time.
                        runCatching { socket.close() }
                        break
                    }
                    if (active.incrementAndGet() > MAX_CONNECTIONS) {
                        active.decrementAndGet()
                        runCatching { client.close() }
                        continue
                    }
                    clients += client
                    scope.launch {
                        try {
                            handle(client)
                        } catch (e: IOException) {
                            // The receiver skipped/seeked (connection dropped), timed out, or upstream stalled: normal.
                            Timber.d(e, "Cast client gone")
                        } finally {
                            clients -= client
                            active.decrementAndGet()
                        }
                    }
                }
            }
            Timber.i("Cast proxy on %s:%d", address.hostAddress, socket.localPort)
            socket
        } catch (e: IOException) {
            Timber.w(e, "Couldn't start cast proxy")
            null
        }
    }

    private fun handle(client: Socket) = client.use { socket ->
        socket.soTimeout = 15_000
        val input = socket.getInputStream()
        val out = socket.getOutputStream()
        val request = readRequest(input) ?: return@use out.status(400, "Bad Request")
        val route = PATH.matchEntire(request.path)?.takeIf { it.groupValues[1] == token }?.groupValues
        val id = route?.get(3)
        val (kind, suffix, seq) = Triple(route?.get(2), route?.get(4).orEmpty(), route?.get(5).orEmpty())
        when {
            request.method != "GET" && request.method != "HEAD" -> out.status(405, "Method Not Allowed")
            id == null || id !in allowed -> out.status(404, "Not Found")
            kind == "track" && suffix.isEmpty() -> proxy(id, request, out)
            kind == "live" && suffix == ".m3u8" -> livePlaylist(id, request, out)
            kind == "live" && seq.isNotEmpty() -> liveSegment(id, seq.toLong(), request, out)
            else -> out.status(404, "Not Found")
        }
    }

    /** The live stream's audio playlist, its segments pointed back at this server. */
    private fun livePlaylist(id: String, request: HttpRequest, out: OutputStream) {
        val media = try {
            fetchLiveMedia(id, forceRefresh = false) ?: fetchLiveMedia(id, forceRefresh = true)
        } catch (e: Exception) {
            Timber.w(e, "Cast: couldn't load live stream %s", id)
            null
        } ?: return out.status(502, "Bad Gateway")
        // Relative to the playlist's own URL: …/live/<id>.m3u8 → …/live/<id>/<seq>.aac
        val (playlist, segments) = LivePlaylist.rewrite(media.first, base = media.second) { seq -> "$id/$seq.aac" }
        liveSegments[id] = segments
        val body = playlist.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        if (request.method == "GET") out.write(body)
        out.flush()
    }

    /** The audio rendition's playlist for [id] and its URL; null when YouTube refused a (probably expired) URL. */
    private fun fetchLiveMedia(id: String, forceRefresh: Boolean): Pair<String, String>? {
        val resolved = runBlocking { resolver.resolve(id, forceRefresh) }
        // The broadcast ended: the id now plays as an ordinary video, which isn't a playlist.
        if (!resolved.live) throw IOException("$id is no longer live")
        val master = resolved.url
        val audio = liveAudio[id]?.takeIf { it.first == master }?.second ?: run {
            val text = getPlaylist(master) ?: return null
            (LivePlaylist.audioPlaylistUrl(text) ?: throw IOException("No audio rendition for $id"))
                .also { liveAudio[id] = master to it }
        }
        val media = getPlaylist(audio) ?: return null
        return media to audio
    }

    /**
     * A playlist's text; null when YouTube refused the URL (expired, revoked, or a restarted
     * broadcast's old manifest), an IOException on other failures or anything too big for one.
     */
    private fun getPlaylist(url: String): String? = client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
        when {
            resp.isSuccessful -> {
                val source = resp.body.source()
                if (!source.request(MAX_PLAYLIST_BYTES + 1)) source.buffer.readUtf8()
                else throw IOException("Playlist over $MAX_PLAYLIST_BYTES bytes")
            }
            resp.code == 403 || resp.code == 404 || resp.code == 410 -> null
            else -> throw IOException("HTTP ${resp.code}")
        }
    }

    private fun liveSegment(id: String, seq: Long, request: HttpRequest, out: OutputStream) {
        val url = liveSegments[id]?.get(seq) ?: return out.status(404, "Not Found")
        val upstream = try {
            client.newCall(Request.Builder().url(url).method(request.method, null).build()).execute()
        } catch (e: Exception) {
            Timber.w(e, "Cast: couldn't fetch segment %d of %s", seq, id)
            return out.status(502, "Bad Gateway")
        }
        pipe(upstream, request, out)
    }

    private fun proxy(id: String, request: HttpRequest, out: OutputStream) {
        val upstream = try {
            fetch(id, request, forceRefresh = false).let { resp ->
                if (resp.code == 403 || resp.code == 410) {
                    resp.close()
                    fetch(id, request, forceRefresh = true) // expired/revoked URL: resolve again once
                } else resp
            }
        } catch (e: Exception) {
            Timber.w(e, "Cast: couldn't stream %s", id)
            return out.status(502, "Bad Gateway")
        }
        pipe(upstream, request, out)
    }

    /** Sends [upstream]'s status, the headers a receiver needs, and (for GET) its body. */
    private fun pipe(upstream: Response, request: HttpRequest, out: OutputStream) {
        upstream.use { resp ->
            val header = buildString {
                append("HTTP/1.1 ${resp.code} ${resp.message.ifEmpty { "OK" }}\r\n")
                for (name in FORWARDED_HEADERS) resp.header(name)?.let { append("$name: $it\r\n") }
                append("Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n")
            }
            out.write(header.toByteArray(Charsets.ISO_8859_1))
            if (request.method == "GET") resp.body.byteStream().use { it.copyTo(out, 32 * 1024) }
            out.flush()
        }
    }

    private fun fetch(id: String, request: HttpRequest, forceRefresh: Boolean): Response {
        val resolved = runBlocking { resolver.resolve(id, forceRefresh) }
        if (resolved.live) throw IOException("$id is a live stream")
        val url = resolved.url
        val builder = Request.Builder().url(url).method(request.method, null)
        request.range?.let { builder.header("Range", it) }
        return client.newCall(builder.build()).execute()
    }

    private data class HttpRequest(val method: String, val path: String, val range: String?)

    /** Reads the request line and headers, refusing anything oversized. */
    private fun readRequest(input: InputStream): HttpRequest? {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (++total > MAX_REQUEST_BYTES) return null
            when (b) {
                '\n'.code -> {
                    val text = line.toString().trimEnd('\r')
                    if (text.isEmpty()) break
                    lines += text
                    line.clear()
                }
                else -> line.append(b.toChar())
            }
        }
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 2) return null
        val range = lines.drop(1).firstOrNull { it.startsWith("range:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.takeIf { RANGE.matches(it) }
        return HttpRequest(parts[0], parts[1], range)
    }

    private fun OutputStream.status(code: Int, text: String) {
        runCatching { write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); flush() }
    }

    /** The phone's IPv4 address on Wi-Fi or Ethernet (not a VPN or mobile data). */
    private fun lanAddress(): InetAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION") // allNetworks: we need the LAN, not necessarily the default network
        val lan = cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        } ?: return null
        return cm.getLinkProperties(lan)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
    }

    private companion object {
        const val MAX_CONNECTIONS = 4
        const val ADDRESS_RECHECK_MS = 30_000L
        const val MAX_REQUEST_BYTES = 8 * 1024

        /** YouTube's hour-long live playlists run to about 1 MB. */
        const val MAX_PLAYLIST_BYTES = 8L * 1024 * 1024
        /** `/<token>/track/<id>`, `/<token>/live/<id>.m3u8` or `/<token>/live/<id>/<sequence>.aac`. */
        val PATH = Regex("/([0-9a-f]{32})/(track|live)/([A-Za-z0-9_-]{11})(\\.m3u8|/(\\d{1,15})\\.aac)?(?:\\?.*)?")
        val RANGE = Regex("bytes=\\d*-\\d*")
        val FORWARDED_HEADERS = listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges")
    }
}
