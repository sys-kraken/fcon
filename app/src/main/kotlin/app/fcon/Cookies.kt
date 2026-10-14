package app.fcon

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

/**
 * Facebook and Instagram only serve most videos to logged-in visitors. The login cookies live in a plain
 * cookies.txt on GitHub that the app downloads in the background (so they can be replaced without a new app
 * release) and hands to yt-dlp for Facebook/Instagram links only: never for YouTube or anything else.
 */
object Cookies {
    private const val TAG = "FconCookies"
    private const val SOURCE = "https://raw.githubusercontent.com/syskraken/fcon-cookies/master/cookies.txt"
    private const val REFRESH_MS = 3 * 3_600_000L
    private const val MAX_BYTES = 200_000

    private val LOGIN_SITES = listOf("facebook.com", "instagram.com")
    private val URL_HOSTS = listOf("facebook.com", "fb.watch", "fb.com", "instagram.com")

    @Volatile private var refreshing = false

    private fun stored(ctx: Context) = File(ctx.filesDir, "cookies.txt")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("fcon", Context.MODE_PRIVATE)

    fun wanted(url: String): Boolean {
        val host = try {
            URI(if (url.contains("://")) url else "https://$url").host?.lowercase()
        } catch (e: Exception) {
            null
        } ?: return false
        return URL_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** Downloads the cookie file unless it was refreshed recently. Never blocks the caller. */
    fun refreshAsync(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        if (refreshing) return
        if (!force && System.currentTimeMillis() - prefs(app).getLong("cookies_checked", 0L) < REFRESH_MS) return
        refreshing = true
        Thread({
            try {
                val text = download()
                val clean = sanitize(text)
                if (clean != null) {
                    val tmp = File(app.filesDir, "cookies.txt.tmp")
                    tmp.writeText(clean)
                    if (!tmp.renameTo(stored(app))) {
                        stored(app).delete()
                        tmp.renameTo(stored(app))
                    }
                    Log.i(TAG, "cookie file updated")
                } else {
                    Log.w(TAG, "downloaded cookie file had no usable login, keeping the old one")
                }
                prefs(app).edit().putLong("cookies_checked", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                Log.w(TAG, "cookie refresh failed: ${e.message}")
            } finally {
                refreshing = false
            }
        }, "fcon-cookies").start()
    }

    private fun download(): String {
        val c = URL(SOURCE).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val bytes = c.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
            if (bytes.size > MAX_BYTES) throw IllegalStateException("cookie file too large")
            return String(bytes, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    /** Keeps only Facebook/Instagram lines, and returns null unless a real login is in there. */
    private fun sanitize(text: String): String? {
        val keep = ArrayList<String>()
        val names = HashSet<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            val fields = line.split('\t')
            if (fields.size < 7) continue
            val domain = fields[0].removePrefix("#HttpOnly_").trimStart('.').lowercase()
            if (LOGIN_SITES.none { domain == it || domain.endsWith(".$it") }) continue
            keep.add(line)
            names.add(domain.takeLast(12) + "|" + fields[5])
        }
        val hasFacebook = names.any { it.endsWith("|c_user") } && names.any { it.endsWith("|xs") }
        val hasInstagram = names.any { it.endsWith("|sessionid") }
        if (!hasFacebook && !hasInstagram) return null
        return "# Netscape HTTP Cookie File\n" + keep.joinToString("\n") + "\n"
    }

    /**
     * Adds --cookies for Facebook/Instagram links. yt-dlp rewrites the cookie file as it runs, so it gets a private
     * copy in the cache folder. Returns that copy (delete it when done) or null if no cookies apply.
     */
    fun apply(ctx: Context, req: YoutubeDLRequest, url: String): File? {
        if (!wanted(url)) return null
        refreshAsync(ctx)
        val src = stored(ctx)
        if (!src.isFile) return null
        val dir = File(ctx.cacheDir, "cookies").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 3_600_000L
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        val copy = File(dir, "cookies-${UUID.randomUUID()}.txt")
        src.copyTo(copy, overwrite = true)
        req.addOption("--cookies", copy.absolutePath)
        return copy
    }
}
