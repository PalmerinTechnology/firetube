package com.palmerintech.firetube.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Evens out volume between songs (YouTube uploads vary a lot in loudness).
 *
 * NewPipe doesn't expose YouTube's per-video loudness value, so this levels in real time:
 * - it measures short-term loudness (power averaged over ~0.3 s, so drums and dynamics don't
 *   skew it), follows it up in about a second and down over several seconds (so it doesn't
 *   "pump" within a song), and steers toward a target level within a limited range, ignoring silence,
 * - it tracks peaks instantly and never boosts them past the limiter's knee, and turns the gain
 *   down fast — so a loud song after a quiet one isn't blasted for seconds,
 * - soft-limits whatever is left so boosted songs never clip.
 * When turned off it glides back to unity and then passes audio through untouched.
 *
 * It's also the last stage before the device's effects (see [AudioEffects]), so it leaves the
 * [headroomDb] they boost by free: output is scaled down after the limiter, and the boosted
 * frequencies then come back up to full scale at most instead of clipping.
 *
 * Requires 16-bit PCM input: [androidx.media3.exoplayer.audio.DefaultAudioSink] converts to it
 * ahead of custom processors as long as float output stays disabled.
 */
@UnstableApi
class VolumeLeveler : BaseAudioProcessor() {

    /** Toggled from settings; when off, the gain glides back to unity. */
    @Volatile var enabled: Boolean = true

    /** How far below full scale to keep the output, in dB, for effects that boost after it. */
    @Volatile var headroomDb: Double = 0.0

    /** Smoothed loudness estimate of the music, in dBFS (RMS). */
    private var loudnessDb = TARGET_DB

    /** Short-term mean power (linear), averaged over [SHORT_TERM] seconds. */
    private var shortTermPower = 0.0

    /** Smoothed peak level (linear), for keeping boosted peaks under the limiter's knee. */
    private var peak = 0.0

    /** Gain currently applied, in dB (smoothed toward the wanted gain). */
    private var gainDb = 0.0

    private var block = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        // The format that applies to these buffers (updated on flush, not on configure).
        val format = this.inputAudioFormat
        val channels = format.channelCount.coerceAtLeast(1)
        val sampleRate = format.sampleRate.coerceAtLeast(1)
        val out = replaceOutputBuffer(bytes)
        val input = inputBuffer.order(ByteOrder.nativeOrder())

        val headroom = 10.0.pow(-headroomDb.coerceAtLeast(0.0) / 20)
        val passthrough = !enabled && abs(gainDb) < 0.01 && headroom == 1.0

        val blockSamples = ((sampleRate * BLOCK_SECONDS).toInt().coerceAtLeast(1)) * channels
        if (block.size < blockSamples) block = ShortArray(blockSamples)
        val samplesTotal = bytes / 2
        var done = 0
        while (done < samplesTotal) {
            val n = minOf(blockSamples, samplesTotal - done)
            var sumSquares = 0.0
            var blockPeak = 0.0
            for (i in 0 until n) {
                val s = input.getShort()
                block[i] = s
                val x = s / 32768.0
                sumSquares += x * x
                if (abs(x) > blockPeak) blockPeak = abs(x)
            }
            updateGain(sqrt(sumSquares / n), blockPeak, n.toDouble() / channels / sampleRate)
            if (passthrough) {
                // Fully off: bit-exact output, but keep measuring so turning it back on is instant.
                gainDb = 0.0
                for (i in 0 until n) out.putShort(block[i])
            } else {
                val gain = 10.0.pow(gainDb / 20)
                for (i in 0 until n) out.putShort(limit(block[i] / 32768.0 * gain, headroom))
            }
            done += n
        }
        inputBuffer.position(inputBuffer.limit()) // an odd trailing byte (never expected) is dropped
        out.flip()
    }

    private fun updateGain(rms: Double, blockPeak: Double, seconds: Double) {
        if (rms > SILENCE_RMS) {
            // Average power first: an asymmetric filter on raw per-block dB would read dynamic music
            // as louder than it is (it settles near a high percentile rather than the average).
            val power = rms * rms
            shortTermPower += (power - shortTermPower) * (seconds / SHORT_TERM).coerceAtMost(1.0)
            val db = 10 * log10(shortTermPower.coerceAtLeast(1e-12))
            val tau = if (db > loudnessDb) LOUDNESS_ATTACK else LOUDNESS_RELEASE
            loudnessDb += (db - loudnessDb) * (seconds / tau).coerceAtMost(1.0)
            peak = if (blockPeak > peak) blockPeak else peak + (blockPeak - peak) * (seconds / PEAK_RELEASE).coerceAtMost(1.0)
        }
        val wanted = if (enabled) {
            // Never boost the music's peaks past the limiter's knee.
            val peakCeiling = if (peak > 0) 20 * log10(LIMIT_KNEE / peak) - PEAK_MARGIN_DB else MAX_GAIN_DB
            (TARGET_DB - loudnessDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB).coerceAtMost(maxOf(peakCeiling, 0.0))
        } else {
            0.0
        }
        // Turn down quickly, turn up gently.
        val tau = if (wanted < gainDb) GAIN_DOWN else GAIN_UP
        gainDb += (wanted - gainDb) * (seconds / tau).coerceAtMost(1.0)
    }

    /** Soft knee above [LIMIT_KNEE]: transparent below it, never exceeds full scale (times [scale]) above it. */
    private fun limit(x: Double, scale: Double): Short {
        val a = abs(x)
        val y = if (a <= LIMIT_KNEE) a else LIMIT_KNEE + (1 - LIMIT_KNEE) * tanh((a - LIMIT_KNEE) / (1 - LIMIT_KNEE))
        val signed = if (x < 0) -y else y
        return (signed * scale * 32767).toInt().coerceIn(-32768, 32767).toShort()
    }

    /** Keep the learned loudness across seeks and track changes — that's what levels songs against each other. */
    override fun onFlush() = Unit

    override fun onReset() {
        loudnessDb = TARGET_DB
        shortTermPower = 0.0
        peak = 0.0
        gainDb = 0.0
        block = ShortArray(0)
    }

    internal val currentGainDb: Double get() = gainDb

    companion object {
        /** Typical RMS of well-mastered streaming music (~ -14 LUFS). */
        const val TARGET_DB = -16.0
        const val MIN_GAIN_DB = -9.0
        const val MAX_GAIN_DB = 6.0
        const val SILENCE_RMS = 0.003 // about -50 dBFS
        const val SHORT_TERM = 0.3
        const val LOUDNESS_ATTACK = 1.0
        const val LOUDNESS_RELEASE = 6.0
        /** Keep boosted peaks this far under the knee. */
        const val PEAK_MARGIN_DB = 0.5
        const val PEAK_RELEASE = 3.0
        const val GAIN_DOWN = 0.1
        const val GAIN_UP = 0.8
        const val LIMIT_KNEE = 0.9
        const val BLOCK_SECONDS = 0.01
    }
}
