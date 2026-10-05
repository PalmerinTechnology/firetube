package com.palmerintech.firetube.player

import com.palmerintech.firetube.data.EqualizerPreset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEffectsTest {
    /** The usual 5-band layout, as Equalizer.getCenterFreq reports it (milliHertz). */
    private val fiveBands = intArrayOf(60_000, 230_000, 910_000, 3_600_000, 14_000_000)
    private val min: Short = -1500
    private val max: Short = 1500

    @Test
    fun flatIsAllZero() {
        val levels = AudioEffects.bandLevels(AudioEffects.curveDb(EqualizerPreset.FLAT), fiveBands, min, max)
        assertArrayEquals(ShortArray(5), levels)
    }

    @Test
    fun presetsMapOneToOneOnFiveBands() {
        val bass = AudioEffects.bandLevels(AudioEffects.curveDb(EqualizerPreset.BASS), fiveBands, min, max)
        assertArrayEquals(shortArrayOf(600, 400, 0, 0, 0), bass)
        val rock = AudioEffects.bandLevels(AudioEffects.curveDb(EqualizerPreset.ROCK), fiveBands, min, max)
        assertArrayEquals(shortArrayOf(500, 300, -100, 300, 500), rock)
    }

    @Test
    fun otherBandLayoutsAreInterpolatedOnALogScale() {
        // 10 bands, 31 Hz – 16 kHz.
        val tenBands = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000).map { it * 1000 }.toIntArray()
        val levels = AudioEffects.bandLevels(AudioEffects.curveDb(EqualizerPreset.BASS), tenBands, min, max)
        assertEquals(600, levels[0].toInt()) // below the curve: held at its first value
        assertTrue(levels[2].toInt() in 400..600) // between 60 and 230 Hz
        assertEquals(0, levels.last().toInt())
        // Halfway between 60 and 230 Hz on a log scale (~117 Hz) is halfway between their levels.
        val mid = AudioEffects.bandLevels(doubleArrayOf(6.0, 4.0, 0.0, 0.0, 0.0), intArrayOf(117_490), min, max)
        assertEquals(500.0, mid[0].toDouble(), 2.0)
    }

    @Test
    fun levelsAreClampedToWhatTheDeviceAllows() {
        val levels = AudioEffects.bandLevels(AudioEffects.curveDb(EqualizerPreset.BASS), fiveBands, -300, 300)
        assertArrayEquals(shortArrayOf(300, 300, 0, 0, 0), levels)
    }

    @Test
    fun everyPresetHasACurveWithinSafeBoost() {
        EqualizerPreset.entries.forEach { p ->
            val curve = AudioEffects.curveDb(p)
            assertEquals(AudioEffects.CURVE_HZ.size, curve.size)
            assertTrue(p.name, curve.all { it in -12.0..AudioEffects.MAX_HEADROOM_DB })
        }
    }
}

class PlaybackSpeedTest {
    @Test
    fun speedIsKeptInRange() {
        assertEquals(0.5f, PlaybackSpeed.clamp(0.1f), 0f)
        assertEquals(2f, PlaybackSpeed.clamp(5f), 0f)
        assertEquals(1f, PlaybackSpeed.clamp(Float.NaN), 0f)
        assertEquals(1.25f, PlaybackSpeed.clamp(1.25f), 0f)
        assertEquals(1.05f, PlaybackSpeed.clamp(1.04f), 0.0001f) // rounded to the slider's step
        assertTrue(PlaybackSpeed.choices.all { it in PlaybackSpeed.MIN..PlaybackSpeed.MAX })
        assertTrue(1f in PlaybackSpeed.choices)
    }

    @Test
    fun aPlayerRoundingTheSpeedStillCountsAsTheSavedOne() {
        // Otherwise the service would keep re-applying the setting to a player that rounds it.
        assertTrue(PlaybackSpeed.same(1.5f, 1.5001f))
        assertTrue(!PlaybackSpeed.same(1.5f, 1f))
    }

    @Test
    fun labels() {
        assertEquals("1x", PlaybackSpeed.label(1f))
        assertEquals("1.5x", PlaybackSpeed.label(1.5f))
        assertEquals("0.75x", PlaybackSpeed.label(0.75f))
        assertEquals("2x", PlaybackSpeed.label(2f))
    }
}
