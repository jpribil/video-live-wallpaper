package io.github.codeg0blin.videowallpaper

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.graphics.scale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Grabs one small still frame of the wallpaper, used to give the system the
 * wallpaper's colours (`WallpaperService.Engine.onComputeColors`). Without
 * them, Material You and the lock-screen clock can't adapt to a live
 * wallpaper and fall back to default colours.
 */
object FrameGrabber {

    private const val TAG = "FrameGrabber"
    private const val MAX_SIDE = 256

    /** A frame at [atMs] (videos) or the first frame (GIFs), at most 256 px on its longer side. */
    @WorkerThread
    fun grab(context: Context, media: WallpaperMedia, atMs: Long): Bitmap? = try {
        if (media.isGif == true) grabGif(context, media) else grabVideo(context, media, atMs)
    } catch (e: Exception) {
        Log.w(TAG, "Could not grab a frame", e)
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun grabVideo(context: Context, media: WallpaperMedia, atMs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, media.uri)
            val frame = retriever.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            return downscale(frame)
        } finally {
            retriever.release()
        }
    }

    private fun grabGif(context: Context, media: WallpaperMedia): Bitmap? {
        val decoder = GifDecoder.parse(GifFiles.readBytes(context.contentResolver, media.uri))
        decoder.advance()
        val full = Bitmap.createBitmap(decoder.pixels, decoder.width, decoder.height, Bitmap.Config.ARGB_8888)
        return downscale(full)
    }

    private fun downscale(src: Bitmap): Bitmap {
        val longer = max(src.width, src.height)
        if (longer <= MAX_SIDE) return src
        val scale = MAX_SIDE.toFloat() / longer
        val out = src.scale(
            (src.width * scale).roundToInt().coerceAtLeast(1),
            (src.height * scale).roundToInt().coerceAtLeast(1)
        )
        if (out !== src) src.recycle()
        return out
    }
}
