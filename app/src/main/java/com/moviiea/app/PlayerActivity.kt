package com.moviiea.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Message
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Moviiea — the in-app player screen.
 *
 * v2.6 FIX — why playback stalled after pressing play: the v2.5 pass
 * re-fetched EVERY request to the video servers with OkHttp, which
 * quietly broke the cookie handshake those servers rely on (their
 * Set-Cookie headers never reached the engine, so the stream calls
 * after pressing play were not recognized) and turned POST requests
 * into GETs. Now only PAGE loads are re-fetched — clean of Android's
 * "X-Requested-With" app tag — and one shared cookie jar keeps the
 * server's session alive between our fetches and the engine's own
 * stream calls. Third-party cookies are on, pop-up windows work, the
 * Chrome-like identity is actually applied, and a crashed video engine
 * rebuilds itself instead of taking the screen down.
 *
 * Secret diagnostics: long-press the title in the player top bar to see
 * the recent engine log (helps remote debugging via a screenshot).
 *
 * The "Open in Chrome" button in the toolbar stays as the backup door:
 * one tap and the same link plays through the proven Chrome path.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_EMBED = "embed"
        private const val EXTRA_TITLE = "title"

        /** Used only if the engine has no user agent yet (never in practice). */
        private const val FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        /** The three video servers (same list as player.html allows). */
        private val EMBED_HOSTS = Regex(
            "^https://(vidapi\\.xyz|vidsrc\\.xyz|(www\\.)?2embed\\.cc)/",
            RegexOption.IGNORE_CASE
        )

        /** The exact shape of a player page URL (for clean re-fetching). */
        private val EMBED_DOC = Regex(
            "^https://(vidapi\\.xyz|vidsrc\\.xyz)/embed/(movie|tv)/\\d+" +
                "|^https://(www\\.)?2embed\\.cc/embed(tv)?/\\d+",
            RegexOption.IGNORE_CASE
        )

        /** Never re-fetch these — media and assets go through the engine. */
        private val MEDIA_EXT = Regex(
            "\\.(m3u8|mpd|ts|mp4|m4s|mp3|aac|vtt|srt|jpg|jpeg|png|webp|gif|css|js|mjs|woff2?)(\\?|#|\$)",
            RegexOption.IGNORE_CASE
        )

        /** Headers we must control ourselves (or that must never pass). */
        private val SKIPPED_HEADERS = setOf(
            "user-agent", "referer", "accept", "cookie", "x-requested-with",
            "host", "accept-encoding", "connection", "content-length",
            "accept-language", "upgrade-insecure-requests",
            "sec-fetch-site", "sec-fetch-mode", "sec-fetch-dest",
            "sec-fetch-user", "origin", "if-none-match", "if-modified-since"
        )

        /** Response headers that must not be copied verbatim back to the engine. */
        private val STRIPPED_RESPONSE_HEADERS = setOf(
            "content-encoding", "content-length", "transfer-encoding",
            "content-type", "keep-alive", "set-cookie"
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

    private lateinit var stage: FrameLayout
    private lateinit var loadProgress: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout

    private var webView: WebView? = null
    private var popupView: WebView? = null

    private var chromeView: View? = null
    private var chromeCallback: WebChromeClient.CustomViewCallback? = null

    /** Chrome-like user agent captured in applyWebSettings (threads read it). */
    @Volatile
    private var playerUa: String = ""

    /** Rolling log shown by the secret diagnostics dialog. */
    private val debugLog = ArrayDeque<String>()

    /**
     * One shared client for page re-fetches. Its cookie jar IS the
     * engine's cookie jar — cookies the server sets on our fetches are
     * visible to the engine's own stream calls, and vice versa.
     */
    private val embedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .cookieJar(object : CookieJar {
                override fun loadForRequest(url: HttpUrl): List<okhttp3.Cookie> {
                    val raw = CookieManager.getInstance().getCookie(url.toString())
                        ?: return emptyList()
                    return raw.split(";")
                        .mapNotNull { okhttp3.Cookie.parse(url, it.trim()) }
                }

                override fun saveFromResponse(url: HttpUrl, cookies: List<okhttp3.Cookie>) {
                    for (cookie in cookies) {
                        CookieManager.getInstance().setCookie(url.toString(), cookie.toString())
                    }
                }
            })
            .build()
    }

    private val embedUrl: String
        get() = intent.getStringExtra(EXTRA_EMBED) ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val titleView = findViewById<TextView>(R.id.playerTitle)
        titleView.text = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.player_title_default)
        titleView.setOnLongClickListener {
            showDebugLog()
            true
        }
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnChrome).setOnClickListener {
            // The backup door: same link, real Chrome engine.
            PlayerLauncher.open(this, embedUrl)
        }
        findViewById<AppCompatButton>(R.id.playerRetry).setOnClickListener {
            errorView.visibility = View.GONE
            webView?.visibility = View.VISIBLE
            loadProgress.visibility = View.VISIBLE
            webView?.reload()
        }

        loadProgress = findViewById(R.id.loadProgress)
        errorView = findViewById(R.id.playerError)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        stage = findViewById(R.id.playerStage)

        logDebug("OPEN $embedUrl")

        val initial = findViewById<WebView>(R.id.playerWebView)
        attachMainClients(initial)
        webView = initial
        initial.loadUrl(PlayerLauncher.bridgeUrl(embedUrl))
    }

    // ------------------------------------------------------------- setup

    private fun applyWebSettings(s: WebSettings) {
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        // Chrome-like signature: drop the WebView tag and the Version
        // marker real Chrome never sends — and actually APPLY it.
        playerUa = s.userAgentString
            .replace("; wv)", ")")
            .replace("Version/4.0 ", "")
        s.userAgentString = playerUa
    }

    private fun prepareWebView(wv: WebView) {
        applyWebSettings(wv.settings)
        wv.setBackgroundColor(Color.BLACK)
        // The video servers remember players with cookies — including
        // cookies set inside their own frame. Chrome allows those; we
        // must too, or the play button spins forever.
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
    }

    private fun attachMainClients(wv: WebView) {
        prepareWebView(wv)
        wv.webChromeClient = mainChrome
        wv.webViewClient = mainClient
    }

    private fun newWebView(): WebView {
        val wv = WebView(this)
        prepareWebView(wv)
        return wv
    }

    // ------------------------------------------------------------ clients

    private fun logConsole(cm: ConsoleMessage) {
        logDebug(
            "JS ${cm.messageLevel()} ${cm.message()}" +
                " (${cm.sourceId() ?: "?"}:${cm.lineNumber()})"
        )
    }

    private val mainChrome = object : WebChromeClient() {
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
            webView?.visibility = View.GONE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun onHideCustomView() {
            exitFullscreen()
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            return openPopup(resultMsg)
        }

        override fun onCloseWindow(window: WebView?) {
            closePopup()
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            logConsole(consoleMessage)
            return super.onConsoleMessage(consoleMessage)
        }
    }

    private val popupChrome = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            loadProgress.progress = newProgress
            loadProgress.visibility =
                if (newProgress >= 100) View.GONE else View.VISIBLE
        }

        override fun onCloseWindow(window: WebView?) {
            closePopup()
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            logConsole(consoleMessage)
            return super.onConsoleMessage(consoleMessage)
        }
    }

    /** Web pages stay inside the player; odd schemes go to Android. */
    private fun keepWebLink(request: WebResourceRequest): Boolean {
        val scheme = request.url.scheme ?: return false
        val internal = scheme.equals("http", true) ||
            scheme.equals("https", true) ||
            scheme.equals("about", true) ||
            scheme.equals("data", true) ||
            scheme.equals("blob", true) ||
            scheme.equals("javascript", true)
        if (internal) return false
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, request.url))
            true
        } catch (e: Exception) {
            true
        }
    }

    private val mainClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean = keepWebLink(request)

        /**
         * THE v2.6 FIX — only PAGE loads of the video servers are
         * re-fetched by us (no app tag, Chrome-like headers, cookies
         * kept). Stream calls, media files and POSTs pass through the
         * engine untouched, so credentials and methods survive intact.
         */
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            return fetchClean(request)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            logDebug("ERR ${error.description} — ${request.url}")
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
            logDebug("ERR($errorCode) $description — $failingUrl")
            if (failingUrl == null || view.url == failingUrl) showPlayerError()
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) {
            val url = request.url.toString()
            if (request.isForMainFrame || EMBED_HOSTS.containsMatchIn(url)) {
                logDebug("HTTP ${errorResponse.statusCode} — $url")
            }
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail
        ): Boolean {
            logDebug("ENGINE CRASH — rebuilding player")
            rebuildWebView()
            return true
        }
    }

    private val popupClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean = keepWebLink(request)

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            logDebug("POPUP ERR ${error.description} — ${request.url}")
        }
    }

    // -------------------------------------------------------- clean fetch

    private fun fetchClean(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (request.method != "GET") return null
        if (!EMBED_HOSTS.containsMatchIn(url)) return null
        if (MEDIA_EXT.containsMatchIn(url)) return null

        var accept: String? = null
        for ((name, value) in request.requestHeaders) {
            if (name.equals("Accept", ignoreCase = true)) {
                accept = value
                break
            }
        }
        val isPage = accept?.contains("text/html", ignoreCase = true) == true ||
            EMBED_DOC.containsMatchIn(url)
        if (!isPage) return null

        return try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", playerUa.ifEmpty { FALLBACK_UA })
                .header("Referer", PlayerLauncher.bridgeUrl(embedUrl))
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9," +
                        "image/avif,image/webp,*/*;q=0.8"
                )
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Site", "cross-site")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Dest", "iframe")
            for ((name, value) in request.requestHeaders) {
                if (name.lowercase(Locale.US) in SKIPPED_HEADERS) continue
                builder.header(name, value)
            }

            val response = embedClient.newCall(builder.build()).execute()
            logDebug("PAGE ${response.code} — $url")
            val body = response.body ?: run {
                response.close()
                return null
            }

            val contentType = response.header("Content-Type") ?: "text/html"
            val mime = contentType.substringBefore(";").trim()
                .ifEmpty { "application/octet-stream" }
            val charset = contentType.substringAfter("charset=", "")
                .substringBefore(";").trim().ifEmpty { null }

            // Rebuild the header map: drop transport markers (OkHttp
            // already decompressed) and duplicates. Cookies were already
            // mirrored into the shared jar by the client itself.
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
            logDebug("FETCH FAIL — $url")
            // Network hiccup: let the engine try its own way.
            null
        }
    }

    // -------------------------------------------------------------- popup

    /** window.open / target=_blank now open here instead of vanishing. */
    private fun openPopup(resultMsg: Message): Boolean {
        closePopup()
        val pw = newWebView()
        pw.webChromeClient = popupChrome
        pw.webViewClient = popupClient
        popupView = pw
        stage.addView(
            pw,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        pw.bringToFront()
        (resultMsg.obj as WebView.WebViewTransport).setWebView(pw)
        resultMsg.sendToTarget()
        logDebug("POPUP opened")
        return true
    }

    private fun closePopup() {
        val pw = popupView ?: return
        popupView = null
        (pw.parent as? ViewGroup)?.removeView(pw)
        pw.destroy()
        logDebug("POPUP closed")
    }

    // ------------------------------------------------------------ recovery

    /** The video engine died — rebuild it quietly instead of dying too. */
    private fun rebuildWebView() {
        val old = webView ?: return
        val parent = old.parent as? ViewGroup
        webView = null
        parent?.removeView(old)
        old.destroy()
        if (isFinishing || isDestroyed) return

        val fresh = newWebView()
        fresh.webChromeClient = mainChrome
        fresh.webViewClient = mainClient
        webView = fresh
        parent?.addView(
            fresh,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        errorView.visibility = View.GONE
        fresh.visibility = View.VISIBLE
        loadProgress.visibility = View.VISIBLE
        fresh.loadUrl(PlayerLauncher.bridgeUrl(embedUrl))
    }

    private fun showPlayerError() {
        loadProgress.visibility = View.GONE
        webView?.visibility = View.GONE
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
        webView?.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // --------------------------------------------------------------- debug

    private fun logDebug(line: String) {
        val stamp = DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        synchronized(debugLog) {
            debugLog.addLast("$stamp  $line")
            while (debugLog.size > 80) debugLog.removeFirst()
        }
    }

    /** Long-press the player title: engine log for remote debugging. */
    private fun showDebugLog() {
        val engineVersion = try {
            packageManager.getPackageInfo("com.google.android.webview", 0)
                .versionName ?: "?"
        } catch (e: Exception) {
            "unknown"
        }
        val lines = synchronized(debugLog) { debugLog.toList() }
        val text = buildString {
            appendLine(getString(R.string.player_log_hint))
            appendLine()
            appendLine("WebView engine: $engineVersion")
            appendLine("— — —")
            if (lines.isEmpty()) appendLine("(no entries yet)")
            else for (line in lines) appendLine(line)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.player_log_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ----------------------------------------------------- back/lifecycle

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 1st Back: close a pop-up window. 2nd: leave fullscreen video.
        // 3rd: page history. Last: slide back out to Moviiea.
        if (popupView != null) {
            closePopup()
            return
        }
        if (chromeView != null) {
            exitFullscreen()
            return
        }
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            return
        }
        super.onBackPressed()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
        popupView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        popupView?.onResume()
    }

    override fun onDestroy() {
        closePopup()
        exitFullscreen()
        webView?.let {
            it.loadUrl("about:blank")
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        super.onDestroy()
    }
}
