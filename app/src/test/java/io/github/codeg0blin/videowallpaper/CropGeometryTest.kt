package io.github.codeg0blin.videowallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class CropGeometryTest {

    private val eps = 1e-5f

    private fun assertQuad(expected: Quad, actual: Quad) {
        assertEquals("left", expected.left, actual.left, eps)
        assertEquals("bottom", expected.bottom, actual.bottom, eps)
        assertEquals("right", expected.right, actual.right, eps)
        assertEquals("top", expected.top, actual.top, eps)
        assertEquals("uLeft", expected.uLeft, actual.uLeft, eps)
        assertEquals("uRight", expected.uRight, actual.uRight, eps)
        assertEquals("vTop", expected.vTop, actual.vTop, eps)
        assertEquals("vBottom", expected.vBottom, actual.vBottom, eps)
    }

    @Test
    fun unknownSizesGiveFullScreen() {
        assertQuad(CropGeometry.FULL_SCREEN, CropGeometry.compute(0, 100, 10, 10, ScalingMode.FILL))
        assertQuad(CropGeometry.FULL_SCREEN, CropGeometry.compute(100, 100, 0, 10, ScalingMode.FIT))
    }

    @Test
    fun sameAspectIsFullScreenInBothModes() {
        for (mode in ScalingMode.entries) {
            assertQuad(CropGeometry.FULL_SCREEN, CropGeometry.compute(1080, 1920, 540, 960, mode))
        }
    }

    @Test
    fun fillCropsWideVideoLeftAndRightAroundCentre() {
        // 16:9 video on a 9:16 screen.
        val visible = (1080f / 1920f) / (1920f / 1080f)
        val margin = (1 - visible) / 2
        val q = CropGeometry.compute(1080, 1920, 1920, 1080, ScalingMode.FILL)
        assertQuad(Quad(-1f, -1f, 1f, 1f, margin, 1 - margin, 0f, 1f), q)
    }

    @Test
    fun fillFocusMovesTheCropWindow() {
        val visible = (1080f / 1920f) / (1920f / 1080f)
        val left = CropGeometry.compute(1080, 1920, 1920, 1080, ScalingMode.FILL, focusX = 0f)
        assertEquals(0f, left.uLeft, eps)
        assertEquals(visible, left.uRight, eps)
        val right = CropGeometry.compute(1080, 1920, 1920, 1080, ScalingMode.FILL, focusX = 1f)
        assertEquals(1f - visible, right.uLeft, eps)
        assertEquals(1f, right.uRight, eps)
        // focusY is irrelevant when only the width overflows.
        val y = CropGeometry.compute(1080, 1920, 1920, 1080, ScalingMode.FILL, focusX = 0f, focusY = 1f)
        assertQuad(left, y)
    }

    @Test
    fun fillCropsTallVideoTopAndBottom() {
        // 1:2 picture in a 1:1 view: half the height is visible.
        val top = CropGeometry.compute(500, 500, 100, 200, ScalingMode.FILL, focusY = 0f)
        assertQuad(Quad(-1f, -1f, 1f, 1f, 0f, 1f, 0f, 0.5f), top)
        val bottom = CropGeometry.compute(500, 500, 100, 200, ScalingMode.FILL, focusY = 1f)
        assertQuad(Quad(-1f, -1f, 1f, 1f, 0f, 1f, 0.5f, 1f), bottom)
    }

    @Test
    fun focusIsClamped() {
        val q = CropGeometry.compute(500, 500, 100, 200, ScalingMode.FILL, focusY = 7f)
        assertEquals(1f, q.vBottom, eps)
    }

    @Test
    fun fitLetterboxesWideVideo() {
        // 2:1 picture in a 1:1 view: half height, bars top and bottom.
        val q = CropGeometry.compute(500, 500, 200, 100, ScalingMode.FIT)
        assertQuad(Quad(-1f, -0.5f, 1f, 0.5f, 0f, 1f, 0f, 1f), q)
    }

    @Test
    fun fitPillarboxesTallVideoAndIgnoresFocus() {
        val q = CropGeometry.compute(500, 500, 100, 200, ScalingMode.FIT, focusX = 0f, focusY = 0f)
        assertQuad(Quad(-0.5f, -1f, 0.5f, 1f, 0f, 1f, 0f, 1f), q)
    }

    @Test
    fun fillOverflowMeasuresTheCutOffPart() {
        val (ox, oy) = CropGeometry.fillOverflow(1080, 1920, 1920, 1080)
        assertEquals(1920f * 1920f / 1080f - 1080f, ox, 0.01f)
        assertEquals(0f, oy, 0.01f)
        assertEquals(0f to 0f, CropGeometry.fillOverflow(0, 0, 10, 10))
    }
}
