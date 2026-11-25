package com.music.plavia.data.innertube

import com.music.plavia.data.innertube.InnerTubeXResolver.ConfigStore
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * Asks YouTube for Hagalo and Umju the same way playback does, then reads
 * the URL. A 403 here is the failure on the phone.
 */
class PlaybackProbeTest {

    @Test
    fun directAudioIsServed() = runBlocking {
        val dir = File("build/probe-players").apply { mkdirs() }
        InnerTubeXResolver.init(dir, memoryStore(), poTokenProvider = null)
        InnerTubeXResolver.debugLogging = true
        val out = File("build/probe-result.txt")
        val client = OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
        val visitorField = Innertube::class.java.getDeclaredField("visitorData")
        visitorField.isAccessible = true
        visitorField.set(Innertube, "CgtBV0FBQUFBQUFBQSiA")
        val lines = mutableListOf<String>()
        try {
            for (id in listOf("5_hMfTfR1L0", "gKtEoGH2Jvk")) {
                val found = InnerTubeXResolver.extract(id, maxKbps = 160)
                    ?: error("$id EXTRACT_NULL")
                val parsed = found.url.toHttpUrlOrNull()
                val code = probe(client, found.url, found, "bytes=1048576-2097151")
                lines += "$id client=${found.clientName} pot=${parsed?.q("pot") != null} " +
                    "nLen=${parsed?.q("n")?.length ?: 0} probe=$code"
            }
        } finally {
            visitorField.set(Innertube, null)
            Innertube.cookie = null
        }
        out.writeText(lines.joinToString("\n"))
        println(lines.joinToString("\n"))
        check(lines.all { it.endsWith("probe=206") }) { lines.joinToString("\n") }
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
