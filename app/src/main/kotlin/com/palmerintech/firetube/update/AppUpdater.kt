package com.palmerintech.firetube.update

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val notes: String,
    val downloadUrl: String,
)

/** Keeps installs current: checks GitHub Releases and installs the new APK in place ([createUpdater]). */
interface AppUpdater {
    val supported: Boolean

    /** The newer release, or null when up to date (or on failure). */
    suspend fun check(): UpdateInfo?

    /** Downloads and hands the APK to the system installer. [onProgress] gets 0f..1f. */
    suspend fun install(update: UpdateInfo, onProgress: (Float) -> Unit)
}
