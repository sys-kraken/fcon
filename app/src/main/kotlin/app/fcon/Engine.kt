package app.fcon

import android.app.Application
import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Starts the bundled python + yt-dlp + ffmpeg and keeps yt-dlp up to date. */
object Engine {
    private const val TAG = "FconEngine"
    private val ready = CountDownLatch(1)

    @Volatile
    var initError: String? = null

    /** Jobs hold the read lock while they run; an update takes the write lock so files never change mid-job. */
    val lock = ReentrantReadWriteLock()

    fun start(app: Application) {
        Thread({
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app) // unpacks ffmpeg's libraries; without it conversion fails
            } catch (e: Throwable) {
                initError = e.message ?: e.toString()
                Log.e(TAG, "init failed", e)
            } finally {
                ready.countDown()
            }
            if (initError == null) updateIfStale(app)
        }, "fcon-engine-init").start()
    }

    /** Blocks until the engine is initialised; throws if it could not start. */
    fun await() {
        ready.await(90, TimeUnit.SECONDS)
        initError?.let { throw IllegalStateException("The downloader could not start: $it") }
    }

    /** Updates yt-dlp unless it was updated within [maxAgeHours]. Returns the update status, or null if skipped. */
    fun updateIfStale(ctx: Context, maxAgeHours: Long = 12): String? {
        val prefs = ctx.getSharedPreferences("fcon", Context.MODE_PRIVATE)
        val last = prefs.getLong("ytdlp_updated", 0L)
        if (System.currentTimeMillis() - last < maxAgeHours * 3_600_000L) return null
        return update(ctx)
    }

    /** Downloads the newest yt-dlp (nightly channel: YouTube breaks things every few days). */
    fun update(ctx: Context): String {
        val w = lock.writeLock()
        w.lock()
        return try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.NIGHTLY)
            ctx.getSharedPreferences("fcon", Context.MODE_PRIVATE).edit()
                .putLong("ytdlp_updated", System.currentTimeMillis()).apply()
            Log.i(TAG, "yt-dlp update: $status -> ${YoutubeDL.getInstance().versionName(ctx)}")
            status?.name ?: "UNKNOWN"
        } catch (e: Throwable) {
            Log.w(TAG, "yt-dlp update failed", e)
            // do not retry for an hour so a missing network does not stall every job
            ctx.getSharedPreferences("fcon", Context.MODE_PRIVATE).edit()
                .putLong("ytdlp_updated", System.currentTimeMillis() - 11 * 3_600_000L).apply()
            "FAILED: ${e.message}"
        } finally {
            w.unlock()
        }
    }

    fun version(ctx: Context): String = try {
        YoutubeDL.getInstance().versionName(ctx) ?: "unknown"
    } catch (e: Throwable) {
        "unknown"
    }
}
