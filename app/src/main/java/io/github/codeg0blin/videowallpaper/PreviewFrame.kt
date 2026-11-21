package io.github.codeg0blin.videowallpaper

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * The preview's container: shaped like the phone's screen, so Fill crops in
 * the preview exactly as it will on the wallpaper, and clipped to rounded
 * corners (which also clips the TextureView inside).
 *
 * It takes the available width, then shrinks (keeping the shape) if that
 * would make it taller than [maxHeightPx].
 */
class PreviewFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** Screen width / height. */
    var aspectRatio: Float = 9f / 19.5f
        set(value) {
            if (value > 0f && value != field) {
                field = value
                requestLayout()
            }
        }

    var maxHeightPx: Int = Int.MAX_VALUE
        set(value) {
            if (value != field) {
                field = value
                requestLayout()
            }
        }

    private val cornerRadius = resources.displayMetrics.density * 20f

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        clipToOutline = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        var height = (availableWidth / aspectRatio).roundToInt()
        var width = availableWidth
        if (height > maxHeightPx) {
            height = maxHeightPx
            width = (height * aspectRatio).roundToInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }
}
