package com.music.plavia.data

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import java.awt.image.BufferedImage
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import javax.imageio.ImageIO
import javax.net.SocketFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BindFallbackTest {

    @AfterTest
    fun release() {
        PlaybackRoute.onAbandoned = null
        Http.clearPlaybackBinding()
    }

    @Test
    fun aBindRefusalFallsBackToTheSystemClientAndSucceeds() = runBlocking {
        Http.bindPlayback(epermSockets(), Dns { listOf(InetAddress.getByName("127.0.0.1")) })
        var boundAttempts = 0
        val played = PlaybackRoute.withDirectOrSystem {
            if (Http.playbackIsDirect) {
                boundAttempts += 1
                Http.playbackClient().socketFactory.createSocket()
            }
            assertSame(Http.client, Http.playbackClient())
            "played"
        }
        assertEquals("played", played)
        assertEquals(1, boundAttempts)
        assertFalse(Http.playbackIsDirect)
    }

    @Test
    fun aFailedProbeStaysOnTheSystemRouteAndAnnouncesOnce() {
        val notices = mutableListOf<String>()
        val retry = BypassRetry()
        val offer = DirectOffer(retry, { notices += it }, "vpn_bypass_refused")
        val refused = SocketException("Binding socket to network 115 failed: EPERM (Operation not permitted)")
        assertFalse(offer.onAvailable(refused))
        assertFalse(offer.onAvailable(refused))
        assertTrue(offer.onSystem)
        assertEquals(listOf("vpn_bypass_refused"), notices)
        assertTrue(retry.gaveUp)
        assertFalse(retry.ask(wanted = true, bound = false, requesting = false, playOrNetwork = false))
    }

    @Test
    fun aBindRefusalIsNotRetriedAgainstTheBoundClient() = runBlocking {
        Http.bindPlayback(epermSockets(), Dns { listOf(InetAddress.getByName("127.0.0.1")) })
        var boundAttempts = 0
        val played = PlaybackRoute.extractAllowingRetries(attempts = 3) {
            if (Http.playbackIsDirect) {
                boundAttempts += 1
                throw SocketException("Binding socket to network 115 failed: EPERM (Operation not permitted)")
            }
            "played"
        }
        assertEquals("played", played)
        assertEquals(1, boundAttempts)
    }

    @Test
    fun darkifyMatchesThePackAndDefaultStaysBlack() {
        val disc = ImageIO.read(foreground())
        val tile = BufferedImage(192, 192, BufferedImage.TYPE_INT_ARGB)
        val canvas = tile.createGraphics()
        canvas.color = java.awt.Color(0x15, 0x15, 0x15)
        canvas.fillRect(0, 0, 192, 192)
        canvas.drawImage(disc, 0, 0, 192, 192, null)
        canvas.dispose()

        val corners = mutableListOf<Int>()
        for (x in 0 until 12) {
            for (y in 0 until 12) {
                corners += tile.getRGB(x, y)
                corners += tile.getRGB(191 - x, y)
                corners += tile.getRGB(x, 191 - y)
                corners += tile.getRGB(191 - x, 191 - y)
            }
        }
        val reds = corners.map { (it shr 16) and 0xFF }.sorted()
        val greens = corners.map { (it shr 8) and 0xFF }.sorted()
        val blues = corners.map { it and 0xFF }.sorted()
        val mid = corners.size / 2
        assertEquals(21, reds[mid])
        assertEquals(21, greens[mid])
        assertEquals(21, blues[mid])

        var left = 192
        var right = 0
        var saturated = 0
        for (y in 0 until 192) {
            for (x in 0 until 192) {
                val pixel = tile.getRGB(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                val saturation = if (max == 0) 0.0 else (max - min).toDouble() / max
                if (saturation > 0.25) {
                    saturated += 1
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        val coverage = (right - left + 1).toDouble() / 192
        assertTrue(coverage in 0.50..0.65, "glyph coverage was $coverage")
        assertTrue(saturated > 100)

        val background = File(foreground().parentFile.parentFile, "drawable/ic_launcher_background.xml").readText()
        val darkify = File(foreground().parentFile.parentFile, "drawable/ic_darkify_background.xml").readText()
        val adaptive = File(foreground().parentFile.parentFile, "mipmap-anydpi/ic_darkify_color.xml").readText()
        assertTrue(background.contains("#FF000000"))
        assertTrue(darkify.contains("#FF151515"))
        assertTrue(adaptive.contains("@drawable/ic_darkify_background"))
        assertFalse(adaptive.contains("@drawable/ic_launcher_background"))
    }

    private fun foreground(): File {
        val candidates = listOf(
            File("app/src/main/res/drawable-nodpi/ic_launcher_foreground.png"),
            File("../app/src/main/res/drawable-nodpi/ic_launcher_foreground.png"),
        )
        return candidates.first { it.isFile }
    }

    private fun epermSockets(): SocketFactory = object : SocketFactory() {
        private fun refused(): Socket =
            throw SocketException("Binding socket to network 115 failed: EPERM (Operation not permitted)")

        override fun createSocket(): Socket = refused()
        override fun createSocket(host: String, port: Int): Socket = refused()
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = refused()
        override fun createSocket(host: InetAddress, port: Int): Socket = refused()
        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket = refused()
    }
}
