package com.palmerintech.firetube.player

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PlaybackResumptionTest {

    private fun saved(index: Int, vararg ids: String) =
        QueueStore.Saved(ids.map { QueueStore.SavedTrack(it, "Title $it", "Artist", 200, null) }, index, positionMs = 42_000)

    @Test
    fun resumesTheSavedQueueWhereItLeftOff() {
        val resumed = LibraryCallback.resumptionOf(saved(1, "a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), resumed.mediaItems.map { it.mediaId })
        assertEquals(1, resumed.startIndex)
        assertEquals(42_000L, resumed.startPositionMs)
        // Playable as-is: the play URI and metadata are there.
        assertEquals(MediaItems.uriOf("b"), resumed.mediaItems[1].localConfiguration?.uri)
        assertEquals("Title b", resumed.mediaItems[1].mediaMetadata.title)
    }

    @Test
    fun clampsAnOutOfRangeIndex() {
        assertEquals(1, LibraryCallback.resumptionOf(saved(5, "a", "b")).startIndex)
        assertEquals(0, LibraryCallback.resumptionOf(saved(-3, "a", "b")).startIndex)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun nothingSaved() {
        LibraryCallback.resumptionOf(null)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun emptySavedQueue() {
        LibraryCallback.resumptionOf(saved(0))
    }
}
