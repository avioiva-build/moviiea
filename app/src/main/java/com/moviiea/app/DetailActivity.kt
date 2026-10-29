package com.moviiea.app

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.util.Locale

/**
 * Details screen: artwork, storyline, cast, trailer, share and the
 * Watch box.
 *
 * The Watch box builds the exact same server links the website builds
 * (VidAPI / VidSrc / 2Embed, keyed by TMDB id) and opens them through the
 * GitHub Pages player bridge in a Chrome Custom Tab. Before the first
 * playback the app checks that the bridge page exists, and if GitHub Pages
 * is not enabled yet it shows friendly setup instructions instead of a
 * broken player.
 *
 * v2.3 additions: Watch trailer (official YouTube trailer), Share, a
 * Cast row, a Browse episodes button for TV shows, Continue Watching
 * recording and an offline-aware error screen.
 */
class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_IS_MOVIE = "isMovie"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSTER = "poster"
        const val EXTRA_SEASON = "season"
        const val EXTRA_EPISODE = "episode"

        fun start(context: Context, item: Tmdb.Title, season: Int = -1, episode: Int = -1) {
            val intent = Intent(context, DetailActivity::class.java)
            intent.putExtra(EXTRA_ID, item.id)
            intent.putExtra(EXTRA_IS_MOVIE, item.isMovie)
            intent.putExtra(EXTRA_TITLE, item.name)
            intent.putExtra(EXTRA_POSTER, item.poster)
            if (season > 0) intent.putExtra(EXTRA_SEASON, season)
            if (episode > 0) intent.putExtra(EXTRA_EPISODE, episode)
            context.startActivity(intent)
        }
    }

    private lateinit var spinnerServer: Spinner
    private lateinit var spinnerSeason: Spinner
    private lateinit var spinnerEpisode: Spinner
    private lateinit var boxSeason: LinearLayout
    private lateinit var boxEpisode: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var recyclerSimilar: RecyclerView
    private lateinit var textSimilarHeader: TextView
    private lateinit var recyclerCast: RecyclerView
    private lateinit var textCastHeader: TextView
    private lateinit var btnFavDetail: ImageView

    private var id = 0
    private var isMovie = true
    private var season = 1
    private var episode = 1
    private var trailerKey: String? = null
    private var pendingSeason = -1   // Continue Watching: remembered season
    private var pendingEpisode = -1  // Continue Watching: remembered episode
    private var currentDetail: Tmdb.Details? = null
    private var initialTitle = ""
    private var initialPoster: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        id = intent.getIntExtra(EXTRA_ID, 0)
        isMovie = intent.getBooleanExtra(EXTRA_IS_MOVIE, true)
        initialTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        initialPoster = intent.getStringExtra(EXTRA_POSTER)
        pendingSeason = intent.getIntExtra(EXTRA_SEASON, -1)
        pendingEpisode = intent.getIntExtra(EXTRA_EPISODE, -1)

        // Instant first paint from the grid, details arrive a moment later.
        findViewById<TextView>(R.id.textTitle).text = initialTitle
        findViewById<AppCompatImageView>(R.id.imgPoster).load(initialPoster) {
            placeholder(R.drawable.poster_ph)
            error(R.drawable.poster_ph)
        }

        spinnerServer = findViewById(R.id.spinnerServer)
        spinnerSeason = findViewById(R.id.spinnerSeason)
        spinnerEpisode = findViewById(R.id.spinnerEpisode)
        boxSeason = findViewById(R.id.boxSeason)
        boxEpisode = findViewById(R.id.boxEpisode)
        progress = findViewById(R.id.detailProgress)
        errorView = findViewById(R.id.detailError)
        recyclerSimilar = findViewById(R.id.recyclerSimilar)
        textSimilarHeader = findViewById(R.id.textSimilarHeader)
        recyclerCast = findViewById(R.id.recyclerCast)
        textCastHeader = findViewById(R.id.textCastHeader)

        findViewById<AppCompatImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<AppCompatButton>(R.id.btnDetailRetry).setOnClickListener { loadDetails() }
        findViewById<AppCompatButton>(R.id.btnPlay).setOnClickListener { onPlayClicked() }
        findViewById<AppCompatButton>(R.id.btnTrailer).setOnClickListener { openTrailer() }
        findViewById<AppCompatButton>(R.id.btnEpisodes).setOnClickListener { openEpisodes() }
        findViewById<AppCompatImageView>(R.id.btnShare).setOnClickListener { shareTitle() }

        btnFavDetail = findViewById(R.id.btnFavDetail)
        updateFavIcon()
        btnFavDetail.setOnClickListener {
            val nowFav = FavoritesStore.toggle(this, asTitle())
            updateFavIcon()
            Toast.makeText(
                this,
                if (nowFav) R.string.added_to_mylist else R.string.removed_from_mylist,
                Toast.LENGTH_SHORT
            ).show()
        }

        setupServerSpinner()
        styleSpinner(spinnerServer)
        styleSpinner(spinnerSeason)
        styleSpinner(spinnerEpisode)

        recyclerSimilar.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerCast.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        if (isMovie) {
            boxSeason.visibility = View.GONE
            boxEpisode.visibility = View.GONE
        }

        loadDetails()
    }

    // ------------------------------------------------------------- favorites

    /** A Title built from everything we currently know about this screen. */
    private fun asTitle(): Tmdb.Title = Tmdb.Title(
        id = id,
        isMovie = isMovie,
        name = currentDetail?.name ?: initialTitle,
        poster = currentDetail?.poster ?: initialPoster,
        rating = currentDetail?.rating ?: 0.0,
        year = currentDetail?.year ?: ""
    )

    private fun updateFavIcon() {
        btnFavDetail.setImageResource(
            if (FavoritesStore.contains(this, id)) R.drawable.ic_heart_filled
            else R.drawable.ic_heart
        )
    }

    // ------------------------------------------------------------- spinners

    private fun setupServerSpinner() {
        val names = PlayerLauncher.SERVERS.map { it.name }
        spinnerServer.adapter = makeAdapter(names)
    }

    private fun makeAdapter(values: List<String>): ArrayAdapter<String> {
        val arrayAdapter = ArrayAdapter(this, R.layout.spinner_item, values)
        arrayAdapter.setDropDownViewResource(R.layout.spinner_item)
        return arrayAdapter
    }

    private fun styleSpinner(spinner: Spinner) {
        // Note: Spinner has getPopupBackground() but the setter is named
        // setPopupBackgroundDrawable(), so we call the method directly.
        spinner.setPopupBackgroundDrawable(ColorDrawable(0xFF16305C.toInt()))
    }

    private fun listener(onSelected: (Int) -> Unit): AdapterView.OnItemSelectedListener =
        object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, rowId: Long) {
                onSelected(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // nothing to do
            }
        }

    private fun onSeasonSelected(d: Tmdb.Details, position: Int) {
        season = d.seasons[position]
        val count = d.episodesPerSeason[position].coerceAtLeast(1)
        spinnerEpisode.adapter = makeAdapter((1..count).map { "Episode $it" })
        episode = 1
    }

    // -------------------------------------------------------------- details

    private fun loadDetails() {
        errorView.visibility = View.GONE
        progress.visibility = View.VISIBLE
        Tmdb.details(
            isMovie, id,
            onDone = { details -> showDetails(details) },
            onError = {
                progress.visibility = View.GONE
                // Friendly message: offline vs. a temporary service problem.
                findViewById<TextView>(R.id.detailErrorText).text = getString(
                    if (isOnline()) R.string.details_unavailable else R.string.offline_message
                )
                errorView.visibility = View.VISIBLE
            }
        )
    }

    private fun showDetails(d: Tmdb.Details) {
        progress.visibility = View.GONE
        currentDetail = d

        findViewById<TextView>(R.id.textTitle).text = d.name

        val meta = ArrayList<String>()
        if (d.year.isNotEmpty()) meta.add(d.year)
        meta.add(String.format(Locale.US, "★ %.1f", d.rating))
        if (d.runtime.isNotEmpty()) meta.add(d.runtime)
        findViewById<TextView>(R.id.textMeta).text = meta.joinToString("  •  ")

        val genresView = findViewById<TextView>(R.id.textGenres)
        if (d.genres.isEmpty()) genresView.visibility = View.GONE
        else {
            genresView.visibility = View.VISIBLE
            genresView.text = d.genres
        }

        findViewById<AppCompatImageView>(R.id.imgBackdrop).load(d.backdrop) {
            placeholder(R.drawable.poster_ph)
            error(R.drawable.poster_ph)
            crossfade(true)
        }
        if (d.poster != null) {
            findViewById<AppCompatImageView>(R.id.imgPoster).load(d.poster) {
                placeholder(R.drawable.poster_ph)
                crossfade(true)
            }
        }

        val overviewView = findViewById<TextView>(R.id.textOverview)
        if (d.overview.isEmpty()) overviewView.visibility = View.GONE
        else {
            overviewView.visibility = View.VISIBLE
            overviewView.text = d.overview
        }

        // TV shows: fill season + episode pickers
        if (!isMovie) {
            spinnerSeason.adapter = makeAdapter(d.seasons.map { "Season $it" })
            spinnerSeason.onItemSelectedListener = listener { position ->
                if (position in d.seasons.indices) onSeasonSelected(d, position)
            }
            spinnerEpisode.onItemSelectedListener = listener { position ->
                episode = position + 1
            }
            // Continue Watching: jump straight to the remembered season + episode.
            // setSelection(x, false) avoids the "fires on first layout" quirk.
            val startIdx = if (pendingSeason in d.seasons) d.seasons.indexOf(pendingSeason) else 0
            onSeasonSelected(d, startIdx)
            if (pendingEpisode > 0 && pendingEpisode <= d.episodesPerSeason[startIdx]) {
                episode = pendingEpisode
                spinnerEpisode.setSelection(pendingEpisode - 1, false)
            }
            spinnerSeason.setSelection(startIdx, false)
            pendingSeason = -1
            pendingEpisode = -1
            boxSeason.visibility = View.VISIBLE
            boxEpisode.visibility = View.VISIBLE
            findViewById<AppCompatButton>(R.id.btnEpisodes).visibility = View.VISIBLE
        }

        // More like this
        if (d.similar.isEmpty()) {
            textSimilarHeader.visibility = View.GONE
            recyclerSimilar.visibility = View.GONE
        } else {
            textSimilarHeader.visibility = View.VISIBLE
            recyclerSimilar.visibility = View.VISIBLE
            recyclerSimilar.adapter = GridAdapter(R.layout.item_similar, { item ->
                DetailActivity.start(this, item)
            })
            (recyclerSimilar.adapter as GridAdapter).submit(d.similar, false)
        }

        // Cast row
        if (d.cast.isEmpty()) {
            textCastHeader.visibility = View.GONE
            recyclerCast.visibility = View.GONE
        } else {
            textCastHeader.visibility = View.VISIBLE
            recyclerCast.visibility = View.VISIBLE
            recyclerCast.adapter = CastAdapter()
            (recyclerCast.adapter as CastAdapter).submit(d.cast)
        }

        // Official trailer button (hidden when TMDB has none)
        trailerKey = d.trailerKey
        findViewById<AppCompatButton>(R.id.btnTrailer).visibility =
            if (trailerKey.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------- trailer + share

    private fun openTrailer() {
        val key = trailerKey ?: return
        // 1st choice: the YouTube app. 2nd: youtube.com in any browser.
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$key")))
            return
        } catch (e: Exception) {
            // fall through to the browser
        }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key"))
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareTitle() {
        val name = currentDetail?.name ?: initialTitle
        val path = if (isMovie) "movie" else "tv"
        val link = "https://www.themoviedb.org/$path/$id"
        try {
            val send = Intent(Intent.ACTION_SEND)
            send.type = "text/plain"
            send.putExtra(Intent.EXTRA_TEXT, getString(R.string.share_text, name, link))
            startActivity(Intent.createChooser(send, getString(R.string.share_title)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openEpisodes() {
        val title = currentDetail?.name ?: initialTitle
        val poster = currentDetail?.poster ?: initialPoster
        EpisodesActivity.start(this, id, title, poster, spinnerServer.selectedItemPosition)
    }

    // ------------------------------------------------------------ playback

    private fun onPlayClicked() {
        // Remember this title (and episode) for Continue Watching.
        HistoryStore.record(
            this, asTitle(),
            if (isMovie) 0 else season,
            if (isMovie) 0 else episode
        )

        val serverIndex = spinnerServer.selectedItemPosition
        val embed = PlayerLauncher.embedUrl(serverIndex, id, season, episode, isMovie)

        if (PlayerLauncher.bridgeVerified) {
            PlayerLauncher.openInApp(this, embed, currentDetail?.name ?: initialTitle)
            return
        }

        // One-time check that the GitHub Pages player bridge is live.
        progress.visibility = View.VISIBLE
        Tmdb.checkUrl(PlayerLauncher.PLAYER_PAGE) { ok ->
            progress.visibility = View.GONE
            if (ok) {
                PlayerLauncher.bridgeVerified = true
                PlayerLauncher.openInApp(this, embed, currentDetail?.name ?: initialTitle)
            } else {
                showBridgeHelp()
            }
        }
    }

    private fun showBridgeHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.bridge_missing_title)
            .setMessage(R.string.bridge_missing_message)
            .setPositiveButton(R.string.ok, null)
            .show()
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
