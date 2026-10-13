package app.fcon

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Splits one yt-dlp run into phases by timestamping its output lines:
 * startup (python + yt-dlp boot), extract (talking to the site), download, encode (ffmpeg and finishing).
 */
class PhaseClock {
    private val t0 = SystemClock.elapsedRealtime()
    private var firstLine = -1L
    private var extractStart = -1L
    private var extractEnd = -1L
    private var downloadStart = -1L
    private var lastDownload = -1L
    private var end = -1L

    fun onLine(line: String) {
        val now = SystemClock.elapsedRealtime() - t0
        if (BuildConfig.DEBUG) Log.d("FconYtdlp", "+%.1fs %s".format(now / 1000.0, line.take(150)))
        if (firstLine < 0) firstLine = now
        if (extractStart < 0 && line.contains("Extracting URL")) extractStart = now
        if (extractEnd < 0 && extractStart >= 0 &&
            ((line.startsWith("[info]") && line.contains("Downloading")) || line.startsWith("[download] Destination"))
        ) extractEnd = now
        if (downloadStart < 0 && line.startsWith("[download] Destination")) downloadStart = now
        if (line.startsWith("[download]")) lastDownload = now
    }

    fun finish() {
        end = SystemClock.elapsedRealtime() - t0
    }

    private fun s(ms: Long) = if (ms < 0) JSONObject.NULL else Math.round(ms / 100.0) / 10.0

    fun toJson(): JSONObject {
        val extract = if (extractStart >= 0 && extractEnd >= 0) extractEnd - extractStart else -1L
        val download = if (downloadStart >= 0 && lastDownload >= downloadStart) lastDownload - downloadStart else -1L
        val encodeFrom = if (lastDownload >= 0) lastDownload else extractEnd
        val encode = if (end >= 0 && encodeFrom >= 0) end - encodeFrom else -1L
        return JSONObject()
            .put("startup_s", s(firstLine))
            .put("extract_s", s(extract))
            .put("download_s", s(download))
            .put("encode_s", s(encode))
            .put("total_s", s(end))
    }
}

/** Debug builds only: create an empty file in the app's files folder to switch an optimisation off for A/B timing. */
object Debug {
    fun flag(ctx: Context, name: String): Boolean = BuildConfig.DEBUG && File(ctx.filesDir, name).exists()

    /** Debug builds: extra yt-dlp arguments read from files/extra_args (one argument per line), for experiments. */
    fun extraArgs(ctx: Context): List<String> {
        if (!BuildConfig.DEBUG) return emptyList()
        val f = File(ctx.filesDir, "extra_args")
        return if (f.exists()) f.readLines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
    }
}

/** Writes timing records to logcat (tag FconTiming) and to files/timing.jsonl. */
object Timing {
    private const val TAG = "FconTiming"

    fun log(ctx: Context, entry: JSONObject) {
        entry.put("version", BuildConfig.VERSION_NAME)
        Log.i(TAG, entry.toString())
        try {
            File(ctx.filesDir, "timing.jsonl").appendText(entry.toString() + "\n")
        } catch (_: Exception) {
        }
    }
}
