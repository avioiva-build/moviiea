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

/**
 * Moviiea v2.4 — the in-app player screen.
 *
 * Playback now happens INSIDE the app: a WebView loads the same GitHub
 * Pages bridge page (player.html) the Chrome path uses, wrapped in a
 * Moviiea-styled screen with the title on top, a thin loading bar, real
 * fullscreen video and a friendly error state with retry.
 *
 * Why this works: the video server is loaded inside an inner frame of the
 * bridge page, modern Android WebView engines no longer stamp the app's
 * "X-Requested-With" signature on inner-frame requests, and the user
 * agent below is cleaned of the WebView tag — so the servers see the
 * same browser signature as Chrome. If a server still refuses, the
 * "Open in Chrome" button in the toolbar opens the same link through the
 * proven Chrome path. One tap, always a way to watch.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_EMBED = "embed"
        private const val EXTRA_TITLE = "title"

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
        // Look like Chrome, not like "WebView": drop the WebView tag.
        s.userAgentString = s.userAgentString.replace("; wv)", ")")

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

    private fun showPlayerError() {
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
