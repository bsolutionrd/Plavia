package com.music.plavia.data

import okhttp3.Dns
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The playback client is [Http.client] until a direct network is bound, and
 * the request that asks for that network is Wi-Fi or mobile data, not a VPN.
 */
class PlaybackRouteTest {

    @AfterTest
    fun release() {
        Http.clearPlaybackBinding()
    }

    @Test
    fun bypassOffUsesTheSharedClient() {
        assertSame(Http.client, Http.playbackClient())
        assertEquals("predeterminada", Http.playbackVia())
    }

    @Test
    fun bypassOnUsesTheDirectSocketAndDns() {
        val sockets = object : SocketFactory() {
            override fun createSocket(): Socket = error("unused")
            override fun createSocket(host: String, port: Int): Socket = error("unused")
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                error("unused")
            override fun createSocket(host: InetAddress, port: Int): Socket = error("unused")
            override fun createSocket(
                address: InetAddress,
                port: Int,
                localAddress: InetAddress,
                localPort: Int,
            ): Socket = error("unused")
        }
        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        Http.bindPlayback(sockets, dns)
        val playback = Http.playbackClient()
        assertTrue(playback !== Http.client)
        assertSame(sockets, playback.socketFactory)
        assertSame(dns, playback.dns)
        assertEquals("directa", Http.playbackVia())
    }

    @Test
    fun releasingTheDirectNetworkReturnsTheSharedClient() {
        Http.bindPlayback(SocketFactory.getDefault(), Dns.SYSTEM)
        Http.clearPlaybackBinding()
        assertSame(Http.client, Http.playbackClient())
    }

    @Test
    fun directRequestAsksForWifiOrCellularWithoutVpn() {
        val transports = mutableListOf<Int>()
        val capabilities = mutableListOf<Int>()
        DirectNetworkRequest.applyTo(
            { transports += it },
            { capabilities += it },
        )
        assertEquals(
            listOf(DirectNetworkRequest.TRANSPORT_WIFI, DirectNetworkRequest.TRANSPORT_CELLULAR),
            transports,
        )
        assertEquals(1, DirectNetworkRequest.TRANSPORT_WIFI)
        assertEquals(0, DirectNetworkRequest.TRANSPORT_CELLULAR)
        assertTrue(DirectNetworkRequest.NET_CAPABILITY_NOT_VPN in capabilities)
        assertEquals(15, DirectNetworkRequest.NET_CAPABILITY_NOT_VPN)
        assertTrue(DirectNetworkRequest.NET_CAPABILITY_INTERNET in capabilities)
    }
}
