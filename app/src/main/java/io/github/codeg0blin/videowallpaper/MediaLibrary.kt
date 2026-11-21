package io.github.codeg0blin.videowallpaper

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.WorkerThread
import java.io.File
import java.io.IOException

/**
 * Keeps the wallpaper's file inside the app's private storage.
 *
 * Up to version 1.3 the app stored the picker's `content://` URI and played
 * straight from it. That broke whenever the original was moved, renamed or
 * deleted, when the SD card was removed, when the persisted permission was
 * revoked, or when it lived with a cloud provider (which then streamed the
 * whole file again on every loop). Now the picked file is copied once into
 * `files/media/`, and only that copy is played. No storage permission is
 * needed at all, and the picker's permission is given back after copying.
 *
 * Everything here does I/O and must run off the main thread.
 */
class MediaLibrary(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val dir = File(appContext.filesDir, "media")

    sealed class Result {
        data class Success(val media: WallpaperMedia, val displayName: String?, val durationMs: Long) : Result()
        data class Failure(val reason: Reason) : Result()
    }

    enum class Reason {
        /** Couldn't open or read the picked file. */
        UNREADABLE,

        /** Not enough free space for the copy. */
        NO_SPACE,

        /** Readable, but contains no video track. */
        NOT_A_VIDEO,

        /** A GIF that is damaged or too large. */
        GIF_UNUSABLE
    }

    /**
     * Copies [source] into private storage and checks that it can be played.
     * [onProgress] gets 0..1, or -1 while the total size is unknown. On
     * failure nothing is left behind and the current wallpaper file is
     * untouched.
     */
    @WorkerThread
    fun import(source: Uri, onProgress: (Float) -> Unit): Result {
        val (displayName, size) = queryNameAndSize(source)
        dir.mkdirs()
        if (size > 0 && freeBytes() < size + FREE_SPACE_MARGIN) return Result.Failure(Reason.NO_SPACE)

        val isGif = GifFiles.looksLikeGif(resolver, source)
        if (isGif && size > GifFiles.MAX_BYTES) return Result.Failure(Reason.GIF_UNUSABLE)

        val temp = File(dir, "import-${System.currentTimeMillis()}.tmp")
        try {
            copy(source, temp, size, onProgress)
        } catch (e: IOException) {
            Log.w(TAG, "Copy failed", e)
            temp.delete()
            return Result.Failure(if (freeBytes() < FREE_SPACE_MARGIN) Reason.NO_SPACE else Reason.UNREADABLE)
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to read the picked file", e)
            temp.delete()
            return Result.Failure(Reason.UNREADABLE)
        }

        val durationMs: Long
        if (isGif) {
            val ok = try {
                GifDecoder.parse(temp.readBytes())
                true
            } catch (e: GifFormatException) {
                false
            } catch (e: OutOfMemoryError) {
                false
            }
            if (!ok) {
                temp.delete()
                return Result.Failure(Reason.GIF_UNUSABLE)
            }
            durationMs = 0L
        } else {
            val probe = probeVideo(temp)
            if (probe == null) {
                temp.delete()
                return Result.Failure(Reason.NOT_A_VIDEO)
            }
            durationMs = probe
        }

        val final = File(dir, "wallpaper-${System.currentTimeMillis()}.${if (isGif) "gif" else extensionFor(displayName)}")
        if (!temp.renameTo(final)) {
            temp.delete()
            return Result.Failure(Reason.UNREADABLE)
        }
        return Result.Success(WallpaperMedia(Uri.fromFile(final), isGif), displayName, durationMs)
    }

    /** Deletes every stored file except [keep] (the current wallpaper), including leftovers of interrupted imports. */
    @WorkerThread
    fun deleteAllExcept(keep: Uri?) {
        val keepPath = keep?.takeIf { it.scheme == "file" }?.path
        dir.listFiles()?.forEach { f -> if (f.absolutePath != keepPath) f.delete() }
    }

    /** True for a `content://` selection saved by version 1.x that should be copied in. */
    fun isLegacy(media: WallpaperMedia): Boolean = media.uri.scheme == "content"

    /**
     * Gives back every persisted picker permission. Since 2.0 the app never
     * needs one after copying, and version 1.x never released old ones.
     */
    fun releaseAllPersistedPermissions() {
        for (p in resolver.persistedUriPermissions) {
            try {
                resolver.releasePersistableUriPermission(p.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Already gone.
            }
        }
    }

    /** Works out whether a 1.x selection (kind not stored) is a GIF. */
    @WorkerThread
    fun detectKind(media: WallpaperMedia): WallpaperMedia =
        if (media.isGif != null) media else media.copy(isGif = GifFiles.looksLikeGif(resolver, media.uri))

    /** Video length in ms, or null if [file] has no video track. */
    @WorkerThread
    fun probeVideo(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return null
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: RuntimeException) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Nothing useful to do.
            }
        }
    }

    private fun copy(source: Uri, target: File, size: Long, onProgress: (Float) -> Unit) {
        val input = resolver.openInputStream(source) ?: throw IOException("Could not open $source")
        input.use { inp ->
            target.outputStream().use { out ->
                val buffer = ByteArray(256 * 1024)
                var copied = 0L
                var lastReported = -1
                onProgress(if (size > 0) 0f else -1f)
                while (true) {
                    val n = inp.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    copied += n
                    if (size > 0) {
                        val percent = (copied * 100 / size).toInt()
                        if (percent != lastReported) {
                            lastReported = percent
                            onProgress((copied.toFloat() / size).coerceAtMost(1f))
                        }
                    }
                }
                out.fd.sync()
            }
        }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String?, Long> = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null to -1L
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx) else null
            val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else -1L
            name to size
        } ?: (null to -1L)
    } catch (e: RuntimeException) {
        null to -1L
    }

    private fun freeBytes(): Long = try {
        StatFs(appContext.filesDir.absolutePath).availableBytes
    } catch (e: IllegalArgumentException) {
        Long.MAX_VALUE
    }

    companion object {
        private const val TAG = "MediaLibrary"

        /** Space to leave free on top of the file itself. */
        private const val FREE_SPACE_MARGIN = 100L * 1024 * 1024

        /** File extension to keep for the copy (helps extractors that sniff by name), from the original name. */
        fun extensionFor(displayName: String?): String {
            val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return if (ext.length in 2..5 && ext.all { it.isLetterOrDigit() }) ext else "mp4"
        }
    }
}
