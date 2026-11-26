package com.music.plavia.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.plavia.R
import com.music.plavia.data.model.Song
import com.music.plavia.data.model.artworkAt
import com.music.plavia.data.stats.ReplaySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The picture behind "Share as image" and the week card's share button.
 * A missing cover or a missing lyric line still produces the card.
 */
object SongShareCard {
    suspend fun song(context: Context, song: Song, footer: String, lyric: String? = null): Bitmap {
        val cover = artwork(context, song.thumbnailUrl)
        val lines = listOfNotNull(song.artist.takeIf { it.isNotBlank() }, lyric?.takeIf { it.isNotBlank() })
        return draw(context, song.title, lines, footer, listOfNotNull(cover))
    }

    suspend fun week(context: Context, summary: ReplaySummary, title: String, footer: String): Bitmap {
        val cover = artwork(context, summary.songs.firstOrNull()?.song?.thumbnailUrl)
        val lines = buildList {
            add("${summary.minutes} min")
            summary.artists.take(3).forEach { add(it.title) }
            summary.songs.take(3).forEach { add(it.song.title) }
        }
        return draw(context, title, lines, footer, listOfNotNull(cover))
    }

    private suspend fun artwork(context: Context, url: String?): Bitmap? {
        val model = url.artworkAt(512) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val result = SingletonImageLoader.get(context).execute(
                    ImageRequest.Builder(context).data(model).allowHardware(false).build(),
                )
                (result as? SuccessResult)?.image?.toBitmap()
            }.getOrNull()
        }
    }

    private fun draw(
        context: Context,
        title: String,
        lines: List<String>,
        footer: String,
        covers: List<Bitmap>,
    ): Bitmap {
        val width = 1080
        val height = 1350
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF000000.toInt())
        val wash = Paint().apply {
            shader = LinearGradient(
                0f, height * 0.45f, width.toFloat(), height.toFloat(),
                0xFFFF4B2E.toInt(), 0xFFFA2D48.toInt(), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, height * 0.55f, width.toFloat(), height.toFloat(), wash)
        val logo = runCatching {
            android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.ic_logo)
        }.getOrNull()
        covers.firstOrNull()?.let { cover ->
            val side = 640f
            val left = (width - side) / 2f
            val dst = RectF(left, 120f, left + side, 120f + side)
            canvas.drawBitmap(cover, null, dst, Paint(Paint.ANTI_ALIAS_FLAG))
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 64f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF2F2F7.toInt()
            textSize = 40f
        }
        var y = 860f
        canvas.drawText(title.take(42), 72f, y, titlePaint)
        lines.take(5).forEach { line ->
            y += 64f
            canvas.drawText(line.take(48), 72f, y, body)
        }
        y += 90f
        canvas.drawText(footer, 72f, y, body)
        logo?.let {
            val dst = RectF(72f, height - 160f, 72f + 280f, height - 80f)
            canvas.drawBitmap(it, null, dst, Paint(Paint.ANTI_ALIAS_FLAG))
        }
        return bitmap
    }
}
