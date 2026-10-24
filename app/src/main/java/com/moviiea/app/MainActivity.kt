package com.moviiea.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Moviiea — main (and only) screen of the app.
 *
 * The app is a native "shell" around the Moviiea website:
 *   - website loads in a secure WebView
 *   - pull down to refresh
 *   - friendly offline screen with a Retry button
 *   - phone Back button moves between pages before leaving the app
 *   - links to other websites open in the phone browser
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** The website the app opens. Change this if the site address ever changes. */
        private const val HOME_URL = "https://moviiea.blogspot.com/"

        /** Links inside the app: the site itself and any other Blogger blog. */
        private fun isInAppUrl(url: Uri): Boolean {
            val host = url.host?.lowercase() ?: return false
            return host == "moviiea.blogspot.com" || host.endsWith(".blogspot.com")
        }
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: LinearLayout

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash screen (brand background + app icon) until the app is ready.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)

        setupWebView()
        setupSwipeToRefresh()
        setupBackButton()

        if (isOnline()) {
            if (savedInstanceState != null) {
                webView.restoreState(savedInstanceState)
            } else {
                webView.loadUrl(HOME_URL)
            }
        } else {
            showOfflineScreen()
        }
    }

    // ------------------------------------------------------------------ setup

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        // Keep the site logged-in state (cookies) between visits.
        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return handleUrl(request.url)
            }

            @Deprecated("Used by old WebView versions")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleUrl(Uri.parse(url))
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                errorView.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                // Only react to errors of the main page, not of single images etc.
                if (request.isForMainFrame && !isOnline()) {
                    showOfflineScreen()
                }
            }

            @Deprecated("Used by old Android versions")
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                if (!isOnline()) {
                    showOfflineScreen()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            // Allow the site to open the phone's file picker (e.g. attach forms).
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                return try {
                    startActivityForResult(intent, 1001)
                    true
                } catch (e: ActivityNotFoundException) {
                    filePathCallback = null
                    false
                }
            }
        }
    }

    /** Decide where a clicked link goes: inside the app or in the phone browser. */
    private fun handleUrl(url: Uri): Boolean {
        val scheme = url.scheme?.lowercase()
        when (scheme) {
            "http", "https" -> {
                return if (isInAppUrl(url)) {
                    false // keep it inside the WebView
                } else {
                    openExternally(url)
                    true
                }
            }
            "mailto", "tel", "sms", "whatsapp", "tg" -> {
                openExternally(url)
                return true
            }
            "intent" -> {
                openExternally(url)
                return true
            }
            else -> return true // block unknown schemes silently
        }
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSwipeToRefresh() {
        swipeRefresh.setOnRefreshListener {
            if (isOnline()) {
                errorView.visibility = View.GONE
                webView.reload()
            } else {
                swipeRefresh.isRefreshing = false
                showOfflineScreen()
            }
        }
    }

    private fun setupBackButton() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    errorView.visibility == View.VISIBLE -> {
                        if (isOnline()) {
                            errorView.visibility = View.GONE
                            webView.loadUrl(HOME_URL)
                        } else {
                            finish()
                        }
                    }
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })
    }

    // ------------------------------------------------------------- offline UI

    private fun showOfflineScreen() {
        swipeRefresh.isRefreshing = false
        progressBar.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        webView.stopLoading()
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            return info != null && info.isConnected
        }
    }

    // --------------------------------------------------------- retry / results

    fun onRetryClicked(view: View) {
        if (isOnline()) {
            errorView.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
            val current = webView.url
            if (current.isNullOrBlank()) webView.loadUrl(HOME_URL) else webView.reload()
        } else {
            Toast.makeText(this, R.string.still_offline, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Handled by onShowFileChooser above")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1001) {
            filePathCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            )
            filePathCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // ----------------------------------------------------------- state & menu

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
