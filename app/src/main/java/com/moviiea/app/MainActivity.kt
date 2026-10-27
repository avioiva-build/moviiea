package com.moviiea.app

import android.content.Context
import android.content.Intent
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
 * a native poster grid with Trending / Movies / TV categories, instant
 * search, endless scrolling and a details screen. Playback happens through
 * the player bridge in a Chrome Custom Tab, which sidesteps the video
 * servers' WebView blocking entirely.
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
    private lateinit var langChips: List<Pair<View, String>>

    private var section = "trending"   // trending | movie | tv | mylist
    private var lang = ""              // "" = all, en, hi, bn, ko
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

        adapter = GridAdapter(R.layout.item_movie, { openDetails(it) }, { onFavChanged() })
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
        setupSearch()

        swipeRefresh.setOnRefreshListener { refresh() }
        findViewById<View>(R.id.retryButton).setOnClickListener { retry() }

        refresh()
    }

    // ------------------------------------------------------------- sections

    private fun setupChips() {
        // --- Row 1: Trending / Movies / TV Shows / My List
        chips.clear()
        chips.add(findViewById(R.id.chipTrending))
        chips.add(findViewById(R.id.chipMovies))
        chips.add(findViewById(R.id.chipTv))
        chips.add(findViewById(R.id.chipMyList))
        val sections = listOf("trending", "movie", "tv", "mylist")
        chips.forEachIndexed { index, chip ->
            chip.setOnClickListener {
                section = sections[index]
                query = null
                searchView.setQuery("", false)
                searchView.clearFocus()
                updateChipLook()
                refresh()
            }
        }
        updateChipLook()

        // --- Row 2: language pills (same filter the website uses)
        langChips = listOf(
            findViewById<View>(R.id.langAll) to "",
            findViewById<View>(R.id.langEnglish) to "en",
            findViewById<View>(R.id.langHindi) to "hi",
            findViewById<View>(R.id.langBengali) to "bn",
            findViewById<View>(R.id.langKorean) to "ko"
        )
        langChips.forEach { (chip, code) ->
            chip.setOnClickListener {
                if (lang == code) return@setOnClickListener
                lang = code
                updateLangLook()
                // My List is local and search is keyword-based, so only the
                // browsing feed reacts to language changes.
                if (section != "mylist" && query == null) refresh()
            }
        }
        updateLangLook()
    }

    private fun updateChipLook() {
        val sections = listOf("trending", "movie", "tv", "mylist")
        chips.forEachIndexed { i, chip -> chip.isSelected = sections[i] == section && query == null }
    }

    private fun updateLangLook() {
        langChips.forEach { (chip, code) -> chip.isSelected = code == lang }
    }

    /** Called after a heart tap on any card. */
    private fun onFavChanged() {
        if (section == "mylist" && query == null) refresh()
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
        // My List lives on the phone — no network needed.
        if (section == "mylist" && query == null) {
            loading = false
            swipeRefresh.isRefreshing = false
            centerProgress.visibility = View.GONE
            footerProgress.visibility = View.GONE
            adapter.submit(FavoritesStore.all(this), false)
            page = 1
            hasMore = false
            if (adapter.isEmpty()) {
                errorMessage.text = getString(R.string.mylist_empty_message)
                errorView.visibility = View.VISIBLE
            } else {
                errorView.visibility = View.GONE
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
