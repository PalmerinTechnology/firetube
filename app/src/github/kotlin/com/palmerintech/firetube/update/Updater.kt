package com.palmerintech.firetube.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.IntentCompat
import com.palmerintech.firetube.BuildConfig
import com.palmerintech.firetube.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

fun createUpdater(context: Context, client: OkHttpClient): AppUpdater = GitHubUpdater(context, client)

/**
 * Updates from the latest release of the public releases repo. Each release carries an APK named
 * `FireTube-<versionName>-<versionCode>.apk`; the version code in the name is what's compared.
 */
private class GitHubUpdater(private val context: Context, private val client: OkHttpClient) : AppUpdater {
    override val supported = true

    override suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(LATEST_RELEASE).header("Accept", "application/vnd.github+json").build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val release = json.decodeFromString(Release.serializer(), resp.body.string())
                val asset = release.assets.firstOrNull { APK_NAME.matches(it.name) } ?: return@withContext null
                val (name, code) = APK_NAME.find(asset.name)!!.destructured
                UpdateInfo(name, code.toInt(), release.body.orEmpty(), asset.browserDownloadUrl)
                    .takeIf { it.versionCode > BuildConfig.VERSION_CODE }
            }
        } catch (e: Exception) {
            Timber.w(e, "Update check failed")
            null
        }
    }

    override suspend fun install(update: UpdateInfo, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Lets the update go through without a prompt when FireTube installed the current version itself.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            writeAndCommit(installer, sessionId, update, onProgress)
        } catch (e: Exception) {
            // Don't leak half-written sessions; the system caps how many an app may hold.
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    private fun writeAndCommit(installer: PackageInstaller, sessionId: Int, update: UpdateInfo, onProgress: (Float) -> Unit) {
        installer.openSession(sessionId).use { session ->
            client.newCall(Request.Builder().url(update.downloadUrl).build()).execute().use { resp ->
                check(resp.isSuccessful) { "Download failed: HTTP ${resp.code}" }
                val total = resp.body.contentLength()
                session.openWrite("base.apk", 0, total).use { out ->
                    resp.body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                    session.fsync(out)
                }
            }
            val callback = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    @Serializable
    private data class Release(
        @kotlinx.serialization.SerialName("tag_name") val tagName: String,
        val body: String? = null,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String,
        @kotlinx.serialization.SerialName("browser_download_url") val browserDownloadUrl: String,
    )

    companion object {
        const val LATEST_RELEASE = "https://api.github.com/repos/spalmerin21/firetube/releases/latest"
        val APK_NAME = Regex("""FireTube-([0-9.]+)-(\d+)\.apk""")
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Shows the system "Update FireTube?" prompt when the installer asks for confirmation. Android
 * blocks activity starts from the background, so the prompt is also offered as a notification.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                notifyConfirm(context, confirm)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                NotificationManagerCompat.from(context).cancel(UPDATE_NOTIFICATION_ID)
                Timber.i("Update installed")
            }
            else -> {
                NotificationManagerCompat.from(context).cancel(UPDATE_NOTIFICATION_ID)
                Timber.w("Update failed (%d): %s", status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            }
        }
    }
}

private fun notifyConfirm(context: Context, confirm: Intent) {
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return
    manager.createNotificationChannel(
        NotificationChannelCompat.Builder(UPDATE_CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH).setName("App updates").build(),
    )
    val tap = PendingIntent.getActivity(context, 0, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("FireTube update ready")
        .setContentText("Tap to finish installing")
        .setContentIntent(tap)
        .setAutoCancel(true)
        .build()
    @Suppress("MissingPermission") // checked by areNotificationsEnabled()
    manager.notify(UPDATE_NOTIFICATION_ID, notification)
}

private const val UPDATE_CHANNEL = "updates"
private const val UPDATE_NOTIFICATION_ID = 3
