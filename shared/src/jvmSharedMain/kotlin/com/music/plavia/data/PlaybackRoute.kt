package com.music.plavia.data

import java.net.SocketException
import kotlin.coroutines.cancellation.CancellationException

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

    /**
     * The VPN accepted the network request and then refused the bind.
     * OkHttp reports it as `Binding socket to network … failed: EPERM`.
     * A timeout or a 403 is not this.
     */
    fun isBindRefused(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty()
            val refused = message.contains("EPERM") || message.contains("Binding socket")
            if (refused && (current is SocketException || current is SecurityException)) return true
            if (current.cause === current) break
            current = current.cause
        }
        return false
    }

    /**
     * The probe could not use the direct socket. A bind refusal, any other
     * [SocketException], or a [SecurityException] means that route is not
     * available; a later resolve must not keep trying it.
     */
    fun probeRejectsRoute(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is SocketException || current is SecurityException || isBindRefused(current)) return true
            if (current.cause === current) break
            current = current.cause
        }
        return false
    }

    /**
     * Runs [block] on the bound client. A bind refusal drops that binding
     * and runs [block] once more, which then sees [Http.client]. The refused
     * socket is not asked a second time.
     */
    suspend fun <T> withDirectOrSystem(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isBindRefused(e) || !Http.playbackIsDirect) throw e
            abandonDirect()
            return block()
        }
    }

    /**
     * The extractor's retry loop. A bind refusal is not one of the transient
     * failures the extra attempts exist for: the bound socket is tried once,
     * then [withDirectOrSystem] has already moved the retry onto [Http.client].
     */
    suspend fun <T> extractAllowingRetries(
        attempts: Int,
        shouldRetry: (Exception) -> Boolean = { true },
        extract: suspend (attempt: Int) -> T,
    ): T {
        var failure: Exception? = null
        for (attempt in 0 until attempts) {
            try {
                return withDirectOrSystem { extract(attempt) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isBindRefused(e) || !shouldRetry(e)) throw e
                TrackLog.w("Playback", "extract attempt ${attempt + 1} of $attempts: ${e.message}")
                failure = e
            }
        }
        throw failure ?: IllegalStateException("extract failed")
    }

    /** Told when a bound socket is dropped, so the phone can say so once. */
    var onAbandoned: (() -> Unit)? = null

    fun abandonDirect() {
        if (!Http.playbackIsDirect) return
        Http.clearPlaybackBinding()
        onAbandoned?.invoke()
    }
}

/**
 * What [PlaybackNetworks][com.music.plavia.playback.PlaybackNetworks] does when
 * the direct network it was handed cannot be bound. Stays on the system
 * route and announces [refusedNotice] once until the next request.
 */
class DirectOffer(
    private val retry: BypassRetry,
    private val publish: (String) -> Unit,
    private val refusedNotice: String,
) {
    var onSystem: Boolean = true
        private set

    private var published = false

    /** @return true when playback should bind the offered socket. */
    fun onAvailable(probeError: Throwable?): Boolean {
        if (probeError != null && PlaybackRoute.probeRejectsRoute(probeError)) {
            onSystem = true
            if (noteRefused()) publish(refusedNotice)
            return false
        }
        onSystem = false
        return true
    }

    /** @return true the first time this refusal should be shown. */
    fun noteRefused(): Boolean {
        onSystem = true
        retry.onTransientFailure()
        if (published) return false
        published = true
        return true
    }

    fun resetAnnouncement() {
        published = false
    }
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
