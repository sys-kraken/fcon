package app.fcon

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/** Puts the FCon cover picture into an MP3 (a copy of the audio, no re-encoding). The MP3 is left alone if anything fails. */
object Cover {
    private const val TAG = "FconCover"

    fun attach(ctx: Context, mp3: File) {
        val tmp = File(mp3.parentFile, "cover-tmp.mp3")
        try {
            val cover = File(ctx.cacheDir, "cover.jpg")
            if (!cover.isFile) ctx.assets.open("cover.jpg").use { i -> cover.outputStream().use { i.copyTo(it) } }
            val ffmpeg = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
            val cmd = listOf(
                ffmpeg.absolutePath, "-y", "-loglevel", "error", "-i", mp3.absolutePath, "-i", cover.absolutePath,
                "-map", "0:a", "-map", "1:v", "-c", "copy", "-id3v2_version", "3",
                "-metadata:s:v", "title=Cover", "-metadata:s:v", "comment=Cover (front)", tmp.absolutePath,
            )
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            // ffmpeg's libraries, plus the C++ runtime that ships with the python package
            val pk = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
            pb.environment()["LD_LIBRARY_PATH"] =
                listOf("ffmpeg", "python").joinToString(":") { File(pk, "$it/usr/lib").absolutePath }
            val p = pb.start()
            val log = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(60, TimeUnit.SECONDS)) p.destroyForcibly()
            if (p.exitValue() == 0 && tmp.length() > mp3.length()) {
                mp3.delete()
                tmp.renameTo(mp3)
            } else {
                Log.w(TAG, "cover not added: ${log.take(300)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "cover not added: ${e.message}")
        } finally {
            tmp.delete()
        }
    }
}
