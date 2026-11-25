package com.music.plavia.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the player shows while a track is stuck: a short preparation line, or a
 * failure the listener can retry. The playback service writes these; the
 * player only reads them.
 */
object PlaybackNotices {
    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private val _preparing = MutableStateFlow(false)
    val preparing: StateFlow<Boolean> = _preparing.asStateFlow()

    /** Set by the playback service. Clears the failure and resolves the track again. */
    @Volatile
    var retry: (() -> Unit)? = null

    fun fail(message: String) {
        _preparing.value = false
        _failure.value = message
    }

    fun clear() {
        _failure.value = null
    }

    fun preparing(active: Boolean) {
        _preparing.value = active
        if (active) _failure.value = null
    }
}
