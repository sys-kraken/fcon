package app.fcon

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Shows the bundled FCon page in a WebView. Every request to the fake host below is answered
 * from inside the app: paths under /api/ go to [Api], everything else is read from assets/www.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var pageLoaded = false
    private var pendingLink: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply { setBackgroundColor(Color.parseColor("#0A0A0B")) }
        setContentView(web)
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
        }
        web.addJavascriptInterface(Bridge(), "FConApp")
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d("FconWeb", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.host != HOST) return null
                val path = request.url.path.orEmpty()
                return if (path.startsWith("/api/")) Api.handle(applicationContext, request) else asset(path)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == HOST) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageLoaded = true
                applyLink()
            }
        }

        pendingLink = linkFrom(intent)
        web.loadUrl("https://$HOST/index.html")

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingLink = linkFrom(intent)
        if (pageLoaded) applyLink()
    }

    /** A link shared to FCon from another app goes straight into the URL box. */
    private fun linkFrom(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return Regex("""https?://\S+""").find(text)?.value
    }

    private fun applyLink() {
        val link = pendingLink ?: return
        pendingLink = null
        val js = "(function(){var i=document.getElementById('urlInput');if(!i)return;" +
            "i.value=${JSONObject.quote(link)};i.dispatchEvent(new Event('input',{bubbles:true}));})();"
        web.evaluateJavascript(js, null)
    }

    private fun asset(path: String): WebResourceResponse {
        val name = path.trimStart('/').ifEmpty { "index.html" }
        if (name.contains("..")) return notFound()
        return try {
            val ext = name.substringAfterLast('.', "")
            val mime = when (ext) {
                "js" -> "text/javascript"
                "html" -> "text/html"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            }
            WebResourceResponse(mime, "UTF-8", assets.open("www/$name"))
        } catch (e: IOException) {
            notFound()
        }
    }

    private fun notFound() = WebResourceResponse(
        "text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)),
    )

    /** Exposed to the page as `window.FConApp`. */
    inner class Bridge {
        /** Saves a converted file to Downloads/FCon. Returns where it went, or "ERR:<reason>". */
        @JavascriptInterface
        fun save(fileId: String): String {
            val stored = JobManager.fileFor(fileId)
                ?: return "ERR:That file is no longer available. Convert it again."
            return try {
                Saver.save(applicationContext, stored.file, stored.name).also {
                    // Only a file that was really saved counts as a download.
                    Firebase.countDownload(applicationContext, stored.file.extension.lowercase() in setOf("mp4", "webm", "mkv"))
                }
            } catch (e: Exception) {
                "ERR:" + (e.message ?: "Could not save the file")
            }
        }

        @JavascriptInterface
        fun version(): String = "FCon ${BuildConfig.VERSION_NAME} · ${Engine.version(applicationContext)}"

        /** The text on the clipboard ("" if there is none). WebView itself is not allowed to read the clipboard. */
        @JavascriptInterface
        fun paste(): String {
            val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            if (clip == null || clip.itemCount == 0) return ""
            return clip.getItemAt(0).coerceToText(applicationContext)?.toString()?.trim().orEmpty()
        }

        /** Opens Downloads/FCon in the Files app (falls back to the Downloads list). */
        @JavascriptInterface
        fun openFolder() {
            runOnUiThread {
                val folder = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/FCon")
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(view)
                } catch (e: ActivityNotFoundException) {
                    startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }

        /** Asks GitHub for the latest release; the answer goes back to the page as JSON. */
        @JavascriptInterface
        fun checkUpdate() {
            Thread {
                val result = try {
                    Updater.check()
                } catch (e: Exception) {
                    Updater.error("Could not reach GitHub: ${e.message ?: e.javaClass.simpleName}")
                }
                pushUpdate(result)
            }.start()
        }

        /** Downloads the release found by [checkUpdate] and opens Android's installer for it. */
        @JavascriptInterface
        fun installUpdate() {
            val release = Updater.pending
            if (release == null) {
                pushUpdate(Updater.error("Check for updates first."))
                return
            }
            if (!packageManager.canRequestPackageInstalls()) {
                // Android makes the user switch "Install unknown apps" on for FCon once.
                runOnUiThread {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                pushUpdate(JSONObject().put("status", "needs_permission").put("latest", release.version))
                return
            }
            Thread {
                try {
                    val apk = Updater.download(applicationContext, release) { percent ->
                        pushUpdate(JSONObject().put("status", "downloading").put("latest", release.version).put("percent", percent))
                    }
                    runOnUiThread { startActivity(Updater.installIntent(this@MainActivity, apk)) }
                    pushUpdate(JSONObject().put("status", "installing").put("latest", release.version))
                } catch (e: Exception) {
                    pushUpdate(Updater.error("Download failed: ${e.message ?: e.javaClass.simpleName}"))
                }
            }.start()
        }
    }

    private fun pushUpdate(state: JSONObject) {
        runOnUiThread {
            web.evaluateJavascript("window.__fconUpdate(${JSONObject.quote(state.toString())})", null)
        }
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
    }
}
