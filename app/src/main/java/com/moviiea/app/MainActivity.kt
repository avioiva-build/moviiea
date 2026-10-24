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
import android.os.Message
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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOME_URL = "https://moviiea.blogspot.com/"

        private val SOCIAL_HOSTS = listOf(
            "facebook.com", "twitter.com", "x.com", "instagram.com",
            "youtube.com", "youtu.be", "t.me", "telegram.me",
            "wa.me", "whatsapp.com", "pinterest.com", "reddit.com"
        )

        private fun isSocialLink(url: Uri): Boolean {
            val host = url.host?.lowercase() ?: return false
            return SOCIAL_HOSTS.any { host == it || host.endsWith(".$it") }
        }
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: LinearLayout

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true

            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportMultipleWindows(true)
            setJavaScriptCanOpenWindowsAutomatically(true)
            userAgentString = userAgentString.replace("; wv)", ")")
        }

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

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val tempView = WebView(view?.context ?: this@MainActivity)
                tempView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        routePopup(request.url)
                        return true
                    }

                    @Deprecated("Used by old WebView versions")
                    override fun shouldOverrideUrlLoading(v: WebView, url: String): Boolean {
                        routePopup(Uri.parse(url))
                        return true
                    }
                }
                (resultMsg?.obj as? WebView.WebViewTransport)?.webView = tempView
                resultMsg?.sendToTarget()
                return true
            }

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

    private fun handleUrl(url: Uri): Boolean {
        val scheme = url.scheme?.lowercase()
        return when (scheme) {
            "http", "https" -> {
                if (isSocialLink(url)) {
                    openExternally(url)
                    true
                } else {
                    false
                }
            }
            "mailto", "tel", "sms", "whatsapp", "tg", "intent" -> {
                openExternally(url)
                true
            }
            else -> true
        }
    }

    private fun routePopup(url: Uri) {
        val scheme = url.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            if (isSocialLink(url)) {
                openExternally(url)
            } else {
                webView.loadUrl(url.toString())
            }
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
