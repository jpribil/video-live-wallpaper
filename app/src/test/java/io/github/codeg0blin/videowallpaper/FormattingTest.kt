package io.github.codeg0blin.videowallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {

    @Test
    fun formatsMinutesSecondsTenths() {
        assertEquals("0:00.0", Formatting.time(0))
        assertEquals("0:00.0", Formatting.time(-500))
        assertEquals("0:02.5", Formatting.time(2_500))
        assertEquals("1:05.3", Formatting.time(65_300))
        assertEquals("0:10.0", Formatting.time(9_960))
        assertEquals("61:00.0", Formatting.time(3_660_000))
    }

    @Test
    fun snapsOntoSliderGrid() {
        assertEquals(1.0f, Formatting.snapToStep(1.0f, 0.25f, 2.0f, 0.05f), 1e-6f)
        assertEquals(1.05f, Formatting.snapToStep(1.04f, 0.25f, 2.0f, 0.05f), 1e-6f)
        assertEquals(0.25f, Formatting.snapToStep(0.1f, 0.25f, 2.0f, 0.05f), 1e-6f)
        assertEquals(2.0f, Formatting.snapToStep(3.7f, 0.25f, 2.0f, 0.05f), 1e-6f)
    }

    @Test
    fun keepsSensibleExtensions() {
        assertEquals("mp4", MediaLibrary.extensionFor("holiday.MP4"))
        assertEquals("webm", MediaLibrary.extensionFor("clip.webm"))
        assertEquals("mp4", MediaLibrary.extensionFor(null))
        assertEquals("mp4", MediaLibrary.extensionFor("no-extension"))
        assertEquals("mp4", MediaLibrary.extensionFor("weird.ext with space"))
    }

    @Test
    fun scalingModeNumbersMatchVersion1() {
        // Version 1.x stored MediaPlayer.VIDEO_SCALING_MODE_* values.
        assertEquals(ScalingMode.FIT, SettingsStore.scalingModeFromStored(1))
        assertEquals(ScalingMode.FILL, SettingsStore.scalingModeFromStored(2))
        assertEquals(ScalingMode.FILL, SettingsStore.scalingModeFromStored(99))
        for (mode in ScalingMode.entries) {
            assertEquals(mode, SettingsStore.scalingModeFromStored(SettingsStore.scalingModeToStored(mode)))
        }
    }
}
