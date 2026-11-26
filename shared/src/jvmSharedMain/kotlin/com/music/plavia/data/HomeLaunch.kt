package com.music.plavia.data

import com.music.plavia.data.model.HomeShelf
import com.music.plavia.data.model.ShelfItem
import com.music.plavia.data.model.Song
import java.time.LocalDate
import java.util.Locale
import kotlin.random.Random

/**
 * The Home greeting, the day's mix, and the two Top 10s.
 *
 * Pure on purpose: the phone draws whatever this returns, and the JVM test
 * can ask it at 09:00, 15:00 and 22:00 without moving the clock.
 */
object HomeGreeting {
    enum class Part { MORNING, AFTERNOON, NIGHT }

    /** 05:00–11:59 morning, 12:00–18:59 afternoon, 19:00–04:59 night. */
    fun part(hour: Int): Part = when (hour.mod(24)) {
        in 5..11 -> Part.MORNING
        in 12..18 -> Part.AFTERNOON
        else -> Part.NIGHT
    }

    /**
     * The name the greeting uses. A name typed in Settings wins. Otherwise
     * the account's first name. Nothing in either place means no name.
     */
    fun firstName(accountName: String?, override: String?): String? {
        val chosen = override?.trim()?.takeIf { it.isNotEmpty() }
            ?: accountName?.trim().orEmpty()
        return chosen.substringBefore(' ').takeIf { it.isNotBlank() }
    }

    fun line(hour: Int, accountName: String?, override: String?, phrases: Map<Part, String>): String {
        val phrase = phrases.getValue(part(hour))
        val name = firstName(accountName, override)
        return if (name == null) phrase else "$phrase, $name"
    }
}

/**
 * One fixed list for a calendar day. The same inputs and the same date
 * always come back in the same order. The next date is a different draw.
 * This is not My Supermix: that one is rebuilt on every tap.
 */
object DailyMix {
    const val MIN = 20
    const val MAX = 25

    fun assemble(
        date: LocalDate,
        history: List<Song>,
        discover: List<Song>,
        newReleases: List<Song>,
        supermix: List<Song>,
        quickPicks: List<Song>,
    ): List<Song> {
        val personal = history + discover + newReleases + supermix
        val pool = personal.ifEmpty { quickPicks + discover }
        val unique = pool.filter { it.videoId.isNotBlank() }.distinctBy { it.videoId }
        if (unique.isEmpty()) return emptyList()
        val shuffled = unique.shuffled(Random(date.toEpochDay()))
        val want = if (shuffled.size >= MIN) {
            MIN + (date.toEpochDay() % (MAX - MIN + 1)).toInt()
        } else {
            shuffled.size
        }
        return shuffled.take(minOf(want, shuffled.size, MAX))
    }
}

data class ChartRow(val rank: Int, val id: String)

object HomeCharts {
    /** Exactly ten rows, ranks 1 through 10. Fewer than ten is no chart. */
    fun top10(ids: List<String>): List<ChartRow>? {
        if (ids.size < 10) return null
        return ids.take(10).mapIndexed { index, id -> ChartRow(index + 1, id) }
    }

    fun local(shelves: List<HomeShelf>): HomeShelf? = chart(shelves, world = false)

    fun world(shelves: List<HomeShelf>): HomeShelf? = chart(shelves, world = true)

    private fun chart(shelves: List<HomeShelf>, world: Boolean): HomeShelf? {
        val shelf = shelves.firstOrNull { shelf ->
            val worldTitle = isWorld(shelf.title)
            val chart = isChart(shelf.title)
            shelf.items.size >= 10 && chart && worldTitle == world
        } ?: return null
        return shelf.copy(items = shelf.items.take(10))
    }

    private fun isWorld(title: String): Boolean {
        val folded = title.lowercase(Locale.ROOT)
        return listOf("global", "world", "mundial").any { folded.contains(it) }
    }

    private fun isChart(title: String): Boolean {
        val folded = title.lowercase(Locale.ROOT)
        return listOf("chart", "top", "mundial", "éxito", "exito", "ranking", "tendenc")
            .any { folded.contains(it) }
    }
}

/** Releases whose title or subtitle names an artist the listener already plays. */
fun premieresForArtists(items: List<ShelfItem>, artists: Collection<String>): List<ShelfItem> {
    val names = artists.map { it.trim().lowercase(Locale.ROOT) }.filter { it.length >= 2 }
    if (names.isEmpty()) return emptyList()
    return items.filter { item ->
        val hay = "${item.title} ${item.subtitle}".lowercase(Locale.ROOT)
        names.any { hay.contains(it) }
    }
}

/** The Replay period the week card reads. The page's own chips do the rest. */
object HomeWeek {
    const val periodName = "THIS_WEEK"
}

fun regionLabel(code: String, locale: Locale = Locale.getDefault()): String {
    if (code.isBlank()) return ""
    return Locale("", code).getDisplayCountry(locale).ifBlank { code }
}
