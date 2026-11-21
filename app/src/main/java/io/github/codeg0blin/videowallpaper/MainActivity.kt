package io.github.codeg0blin.videowallpaper

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.slider.RangeSlider
import io.github.codeg0blin.videowallpaper.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lets the user pick a video or GIF, adjust how it plays, and set it as the
 * live wallpaper through the system's live-wallpaper picker.
 *
 * The preview is drawn by the same [PlaybackController] the wallpaper uses,
 * in a frame shaped like the screen, so what you see here is what the
 * wallpaper will show. Every control writes straight to [SettingsStore]; a
 * running wallpaper picks changes up immediately.
 */
class MainActivity : AppCompatActivity(), PlaybackController.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SettingsStore
    private lateinit var controller: PlaybackController
    private val importer: ImportViewModel by viewModels()

    private var settings: WallpaperSettings? = null
    private var previewSurface: Surface? = null
    private var contentWidth = 0
    private var contentHeight = 0
    private var previewProblem: PlaybackProblem? = null

    // Live crop focus while dragging; written to settings when the finger lifts.
    private var dragFocusX = 0.5f
    private var dragFocusY = 0.5f
    private var dragging = false

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        // Persist the grant so the copy can finish even if this activity is
        // recreated mid-way; it is released again once the copy is done.
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Some providers don't offer persistable grants; the temporary
            // grant still covers a copy that finishes while we're alive.
        }
        importer.import(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so always use light system-bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        store = SettingsStore(this)
        controller = PlaybackController(this, this)
        binding.versionText.text = getString(R.string.version_format, appVersionName())

        setUpPreview()
        setUpControls()
        observeImports()

        renderSettings(store.load(), mediaChanged = true)
        store.prefs.registerOnSharedPreferenceChangeListener(this)

        if (savedInstanceState == null) {
            importer.migrateLegacySelection()
            importer.cleanUp()
        }
    }

    override fun onStart() {
        super.onStart()
        controller.setVisible(true)
    }

    override fun onResume() {
        super.onResume()
        updateActiveState()
        updateProblem()
    }

    override fun onStop() {
        controller.setVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        store.prefs.unregisterOnSharedPreferenceChangeListener(this)
        controller.release()
        previewSurface?.release()
        previewSurface = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    /** targetSdk 35+ always draws edge-to-edge; keep content clear of the system bars. */
    private fun applyWindowInsets() {
        val content = binding.content
        val baseTop = content.paddingTop
        val baseBottom = content.paddingBottom
        val baseStart = content.paddingStart
        val baseEnd = content.paddingEnd
        ViewCompat.setOnApplyWindowInsetsListener(binding.scroll) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            content.updatePadding(
                left = baseStart + bars.left,
                top = baseTop + bars.top,
                right = baseEnd + bars.right,
                bottom = baseBottom + bars.bottom
            )
            insets
        }
    }

    // ---- Preview ----------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpPreview() {
        val frame = binding.previewFrame
        frame.aspectRatio = screenAspectRatio()
        // Leave room for the controls: at most a bit under half the screen tall.
        frame.maxHeightPx = (resources.displayMetrics.heightPixels * 0.45f).roundToInt()

        binding.previewTexture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                val s = Surface(st)
                previewSurface = s
                controller.attachSurface(s, width, height)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                controller.resizeSurface(width, height)
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                controller.detachSurface()
                previewSurface?.release()
                previewSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = canPan()

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (!canPan()) return false
                val (overflowX, overflowY) = CropGeometry.fillOverflow(
                    frame.width, frame.height, contentWidth, contentHeight
                )
                // The picture follows the finger: dragging right reveals
                // more of its left side, i.e. moves the focus left.
                if (overflowX >= 1f) dragFocusX = (dragFocusX + distanceX / overflowX).coerceIn(0f, 1f)
                if (overflowY >= 1f) dragFocusY = (dragFocusY + distanceY / overflowY).coerceIn(0f, 1f)
                dragging = true
                frame.parent.requestDisallowInterceptTouchEvent(true)
                controller.setCrop(ScalingMode.FILL, dragFocusX, dragFocusY)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!canPan()) return false
                dragFocusX = 0.5f
                dragFocusY = 0.5f
                store.setFocus(0.5f, 0.5f)
                return true
            }
        })
        frame.setOnTouchListener { _, event ->
            val handled = gestures.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (dragging) {
                    dragging = false
                    store.setFocus(dragFocusX, dragFocusY)
                }
            }
            handled
        }
    }

    /** Fill mode with a picture that overflows the screen on some axis. */
    private fun canPan(): Boolean {
        val s = settings ?: return false
        if (s.media == null || s.scalingMode != ScalingMode.FILL) return false
        val (ox, oy) = CropGeometry.fillOverflow(
            binding.previewFrame.width, binding.previewFrame.height, contentWidth, contentHeight
        )
        return ox >= 1f || oy >= 1f
    }

    /** Width / height of the screen in portrait, which is the wallpaper's shape. */
    private fun screenAspectRatio(): Float {
        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = resources.displayMetrics
            m.widthPixels to m.heightPixels
        }
        if (w <= 0 || h <= 0) return 9f / 19.5f
        return min(w, h).toFloat() / max(w, h)
    }

    // ---- Controls ---------------------------------------------------------

    private fun setUpControls() {
        binding.pickButton.setOnClickListener {
            pickLauncher.launch(arrayOf("video/*", "image/gif"))
        }
        binding.setWallpaperButton.setOnClickListener { launchLiveWallpaperPicker() }

        binding.speedSlider.addOnChangeListener { _, value, fromUser ->
            binding.speedValueText.text = formatSpeed(value)
            if (fromUser) {
                controller.setSpeed(value)
                // Cheap to apply, so no debounce is needed; the wallpaper
                // just changes its playback speed in place.
                store.setSpeed(value)
            }
        }

        binding.cropToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = if (checkedId == binding.cropFitButton.id) ScalingMode.FIT else ScalingMode.FILL
            if (settings?.scalingMode != mode) store.setScalingMode(mode)
        }

        binding.trimSlider.setLabelFormatter { Formatting.time((it * 1000).toLong()) }
        binding.trimSlider.addOnChangeListener { slider, _, _ ->
            binding.trimValueText.text = trimLabel(slider)
        }
        binding.trimSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) = Unit
            override fun onStopTrackingTouch(slider: RangeSlider) = saveTrim(slider)
        })

        binding.parallaxSwitch.setOnCheckedChangeListener { b, checked -> if (b.isPressed) store.setParallax(checked) }
        binding.powerSaveSwitch.setOnCheckedChangeListener { b, checked -> if (b.isPressed) store.setPauseOnPowerSave(checked) }
        binding.restartSwitch.setOnCheckedChangeListener { b, checked -> if (b.isPressed) store.setRestartOnShow(checked) }
    }

    private fun saveTrim(slider: RangeSlider) {
        val s = settings ?: return
        val values = slider.values
        val startMs = (values[0] * 1000).toLong()
        val endMs = (values[1] * 1000).toLong()
        // Selecting the full length means "no trim".
        val storedEnd = if (endMs >= s.durationMs - END_SNAP_MS) 0L else endMs
        val storedStart = if (startMs <= START_SNAP_MS) 0L else startMs
        store.setTrim(storedStart, storedEnd)
    }

    private fun trimLabel(slider: RangeSlider): String {
        val v = slider.values
        return getString(R.string.trim_format, Formatting.time((v[0] * 1000).toLong()), Formatting.time((v[1] * 1000).toLong()))
    }

    private fun formatSpeed(speed: Float) = getString(R.string.speed_format, speed)

    // ---- Settings → UI ----------------------------------------------------

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key == SettingsStore.KEY_LAST_PROBLEM) {
            updateProblem()
            return
        }
        val new = store.load()
        if (new == settings) return
        renderSettings(new, mediaChanged = new.media != settings?.media)
    }

    private fun renderSettings(s: WallpaperSettings, mediaChanged: Boolean) {
        settings = s
        val hasMedia = s.media != null
        val isGif = s.media?.isGif == true

        if (mediaChanged) {
            contentWidth = 0
            contentHeight = 0
            previewProblem = null
            controller.setTrim(s.trimStartMs, s.trimEndMs)
            loadPreviewMedia(s.media)
            updateProblem()
        }
        controller.setSpeed(s.speed)
        controller.setTrim(s.trimStartMs, s.trimEndMs)
        if (!dragging) {
            dragFocusX = s.focusX
            dragFocusY = s.focusY
            controller.setCrop(s.scalingMode, s.focusX, s.focusY)
        }

        binding.noVideoText.isVisible = !hasMedia
        binding.setWallpaperButton.isEnabled = hasMedia
        binding.fileNameText.isVisible = hasMedia && !s.displayName.isNullOrBlank()
        binding.fileNameText.text = s.displayName

        binding.speedSlider.value = Formatting.snapToStep(s.speed, SettingsStore.MIN_SPEED, SettingsStore.MAX_SPEED, 0.05f)
        binding.speedValueText.text = formatSpeed(s.speed)

        val checkId = if (s.scalingMode == ScalingMode.FIT) binding.cropFitButton.id else binding.cropFillButton.id
        if (binding.cropToggleGroup.checkedButtonId != checkId) binding.cropToggleGroup.check(checkId)

        val showTrim = hasMedia && !isGif && s.durationMs >= MIN_TRIM_DURATION_MS
        binding.trimSection.isVisible = showTrim
        if (showTrim) {
            val total = s.durationMs / 1000f
            val start = (s.trimStartMs / 1000f).coerceIn(0f, total)
            val end = if (s.trimEndMs > 0) (s.trimEndMs / 1000f).coerceIn(start, total) else total
            binding.trimSlider.valueFrom = 0f
            binding.trimSlider.valueTo = total
            binding.trimSlider.setMinSeparationValue(min(MIN_TRIM_SECONDS, total))
            binding.trimSlider.values = listOf(start, end)
            binding.trimValueText.text = trimLabel(binding.trimSlider)
        }

        binding.parallaxSwitch.isChecked = s.parallax
        binding.parallaxSwitch.isEnabled = s.scalingMode == ScalingMode.FILL
        binding.powerSaveSwitch.isChecked = s.pauseOnPowerSave
        binding.restartSwitch.isChecked = s.restartOnShow

        updatePanHint()
    }

    private fun loadPreviewMedia(media: WallpaperMedia?) {
        if (media == null || media.isGif != null) {
            controller.setMedia(media)
            return
        }
        // A 1.x selection whose type isn't stored yet: find out off the main thread.
        lifecycleScope.launch {
            val known = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                MediaLibrary(this@MainActivity).detectKind(media)
            }
            if (settings?.media == media) controller.setMedia(known)
        }
    }

    private fun updatePanHint() {
        binding.panHintText.isVisible = canPan()
    }

    private fun updateProblem() {
        val problem = previewProblem ?: store.lastProblem()
        binding.problemCard.isVisible = problem != null && settings?.media != null
        binding.problemText.text = when (problem) {
            PlaybackProblem.SOURCE_MISSING -> getString(R.string.problem_source_missing)
            PlaybackProblem.UNSUPPORTED -> getString(R.string.problem_unsupported)
            PlaybackProblem.PLAYBACK_FAILED -> getString(R.string.problem_playback_failed)
            PlaybackProblem.RENDERER_FAILED -> getString(R.string.problem_renderer_failed)
            null -> ""
        }
    }

    private fun updateActiveState() {
        val active = try {
            WallpaperManager.getInstance(this).wallpaperInfo?.component ==
                ComponentName(this, VideoWallpaperService::class.java)
        } catch (e: RuntimeException) {
            false
        }
        binding.activeText.isVisible = active
    }

    // ---- Imports ----------------------------------------------------------

    private fun observeImports() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                importer.state.collect { state -> renderImport(state) }
            }
        }
    }

    private fun renderImport(state: ImportViewModel.State) {
        val copying = state is ImportViewModel.State.Copying
        binding.importOverlay.isVisible = copying
        binding.pickButton.isEnabled = !copying
        if (state is ImportViewModel.State.Copying) {
            val indicator = binding.importProgress
            if (state.progress < 0f) {
                if (!indicator.isIndeterminate) {
                    indicator.visibility = View.INVISIBLE
                    indicator.isIndeterminate = true
                    indicator.visibility = View.VISIBLE
                }
                binding.importText.text = getString(R.string.importing)
            } else {
                if (indicator.isIndeterminate) {
                    indicator.visibility = View.INVISIBLE
                    indicator.isIndeterminate = false
                    indicator.visibility = View.VISIBLE
                }
                val percent = (state.progress * 100).roundToInt()
                indicator.setProgressCompat(percent, true)
                binding.importText.text = getString(R.string.importing_percent, percent)
            }
        }
        if (state is ImportViewModel.State.Failed) {
            val message = when (state.reason) {
                MediaLibrary.Reason.UNREADABLE -> R.string.import_unreadable
                MediaLibrary.Reason.NO_SPACE -> R.string.import_no_space
                MediaLibrary.Reason.NOT_A_VIDEO -> R.string.import_not_video
                MediaLibrary.Reason.GIF_UNUSABLE -> R.string.error_gif_unusable
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            importer.acknowledgeFailure()
        }
    }

    // ---- PlaybackController.Listener ----------------------------------------

    override fun onContentSize(width: Int, height: Int) {
        contentWidth = width
        contentHeight = height
        updatePanHint()
    }

    override fun onProblem(problem: PlaybackProblem) {
        previewProblem = problem
        updateProblem()
    }

    override fun onPlaying() {
        if (previewProblem != null) {
            previewProblem = null
            updateProblem()
        }
    }

    // ---- Set wallpaper ----------------------------------------------------

    private fun launchLiveWallpaperPicker() {
        if (settings?.media == null) {
            Toast.makeText(this, R.string.select_video_first, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, VideoWallpaperService::class.java)
        )
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.error_live_wallpaper_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val MIN_TRIM_DURATION_MS = 1500L
        private const val MIN_TRIM_SECONDS = 0.5f
        private const val START_SNAP_MS = 50L
        private const val END_SNAP_MS = 50L
    }
}
