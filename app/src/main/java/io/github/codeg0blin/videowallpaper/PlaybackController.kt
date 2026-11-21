package io.github.codeg0blin.videowallpaper

import android.content.Context
import android.opengl.EGL14
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.annotation.MainThread
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Plays one video or GIF onto one surface. Used by each wallpaper engine and
 * by the in-app preview, so both behave identically.
 *
 * ## Threads
 * - **Main thread:** every public method, the [ExoPlayer] and the [Listener]
 *   callbacks. Nothing else touches the player, so its state can't be read
 *   half-released from another thread (a crash risk in version 1.x, where the
 *   render thread called `MediaPlayer.getVideoWidth()` while the main thread
 *   could be releasing it).
 * - **Render thread** (owned by this class): the [VideoFrameRenderer] and its
 *   EGL context, the GIF decoder and its [GifPlayer]. All state marked
 *   "render thread" below is only touched there; the main thread sends it
 *   changes by posting.
 *
 * ## Surface lifecycle
 * [attachSurface] creates the renderer on the render thread and, once ready,
 * hands its input surface to the player. [detachSurface] detaches the player
 * and then waits for the render thread to destroy the EGL surface before
 * returning, so callers like `Engine.onSurfaceDestroyed` really are finished
 * with the surface when they return. Every attach/detach bumps a generation
 * number; results from an older generation are ignored, so a surface that is
 * created and destroyed in quick succession can't leave a renderer behind.
 */
class PlaybackController(context: Context, private val listener: Listener) {

    interface Listener {
        /** Display size of the current picture (after rotation / pixel aspect). */
        fun onContentSize(width: Int, height: Int) {}

        /** Playback could not continue; see [PlaybackProblem]. */
        fun onProblem(problem: PlaybackProblem) {}

        /** The current media is playing (first frame shown or player ready). */
        fun onPlaying() {}
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val renderThread = HandlerThread("wallpaper-render").apply { start() }
    private val render = Handler(renderThread.looper)

    // ---- Main-thread state ------------------------------------------------

    private var player: ExoPlayer? = null
    private var media: WallpaperMedia? = null
    private var mediaGeneration = 0
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceGeneration = 0
    private var rendererInput: Surface? = null
    private var visible = false
    private var speed = 1f
    private var trimStartMs = 0L
    private var trimEndMs = 0L
    private var retries = 0
    private var readySinceMs = 0L
    private var rendererRebuilds = 0
    private var released = false

    // ---- Render-thread state ----------------------------------------------

    private var renderer: VideoFrameRenderer? = null
    private var rViewWidth = 0
    private var rViewHeight = 0
    private var rVisible = false
    private var rContent = VideoFrameRenderer.Content.NONE
    private var rVideoWidth = 0
    private var rVideoHeight = 0
    private var rMode = ScalingMode.FILL
    private var rFocusX = 0.5f
    private var rFocusY = 0.5f
    private var gifDecoder: GifDecoder? = null
    private var gifPlayer: GifPlayer? = null
    private var rMediaGeneration = 0

    // ---- Surface ----------------------------------------------------------

    /** Starts drawing onto [target]. Replaces any surface attached before. */
    @MainThread
    fun attachSurface(target: Surface, width: Int, height: Int) {
        if (released) return
        if (surface != null) detachSurface()
        surface = target
        surfaceWidth = width
        surfaceHeight = height
        val generation = ++surfaceGeneration
        render.post { createRenderer(target, width, height, generation) }
    }

    @MainThread
    fun resizeSurface(width: Int, height: Int) {
        if (released || surface == null) return
        surfaceWidth = width
        surfaceHeight = height
        render.post {
            rViewWidth = width
            rViewHeight = height
            drawIfVisible()
        }
    }

    /** Stops drawing and frees the EGL surface. Blocks (briefly) until that's done. */
    @MainThread
    fun detachSurface() {
        if (surface == null) return
        surface = null
        surfaceGeneration++
        rendererInput = null
        // Blocks until the decoder has let go of the renderer's input surface.
        player?.clearVideoSurface()
        val done = CountDownLatch(1)
        render.post {
            releaseRenderer()
            done.countDown()
        }
        if (!done.await(DETACH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "Render thread did not release the surface in time")
        }
    }

    // ---- Media ------------------------------------------------------------

    /** Plays [newMedia] (null = show black). [WallpaperMedia.isGif] must be known. */
    @MainThread
    fun setMedia(newMedia: WallpaperMedia?) {
        if (released) return
        media = newMedia
        val generation = ++mediaGeneration
        retries = 0
        readySinceMs = 0
        releasePlayer()
        when {
            newMedia == null -> render.post { showNothing(generation) }
            newMedia.isGif == true -> {
                val gifSpeed = speed
                render.post { loadGif(newMedia, generation, gifSpeed) }
            }
            else -> {
                render.post { prepareForVideo(generation) }
                createPlayer(newMedia)
            }
        }
    }

    @MainThread
    fun setVisible(isVisible: Boolean) {
        if (released) return
        visible = isVisible
        player?.let { p ->
            p.playWhenReady = isVisible
            // A player that gave up after repeated errors gets another
            // chance each time the wallpaper comes back into view.
            if (isVisible && p.playerError != null) {
                retries = 0
                p.prepare()
            }
        }
        render.post {
            rVisible = isVisible
            updateGifRunning()
            drawIfVisible()
        }
    }

    @MainThread
    fun setSpeed(newSpeed: Float) {
        if (released) return
        speed = newSpeed
        player?.setPlaybackSpeed(newSpeed)
        render.post { gifPlayer?.setSpeed(newSpeed) }
    }

    @MainThread
    fun setCrop(mode: ScalingMode, focusX: Float, focusY: Float) {
        if (released) return
        render.post {
            rMode = mode
            rFocusX = focusX
            rFocusY = focusY
            renderer?.setCrop(mode, focusX, focusY)
            drawIfVisible()
        }
    }

    /** Loops only [startMs]..[endMs] of a video (0 = no limit). Ignored for GIFs. */
    @MainThread
    fun setTrim(startMs: Long, endMs: Long) {
        if (released || (startMs == trimStartMs && endMs == trimEndMs)) return
        trimStartMs = startMs
        trimEndMs = endMs
        val current = media ?: return
        val p = player ?: return
        if (current.isGif == true) return
        p.setMediaItem(buildMediaItem(current))
        p.prepare()
    }

    /** Jumps back to the start of the loop. */
    @MainThread
    fun restart() {
        if (released) return
        player?.seekTo(0)
        render.post { gifPlayer?.restart() }
    }

    /** Frees everything. The controller can't be used afterwards. */
    @MainThread
    fun release() {
        if (released) return
        detachSurface()
        released = true
        main.removeCallbacksAndMessages(null)
        releasePlayer()
        render.post {
            gifPlayer?.release()
            gifPlayer = null
            gifDecoder = null
            releaseRenderer()
        }
        renderThread.quitSafely()
    }

    // ---- Video (main thread) ----------------------------------------------

    private fun createPlayer(m: WallpaperMedia) {
        val p = ExoPlayer.Builder(appContext).build()
        // Wallpapers are silent: don't even decode the audio track.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        p.volume = 0f
        p.repeatMode = Player.REPEAT_MODE_ONE
        p.setPlaybackSpeed(speed)
        p.addListener(PlayerListener(p))
        rendererInput?.let { p.setVideoSurface(it) }
        p.setMediaItem(buildMediaItem(m))
        p.playWhenReady = visible
        p.prepare()
        player = p
    }

    private fun buildMediaItem(m: WallpaperMedia): MediaItem {
        val builder = MediaItem.Builder().setUri(m.uri)
        if (trimStartMs > 0 || trimEndMs > 0) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(trimStartMs)
                    .setEndPositionMs(if (trimEndMs > trimStartMs) trimEndMs else C.TIME_END_OF_SOURCE)
                    .build()
            )
        }
        return builder.build()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    private inner class PlayerListener(private val owner: ExoPlayer) : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (owner !== player || videoSize.width <= 0 || videoSize.height <= 0) return
            // Media3 already swaps width/height for 90/270° rotation; apply
            // the pixel aspect ratio so anamorphic videos aren't squashed.
            val w = (videoSize.width * videoSize.pixelWidthHeightRatio).roundToInt()
            val h = videoSize.height
            render.post {
                rVideoWidth = w
                rVideoHeight = h
                renderer?.setVideoSize(w, h)
                drawIfVisible()
            }
            listener.onContentSize(w, h)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (owner !== player) return
            if (playbackState == Player.STATE_READY) {
                readySinceMs = android.os.SystemClock.elapsedRealtime()
                listener.onPlaying()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (owner !== player) return
            Log.w(TAG, "Player error ${error.errorCodeName}", error)
            // A file that plays fine for a while between errors gets a fresh
            // set of retries; one that fails again straight away does not, so
            // a file that is broken part-way through can't loop forever.
            if (readySinceMs > 0 &&
                android.os.SystemClock.elapsedRealtime() - readySinceMs > RETRY_RESET_AFTER_MS
            ) retries = 0
            readySinceMs = 0
            when (PlaybackErrors.classify(error.errorCode, error.cause)) {
                PlaybackErrors.Action.SOURCE_MISSING -> listener.onProblem(PlaybackProblem.SOURCE_MISSING)
                PlaybackErrors.Action.UNSUPPORTED -> listener.onProblem(PlaybackProblem.UNSUPPORTED)
                PlaybackErrors.Action.RETRY -> {
                    if (retries >= PlaybackErrors.MAX_RETRIES) {
                        listener.onProblem(PlaybackProblem.PLAYBACK_FAILED)
                        return
                    }
                    retries++
                    val generation = mediaGeneration
                    main.postDelayed({
                        // Re-preparing a player in the error state retries it.
                        if (generation == mediaGeneration && owner === player) owner.prepare()
                    }, PlaybackErrors.retryDelayMs(retries))
                }
            }
        }
    }

    // ---- Render thread ----------------------------------------------------

    private fun createRenderer(target: Surface, width: Int, height: Int, generation: Int) {
        releaseRenderer()
        val r = VideoFrameRenderer()
        if (!r.start(target)) {
            main.post { if (generation == surfaceGeneration) listener.onProblem(PlaybackProblem.RENDERER_FAILED) }
            return
        }
        renderer = r
        rViewWidth = width
        rViewHeight = height
        r.setCrop(rMode, rFocusX, rFocusY)
        r.setVideoSize(rVideoWidth, rVideoHeight)
        r.setContent(rContent)
        r.setVideoFrameListener(render) { onVideoFrame() }
        gifDecoder?.let { r.uploadGifFrame(it.pixels, it.width, it.height) }
        // Draw once even if hidden, so the surface never shows uninitialised memory.
        r.draw(width, height)
        updateGifRunning()
        val input = r.videoInputSurface
        main.post {
            if (generation != surfaceGeneration || released) return@post
            rendererInput = input
            if (input != null) player?.setVideoSurface(input)
        }
    }

    private fun releaseRenderer() {
        gifPlayer?.pause()
        renderer?.release()
        renderer = null
    }

    private fun onVideoFrame() {
        val r = renderer ?: return
        r.latchVideoFrame()
        if (rContent == VideoFrameRenderer.Content.VIDEO) drawIfVisible()
    }

    private fun drawIfVisible() {
        val r = renderer ?: return
        if (!rVisible) return
        if (r.draw(rViewWidth, rViewHeight)) return
        if (r.lastEglError == EGL14.EGL_CONTEXT_LOST) {
            // The GPU reset our context (rare: driver reset, memory pressure).
            // Rebuild renderer and player surface on the same window.
            Log.w(TAG, "EGL context lost, rebuilding renderer")
            main.post { rebuildRenderer() }
        }
    }

    @MainThread
    private fun rebuildRenderer() {
        val target = surface ?: return
        if (rendererRebuilds >= MAX_RENDERER_REBUILDS) {
            listener.onProblem(PlaybackProblem.RENDERER_FAILED)
            return
        }
        rendererRebuilds++
        val w = surfaceWidth
        val h = surfaceHeight
        detachSurface()
        attachSurface(target, w, h)
    }

    private fun showNothing(generation: Int) {
        rMediaGeneration = generation
        stopGif()
        rContent = VideoFrameRenderer.Content.NONE
        renderer?.setContent(rContent)
        drawIfVisible()
    }

    private fun prepareForVideo(generation: Int) {
        rMediaGeneration = generation
        stopGif()
        rVideoWidth = 0
        rVideoHeight = 0
        rContent = VideoFrameRenderer.Content.VIDEO
        renderer?.setVideoSize(0, 0)
        renderer?.setContent(rContent)
        drawIfVisible()
    }

    private fun loadGif(m: WallpaperMedia, generation: Int, initialSpeed: Float) {
        rMediaGeneration = generation
        stopGif()
        rContent = VideoFrameRenderer.Content.NONE
        renderer?.setContent(rContent)
        drawIfVisible()

        val decoder = try {
            GifDecoder.parse(GifFiles.readBytes(appContext.contentResolver, m.uri))
        } catch (e: GifFormatException) {
            Log.w(TAG, "GIF can't be decoded", e)
            reportFromRender(generation, PlaybackProblem.UNSUPPORTED)
            return
        } catch (e: IOException) {
            Log.w(TAG, "GIF can't be read", e)
            reportFromRender(generation, PlaybackProblem.SOURCE_MISSING)
            return
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to read GIF", e)
            reportFromRender(generation, PlaybackProblem.SOURCE_MISSING)
            return
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory loading GIF", e)
            reportFromRender(generation, PlaybackProblem.UNSUPPORTED)
            return
        }
        if (generation != rMediaGeneration) return

        gifDecoder = decoder
        rContent = VideoFrameRenderer.Content.GIF
        renderer?.setContent(rContent)
        gifPlayer = GifPlayer(render, decoder) { d -> onGifFrame(d) }.also { it.setSpeed(initialSpeed) }
        updateGifRunning()
        main.post {
            if (generation != mediaGeneration) return@post
            listener.onContentSize(decoder.width, decoder.height)
            listener.onPlaying()
        }
    }

    private fun onGifFrame(d: GifDecoder): Boolean {
        val r = renderer ?: return true // No surface right now; keep the player, it's paused.
        if (!r.uploadGifFrame(d.pixels, d.width, d.height)) {
            main.post { listener.onProblem(PlaybackProblem.RENDERER_FAILED) }
            return false
        }
        drawIfVisible()
        return true
    }

    /** GIF frames are only worth decoding while visible and attached. */
    private fun updateGifRunning() {
        val p = gifPlayer ?: return
        if (rVisible && renderer != null) p.start() else p.pause()
    }

    private fun stopGif() {
        gifPlayer?.release()
        gifPlayer = null
        gifDecoder = null
    }

    private fun reportFromRender(generation: Int, problem: PlaybackProblem) {
        main.post { if (generation == mediaGeneration) listener.onProblem(problem) }
    }

    companion object {
        private const val TAG = "PlaybackController"
        private const val DETACH_TIMEOUT_MS = 1000L
        private const val MAX_RENDERER_REBUILDS = 3
        private const val RETRY_RESET_AFTER_MS = 30_000L
    }
}
