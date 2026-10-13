package app.fcon

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File

/** Copies a finished file into the phone's Downloads/FCon folder (no storage permission needed). */
object Saver {
    private val MIME = mapOf(
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "flac" to "audio/flac", "ogg" to "audio/ogg",
        "wav" to "audio/wav", "mp4" to "video/mp4", "webm" to "video/webm", "mkv" to "video/x-matroska",
    )

    /** Returns a short "where it went" message, e.g. "Download/FCon/Song.mp3". */
    fun save(ctx: Context, file: File, displayName: String): String {
        val mime = MIME[file.extension.lowercase()] ?: "application/octet-stream"
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/FCon")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create the file in Downloads")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Could not open the file for writing")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return "Download/FCon/$displayName"
    }
}
