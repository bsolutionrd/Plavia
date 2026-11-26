package com.music.plavia.data

/**
 * Which playback route a URL or a poToken belongs to.
 *
 * A URL minted on the VPN is refused when the next read goes out on the
 * direct network, and a poToken minted by the WebView (which stays on the
 * VPN) does the same to a direct URL. The decision is separate from whether
 * a token is well formed.
 */
object PlaybackRoute {
    @Volatile var tokenOrigin: String = "ninguno"
    @Volatile var tokenAtMs: Long = 0L
    @Volatile var tokenSkipped: Boolean = false

    fun noteToken(origin: String, skipped: Boolean, nowMs: Long) {
        tokenOrigin = origin
        tokenSkipped = skipped
        if (!skipped) tokenAtMs = nowMs
    }

    fun tokenAgeMs(nowMs: Long): Long = if (tokenAtMs == 0L) -1L else nowMs - tokenAtMs

    /** A cached URL is playable only on the route that minted it. */
    fun cachedUrlUsable(cachedVia: String, currentVia: String): Boolean = cachedVia == currentVia

    /**
     * A token minted on the VPN is not sent while playback is on the direct
     * network. The WebView that mints it cannot move to that network.
     */
    fun useMintedToken(playbackDirect: Boolean, mintedOnVpn: Boolean): Boolean =
        !(playbackDirect && mintedOnVpn)
}

/**
 * A direct-network request that failed once is tried again on the next play
 * and when the network changes. It is not left off until the listener flips
 * the switch.
 */
class BypassRetry {
    var gaveUp: Boolean = false
        private set

    fun onTransientFailure() {
        gaveUp = true
    }

    fun reset() {
        gaveUp = false
    }

    /**
     * @return true when a new request should be made. A previous give-up only
     *   blocks the automatic repeat; [playOrNetwork] is the next play or a
     *   network change, and those ask again.
     */
    fun ask(wanted: Boolean, bound: Boolean, requesting: Boolean, playOrNetwork: Boolean): Boolean {
        if (!wanted || bound || requesting) return false
        if (gaveUp && !playOrNetwork) return false
        gaveUp = false
        return true
    }
}
