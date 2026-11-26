package com.music.plavia.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MusicVideoStartTest {
    @Test
    fun thePictureStartsFromTheUrlFoundBesideTheAudio() {
        assertEquals(
            "https://video.example/a",
            MusicVideoStart.firstPlayable("https://video.example/a", "https://video.example/later"),
        )
        assertEquals("https://video.example/later", MusicVideoStart.firstPlayable(null, "https://video.example/later"))
        assertNull(MusicVideoStart.firstPlayable(null, null))
    }

    @Test
    fun theSurfaceLoadsUntilAFrameAndSaysWhenThereIsNoVideo() {
        assertEquals(MusicVideoStart.Step.Loading, MusicVideoStart.step(waiting = true, hasFrame = false, noUrl = false))
        assertEquals(MusicVideoStart.Step.Playing, MusicVideoStart.step(waiting = false, hasFrame = true, noUrl = false))
        assertEquals(MusicVideoStart.Step.NoVideo, MusicVideoStart.step(waiting = false, hasFrame = false, noUrl = true))
    }
}
