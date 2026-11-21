package io.github.codeg0blin.videowallpaper

/** How a picture whose aspect ratio differs from the screen's is placed on it. */
enum class ScalingMode {
    /** Cover the whole screen, cropping whatever sticks out. */
    FILL,

    /** Show the whole picture, leaving black bars where it doesn't reach. */
    FIT
}

/**
 * One textured rectangle, ready to be turned into vertex data.
 *
 * Positions are in GL clip space (-1..1, y up). Texture coordinates use the
 * picture's own convention: u = 0 at its left edge, v = 0 at its **top** edge.
 * The renderer maps that to whatever its texture source needs.
 */
data class Quad(
    val left: Float,
    val bottom: Float,
    val right: Float,
    val top: Float,
    val uLeft: Float,
    val uRight: Float,
    val vTop: Float,
    val vBottom: Float
)

/**
 * Fill/Fit placement math, shared by the wallpaper renderer and the in-app
 * preview's drag-to-pan gesture. Pure Kotlin so it can be unit tested on a
 * plain JVM.
 */
object CropGeometry {

    /** Used before the picture's or the screen's size is known. */
    val FULL_SCREEN = Quad(-1f, -1f, 1f, 1f, 0f, 1f, 0f, 1f)

    /**
     * Where to draw a [contentWidth] x [contentHeight] picture on a
     * [viewWidth] x [viewHeight] surface.
     *
     * In [ScalingMode.FILL] the quad covers the whole surface and only a
     * matching-aspect slice of the picture is sampled. [focusX] / [focusY]
     * (0..1) pick which slice: 0 shows the left/top edge, 1 the right/bottom
     * edge, 0.5 the centre. They only matter on the axis that overflows.
     *
     * In [ScalingMode.FIT] the whole picture is sampled and the quad is
     * shrunk (and centred) so it keeps its aspect ratio; focus is ignored.
     */
    fun compute(
        viewWidth: Int,
        viewHeight: Int,
        contentWidth: Int,
        contentHeight: Int,
        mode: ScalingMode,
        focusX: Float = 0.5f,
        focusY: Float = 0.5f
    ): Quad {
        if (viewWidth <= 0 || viewHeight <= 0 || contentWidth <= 0 || contentHeight <= 0) {
            return FULL_SCREEN
        }
        val viewAspect = viewWidth.toFloat() / viewHeight
        val contentAspect = contentWidth.toFloat() / contentHeight
        val fx = focusX.coerceIn(0f, 1f)
        val fy = focusY.coerceIn(0f, 1f)

        return when (mode) {
            ScalingMode.FIT -> {
                if (contentAspect > viewAspect) {
                    // Wider than the screen: bars above and below.
                    val h = viewAspect / contentAspect
                    Quad(-1f, -h, 1f, h, 0f, 1f, 0f, 1f)
                } else {
                    // Taller than the screen: bars left and right.
                    val w = contentAspect / viewAspect
                    Quad(-w, -1f, w, 1f, 0f, 1f, 0f, 1f)
                }
            }
            ScalingMode.FILL -> {
                if (contentAspect > viewAspect) {
                    // Wider than the screen: crop left/right.
                    val visible = viewAspect / contentAspect
                    val u0 = (1f - visible) * fx
                    Quad(-1f, -1f, 1f, 1f, u0, u0 + visible, 0f, 1f)
                } else {
                    // Taller than the screen: crop top/bottom.
                    val visible = contentAspect / viewAspect
                    val v0 = (1f - visible) * fy
                    Quad(-1f, -1f, 1f, 1f, 0f, 1f, v0, v0 + visible)
                }
            }
        }
    }

    /**
     * How many view pixels of the picture are cut off along each axis in
     * Fill mode (the picture scaled to cover the view, minus the view). Zero
     * on the axis that fits exactly. Used to turn a finger drag in pixels
     * into a focus change: dragging by the full overflow moves focus 0 → 1.
     */
    fun fillOverflow(viewWidth: Int, viewHeight: Int, contentWidth: Int, contentHeight: Int): Pair<Float, Float> {
        if (viewWidth <= 0 || viewHeight <= 0 || contentWidth <= 0 || contentHeight <= 0) return 0f to 0f
        val scale = maxOf(viewWidth.toFloat() / contentWidth, viewHeight.toFloat() / contentHeight)
        val overflowX = (contentWidth * scale - viewWidth).coerceAtLeast(0f)
        val overflowY = (contentHeight * scale - viewHeight).coerceAtLeast(0f)
        return overflowX to overflowY
    }
}
