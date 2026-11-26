package com.music.plavia.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.music.plavia.R
import com.music.plavia.data.DirectNetworkRequest
import com.music.plavia.data.Http
import com.music.plavia.data.TrackLog
import com.music.plavia.data.innertube.StreamResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dns

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

    private val _pending = MutableStateFlow<Notice?>(null)
    val pending: StateFlow<Notice?> = _pending.asStateFlow()

    private var nextId = 0
    private var manager: ConnectivityManager? = null
    private var app: Context? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var wanted = false
    private var gaveUp = false
    private var bound = false

    fun install(context: Context) {
        if (manager != null) return
        app = context.applicationContext
        manager = context.getSystemService(ConnectivityManager::class.java)
    }

    /**
     * @param userChanged the listener flipped the switch, so a previous
     *   timeout may be tried again. A capabilities update must not.
     */
    fun sync(enabled: Boolean, vpnActive: Boolean, userChanged: Boolean = false) {
        Http.systemRoute = if (vpnActive) "vpn" else "predeterminada"
        if (userChanged) gaveUp = false
        wanted = enabled && vpnActive
        if (!wanted) {
            gaveUp = false
            release()
            return
        }
        if (gaveUp || callback != null) return
        request()
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
                val caps = manager.getNetworkCapabilities(network)
                val transport = when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "datos"
                    else -> "otra"
                }
                Http.bindPlayback(
                    network.socketFactory,
                    Dns { host -> network.getAllByName(host).toList() },
                )
                StreamResolver.forgetResolved()
                bound = true
                TrackLog.d("Playback", "bypass on transport=$transport")
            }

            override fun onUnavailable() {
                if (Http.playbackIsDirect) return
                callback = null
                gaveUp = true
                Http.clearPlaybackBinding()
                TrackLog.w("Playback", "bypass off: no direct network")
                val context = app ?: return
                publish(context.getString(R.string.vpn_direct_blocked), openPlayback = false)
            }

            override fun onLost(network: Network) {
                val wasBound = bound
                Http.clearPlaybackBinding()
                if (wasBound) {
                    StreamResolver.forgetResolved()
                    TrackLog.d("Playback", "bypass off")
                }
                bound = false
                callback = null
                runCatching { manager.unregisterNetworkCallback(this) }
                if (wanted && !gaveUp) request()
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
            gaveUp = true
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
        Http.clearPlaybackBinding()
        if (wasBound) {
            StreamResolver.forgetResolved()
            TrackLog.d("Playback", "bypass off")
        }
        bound = false
    }

    private fun publish(message: String, openPlayback: Boolean) {
        nextId += 1
        _pending.value = Notice(nextId, message, openPlayback)
    }

    private const val TIMEOUT_MS = 10_000
}
