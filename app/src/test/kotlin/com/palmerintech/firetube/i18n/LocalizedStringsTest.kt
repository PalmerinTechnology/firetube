package com.palmerintech.firetube.i18n

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palmerintech.firetube.R
import com.palmerintech.firetube.data.formatListened
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Key strings resolve in each language the app ships, plurals included. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class LocalizedStringsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun songs(n: Int) = context.resources.getQuantityString(R.plurals.song_count, n, n)
    private fun plays(n: Int) = context.resources.getQuantityString(R.plurals.play_count, n, n)

    @Test
    fun english() {
        assertEquals("FireTube", context.getString(R.string.app_name))
        assertEquals("Now playing", context.getString(R.string.player_now_playing))
        assertEquals("1 song", songs(1))
        assertEquals("12 songs", songs(12))
        assertEquals("1 play", plays(1))
        assertEquals("3 plays", plays(3))
        assertEquals("Chapter 2 of 5: Intro", context.getString(R.string.chapter_position_description, 2, 5, "Intro"))
        assertEquals("45 sec", formatListened(context.resources, 45_000))
        assertEquals("12 min", formatListened(context.resources, 12 * 60_000L + 59_000))
        assertEquals("3 h 25 min", formatListened(context.resources, (3 * 60 + 25) * 60_000L))
    }

    @Test
    @Config(qualifiers = "es")
    fun spanish() {
        assertEquals("FireTube", context.getString(R.string.app_name))
        assertEquals("Reproduciendo", context.getString(R.string.player_now_playing))
        assertEquals("Configuración", context.getString(R.string.settings_title))
        assertEquals("1 canción", songs(1))
        assertEquals("12 canciones", songs(12))
        assertEquals("1 reproducción", plays(1))
        assertEquals("¿Eliminar “Gym”?", context.getString(R.string.playlist_delete_title, "Gym"))
        assertEquals("45 s", formatListened(context.resources, 45_000))
    }

    @Test
    @Config(qualifiers = "pt-rBR")
    fun brazilianPortuguese() {
        assertEquals("FireTube", context.getString(R.string.app_name))
        assertEquals("Tocando agora", context.getString(R.string.player_now_playing))
        assertEquals("Configurações", context.getString(R.string.settings_title))
        assertEquals("1 música", songs(1))
        assertEquals("12 músicas", songs(12))
        assertEquals("3 reproduções", plays(3))
        assertEquals("Capítulo 2 de 5: Intro", context.getString(R.string.chapter_position_description, 2, 5, "Intro"))
        assertEquals("3 h 25 min", formatListened(context.resources, (3 * 60 + 25) * 60_000L))
    }
}
