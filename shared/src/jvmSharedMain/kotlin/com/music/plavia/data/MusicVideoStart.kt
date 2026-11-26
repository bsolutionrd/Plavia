package com.music.plavia.data

/**
 * What the music-video surface does the moment a video is tapped.
 * The picture URL found beside the audio is used at once. A missing URL
 * is said out loud. A wait is shown only while that URL is still coming.
 */
object MusicVideoStart {
    enum class Step { Loading, Playing, NoVideo }

    fun firstPlayable(alongsideUrl: String?, resolvedUrl: String?): String? =
        alongsideUrl ?: resolvedUrl

    fun step(waiting: Boolean, hasFrame: Boolean, noUrl: Boolean): Step = when {
        noUrl -> Step.NoVideo
        hasFrame -> Step.Playing
        waiting -> Step.Loading
        else -> Step.Loading
    }
}

/** Alias the launcher enables first, then every other alias is turned off. */
object AppIconCatalog {
    const val DARKIFY_COLOR = "LauncherDarkifyColor"

    fun enableThenDisable(aliases: List<String>, chosen: String): Pair<String, List<String>> {
        require(chosen in aliases)
        return chosen to aliases.filter { it != chosen }
    }
}
