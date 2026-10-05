package com.palmerintech.firetube.player

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.palmerintech.firetube.data.EqualizerPreset
import timber.log.Timber
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The equalizer and bass boost, using Android's built-in effects (android.media.audiofx) attached
 * to the player's audio session. They run after the audio leaves ExoPlayer — after [VolumeLeveler] —
 * so whatever they boost is handed to the leveler as [headroomDb] to keep free, or loud songs would clip.
 *
 * Effects only exist while they do something: a flat curve with no bass boost releases them, so
 * the default path stays untouched. Devices without an effect simply don't get it (the settings
 * hide it, see [equalizerSupported]); a failure to create or drive one is logged, never thrown.
 */
@UnstableApi
class AudioEffects(private val leveler: VolumeLeveler) {

    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private var preset = EqualizerPreset.FLAT
    private var bassPercent = 0

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null

    /** The player's audio session changed (or was first assigned). */
    fun attach(audioSessionId: Int) {
        if (audioSessionId == sessionId) return
        release()
        sessionId = audioSessionId
        apply()
    }

    fun update(preset: EqualizerPreset, bassPercent: Int) {
        if (preset == this.preset && bassPercent == this.bassPercent) return
        this.preset = preset
        this.bassPercent = bassPercent.coerceIn(0, 100)
        apply()
    }

    fun release() {
        equalizer?.let { runCatching { it.release() } }
        bassBoost?.let { runCatching { it.release() } }
        equalizer = null
        bassBoost = null
        leveler.headroomDb = 0.0
    }

    private fun apply() {
        // Session 0 is the global output mix: never attach there.
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
        var boostDb = 0.0

        val curve = curveDb(preset)
        if (curve.any { it != 0.0 } && equalizerSupported) {
            val eq = equalizer ?: create { Equalizer(0, sessionId) }.also { equalizer = it }
            if (eq != null) {
                runCatching {
                    val range = eq.bandLevelRange
                    val centers = IntArray(eq.numberOfBands.toInt()) { eq.getCenterFreq(it.toShort()) }
                    val levels = bandLevels(curve, centers, range[0], range[1])
                    levels.forEachIndexed { band, level -> eq.setBandLevel(band.toShort(), level) }
                    eq.enabled = true
                    boostDb = (levels.maxOrNull() ?: 0).coerceAtLeast(0) / 100.0
                }.onFailure { Timber.w(it, "Couldn't apply the equalizer") }
            }
        } else {
            equalizer?.let { runCatching { it.release() } }
            equalizer = null
        }

        if (bassPercent > 0 && bassBoostSupported) {
            val bb = bassBoost ?: create { BassBoost(0, sessionId) }.also { bassBoost = it }
            if (bb != null) {
                runCatching {
                    bb.setStrength((bassPercent * 10).toShort())
                    bb.enabled = true
                    boostDb += bassPercent / 100.0 * BASS_BOOST_HEADROOM_DB
                }.onFailure { Timber.w(it, "Couldn't apply bass boost") }
            }
        } else {
            bassBoost?.let { runCatching { it.release() } }
            bassBoost = null
        }

        leveler.headroomDb = boostDb.coerceAtMost(MAX_HEADROOM_DB)
    }

    /** Creating an effect throws on devices (or sessions) that can't have it. */
    private fun <T : AudioEffect> create(block: () -> T): T? =
        runCatching(block).onFailure { Timber.w(it, "Audio effect unavailable") }.getOrNull()

    companion object {
        /** Rough loudness bass boost adds at full strength (Android doesn't report it). */
        const val BASS_BOOST_HEADROOM_DB = 6.0
        const val MAX_HEADROOM_DB = 9.0

        /** Frequencies (Hz) the preset curves are given at: the common 5-band layout. */
        val CURVE_HZ = doubleArrayOf(60.0, 230.0, 910.0, 3_600.0, 14_000.0)

        /** A preset's gain (dB) at each of [CURVE_HZ]. */
        fun curveDb(preset: EqualizerPreset): DoubleArray = when (preset) {
            EqualizerPreset.FLAT -> doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0)
            EqualizerPreset.BASS -> doubleArrayOf(6.0, 4.0, 0.0, 0.0, 0.0)
            EqualizerPreset.TREBLE -> doubleArrayOf(0.0, 0.0, 0.0, 3.0, 5.0)
            EqualizerPreset.VOCAL -> doubleArrayOf(-2.0, -1.0, 3.0, 3.0, 0.0)
            EqualizerPreset.ROCK -> doubleArrayOf(5.0, 3.0, -1.0, 3.0, 5.0)
            EqualizerPreset.POP -> doubleArrayOf(-1.0, 2.0, 4.0, 2.0, -1.0)
            EqualizerPreset.CLASSICAL -> doubleArrayOf(4.0, 2.0, 0.0, 2.0, 4.0)
        }

        /**
         * Maps a curve onto a device's bands: [centersMilliHz] are the bands' center frequencies
         * (as [Equalizer.getCenterFreq] reports them), interpolated on a log-frequency scale and
         * held flat beyond the curve's ends. Returns millibels clamped to [minMb]..[maxMb].
         */
        fun bandLevels(curveDb: DoubleArray, centersMilliHz: IntArray, minMb: Short, maxMb: Short): ShortArray =
            ShortArray(centersMilliHz.size) { band ->
                val db = interpolate(curveDb, centersMilliHz[band] / 1000.0)
                (db * 100).roundToInt().coerceIn(minMb.toInt(), maxMb.toInt()).toShort()
            }

        private fun interpolate(curveDb: DoubleArray, hz: Double): Double {
            if (hz <= CURVE_HZ.first()) return curveDb.first()
            if (hz >= CURVE_HZ.last()) return curveDb.last()
            val i = CURVE_HZ.indexOfLast { it <= hz }
            val t = (ln(hz) - ln(CURVE_HZ[i])) / (ln(CURVE_HZ[i + 1]) - ln(CURVE_HZ[i]))
            return curveDb[i] + (curveDb[i + 1] - curveDb[i]) * t
        }

        val equalizerSupported: Boolean by lazy { hasEffect(AudioEffect.EFFECT_TYPE_EQUALIZER) }
        val bassBoostSupported: Boolean by lazy { hasEffect(AudioEffect.EFFECT_TYPE_BASS_BOOST) }

        private fun hasEffect(type: java.util.UUID): Boolean =
            runCatching { AudioEffect.queryEffects()?.any { it.type == type } == true }.getOrDefault(false)
    }
}
