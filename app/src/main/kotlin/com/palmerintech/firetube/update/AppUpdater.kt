package com.palmerintech.firetube.update

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val notes: String,
    val downloadUrl: String,
)

/**
 * Keeps sideloaded installs current. The github flavor checks GitHub Releases and installs the
 * APK in place; the amazon flavor is a no-op because the Appstore handles updates.
 * Each flavor provides `createUpdater(...)`.
 */
interface AppUpdater {
    val supported: Boolean

    /** The newer release, or null when up to date (or on failure). */
    suspend fun check(): UpdateInfo?

    /** Downloads and hands the APK to the system installer. [onProgress] gets 0f..1f. */
    suspend fun install(update: UpdateInfo, onProgress: (Float) -> Unit)
}
