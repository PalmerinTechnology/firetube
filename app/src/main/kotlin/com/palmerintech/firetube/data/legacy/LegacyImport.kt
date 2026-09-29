package com.palmerintech.firetube.data.legacy

import android.content.Context
import com.palmerintech.firetube.data.LibraryRepository
import com.palmerintech.firetube.extractor.NewPipeStreamSource
import com.palmerintech.firetube.extractor.Track
import com.palmerintech.firetube.models.Playlist
import com.palmerintech.firetube.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InvalidClassException
import java.io.ObjectInputStream
import java.io.ObjectStreamClass

/**
 * One-time import of what FireTube 1.x left on the device, so upgrading keeps people's music:
 * its cached playlists (`savedPlaylists`) and recently played list (`recent`) in the `ftPrefs`
 * SharedPreferences, both Java-serialized and hex-encoded by 1.x's ObjectSerializer.
 * (1.x's server-side playlists are gone with its backend; only the local cache survives.)
 */
class LegacyImport(private val context: Context, private val library: LibraryRepository) {

    data class Result(val playlists: Int, val recent: Int)

    /**
     * Runs once per install; later calls return null. Safe to re-run after a crash midway:
     * playlists get deterministic ids (already-imported ones are skipped) and history is
     * imported under its own flag.
     */
    suspend fun runOnce(): Result? = withContext(Dispatchers.IO) {
        val state = context.getSharedPreferences("legacy_import", Context.MODE_PRIVATE)
        if (state.getBoolean(KEY_DONE, false)) return@withContext null
        val old = context.getSharedPreferences("ftPrefs", Context.MODE_PRIVATE)

        // Ids are stable across re-runs on this install but unique per install, so two devices'
        // imported playlists never collide once they sync.
        val seed = state.getString(KEY_SEED, null) ?: java.util.UUID.randomUUID().toString().take(8).also {
            state.edit().putString(KEY_SEED, it).commit()
        }
        val playlists = parsePlaylists(old.getString("savedPlaylists", null))
        playlists.forEachIndexed { index, (name, tracks) ->
            val id = "legacy-$seed-$index"
            if (library.playlistEntity(id) == null) library.createPlaylist(name, tracks, id = id)
        }

        val recent = parseVideos(old.getString("recent", null))
        if (!state.getBoolean(KEY_HISTORY_DONE, false)) {
            // Flag first: a crash midway loses the rest of the history rather than duplicating it.
            state.edit().putBoolean(KEY_HISTORY_DONE, true).commit()
            // 1.x kept the newest first. Give each play its own timestamp so the order survives.
            val start = System.currentTimeMillis() - recent.size * 1000L
            recent.asReversed().forEachIndexed { i, track -> library.recordPlay(track, at = start + i * 1000L) }
        }

        state.edit().putBoolean(KEY_DONE, true).commit()
        if (playlists.isNotEmpty() || recent.isNotEmpty()) {
            Timber.i("Imported %d playlists and %d recent songs from 1.x", playlists.size, recent.size)
        }
        Result(playlists.size, recent.size)
    }

    companion object {
        private const val KEY_DONE = "done"
        private const val KEY_HISTORY_DONE = "history_done"
        private const val KEY_SEED = "seed"

        /** 1.x playlists as (name, tracks). Anything unreadable is skipped, never thrown. */
        fun parsePlaylists(encoded: String?): List<Pair<String, List<Track>>> =
            (deserialize(encoded) as? List<*>).orEmpty().filterIsInstance<Playlist>().mapNotNull { p ->
                val tracks = p.videos.orEmpty().mapNotNull { it.toTrack() }.distinctBy { it.id }
                val name = p.title?.trim().orEmpty().ifEmpty { "Imported playlist" }
                if (tracks.isEmpty()) null else name to tracks
            }

        fun parseVideos(encoded: String?): List<Track> =
            (deserialize(encoded) as? List<*>).orEmpty().filterIsInstance<Video>().mapNotNull { it.toTrack() }.distinctBy { it.id }

        private fun Video.toTrack(): Track? {
            val raw = yid?.trim().orEmpty()
            val id = NewPipeStreamSource.videoId(raw) ?: raw.takeIf { VIDEO_ID.matches(it) } ?: return null
            return Track(id, title?.trim().orEmpty().ifEmpty { "Unknown" }, "", duration.toLong().coerceAtLeast(0), thumbUrl)
        }

        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

        /** 1.x's ObjectSerializer: each byte as two letters 'a'..'p' (high nibble first). */
        internal fun decodeHex(str: String): ByteArray? {
            if (str.length % 2 != 0) return null
            return ByteArray(str.length / 2) { i ->
                val hi = str[2 * i] - 'a'
                val lo = str[2 * i + 1] - 'a'
                if (hi !in 0..15 || lo !in 0..15) return null
                ((hi shl 4) or lo).toByte()
            }
        }

        private fun deserialize(encoded: String?): Any? {
            if (encoded.isNullOrEmpty()) return null
            val bytes = decodeHex(encoded) ?: return null
            return try {
                AllowListObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
            } catch (e: Exception) {
                Timber.w(e, "Couldn't read 1.x data")
                null
            }
        }
    }

    /** Only lets the 1.x model classes (and ArrayList) be deserialized. */
    private class AllowListObjectInputStream(input: InputStream) : ObjectInputStream(input) {
        override fun resolveClass(desc: ObjectStreamClass): Class<*> {
            if (desc.name !in ALLOWED) throw InvalidClassException(desc.name, "Not allowed in 1.x import")
            return super.resolveClass(desc)
        }

        companion object {
            val ALLOWED = setOf("java.util.ArrayList", Playlist::class.java.name, Video::class.java.name)
        }
    }
}
