package app.fcon

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Talks to the project's Firebase over plain REST (no Firebase SDK): anonymous sign-in, issue reports, the
 * announcement card and the download counter. Who may do what is decided by the Firestore security rules
 * (admin/firestore.rules), because the key below is public by design.
 */
object Firebase {
    private const val TAG = "FconFirebase"
    private const val API_KEY = "AIzaSyCYgp2ly_OtJ71dSrd803Z2BkWqZXEwBZw"
    private const val PROJECT = "file-conversion-online-network"
    private const val DOCS = "https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents"
    private const val DOC_PATH = "projects/$PROJECT/databases/(default)/documents"

    private val CATEGORIES = setOf(
        "Conversion failed", "Facebook conversion failed", "Instagram conversion failed",
        "Audio or video quality issue", "Other issue",
    )

    private val ADJECTIVES = listOf("Swift", "Calm", "Bright", "Lucky", "Quiet", "Brave", "Gentle", "Sunny", "Clever", "Mellow", "Bold", "Cosmic", "Misty", "Nimble", "Witty", "Zesty")
    private val ANIMALS = listOf("Otter", "Falcon", "Panda", "Fox", "Heron", "Lynx", "Koala", "Gecko", "Raven", "Tiger", "Whale", "Moth", "Badger", "Finch", "Ibis", "Yak")

    private var idToken: String? = null
    private var expiresAt = 0L
    private val authLock = Any()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("fcon", Context.MODE_PRIVATE)

    private class Reply(val code: Int, val body: String)

    private fun call(method: String, url: String, body: String? = null, token: String? = null, form: Boolean = false): Reply {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            return Reply(c.responseCode, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            c.disconnect()
        }
    }

    /** One anonymous account per install; the token is refreshed when it runs out. Returns (token, uid). */
    private fun signedIn(ctx: Context): Pair<String, String> = synchronized(authLock) {
        val p = prefs(ctx)
        val uid = p.getString("fb_uid", null)
        val token = idToken
        if (uid != null && token != null && System.currentTimeMillis() < expiresAt) return token to uid
        val refresh = p.getString("fb_refresh", null)
        if (uid != null && refresh != null) {
            val r = call("POST", "https://securetoken.googleapis.com/v1/token?key=$API_KEY", "grant_type=refresh_token&refresh_token=$refresh", form = true)
            if (r.code == 200) {
                val j = JSONObject(r.body)
                idToken = j.getString("id_token")
                expiresAt = System.currentTimeMillis() + j.getString("expires_in").toLong() * 1000 - 120_000
                p.edit().putString("fb_refresh", j.getString("refresh_token")).apply()
                return idToken!! to uid
            }
        }
        val r = call("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$API_KEY", "{\"returnSecureToken\":true}")
        if (r.code != 200) throw IllegalStateException("Could not reach the report service (${r.code})")
        val j = JSONObject(r.body)
        idToken = j.getString("idToken")
        expiresAt = System.currentTimeMillis() + j.getString("expiresIn").toLong() * 1000 - 120_000
        p.edit().putString("fb_uid", j.getString("localId")).putString("fb_refresh", j.getString("refreshToken")).apply()
        return idToken!! to j.getString("localId")
    }

    /** "Swift Otter 4821": a made-up name derived from the install's id, so reports never carry a real identity. */
    private fun anonName(uid: String): String {
        val h = uid.fold(7L) { a, ch -> (a * 31 + ch.code) and 0x7fffffff }
        return "${ADJECTIVES[(h % ADJECTIVES.size).toInt()]} ${ANIMALS[((h / 16) % ANIMALS.size).toInt()]} ${1000 + (h / 256) % 9000}"
    }

    private fun str(v: String) = JSONObject().put("stringValue", v)

    fun sendReport(ctx: Context, category: String, details: String) {
        require(category in CATEGORIES) { "Choose what went wrong first" }
        val text = details.trim().take(1000)
        val (token, uid) = signedIn(ctx)
        val fields = JSONObject()
            .put("category", str(category))
            .put("details", str(text))
            .put("name", str(anonName(uid)))
            .put("uid", str(uid))
            .put("status", str("Pending"))
            .put("createdAt", JSONObject().put("timestampValue", Instant.now().toString()))
            .put("appVersion", str(BuildConfig.VERSION_NAME))
            .put("device", str("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · yt-dlp ${Engine.version(ctx)}".take(200)))
        val r = call("POST", "$DOCS/reports", JSONObject().put("fields", fields).toString(), token)
        if (r.code !in 200..299) {
            Log.w(TAG, "report rejected: ${r.code} ${r.body.take(200)}")
            throw IllegalStateException("The report could not be sent (${r.code}). Please try again later.")
        }
    }

    /** The announcement the admin dashboard published, or an inactive one. Readable by everybody, so no sign-in. */
    fun announcement(): JSONObject {
        val r = call("GET", "$DOCS/announcements/current")
        if (r.code == 404) return JSONObject().put("active", false)
        if (r.code != 200) throw IllegalStateException("announcement ${r.code}")
        val doc = JSONObject(r.body)
        val f = doc.optJSONObject("fields") ?: return JSONObject().put("active", false)
        fun s(k: String) = f.optJSONObject(k)?.optString("stringValue").orEmpty()
        return JSONObject()
            .put("active", f.optJSONObject("active")?.optBoolean("booleanValue") ?: false)
            .put("title", s("title"))
            .put("message", s("message"))
            .put("showUpdate", f.optJSONObject("showUpdate")?.optBoolean("booleanValue") ?: false)
            .put("id", doc.optString("updateTime"))
    }

    /**
     * Counts one successful download (called after the file was really saved). If the phone is offline the count
     * is kept and sent with the next download.
     */
    fun countDownload(ctx: Context, isVideo: Boolean) {
        val app = ctx.applicationContext
        Thread({
            val p = prefs(app)
            synchronized(authLock) {
                val key = if (isVideo) "fb_pending_video" else "fb_pending_audio"
                p.edit().putInt(key, p.getInt(key, 0) + 1).apply()
            }
            try {
                flushDownloads(app)
            } catch (e: Exception) {
                Log.w(TAG, "download count kept for later: ${e.message}")
            }
        }, "fcon-count").start()
    }

    private fun flushDownloads(ctx: Context) {
        val p = prefs(ctx)
        val audio: Int
        val video: Int
        synchronized(authLock) {
            audio = p.getInt("fb_pending_audio", 0).coerceAtMost(20)
            video = p.getInt("fb_pending_video", 0).coerceAtMost(20 - audio)
        }
        val n = audio + video
        if (n == 0) return
        val (token, _) = signedIn(ctx)
        val transforms = JSONArray().put(inc("downloads", n))
        if (audio > 0) transforms.put(inc("audio", audio))
        if (video > 0) transforms.put(inc("video", video))
        val write = JSONObject().put(
            "transform",
            JSONObject().put("document", "$DOC_PATH/stats/global").put("fieldTransforms", transforms),
        )
        val r = call("POST", "$DOCS:commit", JSONObject().put("writes", JSONArray().put(write)).toString(), token)
        if (r.code !in 200..299) throw IllegalStateException("count ${r.code}")
        synchronized(authLock) {
            p.edit().putInt("fb_pending_audio", p.getInt("fb_pending_audio", 0) - audio)
                .putInt("fb_pending_video", p.getInt("fb_pending_video", 0) - video).apply()
        }
    }

    private fun inc(field: String, by: Int) =
        JSONObject().put("fieldPath", field).put("increment", JSONObject().put("integerValue", by.toString()))
}
