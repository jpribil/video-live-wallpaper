package io.github.codeg0blin.videowallpaper

import androidx.media3.common.PlaybackException

/**
 * Sorts Media3 player errors into "retry", "the file is gone" and "this
 * file can't be played here". Kept separate from [PlaybackController] so the
 * rules can be unit tested.
 *
 * Version 1.x cleared the user's selection on most errors, which turned
 * one-off failures (another app holding the hardware decoder, a media server
 * restart) into a silently blank wallpaper. Now nothing is ever cleared
 * automatically: transient errors are retried, and permanent ones are
 * reported so the app can tell the user what happened.
 */
object PlaybackErrors {

    enum class Action { RETRY, SOURCE_MISSING, UNSUPPORTED }

    /** How many times in a row a transient error is retried before giving up. */
    const val MAX_RETRIES = 3

    /** Delay before retry number [attempt] (1-based): 1 s, 2 s, 4 s. */
    fun retryDelayMs(attempt: Int): Long = 1000L shl (attempt - 1).coerceIn(0, 5)

    /**
     * Like [classify], but also looks at the exception chain: a lost
     * permission arrives as a generic IO error whose cause is a
     * SecurityException, and retrying that can never help.
     */
    fun classify(errorCode: Int, cause: Throwable?): Action {
        var t = cause
        var depth = 0
        while (t != null && depth < 10) {
            if (t is SecurityException || t is java.io.FileNotFoundException) return Action.SOURCE_MISSING
            // Media3 wraps unexpected loader exceptions and keeps only the message.
            if (t.message?.contains("SecurityException") == true) return Action.SOURCE_MISSING
            t = t.cause
            depth++
        }
        return classify(errorCode)
    }

    fun classify(errorCode: Int): Action = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> Action.SOURCE_MISSING

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> Action.UNSUPPORTED

        // Decoder init/decoding failures, unspecified and other IO errors are
        // usually caused by something outside the file (codec busy, low
        // memory, storage briefly unavailable), so they are worth retrying.
        else -> Action.RETRY
    }
}
