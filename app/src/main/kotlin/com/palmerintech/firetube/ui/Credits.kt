package com.palmerintech.firetube.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.palmerintech.firetube.R
import com.palmerintech.firetube.extractor.ArtistCredit
import com.palmerintech.firetube.extractor.Chapter
import com.palmerintech.firetube.extractor.Track

/** A track's artist in the user's language: "Shakira y 2 más" for YouTube's "Shakira and 2 more". */
fun artistLabel(res: Resources, artist: String): String {
    val credit = ArtistCredit.parse(artist)
    return if (credit.others > 0) res.getQuantityString(R.plurals.artist_and_others, credit.others, credit.primary, credit.others) else artist
}

/** [artistLabel] for [track], in Compose. */
@Composable
fun artistLabel(track: Track): String {
    val credit = track.credit
    return if (credit.others > 0) pluralStringResource(R.plurals.artist_and_others, credit.others, credit.primary, credit.others) else track.artist
}

/** A chapter's title, or "Chapter 3" for one YouTube left untitled ([index] is 0-based). */
@Composable
fun chapterTitle(chapter: Chapter, index: Int): String =
    chapter.title.ifBlank { stringResource(R.string.chapter_untitled, index + 1) }
