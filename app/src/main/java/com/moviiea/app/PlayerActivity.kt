package com.moviiea.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Moviiea — the in-app player screen.
 *
 * v2.5 FIX: the video servers silently return an EMPTY page (black
 * screen) to any request that carries Android's "X-Requested-With" app
 * tag. WebView stamps that tag on frame navigations and it cannot be
 * switched off. So this screen now intercepts every request to the
 * video servers and re-fetches it itself with OkHttp — without the tag,
 * with a Chrome-like user agent and the GitHub Pages referer — then
 * hands the response to the in-app engine. The server can no longer
 * tell the difference from Chrome.
 *
 * The "Open in Chrome" button in the toolbar stays as the backup door:
 * one tap and the same link plays through the proven Chrome path.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_EMBED = "embed"
        private const val EXTRA_TITLE = "title"

        /** The three video servers (same list as player.html allows). */
        private val EMBED_HOSTS = Regex(
            "^https://(vidapi\\.xyz|vidsrc\\.xyz|(www\\.)?2embed\\.cc)/",
            RegexOption.IGNORE_CASE
        )

        /** The exact shape of a player page URL (for the refused message). */
        private val EMBED_DOC = Regex(
            "^https://(vidapi\\.xyz|vidsrc\\.xyz)/embed/(movie|tv)/\\d+" +
                "|^https://(www\\.)?2embed\\.cc/embed(tv)?/\\d+",
            RegexOption.IGNORE_CASE
        )

        /** Headers we must control ourselves (or that must never pass). */
        private val SKIPPED_HEADERS = setOf(
            "user-agent", "referer", "accept", "x-requested-with",
            "host", "accept-encoding", "connection", "content-length"
        )

        /** Response headers that must not be copied verbatim back to the engine. */
        private val STRIPPED_RESPONSE_HEADERS = setOf(
            "content-encoding", "content-length", "transfer-encoding",
            "content-type", "keep-alive"
        )

        fun start(context: Context, embedUrl: String, title: String) {
            val intent = Intent(context, PlayerActivity::class.java)
            intent.putExtra(EXTRA_EMBED, embedUrl)
            intent.putExtra(EXTRA_TITLE, title)
            context.startActivity(intent)
            if (context is Activity) {
                context.overridePendingTransition(
                    R.anim.slide_in_right, R.anim.slide_out_left
                )
            }
        }
    }

    private lateinit var webView: WebView
    private lateinit var loadProgress: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout

    private var chromeView: View? = null
    private var chromeCallback: WebChromeClient.CustomViewCallback? = null

    /** Chrome-like user agent captured in setupWebView (worker threads read it). */
    @Volatile
    private var playerUa: String = ""

    /** One shared client — used only inside the request interceptor. */
    private val embedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val embedUrl: String
        get() = intent.getStringExtra(EXTRA_EMBED) ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        findViewById<TextView>(R.id.playerTitle).text =
            intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
                ?: getString(R.string.player_title_default)
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnChrome).setOnClickListener {
            // The backup door: same link, real Chrome engine.
            PlayerLauncher.open(this, embedUrl)
        }
        findViewById<AppCompatButton>(R.id.playerRetry).setOnClickListener {
            errorView.visibility = View.GONE
            webView.visibility = View.VISIBLE
            loadProgress.visibility = View.VISIBLE
            webView.reload()
        }

        loadProgress = findViewById(R.id.loadProgress)
        errorView = findViewById(R.id.playerError)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        webView = findViewById(R.id.playerWebView)

        setupWebView()
        webView.loadUrl(PlayerLauncher.bridgeUrl(embedUrl))
    }

    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        // Chrome-like signature: drop the WebView tag and the Version
        // marker that real Chrome never sends.
        playerUa = s.userAgentString
            .replace("; wv)", ")")
            .replace("Version/4.0 ", "")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                loadProgress.progress = newProgress
                loadProgress.visibility =
                    if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (chromeView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                chromeView = view
                chromeCallback = callback
                fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }

        webView.webViewClient = object : WebViewClient() {

            /**
             * THE v2.5 FIX — fetch video-server pages ourselves, without
             * the "X-Requested-With" app tag that makes the servers send
             * an empty page. Everything else loads the normal way.
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                if (!EMBED_HOSTS.containsMatchIn(url)) return null

                return try {
                    val builder = Request.Builder()
                        .url(url)
                        .header(
                            "User-Agent",
                            playerUa.ifEmpty {
                                "Mozilla/5.0 (Linux; Android 13) " +
                                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                    "Chrome/124.0.0.0 Mobile Safari/537.36"
                            }
                        )
                        .header("Referer", PlayerLauncher.PLAYER_PAGE)
                        .header(
                            "Accept",
                            "text/html,application/xhtml+xml," +
                                "application/xml;q=0.9,*/*;q=0.8"
                        )
                    for ((name, value) in request.requestHeaders) {
                        if (name.lowercase(Locale.US) in SKIPPED_HEADERS) continue
                        builder.header(name, value)
                    }

                    val response = embedClient.newCall(builder.build()).execute()
                    val body = response.body ?: run {
                        response.close()
                        return null
                    }

                    // Friendly message when a server flat-out refuses.
                    if (EMBED_DOC.containsMatchIn(url) && !response.isSuccessful) {
                        runOnUiThread { showPlayerError(refused = true) }
                    }

                    val contentType = response.header("Content-Type") ?: "text/html"
                    val mime = contentType.substringBefore(";").trim()
                        .ifEmpty { "application/octet-stream" }
                    val charset = contentType.substringAfter("charset=", "")
                        .substringBefore(";").trim().ifEmpty { null }

                    // Rebuild the header map: drop length/encoding markers
                    // (OkHttp already decompressed) and duplicates.
                    val headers = HashMap<String, String>()
                    for ((name, value) in response.headers) {
                        val lower = name.lowercase(Locale.US)
                        if (lower in SKIPPED_HEADERS ||
                            lower in STRIPPED_RESPONSE_HEADERS ||
                            headers.containsKey(lower)
                        ) {
                            continue
                        }
                        headers[lower] = value
                    }

                    WebResourceResponse(
                        mime,
                        charset,
                        response.code,
                        response.message.ifEmpty { "OK" },
                        headers,
                        body.byteStream()
                    )
                } catch (e: Exception) {
                    // Network hiccup: let the engine try its own way.
                    null
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                // Ads and trackers fail all the time — only a broken MAIN
                // page is worth the friendly error screen.
                if (request.isForMainFrame) showPlayerError()
            }

            @Deprecated("Called on Android 5.x (API 22) and below")
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                showPlayerError()
            }
        }
    }

    private fun showPlayerError(refused: Boolean = false) {
        findViewById<TextView>(R.id.playerErrorText).text = getString(
            if (refused) R.string.player_refused_message
            else R.string.player_error_message
        )
        loadProgress.visibility = View.GONE
        webView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
    }

    private fun exitFullscreen() {
        val view = chromeView ?: return
        chromeView = null
        val callback = chromeCallback
        chromeCallback = null
        callback?.onCustomViewHidden()
        fullscreenContainer.removeAllViews()
        fullscreenContainer.visibility = View.GONE
        webView.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 1st Back tap: leave fullscreen video. 2nd: page history.
        // Last: slide back out to Moviiea.
        if (chromeView != null) {
            exitFullscreen()
            return
        }
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
            return
        }
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    override fun onPause() {
        super.onPause()
        if (this::webView.isInitialized) webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (this::webView.isInitialized) webView.onResume()
    }

    override fun onDestroy() {
        exitFullscreen()
        if (this::webView.isInitialized) {
            webView.loadUrl("about:blank")
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }
}
