package com.music.plavia.data

/**
 * The anonymous "still playing" beat. One post every [INTERVAL_MS] while
 * playback is running, and nothing at all while it is paused or the listener
 * has turned the count off. The network call is supplied by the app; this
 * class only decides when a beat is due and what it says.
 */
class LiveSession(
    private val now: () -> Long,
    private val enabled: () -> Boolean,
    private val deviceId: () -> String?,
    private val version: String,
    private val post: (url: String, body: String) -> Unit,
) {
    var playing: Boolean = false
        private set

    private var nextDueAt: Long = 0L

    fun onPlayback(isPlaying: Boolean) {
        playing = isPlaying
        if (!isPlaying) nextDueAt = 0L
    }

    /**
     * One attempt. False means this tick sends nothing: paused, turned off,
     * or still inside the 30 second window. A throw from [post] is the
     * caller's to discard; the next tick is the next chance.
     */
    fun pulse(at: Long = now()): Boolean {
        if (!playing || !enabled()) return false
        if (at < nextDueAt) return false
        val id = deviceId() ?: return false
        post(LivePulse.URL, LivePulse.body(id, version))
        nextDueAt = at + LivePulse.INTERVAL_MS
        return true
    }
}

object LivePulse {
    const val URL = "https://perezsystems.com/plavia-stats/heartbeat.php"
    const val INTERVAL_MS = 30_000L
    const val PLATFORM = "android"

    fun body(id: String, version: String): String = buildString {
        append("{\"id\":")
        append(quote(id))
        append(",\"platform\":\"")
        append(PLATFORM)
        append("\",\"version\":")
        append(quote(version))
        append("}")
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(char)
            }
        }
        append('"')
    }
}
