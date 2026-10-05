package com.palmerintech.firetube.player

import com.palmerintech.firetube.extractor.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChapterStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val mix = listOf(Chapter("Intro", 0), Chapter("", 60_000), Chapter("Outro", 120_000))
    private val dir get() = File(tmp.root, "chapters")
    private val store get() = ChapterStore(dir)

    @Test
    fun savedChaptersSurviveANewInstance() {
        store.save("mix", mix)
        // Another instance, as after an app restart.
        assertEquals(mix, ChapterStore(dir).load("mix"))
        assertTrue(store.has("mix"))
        assertEquals(setOf("mix"), store.ids())
        assertNull(store.load("other"))
    }

    @Test
    fun deleteRemovesThem() {
        store.save("mix", mix)
        store.delete("mix")
        assertNull(store.load("mix"))
        assertFalse(store.has("mix"))
        assertEquals(emptySet<String>(), store.ids())
    }

    @Test
    fun aCorruptFileReadsAsNone() {
        dir.mkdirs()
        File(dir, "mix.json").writeText("{not json")
        assertNull(store.load("mix"))
    }

    @Test
    fun idsThatArentPlainNamesAreIgnored() {
        store.save("../escape", mix)
        assertFalse(File(tmp.root, "escape.json").exists())
        assertNull(store.load("../escape"))
    }

    @Test
    fun keepForSavesNewDownloadsAndDropsRemovedOnes() {
        val s = store
        s.save("removed", mix)
        val known = mapOf("a" to mix, "b" to emptyList<Chapter>())
        s.keepFor(setOf("a", "b", "c")) { known[it] }
        assertEquals(mix, s.load("a"))
        // No chapters known (or none at all): nothing written.
        assertFalse(s.has("b"))
        assertFalse(s.has("c"))
        // No longer downloaded.
        assertFalse(s.has("removed"))
    }

    @Test
    fun keepForLeavesWhatsStored() {
        val s = store
        s.save("a", mix)
        s.keepFor(setOf("a")) { listOf(Chapter("Other", 0), Chapter("List", 1_000)) }
        assertEquals(mix, s.load("a"))
    }
}
