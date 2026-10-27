package com.moviiea.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Moviiea v2 — a standalone movie & TV browser.
 *
 * The app speaks directly to TMDB (the same data source the website uses):
 * a native poster grid with Trending / Movies / TV / My List categories,
 * language pills (All / English / Hindi / Bengali / Korean), instant
 * search, endless scrolling and a details screen. Playback happens through
 * the player bridge in a styled Chrome Custom Tab, which sidesteps the
 * video servers' WebView blocking entirely.
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val PAGE_SIZE = 20
        const val SPAN_COUNT = 3
    }

    private lateinit var adapter: GridAdapter
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var centerProgress: ProgressBar
    private lateinit var footerProgress: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var searchView: SearchView

    private val chips = ArrayList<View>(4)
    private val langChips = ArrayList<Pair<View, String>>(5)

    private var section = "trending"   // trending | movie | tv | mylist
    private var lang = ""              // "" = All, else TMDB original language code
    private var query: String? = null  // non-null while searching
    private var page = 1
    private var loading = false
    private var hasMore = true
    private var requestNo = 0          // ignores answers that arrive too late

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash screen (brand background + app icon) until the app is ready.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swipeRefresh = findViewById(R.id.swipeRefresh)
        centerProgress = findViewById(R.id.centerProgress)
        footerProgress = findViewById(R.id.footerProgress)
        errorView = findViewById(R.id.errorView)
        errorMessage = findViewById(R.id.errorMessage)
        searchView = findViewById(R.id.searchView)

        adapter = GridAdapter(R.layout.item_movie, { openDetails(it) }, {
            // a heart was tapped on a card — keep My List live
            if (section == "mylist") refresh()
        })
        layoutManager = GridLayoutManager(this, SPAN_COUNT)
        val recycler = findViewById<RecyclerView>(R.id.recyclerGrid)
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loading || !hasMore) return
                val last = layoutManager.findLastVisibleItemPosition()
                if (last >= adapter.itemCount - 4) loadMore()
            }
        })

        setupChips()
        setupLangChips()
        setupSearch()

        swipeRefresh.setOnRefreshListener { refresh() }
        findViewById<View>(R.id.retryButton).setOnClickListener { retry() }

        // Tapping the Moviiea name or the AVIOIVA badge opens the About page.
        findViewById<View>(R.id.imgWordmark).setOnClickListener { AboutActivity.start(this) }
        findViewById<View>(R.id.imgBrandBadge).setOnClickListener { AboutActivity.start(this) }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the details screen: hearts may have changed.
        if (section == "mylist") refresh()
    }

    // ------------------------------------------------------------- sections

    private fun setupChips() {
        chips.clear()
        chips.add(findViewById(R.id.chipTrending))
        chips.add(findViewById(R.id.chipMovies))
        chips.add(findViewById(R.id.chipTv))
        chips.add(findViewById(R.id.chipMyList))
        chips.forEachIndexed { index, chip ->
            chip.setOnClickListener {
                val target = when (index) {
                    1 -> "movie"
                    2 -> "tv"
                    3 -> "mylist"
                    else -> "trending"
                }
                selectSection(target)
            }
        }
        updateChipLook()
    }

    private fun selectSection(newSection: String) {
        section = newSection
        query = null
        searchView.setQuery("", false)
        searchView.clearFocus()
        updateChipLook()
        refresh()
    }

    private fun updateChipLook() {
        val sections = listOf("trending", "movie", "tv", "mylist")
        chips.forEachIndexed { i, chip -> chip.isSelected = sections[i] == section && query == null }
    }

    // -------------------------------------------------------- language pills

    private fun setupLangChips() {
        langChips.clear()
        langChips.add(Pair(findViewById<View>(R.id.langAll), ""))
        langChips.add(Pair(findViewById<View>(R.id.langEnglish), "en"))
        langChips.add(Pair(findViewById<View>(R.id.langHindi), "hi"))
        langChips.add(Pair(findViewById<View>(R.id.langBengali), "bn"))
        langChips.add(Pair(findViewById<View>(R.id.langKorean), "ko"))
        langChips.forEach { (chip, code) ->
            chip.setOnClickListener {
                if (lang != code) {
                    lang = code
                    updateLangLook()
                    refresh()
                }
            }
        }
        updateLangLook()
    }

    private fun updateLangLook() {
        langChips.forEach { (chip, code) -> chip.isSelected = code == lang }
    }

    // --------------------------------------------------------------- search

    private fun setupSearch() {
        searchView.setIconifiedByDefault(false)
        searchView.queryHint = getString(R.string.search_hint)
        searchView.maxWidth = Int.MAX_VALUE
        searchView.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchView.inputType = EditorInfo.TYPE_CLASS_TEXT
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(newQuery: String?): Boolean {
                val q = newQuery?.trim() ?: ""
                if (q.isNotEmpty()) {
                    query = q
                    updateChipLook()
                    refresh()
                    searchView.clearFocus()
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (newText.isNullOrEmpty() && query != null) {
                    // search field was cleared -> back to browsing
                    query = null
                    updateChipLook()
                    refresh()
                }
                return true
            }
        })
    }

    // --------------------------------------------------------------- loading

    private fun retry() {
        if (isOnline()) refresh()
        else Toast.makeText(this, R.string.still_offline, Toast.LENGTH_SHORT).show()
    }

    private fun refresh() {
        load(newPage = 1, append = false)
    }

    private fun loadMore() {
        if (loading) return
        load(newPage = page + 1, append = true)
    }

    private fun load(newPage: Int, append: Boolean) {
        // My List is stored on the device — no network involved at all.
        if (query == null && section == "mylist") {
            loading = false
            swipeRefresh.isRefreshing = false
            centerProgress.visibility = View.GONE
            footerProgress.visibility = View.GONE
            errorView.visibility = View.GONE
            val saved = FavoritesStore.all(this)
            adapter.submit(saved, false)
            page = 1
            hasMore = false
            if (saved.isEmpty()) {
                errorMessage.text = getString(R.string.mylist_empty)
                errorView.visibility = View.VISIBLE
            }
            return
        }

        if (!isOnline()) {
            swipeRefresh.isRefreshing = false
            if (adapter.isEmpty()) showOffline() else informOffline()
            return
        }

        loading = true
        requestNo++
        val thisRequest = requestNo

        if (append) {
            footerProgress.visibility = View.VISIBLE
        } else {
            footerProgress.visibility = View.GONE
        }
        if (!append && adapter.isEmpty()) {
            centerProgress.visibility = View.VISIBLE
            errorView.visibility = View.GONE
        }

        val finished: (List<Tmdb.Title>) -> Unit = { results ->
            if (thisRequest == requestNo) {
                loading = false
                swipeRefresh.isRefreshing = false
                centerProgress.visibility = View.GONE
                footerProgress.visibility = View.GONE
                adapter.submit(results, append)
                page = newPage
                hasMore = results.size >= PAGE_SIZE
                if (adapter.isEmpty()) {
                    errorMessage.text = getString(R.string.no_results_message)
                    errorView.visibility = View.VISIBLE
                } else {
                    errorView.visibility = View.GONE
                }
            }
        }

        val failed: (String) -> Unit = { message ->
            if (thisRequest == requestNo) {
                loading = false
                swipeRefresh.isRefreshing = false
                centerProgress.visibility = View.GONE
                footerProgress.visibility = View.GONE
                if (adapter.isEmpty()) showOffline()
                else Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }

        val q = query
        if (q != null) Tmdb.search(q, newPage, finished, failed)
        else Tmdb.feed(section, lang, newPage, finished, failed)
    }

    private fun showOffline() {
        errorMessage.text = getString(R.string.offline_message)
        errorView.visibility = View.VISIBLE
    }

    private fun informOffline() {
        swipeRefresh.isRefreshing = false
        Toast.makeText(this, R.string.still_offline, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- details

    private fun openDetails(item: Tmdb.Title) {
        DetailActivity.start(this, item)
    }

    // ------------------------------------------------------------- utilities

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        @Suppress("DEPRECATION")
        val info = cm.activeNetworkInfo
        @Suppress("DEPRECATION")
        return info != null && info.isConnected
    }
}
