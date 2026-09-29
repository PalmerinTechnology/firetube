package com.palmerintech.firetube

/**
 * How people can support FireTube, which is free and has no ads: a tip jar (Ko-fi etc.) set with
 * `DONATE_URL` in app/build.gradle.kts. While it's empty, every support prompt is hidden.
 */
object Support {
    val donateUrl: String? = BuildConfig.DONATE_URL.ifBlank { null }

    /** Plays before the Home screen first suggests supporting — never on a first run. */
    const val PLAYS_BEFORE_ASKING = 25

    /** After "Not now", wait this long before asking again. */
    const val ASK_AGAIN_AFTER_MS = 30L * 24 * 60 * 60 * 1000
}
