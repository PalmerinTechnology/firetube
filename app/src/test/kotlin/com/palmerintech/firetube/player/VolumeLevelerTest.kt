package com.palmerintech.firetube.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class VolumeLevelerTest {
    private val format = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

    /** Feeds [seconds] of a stereo 440 Hz sine at [levelDb] dBFS RMS; returns the output samples. */
    private fun VolumeLeveler.play(levelDb: Double, seconds: Double): ShortArray {
        val amplitude = 10.0.let { Math.pow(it, levelDb / 20) } * sqrt(2.0)
        val frames = (format.sampleRate * seconds).toInt()
        val out = ArrayList<Short>(frames * 2)
        var frame = 0
        while (frame < frames) {
            val chunk = minOf(4096, frames - frame)
            val buf = ByteBuffer.allocateDirect(chunk * 4).order(ByteOrder.nativeOrder())
            repeat(chunk) { i ->
                val v = (amplitude * sin(2 * PI * 440 * (frame + i) / format.sampleRate) * 32767).toInt().toShort()
                buf.putShort(v); buf.putShort(v)
            }
            buf.flip()
            queueInput(buf)
            val o = output
            while (o.hasRemaining()) out += o.getShort()
            frame += chunk
        }
        return out.toShortArray()
    }

    private fun ShortArray.rmsDb(fromFraction: Double = 0.75): Double {
        val tail = copyOfRange((size * fromFraction).toInt(), size)
        val ms = tail.sumOf { (it / 32768.0).let { x -> x * x } } / tail.size
        return 20 * log10(sqrt(ms))
    }

    private fun leveler() = VolumeLeveler().apply { configure(format); flush() }

    @Test
    fun quietSongIsBoostedButCapped() {
        val out = leveler().play(levelDb = -30.0, seconds = 40.0)
        // Boosted by the maximum (+6 dB), not all the way to the target.
        assertEquals(-24.0, out.rmsDb(), 0.7)
    }

    @Test
    fun loudSongIsTurnedDown() {
        val out = leveler().play(levelDb = -8.0, seconds = 40.0)
        assertEquals(VolumeLeveler.TARGET_DB, out.rmsDb(), 1.0)
    }

    @Test
    fun songsAtTheTargetAreLeftAlone() {
        val l = leveler()
        l.play(levelDb = VolumeLeveler.TARGET_DB, seconds = 20.0)
        assertEquals(0.0, l.currentGainDb, 0.5)
    }

    @Test
    fun boostedPeaksNeverClip() {
        // A loud-peaked song right after a quiet one (gain still high): the limiter must hold.
        val l = leveler()
        l.play(levelDb = -30.0, seconds = 30.0)
        val out = l.play(levelDb = -3.0, seconds = 2.0)
        assertTrue(out.all { it in -32767..32767 })
    }

    @Test
    fun dynamicMusicIsNotTurnedDownMoreThanSteadyMusic() {
        // Block levels jump around by ±6 dB (drums, dynamics). The right gain is set by the signal's
        // true (power) RMS — not skewed upward by the jitter, as a naive fast-attack meter would be.
        val l = leveler()
        var powerSum = 0.0
        var samples = 0L
        val random = java.util.Random(42)
        val blockFrames = format.sampleRate / 100
        var t = 0L
        repeat(60 * 100) { // 60 s of 10 ms blocks
            val levelDb = VolumeLeveler.TARGET_DB + (random.nextDouble() * 12 - 6)
            val amplitude = Math.pow(10.0, levelDb / 20) * sqrt(2.0)
            val buf = ByteBuffer.allocateDirect(blockFrames * 4).order(ByteOrder.nativeOrder())
            repeat(blockFrames) {
                val v = (amplitude * sin(2 * PI * 440 * t++ / format.sampleRate) * 32767).toInt().toShort()
                buf.putShort(v); buf.putShort(v)
                powerSum += 2 * (v / 32768.0) * (v / 32768.0)
                samples += 2
            }
            buf.flip()
            l.queueInput(buf)
            l.output.let { it.position(it.limit()) }
        }
        val trueRmsDb = 10 * log10(powerSum / samples)
        assertEquals(VolumeLeveler.TARGET_DB - trueRmsDb, l.currentGainDb, 0.7)
    }

    @Test
    fun loudSongAfterQuietOneIsNotBlastedOrSquashed() {
        val l = leveler()
        l.play(levelDb = -30.0, seconds = 30.0) // gain pinned at +6 dB
        val loud = l.play(levelDb = -8.0, seconds = 3.0)
        // After the first half second the gain must have come down: nothing left in the limiter.
        val settled = loud.copyOfRange(loud.size / 6, loud.size)
        val squashed = settled.count { abs(it.toInt()) > (VolumeLeveler.LIMIT_KNEE * 32767).toInt() }
        assertEquals(0, squashed)
        assertTrue("gain ${l.currentGainDb}", l.currentGainDb < 0)
    }

    @Test
    fun offIsBitExactPassthrough() {
        val l = leveler().apply { enabled = false }
        val out = l.play(levelDb = -1.0, seconds = 1.0)
        val amplitude = Math.pow(10.0, -1.0 / 20) * sqrt(2.0)
        val expected = ShortArray(out.size) { i ->
            val frame = i / 2
            (amplitude * sin(2 * PI * 440 * frame / format.sampleRate) * 32767).toInt().toShort()
        }
        assertTrue(out.contentEquals(expected))
    }

    @Test
    fun disabledGlidesBackToUnity() {
        val l = leveler()
        l.play(levelDb = -8.0, seconds = 30.0)
        assertTrue(l.currentGainDb < -3)
        l.enabled = false
        l.play(levelDb = -8.0, seconds = 5.0)
        assertEquals(0.0, l.currentGainDb, 0.1)
    }

    @Test
    fun silenceDoesNotRaiseTheGain() {
        val l = leveler()
        l.play(levelDb = VolumeLeveler.TARGET_DB, seconds = 20.0)
        l.play(levelDb = -80.0, seconds = 20.0) // a long quiet gap
        assertEquals(0.0, l.currentGainDb, 0.5)
    }
}
