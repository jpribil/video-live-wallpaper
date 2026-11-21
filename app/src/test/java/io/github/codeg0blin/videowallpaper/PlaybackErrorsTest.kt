package io.github.codeg0blin.videowallpaper

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackErrorsTest {

    @Test
    fun missingFilesAreReportedNotRetried() {
        assertEquals(PlaybackErrors.Action.SOURCE_MISSING, PlaybackErrors.classify(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertEquals(PlaybackErrors.Action.SOURCE_MISSING, PlaybackErrors.classify(PlaybackException.ERROR_CODE_IO_NO_PERMISSION))
    }

    @Test
    fun unplayableFormatsAreReportedNotRetried() {
        val codes = listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
        )
        for (code in codes) assertEquals(PlaybackErrors.Action.UNSUPPORTED, PlaybackErrors.classify(code))
    }

    @Test
    fun transientFailuresAreRetried() {
        val codes = listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        )
        for (code in codes) assertEquals(PlaybackErrors.Action.RETRY, PlaybackErrors.classify(code))
    }

    @Test
    fun lostPermissionInTheCauseChainIsSourceMissing() {
        val wrapped = RuntimeException("Source error", IllegalStateException("x", SecurityException("Permission Denial")))
        assertEquals(PlaybackErrors.Action.SOURCE_MISSING, PlaybackErrors.classify(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, wrapped))
        val messageOnly = RuntimeException("Unexpected SecurityException: Permission Denial")
        assertEquals(PlaybackErrors.Action.SOURCE_MISSING, PlaybackErrors.classify(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, messageOnly))
        assertEquals(PlaybackErrors.Action.SOURCE_MISSING, PlaybackErrors.classify(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, java.io.FileNotFoundException()))
        assertEquals(PlaybackErrors.Action.RETRY, PlaybackErrors.classify(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, RuntimeException("boom")))
    }

    @Test
    fun retryDelayDoubles() {
        assertEquals(1000L, PlaybackErrors.retryDelayMs(1))
        assertEquals(2000L, PlaybackErrors.retryDelayMs(2))
        assertEquals(4000L, PlaybackErrors.retryDelayMs(3))
    }
}
