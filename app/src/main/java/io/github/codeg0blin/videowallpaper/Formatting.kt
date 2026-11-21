package io.github.codeg0blin.videowallpaper

import java.util.Locale
import kotlin.math.roundToInt

/** Small, pure helpers for the settings screen (unit tested). */
object Formatting {

    /** m:ss.t, e.g. 65 300 ms → "1:05.3". Negative values count as 0. */
    fun time(ms: Long): String {
        val tenths = (ms.coerceAtLeast(0) + 50) / 100
        val minutes = tenths / 600
        val seconds = (tenths / 10) % 60
        return String.format(Locale.ROOT, "%d:%02d.%d", minutes, seconds, tenths % 10)
    }

    /**
     * Rounds [value] onto a slider's step grid ([from] + n * [step]) and into
     * range. Material's Slider throws if given a value off its grid, which a
     * speed saved by an older version could be.
     */
    fun snapToStep(value: Float, from: Float, to: Float, step: Float): Float {
        val steps = ((value - from) / step).roundToInt()
        return (from + steps * step).coerceIn(from, to)
    }
}
