package com.palmerintech.firetube.ui

import android.Manifest
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.media3.common.util.UnstableApi
import com.palmerintech.firetube.FireTubeApp
import com.palmerintech.firetube.data.UserSettings
import com.palmerintech.firetube.ui.theme.FireTubeTheme

@UnstableApi
class MainActivity : ComponentActivity() {
    private val container by lazy { (application as FireTubeApp).container }
    private val pendingLink = mutableStateOf<String?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        container.player.connect()
        if (savedInstanceState == null) pendingLink.value = linkFrom(intent)

        // Media notifications need this on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val settings by container.settings.settings.collectAsState(UserSettings())
            FireTubeTheme(settings.themeMode, settings.dynamicColor) {
                FireTubeRoot(container, pendingLink.value, onLinkHandled = { handled ->
                    // Only clear it if a newer link hasn't replaced it meanwhile.
                    if (pendingLink.value == handled) pendingLink.value = null
                })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { pendingLink.value = it }
    }

    private fun linkFrom(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
            Regex("""https?://\S+""").find(text)?.value
        }
        Intent.ACTION_VIEW -> intent.dataString
        // "Play <song> on FireTube" from Assistant / Android Auto.
        MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH ->
            intent.getStringExtra(SearchManager.QUERY)?.takeIf { it.isNotBlank() }?.let { SEARCH_PREFIX + it }
        // Tapping the song on the home-screen widget.
        ACTION_OPEN_PLAYER -> OPEN_PLAYER
        else -> null
    }

    companion object {
        /** Marks a pending "link" that is really a voice search query. */
        const val SEARCH_PREFIX = "search:"

        /** Opens Now Playing (from the widget). */
        const val ACTION_OPEN_PLAYER = "com.palmerintech.firetube.action.OPEN_PLAYER"

        /** The pending "link" for [ACTION_OPEN_PLAYER]. */
        const val OPEN_PLAYER = "player:"
    }
}
