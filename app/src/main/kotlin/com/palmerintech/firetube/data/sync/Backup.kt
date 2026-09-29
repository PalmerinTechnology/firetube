package com.palmerintech.firetube.data.sync

import com.palmerintech.firetube.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/** Export / import the library as a JSON file — works with no account at all. */
class Backup(private val library: LibraryRepository) {

    @Serializable
    private data class File(val format: String = FORMAT, val version: Int = 1, val playlists: List<PlaylistSnapshot>)

    suspend fun export(out: OutputStream) {
        val file = File(playlists = library.allSnapshots().filterNot { it.deleted })
        withContext(Dispatchers.IO) { out.use { it.write(json.encodeToString(File.serializer(), file).encodeToByteArray()) } }
    }

    /** Merges a backup into the library (newer copy of each playlist wins). Returns how many playlists it held. */
    suspend fun import(input: InputStream): Int {
        val text = withContext(Dispatchers.IO) { input.use { it.readBytes().decodeToString() } }
        val file = json.decodeFromString(File.serializer(), text)
        require(file.format == FORMAT) { "Not a FireTube backup" }
        library.mergeSnapshots(file.playlists)
        return file.playlists.size
    }

    private companion object {
        const val FORMAT = "firetube-backup"
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}
