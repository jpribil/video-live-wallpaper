package io.github.codeg0blin.videowallpaper

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

/** The file the wallpaper plays. [isGif] is null for selections saved by app versions before 2.0. */
data class WallpaperMedia(val uri: Uri, val isGif: Boolean?)

/** Everything the wallpaper and the preview need to know, read in one go. */
data class WallpaperSettings(
    val media: WallpaperMedia?,
    val displayName: String?,
    val durationMs: Long,
    val speed: Float,
    val scalingMode: ScalingMode,
    val focusX: Float,
    val focusY: Float,
    /** Start of the looped section in ms. 0 = from the beginning. */
    val trimStartMs: Long,
    /** End of the looped section in ms. 0 = to the end. */
    val trimEndMs: Long,
    val parallax: Boolean,
    val pauseOnPowerSave: Boolean,
    val restartOnShow: Boolean
) {
    /** Trim only applies to videos; GIFs always play whole. */
    val hasTrim: Boolean get() = media?.isGif != true && (trimStartMs > 0 || trimEndMs > 0)
}

/** Why the wallpaper last failed to play, written by the service and shown by the app. */
enum class PlaybackProblem {
    /** The file is gone or can no longer be read. */
    SOURCE_MISSING,

    /** The file can be read but the device can't decode it. */
    UNSUPPORTED,

    /** Repeated decoder/player failures that retrying didn't fix. */
    PLAYBACK_FAILED,

    /** OpenGL could not be set up on this surface. */
    RENDERER_FAILED
}

/**
 * Typed access to the app's settings.
 *
 * Uses the same file that `PreferenceManager.getDefaultSharedPreferences`
 * used up to version 1.3, so settings survive the upgrade without a
 * migration step. Keys and the stored scaling-mode numbers are unchanged
 * for the same reason.
 *
 * The app and the wallpaper service run in the same process, so a write
 * from one is seen by the other's [SharedPreferences.OnSharedPreferenceChangeListener]
 * straight away; that is how settings changes reach a running wallpaper.
 */
class SettingsStore(context: Context) {

    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)

    fun load(): WallpaperSettings {
        val uriString = prefs.getString(KEY_MEDIA_URI, null)
        val media = uriString?.takeIf { it.isNotBlank() }?.let { s ->
            val kind = prefs.getString(KEY_MEDIA_KIND, null)
            WallpaperMedia(s.toUri(), kind?.let { it == KIND_GIF })
        }
        return WallpaperSettings(
            media = media,
            displayName = prefs.getString(KEY_DISPLAY_NAME, null),
            durationMs = prefs.getLong(KEY_DURATION_MS, 0L),
            speed = prefs.getFloat(KEY_SPEED, DEFAULT_SPEED).coerceIn(MIN_SPEED, MAX_SPEED),
            scalingMode = scalingModeFromStored(prefs.getInt(KEY_SCALING_MODE, STORED_FILL)),
            focusX = prefs.getFloat(KEY_FOCUS_X, 0.5f).coerceIn(0f, 1f),
            focusY = prefs.getFloat(KEY_FOCUS_Y, 0.5f).coerceIn(0f, 1f),
            trimStartMs = prefs.getLong(KEY_TRIM_START_MS, 0L).coerceAtLeast(0L),
            trimEndMs = prefs.getLong(KEY_TRIM_END_MS, 0L).coerceAtLeast(0L),
            parallax = prefs.getBoolean(KEY_PARALLAX, false),
            pauseOnPowerSave = prefs.getBoolean(KEY_PAUSE_ON_POWER_SAVE, false),
            restartOnShow = prefs.getBoolean(KEY_RESTART_ON_SHOW, false)
        )
    }

    /**
     * Makes [media] the wallpaper. Crop focus and trim are reset because they
     * belong to the previous file, and any old error is cleared.
     */
    fun setMedia(media: WallpaperMedia, displayName: String?, durationMs: Long) = prefs.edit {
        putString(KEY_MEDIA_KIND, if (media.isGif == true) KIND_GIF else KIND_VIDEO)
        putString(KEY_DISPLAY_NAME, displayName)
        putLong(KEY_DURATION_MS, durationMs)
        putFloat(KEY_FOCUS_X, 0.5f)
        putFloat(KEY_FOCUS_Y, 0.5f)
        putLong(KEY_TRIM_START_MS, 0L)
        putLong(KEY_TRIM_END_MS, 0L)
        remove(KEY_LAST_PROBLEM)
        // Written last so listeners reacting to the URI see the rest already.
        putString(KEY_MEDIA_URI, media.uri.toString())
    }

    fun setSpeed(speed: Float) = prefs.edit { putFloat(KEY_SPEED, speed.coerceIn(MIN_SPEED, MAX_SPEED)) }

    fun setScalingMode(mode: ScalingMode) = prefs.edit { putInt(KEY_SCALING_MODE, scalingModeToStored(mode)) }

    fun setFocus(x: Float, y: Float) = prefs.edit {
        putFloat(KEY_FOCUS_X, x.coerceIn(0f, 1f))
        putFloat(KEY_FOCUS_Y, y.coerceIn(0f, 1f))
    }

    fun setTrim(startMs: Long, endMs: Long) = prefs.edit {
        putLong(KEY_TRIM_START_MS, startMs.coerceAtLeast(0L))
        putLong(KEY_TRIM_END_MS, endMs.coerceAtLeast(0L))
    }

    fun setParallax(enabled: Boolean) = prefs.edit { putBoolean(KEY_PARALLAX, enabled) }
    fun setPauseOnPowerSave(enabled: Boolean) = prefs.edit { putBoolean(KEY_PAUSE_ON_POWER_SAVE, enabled) }
    fun setRestartOnShow(enabled: Boolean) = prefs.edit { putBoolean(KEY_RESTART_ON_SHOW, enabled) }

    fun lastProblem(): PlaybackProblem? =
        prefs.getString(KEY_LAST_PROBLEM, null)?.let { name -> PlaybackProblem.entries.firstOrNull { it.name == name } }

    fun setLastProblem(problem: PlaybackProblem?) = prefs.edit {
        if (problem == null) remove(KEY_LAST_PROBLEM) else putString(KEY_LAST_PROBLEM, problem.name)
    }

    companion object {
        // Keys from version 1.x — do not rename.
        const val KEY_MEDIA_URI = "selected_video_uri"
        const val KEY_SPEED = "playback_speed"
        const val KEY_SCALING_MODE = "scaling_mode"

        // Added in 2.0.
        const val KEY_MEDIA_KIND = "media_kind"
        const val KEY_DISPLAY_NAME = "media_display_name"
        const val KEY_DURATION_MS = "media_duration_ms"
        const val KEY_FOCUS_X = "crop_focus_x"
        const val KEY_FOCUS_Y = "crop_focus_y"
        const val KEY_TRIM_START_MS = "trim_start_ms"
        const val KEY_TRIM_END_MS = "trim_end_ms"
        const val KEY_PARALLAX = "parallax"
        const val KEY_PAUSE_ON_POWER_SAVE = "pause_on_power_save"
        const val KEY_RESTART_ON_SHOW = "restart_on_show"
        const val KEY_LAST_PROBLEM = "last_problem"

        private const val KIND_GIF = "gif"
        private const val KIND_VIDEO = "video"

        const val DEFAULT_SPEED = 1.0f
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 2.0f

        // Version 1.x stored MediaPlayer.VIDEO_SCALING_MODE_* numbers.
        private const val STORED_FIT = 1
        private const val STORED_FILL = 2

        fun scalingModeFromStored(value: Int): ScalingMode = if (value == STORED_FIT) ScalingMode.FIT else ScalingMode.FILL
        fun scalingModeToStored(mode: ScalingMode): Int = if (mode == ScalingMode.FIT) STORED_FIT else STORED_FILL
    }
}
