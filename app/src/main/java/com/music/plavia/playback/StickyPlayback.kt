package com.music.plavia.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.music.plavia.data.Http
import okhttp3.Call

/**
 * An HTTP source that keeps the client it was opened with.
 *
 * [Http.playbackClient] changes when the direct network is bound or released.
 * A URL already in the player's hands was minted on the previous network, and
 * the next range of that URL on the new one is refused. Capturing the client
 * here leaves the open song on the network that minted it until the service
 * swaps in a URL minted on the new one.
 */
@UnstableApi
fun stickyPlaybackHttpFactory(
    headers: Map<String, String> = emptyMap(),
): DataSource.Factory = DataSource.Factory {
    val captured = Http.playbackClient()
    OkHttpDataSource.Factory(Call.Factory { request -> captured.newCall(request) })
        .setDefaultRequestProperties(headers)
        .createDataSource()
}
