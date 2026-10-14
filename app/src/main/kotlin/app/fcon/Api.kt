package app.fcon

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * The same JSON contract as the FCon server API, answered in-process (no network port is opened).
 * The page cannot send POST bodies through WebView interception, so config.js turns them into
 * GET requests with a ?body=<json> parameter.
 */
object Api {
    fun handle(ctx: Context, request: WebResourceRequest): WebResourceResponse {
        val path = request.url.path.orEmpty()
        return try {
            when {
                path == "/api/stats" -> json(
                    200,
                    JSONObject().put("total", 0).put("audio", 0).put("video", 0).put("success", 0)
                        .put("failed", 0).put("this_month", 0).put("unique_ips", 0).put("total_conversions", 0),
                )
                path == "/api/info" -> info(ctx, body(request))
                path == "/api/report" -> {
                    val b = body(request)
                    Firebase.sendReport(ctx, b.optString("category"), b.optString("details"))
                    json(200, JSONObject().put("ok", true))
                }
                path == "/api/announcement" -> json(200, Firebase.announcement())
                path == "/api/convert" -> json(200, JobManager.start(ctx, body(request)))
                path.startsWith("/api/progress/") -> progress(path.substringAfterLast('/'))
                path.startsWith("/api/result/") -> result(path.substringAfterLast('/'))
                else -> json(404, error("Not found"))
            }
        } catch (e: IllegalArgumentException) {
            json(400, error(e.message ?: "Bad request"))
        } catch (e: Throwable) {
            json(500, error(e.message ?: e.toString()))
        }
    }

    private fun body(request: WebResourceRequest): JSONObject =
        JSONObject(request.url.getQueryParameter("body") ?: "{}")

    /** Video resolution and duration, so the page can limit its resolution list and trim sliders. */
    private fun info(ctx: Context, body: JSONObject): WebResourceResponse {
        val url = body.optString("url").trim()
        require(url.isNotEmpty()) { "No URL provided" }
        // The lookup is saved so that Convert does not have to ask the site again (see InfoCache).
        // The quick lookup already lists every resolution (even 4K), so it is all the page needs.
        val entry = InfoCache.fetch(ctx, url)
        return json(
            200,
            JSONObject().put("max_resolution", if (entry.maxHeight > 0) entry.maxHeight else 1080)
                .put("duration", entry.duration),
        )
    }

    /** Mirrors the server's event stream values: "12.5" while downloading, "100" when done, "ERROR" on failure. */
    private fun progress(id: String): WebResourceResponse {
        val job = JobManager.job(id) ?: return text(404, "unknown job")
        return text(
            200,
            when (job.state) {
                "done" -> "100"
                "error" -> "ERROR"
                else -> job.progress.toString()
            },
        )
    }

    private fun result(id: String): WebResourceResponse {
        val job = JobManager.job(id) ?: return json(404, error("Result not ready or expired"))
        return when (job.state) {
            "done" -> json(200, job.result ?: JSONObject())
            "error" -> json(500, error(job.error ?: "Conversion failed. Please try again."))
            else -> json(404, error("Result not ready or expired"))
        }
    }

    private fun error(message: String) = JSONObject().put("error", message)
    private fun json(status: Int, obj: JSONObject) = response(status, "application/json", obj.toString())
    private fun text(status: Int, s: String) = response(status, "text/plain", s)

    private fun response(status: Int, mime: String, body: String): WebResourceResponse {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }
        return WebResourceResponse(mime, "UTF-8", status, reason, mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(body.toByteArray()))
    }
}
