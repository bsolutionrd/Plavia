package com.music.plavia.data.innertube

import com.music.plavia.data.innertube.InnerTubeXResolver.ConfigStore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * Hagalo, Umju and El Heladero, with a signed-in session installed the way
 * the phone's shell does. A warm-up that rewrites that session while a
 * resolve is in flight makes the probe miss 206. The phone range is the one
 * that came back 403 for Hagalo.
 */
class PlaybackProbeTest {

    @Test
    fun signedInPlaybackProbes() = runBlocking {
        val dir = File("build/probe-players").apply { mkdirs() }
        InnerTubeXResolver.init(dir, memoryStore(), poTokenProvider = null)
        InnerTubeXResolver.debugLogging = true
        // Cookie first: assigning it clears any visitor the shell just adopted.
        Innertube.cookie = "SAPISID=plavia-session; __Secure-3PAPISID=plavia-session"
        Innertube.adoptSessionScope(
            pageId = null,
            dataSyncId = null,
            authUser = "0",
            visitorData = "CgtBV0FBQUFBQUFBQSiA",
            clientVersion = null,
            loggedIn = true,
        )
        val out = File("build/probe-result.txt")
        val client = OkHttpClient.Builder()
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
        val lines = mutableListOf<String>()
        try {
            coroutineScope {
                // The phone calls awaitReady again on every resolve, which is
                // what restarts the warm-up while a player request is in flight.
                val warmups = launch {
                    while (isActive) {
                        InnerTubeXResolver.awaitReady()
                        delay(40)
                    }
                }
                try {
                    for (id in listOf("5_hMfTfR1L0", "gKtEoGH2Jvk", "ciqR8eQDr2w")) {
                        val found = try {
                            InnerTubeXResolver.extract(id, maxKbps = 160)
                        } catch (e: Exception) {
                            lines += "$id EXTRACT_FAIL ${e.javaClass.simpleName}: ${e.message}"
                            continue
                        }
                        if (found == null) {
                            lines += "$id EXTRACT_NULL"
                            continue
                        }
                        val parsed = found.url.toHttpUrlOrNull()
                        val code = probe(client, found.url, found, "bytes=1048576-2097151")
                        lines += "$id client=${found.clientName} itag=${parsed?.q("itag")} " +
                            "nLen=${parsed?.q("n")?.length ?: 0} " +
                            "sig=${parsed?.q("sig") != null} s=${parsed?.q("s") != null} " +
                            "pot=${parsed?.q("pot") != null} probe=$code"
                    }
                } finally {
                    warmups.cancel()
                }
            }
        } finally {
            Innertube.cookie = null
        }
        out.writeText(lines.joinToString("\n"))
        println(lines.joinToString("\n"))
        check(lines.size == 3 && lines.all { it.endsWith("probe=206") }) {
            lines.joinToString("\n")
        }
    }

    /**
     * The phone's exact path: StreamResolver.resolve (probe loop, SABR,
     * upload escalation, 12 s ceiling) with the Lossless ceiling
     * (maxKbps = MAX_VALUE) and no Premium audio — the parameters the phone
     * uses when it 403s Hagalo and fails the audio switch to WjIsrYL8ER0.
     * extract() alone passing does not prove this path works.
     */
    @Test
    fun fullResolveLossless() = runBlocking {
        val dir = File("build/probe-players-full").apply { mkdirs() }
        InnerTubeXResolver.init(dir, memoryStore(), poTokenProvider = null)
        InnerTubeXResolver.debugLogging = true
        Innertube.cookie = "SAPISID=plavia-session; __Secure-3PAPISID=plavia-session"
        Innertube.adoptSessionScope(
            pageId = null,
            dataSyncId = null,
            authUser = "0",
            visitorData = "CgtBV0FBQUFBQUFBQSiA",
            clientVersion = null,
            loggedIn = true,
        )
        StreamResolver.maxKbps = { Int.MAX_VALUE }
        StreamResolver.premiumAudio = { false }
        val out = File("build/full-resolve-result.txt")
        val lines = mutableListOf<String>()
        try {
            coroutineScope {
                val warmups = launch {
                    while (isActive) {
                        InnerTubeXResolver.awaitReady()
                        delay(40)
                    }
                }
                try {
                    for (id in listOf("5_hMfTfR1L0", "gKtEoGH2Jvk", "ciqR8eQDr2w", "WjIsrYL8ER0")) {
                        val url = try {
                            StreamResolver.resolve(id)
                        } catch (e: Exception) {
                            lines += "$id RESOLVE_FAIL ${e.javaClass.simpleName}: ${e.message}"
                            continue
                        }
                        val parsed = url.toHttpUrlOrNull()
                        lines += "$id RESOLVED itag=${parsed?.q("itag")} host=${parsed?.host} " +
                            "pot=${parsed?.q("pot") != null}"
                    }
                } finally {
                    warmups.cancel()
                }
            }
        } finally {
            Innertube.cookie = null
            StreamResolver.maxKbps = { 160 }
            StreamResolver.premiumAudio = { false }
        }
        out.writeText(lines.joinToString("\n"))
        println(lines.joinToString("\n"))
        check(lines.size == 4 && lines.all { it.startsWith("RESOLVED") || it.contains(" RESOLVED ") }) {
            lines.joinToString("\n")
        }
    }

    private fun probe(
        client: OkHttpClient,
        url: String,
        found: InnerTubeXResolver.Extracted,
        range: String,
    ): Int {
        val headers = found.headers.ifEmpty { PlayerClient.forStreamUrl(url).mediaHeaders() }
        val builder = Request.Builder().url(url).header("Range", range)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return client.newCall(builder.build()).execute().use { it.code }
    }

    private fun memoryStore() = object : ConfigStore {
        private val strings = ConcurrentHashMap<String, String>()
        private val longs = ConcurrentHashMap<String, Long>()
        override fun getString(key: String): String? = strings[key]
        override fun getLong(key: String): Long? = longs[key]
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun putLong(key: String, value: Long) { longs[key] = value }
    }

    private fun okhttp3.HttpUrl.q(name: String) = queryParameter(name)
}
