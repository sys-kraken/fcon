package app.fcon

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks the latest GitHub release of this app and downloads its APK for this phone's CPU type. */
object Updater {
    const val REPO = "syskraken/fcon-android"

    class Release(val version: String, val apkName: String, val apkUrl: String, val size: Long)

    /** The newer release found by the last [check], waiting to be installed. */
    @Volatile
    var pending: Release? = null

    fun error(message: String): JSONObject = JSONObject().put("status", "error").put("message", message)

    fun check(): JSONObject {
        val current = BuildConfig.VERSION_NAME
        val conn = open("https://api.github.com/repos/$REPO/releases/latest")
        try {
            when (conn.responseCode) {
                200 -> Unit
                404 -> return error("No release has been published yet.")
                else -> return error("GitHub answered ${conn.responseCode}. Try again later.")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val latest = json.getString("tag_name").removePrefix("v")
            if (!isNewer(latest, current)) {
                pending = null
                return JSONObject().put("status", "uptodate").put("current", current)
            }
            val assets = json.getJSONArray("assets")
            var chosen: Release? = null
            // Build.SUPPORTED_ABIS is ordered by preference (arm64-v8a first on a modern phone).
            search@ for (abi in Build.SUPPORTED_ABIS) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.getString("name")
                    if (name.endsWith(".apk") && name.contains(abi)) {
                        chosen = Release(latest, name, a.getString("browser_download_url"), a.getLong("size"))
                        break@search
                    }
                }
            }
            if (chosen == null) {
                return error("Release $latest has no APK for this phone (${Build.SUPPORTED_ABIS.joinToString()}).")
            }
            pending = chosen
            return JSONObject().put("status", "available").put("current", current).put("latest", latest)
                .put("size", "%.0f".format(chosen.size / 1048576.0))
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the APK into the app's cache; [onPercent] is called when the percentage changes. */
    fun download(ctx: Context, release: Release, onPercent: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val out = File(dir, release.apkName)
        val conn = open(release.apkUrl)
        conn.setRequestProperty("Accept", "application/octet-stream")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("GitHub answered ${conn.responseCode}")
            var done = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val percent = if (release.size > 0) (done * 100 / release.size).toInt() else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onPercent(percent)
                        }
                    }
                }
            }
            if (release.size > 0 && out.length() != release.size) throw IllegalStateException("the download was incomplete")
            return out
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    /** Opens Android's package installer for the downloaded APK. */
    fun installIntent(ctx: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** "1.10.0" is newer than "1.9.3"; compares numeric parts only. */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "FCon/${BuildConfig.VERSION_NAME}")
        }
}
