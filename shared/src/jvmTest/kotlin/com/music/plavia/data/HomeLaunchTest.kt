package com.music.plavia.data

import com.music.plavia.data.model.HomeShelf
import com.music.plavia.data.model.ShelfItem
import com.music.plavia.data.model.Song
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeLaunchTest {
    private val spanish = mapOf(
        HomeGreeting.Part.MORNING to "Buenos días",
        HomeGreeting.Part.AFTERNOON to "Buenas tardes",
        HomeGreeting.Part.NIGHT to "Buenas noches",
    )

    @Test
    fun greetingFollowsTheHourAndTheFirstName() {
        assertEquals("Buenos días, Luis", HomeGreeting.line(9, "Luis Pérez", null, spanish))
        assertEquals("Buenas tardes, Luis", HomeGreeting.line(15, "Luis Pérez", null, spanish))
        assertEquals("Buenas noches, Luis", HomeGreeting.line(22, "Luis Pérez", null, spanish))
        assertEquals("Buenos días", HomeGreeting.line(9, null, "  ", spanish))
        assertEquals("Buenas noches, Ana", HomeGreeting.line(22, "Luis Pérez", "Ana", spanish))
        assertEquals(HomeGreeting.Part.NIGHT, HomeGreeting.part(4))
        assertEquals(HomeGreeting.Part.MORNING, HomeGreeting.part(5))
        assertEquals(HomeGreeting.Part.NIGHT, HomeGreeting.part(19))
    }

    @Test
    fun dailyMixIsStableForADateAndChangesTheNextDay() {
        val date = LocalDate.of(2026, 11, 26)
        val history = songs("h", 30)
        val discover = songs("d", 12)
        val releases = songs("n", 12)
        val supermix = songs("s", 12)
        val first = DailyMix.assemble(date, history, discover, releases, supermix, emptyList())
        val again = DailyMix.assemble(date, history, discover, releases, supermix, emptyList())
        assertEquals(first.map { it.videoId }, again.map { it.videoId })
        assertTrue(first.size in DailyMix.MIN..DailyMix.MAX)
        val next = DailyMix.assemble(date.plusDays(1), history, discover, releases, supermix, emptyList())
        assertNotEquals(first.map { it.videoId }, next.map { it.videoId })
    }

    @Test
    fun dailyMixWithoutHistoryUsesQuickPicks() {
        val picks = songs("q", 22)
        val mix = DailyMix.assemble(
            LocalDate.of(2026, 11, 26),
            history = emptyList(),
            discover = emptyList(),
            newReleases = emptyList(),
            supermix = emptyList(),
            quickPicks = picks,
        )
        assertTrue(mix.isNotEmpty())
        assertTrue(mix.all { it.videoId.startsWith("q") })
    }

    @Test
    fun top10IsTenNumberedRowsOrNothing() {
        assertNull(HomeCharts.top10((1..9).map { "id$it" }))
        val rows = HomeCharts.top10((1..15).map { "id$it" })!!
        assertEquals(10, rows.size)
        assertEquals((1..10).toList(), rows.map { it.rank })
        assertEquals("id1", rows.first().id)
        assertEquals("id10", rows.last().id)
        val shelves = listOf(
            shelf("Top songs - Dominican Republic", 12),
            shelf("Top songs - Global", 12),
        )
        assertEquals(10, HomeCharts.local(shelves)!!.items.size)
        assertEquals(10, HomeCharts.world(shelves)!!.items.size)
        assertTrue(HomeCharts.world(shelves)!!.title.contains("Global"))
        assertNull(HomeCharts.local(listOf(shelf("Top songs - Global", 12))))
    }

    @Test
    fun weekCardReadsThisWeek() {
        assertEquals("THIS_WEEK", HomeWeek.periodName)
    }

    private fun songs(prefix: String, count: Int) = List(count) {
        Song(videoId = "$prefix$it", title = "Song $it", artist = "Artist", thumbnailUrl = null)
    }

    private fun shelf(title: String, count: Int) = HomeShelf(
        title = title,
        items = List(count) {
            ShelfItem("Song $it", "Artist", null, "v$it", null)
        },
    )
}
