package app.fcon

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Runs conversions one at a time and keeps their state for the page to poll. */
object JobManager {
    private const val TAG = "FconJobs"
    private val MEDIA_EXTS = setOf("mp3", "wav", "flac", "m4a", "ogg", "mp4", "webm", "mkv")
    private val FORMAT_EXT = mapOf(
        "mp3" to "mp3", "wav" to "wav", "flac" to "flac", "aac" to "m4a", "ogg" to "ogg",
        "mp4" to "mp4", "webm" to "webm", "mkv" to "mkv",
    )
    private val VIDEO = setOf("mp4", "webm", "mkv")
    private val RESOLUTIONS = setOf("360", "480", "720", "1080", "1440", "2160")
    private val INTERMEDIATE = Regex("""\.f\d+$""")

    class Job(val id: String) {
        @Volatile var state = "queued" // queued | running | done | error
        @Volatile var progress = 0f
        @Volatile var error: String? = null
        @Volatile var result: JSONObject? = null
    }

    class StoredFile(val file: File, val name: String)

    private val jobs = ConcurrentHashMap<String, Job>()
    private val files = ConcurrentHashMap<String, StoredFile>()
    private val executor = Executors.newSingleThreadExecutor()
    private val active = AtomicInteger(0)

    fun job(id: String): Job? = jobs[id]
    fun fileFor(fileId: String): StoredFile? = files[fileId]?.takeIf { it.file.isFile }

    fun start(ctx: Context, body: JSONObject): JSONObject {
        val url = body.optString("url").trim()
        require(url.isNotEmpty()) { "No URL provided" }
        val id = body.optString("job_id").ifEmpty { UUID.randomUUID().toString() }
        jobs[id] = Job(id)
        if (active.getAndIncrement() == 0) ConversionService.start(ctx)
        val app = ctx.applicationContext
        executor.execute { run(app, jobs.getValue(id), body, url) }
        return JSONObject().put("job_id", id).put("status", "processing")
            .put("message", "Conversion started in background")
    }

    private fun run(ctx: Context, job: Job, body: JSONObject, url: String) {
        val fileId = UUID.randomUUID().toString()
        val dir = File(ctx.cacheDir, "jobs/$fileId").apply { mkdirs() }
        try {
            Engine.await()
            job.state = "running"
            val format = body.optString("format", "mp3").lowercase()
            val ext = FORMAT_EXT[format] ?: "mp3"
            val isVideo = format in VIDEO
            val quality = body.optString("quality", "192")
            val resolution = body.optString("resolution", "1080").let { if (it in RESOLUTIONS) it else "1080" }
            val trim = body.optString("mode") == "trim"
            val start = if (trim) body.optString("start_time") else ""
            val end = if (trim) body.optString("end_time") else ""

            fun attempt() {
                Engine.lock.readLock().lock()
                try {
                    dir.listFiles()?.forEach { it.deleteRecursively() }
                    job.progress = 0f
                    val req = buildRequest(url, dir, ext, isVideo, quality, resolution, start, end)
                    YoutubeDL.getInstance().execute(req, job.id) { p, _, _ ->
                        if (p >= 0) {
                            job.progress = p
                            ConversionService.progress(ctx, p.toInt())
                        }
                    }
                } finally {
                    Engine.lock.readLock().unlock()
                }
            }

            try {
                attempt()
            } catch (e: Exception) {
                // YouTube changes often: if yt-dlp looks outdated, update it and retry once (at most hourly).
                if (needsNewerYtdlp(e.message.orEmpty()) && Engine.updateIfStale(ctx, 1) != null) attempt() else throw e
            }

            val out = dir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in MEDIA_EXTS && !INTERMEDIATE.containsMatchIn(it.nameWithoutExtension) }
                ?.maxByOrNull { it.length() }
                ?: throw IllegalStateException("Output file not created")

            files[fileId] = StoredFile(out, out.name)
            job.result = JSONObject()
                .put("url", "/api/download/$fileId")
                .put("filename", out.name)
                .put("title", out.nameWithoutExtension)
                .put("status", "local")
                .put("is_video", isVideo)
                .put("resolution", if (isVideo) resolution else JSONObject.NULL)
            job.state = "done"
        } catch (e: Throwable) {
            Log.w(TAG, "job failed", e)
            job.error = cleanError(e.message ?: e.toString())
            job.state = "error"
            dir.deleteRecursively()
        } finally {
            if (active.decrementAndGet() == 0) ConversionService.stop(ctx)
            cleanOld(ctx)
        }
    }

    private fun buildRequest(
        url: String, dir: File, ext: String, isVideo: Boolean, quality: String,
        resolution: String, start: String, end: String,
    ): YoutubeDLRequest {
        val req = YoutubeDLRequest(url)
        req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        req.addOption("--embed-metadata")
        req.addOption("--trim-filenames", 150)
        req.addOption("-o", File(dir, "%(title)s.%(ext)s").absolutePath)
        if (isVideo) {
            req.addOption(
                "-f",
                "bestvideo[height<=$resolution][ext=mp4]+bestaudio[ext=m4a]" +
                    "/bestvideo[height<=$resolution]+bestaudio/best[height<=$resolution]/best",
            )
            req.addOption("--merge-output-format", ext)
        } else {
            req.addOption("-x")
            req.addOption("--audio-format", ext)
            req.addOption("--audio-quality", quality)
        }
        if (start.isNotEmpty() && end.isNotEmpty()) req.addOption("--download-sections", "*$start-$end")
        return req
    }

    private fun needsNewerYtdlp(msg: String): Boolean = listOf(
        "Sign in to confirm", "HTTP Error 403", "Unable to extract", "nsig", "player response",
        "Requested format is not available",
    ).any { msg.contains(it, ignoreCase = true) }

    /** yt-dlp prints its real error on the last "ERROR:" line; keep that, drop the noise. */
    private fun cleanError(raw: String): String {
        val line = raw.lines().lastOrNull { it.trimStart().startsWith("ERROR:") }
            ?: raw.lines().lastOrNull { it.isNotBlank() } ?: raw
        return line.trim().removePrefix("ERROR:").trim().take(220).ifEmpty { "Conversion failed. Please try again." }
    }

    /** Converted files only need to live until they are saved; sweep leftovers older than 3 hours. */
    private fun cleanOld(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 3 * 3_600_000L
        File(ctx.cacheDir, "jobs").listFiles()?.forEach { d ->
            if (d.lastModified() < cutoff) d.deleteRecursively()
        }
        files.entries.removeIf { !it.value.file.exists() }
    }
}
