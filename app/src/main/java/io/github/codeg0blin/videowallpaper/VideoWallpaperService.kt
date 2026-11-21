package io.github.codeg0blin.videowallpaper

import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The live wallpaper. Each [Engine] (Android may create several at once, for
 * example the system picker's preview next to the real wallpaper) owns its
 * own [PlaybackController]; this class only translates wallpaper events and
 * settings into controller calls.
 */
class VideoWallpaperService : WallpaperService() {

    /** Background thread for grabbing a frame for the wallpaper colours. */
    private lateinit var io: ExecutorService

    override fun onCreate() {
        super.onCreate()
        io = Executors.newSingleThreadExecutor()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onCreateEngine(): Engine = VideoEngine()

    inner class VideoEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener, PlaybackController.Listener {

        private val main = Handler(Looper.getMainLooper())
        private val store = SettingsStore(this@VideoWallpaperService)
        private lateinit var controller: PlaybackController
        private var settings = store.load()

        private var systemVisible = false
        private var effectiveVisible = false
        private var powerSave = false
        private var homeScreenOffsetX = 0.5f
        private var colors: WallpaperColors? = null
        private var mediaRequest = 0

        private val powerSaveReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                powerSave = isPowerSaveMode()
                updateVisibility()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            controller = PlaybackController(this@VideoWallpaperService, this)
            // Needed for the parallax option; harmless otherwise.
            setOffsetNotificationsEnabled(true)
            store.prefs.registerOnSharedPreferenceChangeListener(this)
            ContextCompat.registerReceiver(
                this@VideoWallpaperService,
                powerSaveReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            powerSave = isPowerSaveMode()
            applySettings(mediaChanged = true)
        }

        override fun onDestroy() {
            store.prefs.unregisterOnSharedPreferenceChangeListener(this)
            try {
                unregisterReceiver(powerSaveReceiver)
            } catch (e: IllegalArgumentException) {
                // Not registered.
            }
            main.removeCallbacksAndMessages(null)
            controller.release()
            super.onDestroy()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            val frame = holder.surfaceFrame
            controller.attachSurface(holder.surface, frame.width(), frame.height())
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            controller.resizeSurface(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            // Blocks until the EGL surface is gone, as the system expects.
            controller.detachSurface()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            systemVisible = visible
            updateVisibility()
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int
        ) {
            // Launchers that support it report 0..1 as the user swipes across
            // home screen pages. Lock screen and preview stay at the centre.
            homeScreenOffsetX = if (isPreview) 0.5f else xOffset.coerceIn(0f, 1f)
            if (settings.parallax) applyCrop()
        }

        @RequiresApi(Build.VERSION_CODES.O_MR1)
        override fun onComputeColors(): WallpaperColors? = colors

        // ---- Settings ------------------------------------------------------

        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            // One edit can change several keys and this runs once per key, but
            // load() always sees the whole edit, so compare snapshots instead
            // of reacting to individual keys.
            val old = settings
            val new = store.load()
            if (new == old) return
            settings = new
            val mediaChanged = new.media != old.media
            applySettings(mediaChanged)
            if (!mediaChanged && new.trimStartMs != old.trimStartMs) refreshColors()
        }

        private fun applySettings(mediaChanged: Boolean) {
            controller.setSpeed(settings.speed)
            applyCrop()
            controller.setTrim(settings.trimStartMs, settings.trimEndMs)
            updateVisibility()
            if (mediaChanged) loadMedia()
        }

        private fun applyCrop() {
            val focusX = if (settings.parallax && settings.scalingMode == ScalingMode.FILL) homeScreenOffsetX else settings.focusX
            controller.setCrop(settings.scalingMode, focusX, settings.focusY)
        }

        private fun loadMedia() {
            val media = settings.media
            ++mediaRequest
            if (media == null) {
                controller.setMedia(null)
                return
            }
            controller.setMedia(media)
            refreshColors()
        }

        private fun refreshColors() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
            val media = settings.media ?: return
            val at = settings.trimStartMs
            val request = mediaRequest
            io.execute {
                val frame = FrameGrabber.grab(this@VideoWallpaperService, media, at) ?: return@execute
                val computed = WallpaperColors.fromBitmap(frame)
                frame.recycle()
                main.post {
                    if (request != mediaRequest) return@post
                    colors = computed
                    Log.i(TAG, "Wallpaper colours: primary=${computed.primaryColor}")
                    notifyColorsChanged()
                }
            }
        }

        // ---- Visibility ----------------------------------------------------

        private fun updateVisibility() {
            val shouldPlay = systemVisible && !(settings.pauseOnPowerSave && powerSave)
            if (shouldPlay == effectiveVisible) return
            effectiveVisible = shouldPlay
            if (shouldPlay && settings.restartOnShow) controller.restart()
            controller.setVisible(shouldPlay)
        }

        private fun isPowerSaveMode(): Boolean =
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode

        // ---- PlaybackController.Listener ----------------------------------

        override fun onProblem(problem: PlaybackProblem) {
            // Never clear the selection: just record what went wrong so the
            // app can explain it. The preview engine stays quiet, the system
            // picker shows its own state.
            if (!isPreview) store.setLastProblem(problem)
        }

        override fun onPlaying() {
            if (!isPreview && store.lastProblem() != null) store.setLastProblem(null)
        }
    }

    private companion object {
        const val TAG = "VideoWallpaperService"
    }
}
