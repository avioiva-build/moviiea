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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load

/**
 * TV Episode browser: season pills on top, a full episode list below
 * (still image, number, name, air date, storyline). Tapping an episode
 * plays it through the same player bridge as the details screen and
 * saves it to Continue Watching.
 */
class EpisodesActivity : AppCompatActivity() {

    private lateinit var listAdapter: EpisodeAdapter
    private lateinit var pillRecycler: RecyclerView
    private lateinit var recycler: RecyclerView
    private lateinit var centerProgress: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var seasonTitle: TextView

    private var showId = 0
    private var serverIndex = 0
    private var showTitle = ""
    private var poster: String? = null
    private val seasons = ArrayList<Int>()
    private var selectedSeason = 1
    private var loading = false
    private var requestNo = 0

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSTER = "poster"
        const val EXTRA_SERVER = "server"

        fun start(context: Context, showId: Int, title: String, poster: String?, serverIndex: Int) {
            val intent = Intent(context, EpisodesActivity::class.java)
            intent.putExtra(EXTRA_ID, showId)
            intent.putExtra(EXTRA_TITLE, title)
            intent.putExtra(EXTRA_POSTER, poster)
            intent.putExtra(EXTRA_SERVER, serverIndex)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_episodes)

        showId = intent.getIntExtra(EXTRA_ID, 0)
        showTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        poster = intent.getStringExtra(EXTRA_POSTER)
        serverIndex = intent.getIntExtra(EXTRA_SERVER, 0)

        pillRecycler = findViewById(R.id.recyclerSeasons)
        recycler = findViewById(R.id.recyclerEpisodes)
        centerProgress = findViewById(R.id.centerProgress)
        errorView = findViewById(R.id.errorView)
        errorMessage = findViewById(R.id.errorMessage)
        seasonTitle = findViewById(R.id.textSeasonName)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.retryButton).setOnClickListener {
            if (seasons.isNotEmpty()) loadEpisodes(selectedSeason) else loadShow()
        }

        listAdapter = EpisodeAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = listAdapter
        pillRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        loadShow()
    }

    // ------------------------------------------------------------------ data

    private fun loadShow() {
        if (loading) return
        loading = true
        centerProgress.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        Tmdb.details(
            false, showId,
            onDone = { d ->
                loading = false
                centerProgress.visibility = View.GONE
                seasons.clear()
                seasons.addAll(d.seasons)
                pillRecycler.adapter = SeasonPillAdapter()
                if (seasons.isNotEmpty()) selectSeason(seasons[0]) else showEmpty()
            },
            onError = { message -> failed(message) }
        )
    }

    private fun selectSeason(number: Int) {
        selectedSeason = number
        seasonTitle.text = getString(R.string.season_number_fmt, number)
        (pillRecycler.adapter as? SeasonPillAdapter)?.notifyDataSetChanged()
        loadEpisodes(number)
    }

    private fun loadEpisodes(season: Int) {
        if (loading) return
        loading = true
        requestNo++
        val thisRequest = requestNo
        centerProgress.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        Tmdb.episodes(
            showId, season,
            onDone = { list ->
                if (thisRequest != requestNo) return@episodes
                loading = false
                centerProgress.visibility = View.GONE
                listAdapter.submit(list)
                if (list.isEmpty()) showEmpty()
            },
            onError = { message ->
                if (thisRequest != requestNo) return@episodes
                failed(message)
            }
        )
    }

    private fun failed(message: String) {
        loading = false
        centerProgress.visibility = View.GONE
        errorMessage.text = getString(R.string.offline_message)
        errorView.visibility = View.VISIBLE
    }

    private fun showEmpty() {
        loading = false
        centerProgress.visibility = View.GONE
        errorMessage.text = getString(R.string.no_results_message)
        errorView.visibility = View.VISIBLE
    }

    // --------------------------------------------------------------- playback

    private fun play(ep: Tmdb.Episode) {
        HistoryStore.record(
            this,
            Tmdb.Title(
                id = showId,
                isMovie = false,
                name = showTitle,
                poster = poster,
                rating = 0.0,
                year = ""
            ),
            selectedSeason,
            ep.number
        )
        PlayerLauncher.open(
            this,
            PlayerLauncher.embedUrl(serverIndex, showId, selectedSeason, ep.number, false)
        )
    }

    // ------------------------------------------------------------ season pills

    private inner class SeasonPillAdapter : RecyclerView.Adapter<SeasonPillAdapter.PillVH>() {

        inner class PillVH(view: View) : RecyclerView.ViewHolder(view) {
            val pill: TextView = view.findViewById(R.id.textPill)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PillVH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_genre_pill, parent, false)
            return PillVH(view)
        }

        override fun getItemCount() = seasons.size

        override fun onBindViewHolder(holder: PillVH, position: Int) {
            val number = seasons[position]
            holder.pill.text = getString(R.string.season_number_fmt, number)
            holder.pill.isSelected = number == selectedSeason
            holder.pill.setOnClickListener {
                if (number != selectedSeason) selectSeason(number)
            }
        }
    }

    // ---------------------------------------------------------- episode list

    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeAdapter.EVH>() {

        private val items = ArrayList<Tmdb.Episode>()

        fun submit(list: List<Tmdb.Episode>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        inner class EVH(view: View) : RecyclerView.ViewHolder(view) {
            val still: AppCompatImageView = view.findViewById(R.id.imgStill)
            val number: TextView = view.findViewById(R.id.textEpNumber)
            val name: TextView = view.findViewById(R.id.textEpName)
            val air: TextView = view.findViewById(R.id.textEpAir)
            val overview: TextView = view.findViewById(R.id.textEpOverview)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EVH =
            EVH(LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: EVH, position: Int) {
            val ep = items[position]
            holder.number.text = getString(R.string.episode_number_fmt, ep.number)
            holder.name.text =
                if (ep.name.isEmpty()) getString(R.string.episode_unnamed) else ep.name
            if (ep.airDate.isEmpty()) {
                holder.air.visibility = View.GONE
            } else {
                holder.air.visibility = View.VISIBLE
                holder.air.text = ep.airDate
            }
            if (ep.overview.isEmpty()) {
                holder.overview.visibility = View.GONE
            } else {
                holder.overview.visibility = View.VISIBLE
                holder.overview.text = ep.overview
            }
            holder.still.load(ep.still) {
                placeholder(R.drawable.poster_ph)
                error(R.drawable.poster_ph)
                crossfade(true)
            }
            holder.itemView.setOnClickListener { play(ep) }
        }
    }
}
