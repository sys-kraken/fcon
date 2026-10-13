package app.fcon

import android.content.Context
import android.os.SystemClock
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One yt-dlp lookup per video. The length/resolution check that runs when a link is pasted and the
 * conversion share it: the conversion loads the saved lookup (--load-info-json) instead of asking
 * YouTube again, which used to cost about 12 seconds every time.
 */
object InfoCache {
    class Entry(val file: File, val duration: Int, val maxHeight: Int, val at: Long, val full: Boolean)

    private const val MAX_AGE_MS = 15 * 60_000L // download links inside the lookup expire, so do not keep it long

    /**
     * YouTube hides its download links behind a puzzle in its player script, and solving it takes ~11 s on a phone.
     * The "fast" lookup skips the player script: ~4 s, enough for audio and video up to 1080p. 1440p/4K need the
     * "full" lookup (the puzzle solved).
     */
    private const val FAST_ARGS = "youtube:player_skip=js"
    private val done = ConcurrentHashMap<String, Entry>()
    private val running = ConcurrentHashMap<String, FutureTask<Entry>>()
    private val active = AtomicInteger(0)

    /** True while a lookup is running (the yt-dlp updater waits for this). */
    val busy: Boolean get() = active.get() > 0

    private fun key(url: String, full: Boolean) = if (full) "$url#full" else url

    fun drop(url: String) {
        for (full in listOf(false, true)) done.remove(key(url, full))?.file?.delete()
    }

    /** Returns the lookup for [url], doing it only if nobody has (callers for the same URL share one lookup). */
    fun fetch(ctx: Context, url: String, full: Boolean = false): Entry {
        val k = key(url, full)
        done[k]?.let { if (System.currentTimeMillis() - it.at < MAX_AGE_MS && it.file.exists()) return it }
        val mine = FutureTask<Entry> { lookup(ctx.applicationContext, url, full) }
        val other = running.putIfAbsent(k, mine)
        try {
            if (other != null) return other.get(150, TimeUnit.SECONDS)
            mine.run()
            return mine.get()
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        } finally {
            if (other == null) running.remove(k)
        }
    }

    private fun lookup(ctx: Context, url: String, full: Boolean): Entry {
        Engine.await()
        active.incrementAndGet()
        Engine.lock.readLock().lock()
        try {
            val started = SystemClock.elapsedRealtime()
            val req = YoutubeDLRequest(url)
            req.addOption("--no-playlist")
            req.addOption("--dump-json")
            if (!full && !Debug.flag(ctx, "nofast")) req.addOption("--extractor-args", FAST_ARGS)
            Engine.applyCommon(ctx, req)
            val out = YoutubeDL.getInstance().execute(req, null, null).out.trim()
            val line = out.lineSequence().lastOrNull { it.startsWith("{") }
                ?: throw IllegalStateException("The site returned no video information")
            val json = JSONObject(line)

            val duration = json.optDouble("duration", 0.0).let { if (it.isNaN()) 0 else it.toInt() }
            var maxHeight = 0
            json.optJSONArray("formats")?.let { formats ->
                for (i in 0 until formats.length()) {
                    val f = formats.optJSONObject(i) ?: continue
                    val vcodec = f.optString("vcodec", "none")
                    if (vcodec != "none" && vcodec != "null") maxHeight = maxOf(maxHeight, f.optInt("height", 0))
                }
            }

            val file = File(File(ctx.cacheDir, "info").apply { mkdirs() }, sha1(key(url, full)).take(16) + ".json")
            file.writeText(line)
            val entry = Entry(file, duration, maxHeight, System.currentTimeMillis(), full)
            done[key(url, full)] = entry
            Timing.log(
                ctx,
                JSONObject().put("type", "info").put("url", url).put("full", full)
                    .put("total_s", Math.round((SystemClock.elapsedRealtime() - started) / 100.0) / 10.0),
            )
            return entry
        } finally {
            Engine.lock.readLock().unlock()
            active.decrementAndGet()
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
