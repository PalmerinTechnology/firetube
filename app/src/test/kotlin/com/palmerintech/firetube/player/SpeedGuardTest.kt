package com.palmerintech.firetube.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedGuardTest {
    private fun guard(setting: Float = 1.5f) = SpeedGuard().apply { reset(setting) }

    @Test
    fun sendsTheSettingOnceAndStopsWhenTaken() {
        val g = guard()
        assertEquals(1.5f, g.next(reported = 1f, canSet = true))
        assertNull(g.next(reported = 1.5f, canSet = true))
        assertFalse(g.unsupported.value)
    }

    @Test
    fun aPlayerThatKeepsRefusingIsLeftAloneAfterTwoRejections() {
        val g = guard()
        val sent = (1..10).mapNotNull { g.next(reported = 1f, canSet = true) }
        // The first attempt, one retry, then it gives up: no endless round trips.
        assertEquals(listOf(1.5f, 1.5f), sent)
        assertTrue(g.unsupported.value)
    }

    @Test
    fun aPlayerThatAcceptsThenRevertsIsAlsoBounded() {
        val g = guard()
        var sends = 0
        repeat(10) {
            if (g.next(reported = 1.5f, canSet = true) != null) sends++ // optimistic echo
            if (g.next(reported = 1f, canSet = true) != null) sends++ // the receiver's real answer
        }
        assertEquals(2, sends)
        assertTrue(g.unsupported.value)
    }

    @Test
    fun aPlayerWithoutSpeedControlIsUnsupportedStraightAway() {
        val g = guard()
        assertNull(g.next(reported = 1f, canSet = false))
        assertTrue(g.unsupported.value)
    }

    @Test
    fun resetTriesAgain() {
        val g = guard()
        repeat(5) { g.next(reported = 1f, canSet = true) }
        assertTrue(g.unsupported.value)
        g.reset() // another device, or the media loaded
        assertFalse(g.unsupported.value)
        assertEquals(1.5f, g.next(reported = 1f, canSet = true))
        g.reset(2f) // a new setting
        assertEquals(2f, g.setting)
        assertEquals(2f, g.next(reported = 1f, canSet = true))
    }

    @Test
    fun nothingToDoWhenThePlayerAlreadyMatches() {
        val g = guard(1f)
        assertNull(g.next(reported = 1f, canSet = false))
        assertFalse(g.unsupported.value)
    }
}
