package com.palmerintech.firetube

import android.app.Application
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.palmerintech.firetube.data.legacy.LegacyImport
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

@UnstableApi
class FireTubeApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        container = AppContainer(this)
        container.sync.start()
        // Crash reports follow the user's setting (Settings > Privacy).
        if (container.firebaseConfigured) {
            container.appScope.launch {
                container.settings.settings.map { it.crashReports }.distinctUntilChanged().collect { enabled ->
                    FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = enabled
                }
            }
        }
        // Bring over playlists and history left behind by FireTube 1.x (once).
        container.appScope.launch { runCatching { LegacyImport(this@FireTubeApp, container.library).runOnce() } }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.httpClient })) }
        .crossfade(true)
        .build()
}
