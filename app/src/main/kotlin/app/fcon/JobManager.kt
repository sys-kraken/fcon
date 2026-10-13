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

    /** True when no conversion is queued or running. */
    fun isIdle(): Boolean = active.get() == 0
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

            // One lookup per video: reuse the one made when the link was pasted (or wait for it if it is still running).
            // Audio and video up to 1080p use the quick lookup; 1440p/4K need the full one (see InfoCache).
            val needFull = isVideo && resolution.toInt() > 1080
            var info: InfoCache.Entry? = if (Debug.flag(ctx, "nocache")) null else try {
                InfoCache.fetch(ctx, url, needFull)
            } catch (e: Exception) {
                null // the normal run below will hit the same problem and report the real error
            }
            var clock = PhaseClock()

            fun attempt() {
                Engine.lock.readLock().lock()
                try {
                    dir.listFiles()?.forEach { it.deleteRecursively() }
                    job.progress = 0f
                    val req = buildRequest(ctx, url, info, dir, ext, isVideo, quality, resolution, start, end)
                    clock = PhaseClock()
                    YoutubeDL.getInstance().execute(req, job.id) { p, _, line ->
                        clock.onLine(line)
                        if (p >= 0) {
                            job.progress = p
                            ConversionService.progress(ctx, p.toInt())
                        }
                    }
                    clock.finish()
                } finally {
                    Engine.lock.readLock().unlock()
                }
            }

            // YouTube changes often: if yt-dlp looks outdated, update it and retry once (at most hourly).
            fun retryAfterUpdate(e: Exception) {
                if (needsNewerYtdlp(e.message.orEmpty()) && Engine.updateIfStale(ctx, 1) != null) attempt() else throw e
            }
            try {
                attempt()
            } catch (e: Exception) {
                if (info != null) {
                    // The saved lookup may have gone stale (its download links expire): ask the site again.
                    InfoCache.drop(url)
                    info = null
                    try {
                        attempt()
                    } catch (e2: Exception) {
                        retryAfterUpdate(e2)
                    }
                } else {
                    retryAfterUpdate(e)
                }
            }

            val out = dir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in MEDIA_EXTS && !INTERMEDIATE.containsMatchIn(it.nameWithoutExtension) }
                ?.maxByOrNull { it.length() }
                ?: throw IllegalStateException("Output file not created")

            Timing.log(
                ctx,
                clock.toJson().put("type", "convert").put("format", format)
                    .put("quality", if (isVideo) resolution + "p" else quality + "k")
                    .put("trim", trim).put("mb", Math.round(out.length() / 104857.6) / 10.0).put("url", url)
                    .put("reused_lookup", info != null).put("full_lookup", needFull),
            )

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
        ctx: Context, url: String, info: InfoCache.Entry?, dir: File, ext: String, isVideo: Boolean,
        quality: String, resolution: String, start: String, end: String,
    ): YoutubeDLRequest {
        // With a saved lookup there is no URL to give yt-dlp: it loads the lookup file instead.
        val req = if (info != null) YoutubeDLRequest(emptyList<String>()).apply { addOption("--load-info-json", info.file.absolutePath) }
        else YoutubeDLRequest(url)
        Engine.applyCommon(ctx, req)
        req.addOption("--concurrent-fragments", 4)
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
            // LAME's speed/quality dial (0 best ... 9 fastest, default ~5): 7 encodes ~40% faster at the same bitrate
            // and size; the difference is hard to hear at 192-320 kbps.
            if (ext == "mp3") req.addOption("--postprocessor-args", "ExtractAudio:-compression_level 7")
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
