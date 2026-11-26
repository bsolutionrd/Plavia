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

    data class PlayerHint(val message: String, val actionLabel: String?)

    private val _hint = MutableStateFlow<PlayerHint?>(null)
    val hint: StateFlow<PlayerHint?> = _hint.asStateFlow()

    /** Set by the playback service. Clears the failure and resolves the track again. */
    @Volatile
    var retry: (() -> Unit)? = null

    /** Opens the playback setting from the VPN hint. The activity installs it. */
    @Volatile
    var hintAction: (() -> Unit)? = null

    fun fail(message: String) {
        _preparing.value = false
        _failure.value = message
    }

    fun showHint(message: String, actionLabel: String?, onAction: (() -> Unit)?) {
        hintAction = onAction
        _hint.value = PlayerHint(message, actionLabel)
    }

    fun clearHint() {
        hintAction = null
        _hint.value = null
    }

    fun clear() {
        _failure.value = null
    }

    fun preparing(active: Boolean) {
        _preparing.value = active
        if (active) _failure.value = null
    }
}
