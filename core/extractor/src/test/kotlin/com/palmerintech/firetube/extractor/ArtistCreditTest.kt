package com.palmerintech.firetube.extractor

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistCreditTest {

    @Test
    fun collaborationsSplitIntoFirstArtistAndCount() {
        assertEquals(ArtistCredit("Shakira", 2), ArtistCredit.parse("Shakira and 2 more"))
        assertEquals(ArtistCredit("Bad Bunny", 1), ArtistCredit.parse(" Bad Bunny and 1 more "))
        assertEquals(ArtistCredit("Maroon 5", 3), ArtistCredit.parse("Maroon 5 and 3 more"))
        // "and" inside the first name stays there.
        assertEquals(ArtistCredit("Earth, Wind and Fire", 2), ArtistCredit.parse("Earth, Wind and Fire and 2 more"))
    }

    @Test
    fun otherNamesAreKeptWhole() {
        for (name in listOf("Simon and Garfunkel", "Florence and the Machine", "Adele", "", "and 2 more", "Shakira and 0 more", "Shakira and many more")) {
            assertEquals(name, ArtistCredit(name), ArtistCredit.parse(name))
        }
    }

    @Test
    fun trackExposesItsCredit() {
        val track = Track("id", "Song", "Shakira and 2 more", 200, null)
        assertEquals(ArtistCredit("Shakira", 2), track.credit)
        // What's stored doesn't change.
        assertEquals("Shakira and 2 more", track.artist)
    }
}
