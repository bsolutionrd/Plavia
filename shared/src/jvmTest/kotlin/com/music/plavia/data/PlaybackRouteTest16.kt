package com.music.plavia.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackRouteTest16 {
    @Test
    fun aCachedUrlIsUsedOnlyOnTheRouteThatMintedIt() {
        assertTrue(PlaybackRoute.cachedUrlUsable("directa", "directa"))
        assertFalse(PlaybackRoute.cachedUrlUsable("vpn", "directa"))
        assertFalse(PlaybackRoute.cachedUrlUsable("predeterminada", "directa"))
    }

    @Test
    fun aVpnMintedTokenIsNotSentOnTheDirectRoute() {
        assertFalse(PlaybackRoute.useMintedToken(playbackDirect = true, mintedOnVpn = true))
        assertTrue(PlaybackRoute.useMintedToken(playbackDirect = false, mintedOnVpn = true))
        assertTrue(PlaybackRoute.useMintedToken(playbackDirect = true, mintedOnVpn = false))
    }

    @Test
    fun aTransientMissRetriesOnTheNextPlayAndOnANetworkChange() {
        val bypass = BypassRetry()
        assertTrue(bypass.ask(wanted = true, bound = false, requesting = false, playOrNetwork = false))
        bypass.onTransientFailure()
        assertTrue(bypass.gaveUp)
        assertFalse(bypass.ask(wanted = true, bound = false, requesting = false, playOrNetwork = false))
        assertTrue(bypass.ask(wanted = true, bound = false, requesting = false, playOrNetwork = true))
        assertFalse(bypass.gaveUp)
        bypass.onTransientFailure()
        assertTrue(bypass.ask(wanted = true, bound = false, requesting = false, playOrNetwork = true))
    }
}
