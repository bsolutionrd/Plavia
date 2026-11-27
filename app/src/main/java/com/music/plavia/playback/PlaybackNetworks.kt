package com.music.plavia.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.music.plavia.R
import com.music.plavia.data.BypassRetry
import com.music.plavia.data.DirectNetworkRequest
import com.music.plavia.data.DirectOffer
import com.music.plavia.data.Http
import com.music.plavia.data.PlaybackRoute
import com.music.plavia.data.TrackLog
import com.music.plavia.data.innertube.StreamResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dns
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Asks Android for a Wi-Fi or mobile network that is not a VPN, and points
 * playback at it. The process itself stays where it is, so the WebView that
 * mints poTokens keeps using the VPN.
 *
 * [CHANGE_NETWORK_STATE] is required for [ConnectivityManager.requestNetwork].
 * It is a normal permission.
 */
object PlaybackNetworks {
    data class Notice(val id: Int, val message: String, val openPlayback: Boolean)

    /** A binding the service applies, then remints the song that is playing. */
    sealed class RouteChange {
        class Direct(val socketFactory: SocketFactory, val dns: Dns) : RouteChange()
        object System : RouteChange()
    }

    /**
     * The service installs this so a route change remints the open song
     * before the next range leaves the network that minted its URL.
     * [allowRollback] is false when that previous network is already gone.
     * Null while the service is down: the binding is applied immediately,
     * because nothing is playing to keep on the old route.
     */
    var onRouteChange: ((RouteChange, allowRollback: Boolean) -> Unit)? = null

    private val _pending = MutableStateFlow<Notice?>(null)
    val pending: StateFlow<Notice?> = _pending.asStateFlow()

    private var nextId = 0
    private var manager: ConnectivityManager? = null
    private var app: Context? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var wanted = false
    private val bypass = BypassRetry()
    private val offer = DirectOffer(bypass, ::announceRefused, REFUSED)
    private var bound = false

    fun install(context: Context) {
        if (manager != null) return
        app = context.applicationContext
        manager = context.getSystemService(ConnectivityManager::class.java)
        PlaybackRoute.onAbandoned = { refuseBoundRoute() }
    }

    /**
     * @param userChanged the listener flipped the switch, so a previous
     *   timeout may be tried again. A capabilities update must not; the next
     *   play and a network change go through [onPlayOrNetwork].
     */
    fun sync(enabled: Boolean, vpnActive: Boolean, userChanged: Boolean = false) {
        Http.systemRoute = if (vpnActive) "vpn" else "predeterminada"
        if (userChanged) {
            bypass.reset()
            offer.resetAnnouncement()
        }
        wanted = enabled && vpnActive
        if (!wanted) {
            bypass.reset()
            offer.resetAnnouncement()
            release()
            return
        }
        if (bypass.ask(wanted, bound, callback != null, playOrNetwork = false)) request()
    }

    /** A new play, or the network changing, asks again after a transient miss. */
    fun onPlayOrNetwork() {
        if (bypass.ask(wanted, bound, callback != null, playOrNetwork = true)) {
            offer.resetAnnouncement()
            request()
        }
    }

    /** The player hit EPERM on a socket that was already bound. Drop it once. */
    fun refuseBypass() {
        if (!Http.playbackIsDirect && offer.onSystem) return
        PlaybackRoute.abandonDirect()
    }

    fun showHint(message: String) {
        publish(message, openPlayback = true)
    }

    fun consume() {
        _pending.value = null
    }

    private fun request() {
        val manager = manager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val factory = network.socketFactory
                val dns = Dns { host -> network.getAllByName(host).toList() }
                val probeError = probeBind(factory, dns)
                if (!offer.onAvailable(probeError)) {
                    bound = false
                    callback = null
                    runCatching { manager.unregisterNetworkCallback(this) }
                    TrackLog.w("Playback", "bypass refused: EPERM")
                    return
                }
                val caps = manager.getNetworkCapabilities(network)
                val transport = when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "datos"
                    else -> "otra"
                }
                bound = true
                TrackLog.d("Playback", "bypass on transport=$transport")
                dispatch(
                    RouteChange.Direct(factory, dns),
                    allowRollback = true,
                )
            }

            override fun onUnavailable() {
                if (Http.playbackIsDirect) return
                callback = null
                bypass.onTransientFailure()
                Http.clearPlaybackBinding()
                TrackLog.w("Playback", "bypass off: no direct network")
                val context = app ?: return
                publish(context.getString(R.string.vpn_direct_blocked), openPlayback = false)
            }

            override fun onLost(network: Network) {
                val wasBound = bound
                bound = false
                callback = null
                runCatching { manager.unregisterNetworkCallback(this) }
                if (wasBound) {
                    TrackLog.d("Playback", "bypass off")
                    // The direct network is gone. Rolling back would point the
                    // song at a socket that no longer exists, so a failed
                    // remint stays on the VPN, which is where the URL in hand
                    // was minted before the bypass.
                    dispatch(RouteChange.System, allowRollback = false)
                }
                if (wanted && !bypass.gaveUp) onPlayOrNetwork()
            }
        }
        callback = cb
        val request = NetworkRequest.Builder().apply {
            DirectNetworkRequest.applyTo(
                { addTransportType(it) },
                { addCapability(it) },
            )
        }.build()
        try {
            manager.requestNetwork(request, cb, TIMEOUT_MS)
        } catch (e: SecurityException) {
            callback = null
            bypass.onTransientFailure()
            TrackLog.w("Playback", "bypass off: ${e.javaClass.simpleName}")
            val context = app ?: return
            publish(context.getString(R.string.vpn_direct_blocked), openPlayback = false)
        }
    }

    private fun release() {
        val cb = callback
        callback = null
        if (cb != null) runCatching { manager?.unregisterNetworkCallback(cb) }
        val wasBound = bound || Http.playbackIsDirect
        bound = false
        if (wasBound) {
            TrackLog.d("Playback", "bypass off")
            dispatch(RouteChange.System, allowRollback = true)
        }
    }

    private fun dispatch(change: RouteChange, allowRollback: Boolean) {
        val hook = onRouteChange
        if (hook != null) {
            hook(change, allowRollback)
            return
        }
        when (change) {
            is RouteChange.Direct -> Http.bindPlayback(change.socketFactory, change.dns)
            RouteChange.System -> Http.clearPlaybackBinding()
        }
        StreamResolver.forgetResolved()
    }

    /**
     * One connect on the socket the direct network offered. EPERM comes back
     * from [android.net.Network.bindSocket] inside [SocketFactory.createSocket],
     * before any media request is worth starting.
     */
    private fun probeBind(factory: SocketFactory, dns: Dns): Throwable? {
        var socket: Socket? = null
        return try {
            val host = dns.lookup(PROBE_HOST).firstOrNull() ?: return java.io.IOException("no address")
            socket = factory.createSocket()
            socket.connect(InetSocketAddress(host, 443), PROBE_TIMEOUT_MS)
            null
        } catch (e: Exception) {
            e
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun refuseBoundRoute() {
        bound = false
        val announce = offer.noteRefused()
        val cb = callback
        callback = null
        if (cb != null) runCatching { manager?.unregisterNetworkCallback(cb) }
        if (announce) {
            TrackLog.w("Playback", "bypass refused: EPERM")
            announceRefused(REFUSED)
        }
    }

    private fun announceRefused(notice: String) {
        if (notice != REFUSED) return
        val context = app ?: return
        publish(context.getString(R.string.vpn_bypass_refused), openPlayback = false)
    }

    private fun publish(message: String, openPlayback: Boolean) {
        nextId += 1
        _pending.value = Notice(nextId, message, openPlayback)
    }

    private const val TIMEOUT_MS = 10_000
    private const val PROBE_TIMEOUT_MS = 3_000
    private const val PROBE_HOST = "youtubei.googleapis.com"
    private const val REFUSED = "vpn_bypass_refused"
}
