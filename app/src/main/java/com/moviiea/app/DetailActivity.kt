package com.moviiea.app

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
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
 * Details screen: artwork, storyline and the Watch box.
 *
 * The Watch box builds the exact same server links the website builds
 * (VidAPI / VidSrc / 2Embed, keyed by TMDB id) and opens them through the
 * GitHub Pages player bridge in a Chrome Custom Tab. Before the first
 * playback the app checks that the bridge page exists, and if GitHub Pages
 * is not enabled yet it shows friendly setup instructions instead of a
 * broken player.
 */
class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_IS_MOVIE = "isMovie"
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSTER = "poster"

        fun start(context: Context, item: Tmdb.Title) {
            val intent = Intent(context, DetailActivity::class.java)
            intent.putExtra(EXTRA_ID, item.id)
            intent.putExtra(EXTRA_IS_MOVIE, item.isMovie)
            intent.putExtra(EXTRA_TITLE, item.name)
            intent.putExtra(EXTRA_POSTER, item.poster)
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
    private lateinit var btnFavDetail: ImageView

    private var id = 0
    private var isMovie = true
    private var season = 1
    private var episode = 1
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

        findViewById<AppCompatImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<AppCompatButton>(R.id.btnDetailRetry).setOnClickListener { loadDetails() }
        findViewById<AppCompatButton>(R.id.btnPlay).setOnClickListener { onPlayClicked() }

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
            onSeasonSelected(d, 0)
            boxSeason.visibility = View.VISIBLE
            boxEpisode.visibility = View.VISIBLE
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
    }

    // ------------------------------------------------------------ playback

    private fun onPlayClicked() {
        val serverIndex = spinnerServer.selectedItemPosition
        val embed = PlayerLauncher.embedUrl(serverIndex, id, season, episode, isMovie)

        if (PlayerLauncher.bridgeVerified) {
            PlayerLauncher.open(this, embed)
            return
        }

        // One-time check that the GitHub Pages player bridge is live.
        progress.visibility = View.VISIBLE
        Tmdb.checkUrl(PlayerLauncher.PLAYER_PAGE) { ok ->
            progress.visibility = View.GONE
            if (ok) {
                PlayerLauncher.bridgeVerified = true
                PlayerLauncher.open(this, embed)
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
}
