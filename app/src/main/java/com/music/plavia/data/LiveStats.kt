package com.music.plavia.data

import com.music.plavia.BuildConfig
import com.music.plavia.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/**
 * Posts the anonymous beat while something is actually playing.
 * A missed post is dropped. Nothing is shown and nothing is logged.
 */
object LiveStats {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = LiveSession(
        now = System::currentTimeMillis,
        enabled = { AppSettings.countLiveListens.value },
        deviceId = { AppSettings.liveDeviceIdOrCreate() },
        version = BuildConfig.VERSION_NAME,
        post = ::postOnce,
    )
    private var job: Job? = null
    private var allowed = true

    fun onPlaying(isPlaying: Boolean) {
        session.onPlayback(isPlaying)
        arm()
    }

    fun onAllowed(enabled: Boolean) {
        allowed = enabled
        arm()
    }

    fun stop() {
        session.onPlayback(false)
        job?.cancel()
        job = null
    }

    private fun arm() {
        job?.cancel()
        job = null
        if (!session.playing || !allowed || !AppSettings.countLiveListens.value) return
        job = scope.launch {
            while (isActive) {
                runCatching { session.pulse() }
                delay(LivePulse.INTERVAL_MS)
            }
        }
    }

    private fun postOnce(url: String, body: String) {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        Http.client.newCall(request).execute().close()
    }
}
