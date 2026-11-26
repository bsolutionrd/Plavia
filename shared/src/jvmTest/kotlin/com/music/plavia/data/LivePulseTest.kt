package com.music.plavia.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LivePulseTest {
    @Test
    fun bodyNamesOnlyTheDeviceThePlatformAndTheVersion() {
        assertEquals(
            "https://perezsystems.com/plavia-stats/heartbeat.php",
            LivePulse.URL,
        )
        assertEquals(30_000L, LivePulse.INTERVAL_MS)
        assertEquals(
            """{"id":"device-1","platform":"android","version":"1.11"}""",
            LivePulse.body("device-1", "1.11"),
        )
    }

    @Test
    fun aPlayingDeviceBeatsImmediatelyAndThenEveryThirtySeconds() {
        var clock = 1_000L
        val sent = mutableListOf<Pair<String, String>>()
        val session = session(sent, now = { clock })
        session.onPlayback(true)
        assertTrue(session.pulse())
        assertEquals(1, sent.size)
        assertEquals(LivePulse.URL, sent.single().first)
        clock = 1_000L + 29_999L
        assertFalse(session.pulse())
        assertEquals(1, sent.size)
        clock = 1_000L + 30_000L
        assertTrue(session.pulse())
        assertEquals(2, sent.size)
    }

    @Test
    fun pausingStopsTheBeatAndPlayingAgainStartsIt() {
        var clock = 5_000L
        val sent = mutableListOf<Pair<String, String>>()
        val session = session(sent, now = { clock })
        session.onPlayback(true)
        assertTrue(session.pulse())
        session.onPlayback(false)
        clock += 1_000L
        assertFalse(session.pulse())
        assertEquals(1, sent.size)
        session.onPlayback(true)
        assertTrue(session.pulse())
        assertEquals(2, sent.size)
    }

    @Test
    fun turnedOffSendsNothingAndDoesNotAskForAnId() {
        var asked = false
        val sent = mutableListOf<Pair<String, String>>()
        val session = LiveSession(
            now = { 0L },
            enabled = { false },
            deviceId = {
                asked = true
                "should-not-be-created"
            },
            version = "1.11",
            post = { url, body -> sent += url to body },
        )
        session.onPlayback(true)
        assertFalse(session.pulse())
        assertFalse(asked)
        assertTrue(sent.isEmpty())
    }

    private fun session(
        sent: MutableList<Pair<String, String>>,
        now: () -> Long,
        enabled: () -> Boolean = { true },
    ) = LiveSession(
        now = now,
        enabled = enabled,
        deviceId = { "device-1" },
        version = "1.11",
        post = { url, body -> sent += url to body },
    )
}
