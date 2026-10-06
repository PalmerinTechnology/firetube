package com.palmerintech.firetube.player

import com.palmerintech.firetube.extractor.Chapter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * Chapters of downloaded songs, one small JSON file per track id in [dir], so a download played
 * after a restart (never resolved, so [StreamResolver] has no chapters for it) still has them.
 * [Downloads] keeps the files in step with the download index. Blocking: call off the main thread.
 */
class ChapterStore(private val dir: File) {

    @Serializable
    private data class Stored(val title: String, val startMs: Long)

    fun load(trackId: String): List<Chapter>? {
        val file = fileOf(trackId) ?: return null
        if (file.length() == 0L) return null
        return runCatching { json.decodeFromString<List<Stored>>(file.readText()).map { Chapter(it.title, it.startMs) } }
            .onFailure { Timber.w(it, "Couldn't read chapters of %s", trackId) }
            .getOrNull()
    }

    // An empty file (left by a power cut before its data reached the disk) counts as none, so it's rewritten.
    fun has(trackId: String) = (fileOf(trackId)?.length() ?: 0L) > 0L

    /** Written to a temporary file, synced, and renamed, so a crash can't leave a half-written one. */
    fun save(trackId: String, chapters: List<Chapter>) {
        val file = fileOf(trackId) ?: return
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "${file.name}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(chapters.map { Stored(it.title, it.startMs) }).toByteArray())
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                check(tmp.renameTo(file)) { "rename failed" }
            }
        }.onFailure { Timber.w(it, "Couldn't save chapters of %s", trackId) }
    }

    fun delete(trackId: String) {
        fileOf(trackId)?.delete()
    }

    /** Track ids with chapters stored. */
    fun ids(): Set<String> =
        dir.listFiles().orEmpty().map { it.name }.filter { it.endsWith(SUFFIX) }.map { it.removeSuffix(SUFFIX) }.toSet()

    /**
     * Brings the store in line with [downloads] (track ids in the download index): saves chapters
     * [known] for a download that has none stored yet, and deletes those of removed downloads.
     */
    fun keepFor(downloads: Set<String>, known: (String) -> List<Chapter>?) {
        for (id in downloads) {
            if (!has(id)) known(id)?.takeIf { it.isNotEmpty() }?.let { save(id, it) }
        }
        (ids() - downloads).forEach(::delete)
    }

    /** Null for anything that isn't a plain id, so it can't name a file outside [dir]. */
    private fun fileOf(trackId: String) = if (ID.matches(trackId)) File(dir, trackId + SUFFIX) else null

    private companion object {
        const val SUFFIX = ".json"
        val ID = Regex("[A-Za-z0-9_-]{1,64}")
        val json = Json { ignoreUnknownKeys = true }
    }
}
