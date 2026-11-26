package com.music.plavia.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.music.plavia.R
import com.music.plavia.data.HomeGreeting
import com.music.plavia.data.model.HomeShelf
import com.music.plavia.data.model.ShelfItem
import com.music.plavia.data.model.Song
import com.music.plavia.data.stats.ReplaySummary
import com.music.plavia.ui.replay.cacheForSharing
import com.music.plavia.ui.replay.sendIntent
import com.music.plavia.ui.share.SongShareCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What Home draws above the feed that was already there. */
data class HomeLaunchBinder(
    val greeting: String?,
    val suggestion: String?,
    val suggestionSong: Song?,
    val dailyMix: List<Song>,
    val dailyMixLabel: String,
    val localPremiere: HomeShelf?,
    val worldPremiere: HomeShelf?,
    val localTop: List<ShelfItem>,
    val worldTop: List<ShelfItem>,
    val countryName: String,
    val artistPremieres: HomeShelf?,
    val week: ReplaySummary?,
    val weekLines: List<String>,
)

@Composable
fun bindHomeLaunch(
    homeGreetingOn: Boolean,
    greetingName: String,
    accountName: String?,
    downloadsOnly: Boolean,
    savedIds: Set<String>,
    dailyMix: List<Song>,
    localPremiere: HomeShelf?,
    worldPremiere: HomeShelf?,
    localChart: HomeShelf?,
    worldChart: HomeShelf?,
    artistPremiereItems: List<ShelfItem>,
    weekSummary: ReplaySummary?,
): HomeLaunchBinder {
    val greeting = if (homeGreetingOn) {
        HomeGreeting.line(
            LocalTime.now().hour,
            accountName,
            greetingName,
            mapOf(
                HomeGreeting.Part.MORNING to stringResource(R.string.greeting_morning),
                HomeGreeting.Part.AFTERNOON to stringResource(R.string.greeting_afternoon),
                HomeGreeting.Part.NIGHT to stringResource(R.string.greeting_night),
            ),
        )
    } else {
        null
    }
    val shownDailyMix = if (downloadsOnly) dailyMix.filter { it.videoId in savedIds } else dailyMix
    val suggestionSong = shownDailyMix.firstOrNull()?.takeIf { it.artist.isNotBlank() }
    val suggestion = if (homeGreetingOn && suggestionSong != null) {
        stringResource(
            when (HomeGreeting.part(LocalTime.now().hour)) {
                HomeGreeting.Part.MORNING -> R.string.suggestion_morning
                HomeGreeting.Part.AFTERNOON -> R.string.suggestion_afternoon
                HomeGreeting.Part.NIGHT -> R.string.suggestion_night
            },
            suggestionSong.title,
            suggestionSong.artist,
        )
    } else {
        null
    }
    val shownWeek = weekSummary?.takeUnless { downloadsOnly }
    return HomeLaunchBinder(
        greeting = greeting,
        suggestion = suggestion,
        suggestionSong = suggestionSong,
        dailyMix = shownDailyMix,
        dailyMixLabel = stringResource(
            R.string.daily_mix,
            LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())),
        ),
        localPremiere = if (downloadsOnly) localPremiere?.keptDownloads(savedIds) else localPremiere,
        worldPremiere = if (downloadsOnly) worldPremiere?.keptDownloads(savedIds) else worldPremiere,
        localTop = ranked(localChart, downloadsOnly, savedIds),
        worldTop = ranked(worldChart, downloadsOnly, savedIds),
        countryName = com.music.plavia.data.regionLabel(
            com.music.plavia.data.innertube.Innertube.currentRegion,
        ),
        artistPremieres = artistPremiereItems
            .let { rows -> if (!downloadsOnly) rows else rows.downloaded(savedIds) }
            .takeIf { it.isNotEmpty() }
            ?.let { HomeShelf(stringResource(R.string.artist_premieres), it) },
        week = shownWeek,
        weekLines = shownWeek?.let { summary ->
            listOf(stringResource(R.string.week_minutes, summary.minutes)) +
                summary.artists.take(3).map { it.title } +
                summary.songs.take(3).map { it.song.title }
        }.orEmpty(),
    )
}

fun shareSongImage(context: Context, scope: CoroutineScope, song: Song) {
    scope.launch {
        val bitmap = SongShareCard.song(context, song, context.getString(R.string.listen_on_plavia))
        val uri = cacheForSharing(context, bitmap, "song-card.png") ?: return@launch
        context.startActivity(
            Intent.createChooser(sendIntent(uri), context.getString(R.string.share_as_image)),
        )
    }
}

fun shareWeekImage(context: Context, scope: CoroutineScope, summary: ReplaySummary) {
    scope.launch {
        val bitmap = SongShareCard.week(
            context,
            summary,
            context.getString(R.string.week_in_music),
            context.getString(R.string.listen_on_plavia),
        )
        val uri = cacheForSharing(context, bitmap, "week-card.png") ?: return@launch
        context.startActivity(
            Intent.createChooser(sendIntent(uri), context.getString(R.string.share_as_image)),
        )
    }
}

private fun ranked(shelf: HomeShelf?, downloadsOnly: Boolean, savedIds: Set<String>): List<ShelfItem> {
    val items = if (downloadsOnly) shelf?.items.orEmpty().downloaded(savedIds) else shelf?.items.orEmpty()
    return if (items.size >= 10) items.take(10) else emptyList()
}

private fun List<ShelfItem>.downloaded(savedIds: Set<String>): List<ShelfItem> = filter { item ->
    val id = item.videoId
    id != null && id in savedIds
}

private fun HomeShelf.keptDownloads(ids: Set<String>): HomeShelf? =
    copy(items = items.downloaded(ids)).takeIf { it.items.isNotEmpty() }
