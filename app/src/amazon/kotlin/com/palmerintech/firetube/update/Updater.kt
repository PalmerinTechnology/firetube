package com.palmerintech.firetube.update

import android.content.Context
import okhttp3.OkHttpClient

/** Amazon Appstore builds are updated by the store. */
fun createUpdater(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") client: OkHttpClient): AppUpdater =
    object : AppUpdater {
        override val supported = false
        override suspend fun check(): UpdateInfo? = null
        override suspend fun install(update: UpdateInfo, onProgress: (Float) -> Unit) = Unit
    }
