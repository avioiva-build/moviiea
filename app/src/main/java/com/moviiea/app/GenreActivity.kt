package com.moviiea.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Browse by Genre: a pill row of TMDB genres on top and the matching
 * movie grid below. Works like the home screen — endless scrolling,
 * friendly offline screen with Retry.
 */
class GenreActivity : AppCompatActivity() {

    // One companion object holds the layout constants AND the start() helper.
    companion object {
        private const val PAGE_SIZE = 20
        private const val SPAN_COUNT = 3

        fun start(context: Context) {
            context.startActivity(Intent(context, GenreActivity::class.java))
        }
    }

    private lateinit var adapter: GridAdapter
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var pillRecycler: RecyclerView
    private lateinit var centerProgress: ProgressBar
    private lateinit var footerProgress: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var errorMessage: TextView

    private val genres = ArrayList<Tmdb.Genre>()
    private var selected = -1
    private var page = 1
    private var loading = false
    private var hasMore = true
    private var requestNo = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_genre)

        pillRecycler = findViewById(R.id.recyclerGenres)
        centerProgress = findViewById(R.id.centerProgress)
        footerProgress = findViewById(R.id.footerProgress)
        errorView = findViewById(R.id.errorView)
        errorMessage = findViewById(R.id.errorMessage)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.retryButton).setOnClickListener {
            if (selected > 0) load(1, false) else loadGenres()
        }

        adapter = GridAdapter(R.layout.item_movie, { DetailActivity.start(this, it) }, null)
        layoutManager = GridLayoutManager(this, SPAN_COUNT)
        val grid = findViewById<RecyclerView>(R.id.recyclerGrid)
        grid.layoutManager = layoutManager
        grid.adapter = adapter
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loading || !hasMore) return
                val last = layoutManager.findLastVisibleItemPosition()
                if (last >= adapter.itemCount - 4) loadMore()
            }
        })

        pillRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        loadGenres()
    }

    // ----------------------------------------------------------------- genres

    private fun loadGenres() {
        if (loading) return
        loading = true
        centerProgress.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        Tmdb.genres(
            onDone = { list ->
                loading = false
                centerProgress.visibility = View.GONE
                genres.clear()
                genres.addAll(list)
                pillRecycler.adapter = GenrePillAdapter()
                if (list.isNotEmpty()) select(0) else showEmpty()
            },
            onError = { message -> failed(message) }
        )
    }

    private fun select(position: Int) {
        if (position !in genres.indices) return
        selected = genres[position].id
        (pillRecycler.adapter as? GenrePillAdapter)?.notifyDataSetChanged()
        load(1, false)
    }

    private fun loadMore() {
        if (loading || selected <= 0) return
        load(page + 1, true)
    }

    private fun load(newPage: Int, append: Boolean) {
        loading = true
        requestNo++
        val thisRequest = requestNo
        footerProgress.visibility = if (append) View.VISIBLE else View.GONE
        if (!append && adapter.isEmpty()) {
            centerProgress.visibility = View.VISIBLE
            errorView.visibility = View.GONE
        }

        val finished: (List<Tmdb.Title>) -> Unit = { results ->
            if (thisRequest == requestNo) {
                loading = false
                centerProgress.visibility = View.GONE
                footerProgress.visibility = View.GONE
                adapter.submit(results, append)
                page = newPage
                hasMore = results.size >= PAGE_SIZE
                if (adapter.isEmpty()) showEmpty() else errorView.visibility = View.GONE
            }
        }
        val failed: (String) -> Unit = { message -> failed(message) }
        Tmdb.byGenre(selected, newPage, finished, failed)
    }

    private fun failed(message: String) {
        loading = false
        centerProgress.visibility = View.GONE
        footerProgress.visibility = View.GONE
        if (adapter.isEmpty() && selected <= 0) {
            // could not even load the genre list
            errorMessage.text = getString(R.string.offline_message)
            errorView.visibility = View.VISIBLE
        } else if (adapter.isEmpty()) {
            errorMessage.text = getString(R.string.no_results_message)
            errorView.visibility = View.VISIBLE
        } else {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEmpty() {
        errorMessage.text = getString(R.string.no_results_message)
        errorView.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------- genre pills

    private inner class GenrePillAdapter : RecyclerView.Adapter<GenrePillAdapter.PillVH>() {

        inner class PillVH(view: View) : RecyclerView.ViewHolder(view) {
            val pill: TextView = view.findViewById(R.id.textPill)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PillVH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_genre_pill, parent, false)
            return PillVH(view)
        }

        override fun getItemCount() = genres.size

        override fun onBindViewHolder(holder: PillVH, position: Int) {
            val g = genres[position]
            holder.pill.text = g.name
            holder.pill.isSelected = g.id == selected
            holder.pill.setOnClickListener {
                if (g.id != selected) select(holder.bindingAdapterPosition)
            }
        }
    }
}
