package com.moviiea.app

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Talks to TMDB (The Movie Database) — the exact same data source the
 * Moviiea website uses — and turns the answers into simple Kotlin objects.
 *
 * All calls run on a small background thread pool and the results are
 * delivered back on the main (UI) thread, so callers can touch views
 * directly inside the callbacks.
 */
object Tmdb {

    private const val API_KEY = "05902896074695709d7763505bb88b4d"
    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMG_W342 = "https://image.tmdb.org/t/p/w342"
    private const val IMG_W500 = "https://image.tmdb.org/t/p/w500"
    private const val IMG_W780 = "https://image.tmdb.org/t/p/w780"

    /** One row in the browse grid / search results / "More like this". */
    data class Title(
        val id: Int,
        val isMovie: Boolean,
        val name: String,
        val poster: String?,
        val rating: Double,
        val year: String
    )

    /** Everything the details screen shows. */
    data class Details(
        val id: Int,
        val isMovie: Boolean,
        val name: String,
        val poster: String?,
        val backdrop: String?,
        val rating: Double,
        val year: String,
        val runtime: String,
        val genres: String,
        val overview: String,
        val seasons: List<Int>,
        val episodesPerSeason: List<Int>,
        val similar: List<Title>
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------ public API

    /**
     * Browse feed: "trending", "movie" or "tv".
     *
     * [lang] filters by original language ("en", "hi", "bn", "ko"...
     * — empty string means All). Trending + a language maps to the
     * popular movies of that language, exactly like the website does.
     */
    fun feed(section: String, lang: String, page: Int, onDone: (List<Title>) -> Unit, onError: (String) -> Unit) {
        val langParam = if (lang.isEmpty()) "" else "&with_original_language=$lang"
        val url = when {
            section == "movie" ->
                "$BASE/discover/movie?api_key=$API_KEY&sort_by=popularity.desc&include_adult=false&page=$page$langParam"
            section == "tv" ->
                "$BASE/discover/tv?api_key=$API_KEY&sort_by=popularity.desc&include_adult=false&page=$page$langParam"
            lang.isNotEmpty() ->
                "$BASE/discover/movie?api_key=$API_KEY&sort_by=popularity.desc&include_adult=false&with_original_language=$lang&page=$page"
            else ->
                "$BASE/trending/all/day?api_key=$API_KEY&page=$page"
        }
        fetchTitles(url, if (section == "tv") "tv" else "movie", onDone, onError)
    }

    /** Search movies + shows at once (people are filtered out). */
    fun search(query: String, page: Int, onDone: (List<Title>) -> Unit, onError: (String) -> Unit) {
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val url = "$BASE/search/multi?api_key=$API_KEY&query=$q&include_adult=false&page=$page"
        fetchTitles(url, "movie", onDone, onError)
    }

    /** Full details for one movie or show. */
    fun details(isMovie: Boolean, id: Int, onDone: (Details) -> Unit, onError: (String) -> Unit) {
        async({
            val path = if (isMovie) "movie" else "tv"
            val json = get("$BASE/$path/$id?api_key=$API_KEY&append_to_response=similar")
            parseDetails(json, isMovie)
        }, onDone, onError)
    }

    /** Quick reachability check (used for the GitHub Pages player bridge). */
    fun checkUrl(url: String, onDone: (Boolean) -> Unit) {
        pool.execute {
            var ok = false
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    ok = response.isSuccessful
                }
            } catch (e: Exception) {
                ok = false
            }
            main.post { onDone(ok) }
        }
    }

    // ------------------------------------------------------------ internals

    private fun <T> async(work: () -> T, onDone: (T) -> Unit, onError: (String) -> Unit) {
        pool.execute {
            try {
                val result = work()
                main.post { onDone(result) }
            } catch (e: Exception) {
                main.post { onError(e.message ?: "Network error") }
            }
        }
    }

    private fun fetchTitles(
        url: String,
        defaultType: String,
        onDone: (List<Title>) -> Unit,
        onError: (String) -> Unit
    ) {
        async({ parseResults(get(url), defaultType) }, onDone, onError)
    }

    private fun get(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            )
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw Exception("Empty response")
            if (!response.isSuccessful) throw Exception("Service error (${response.code})")
            return JSONObject(body)
        }
    }

    /** org.json returns the text "null" for JSON nulls — normalize that away. */
    private fun optStr(o: JSONObject, key: String): String {
        val v = o.optString(key, "")
        return if (v == "null") "" else v
    }

    private fun yearOf(date: String): String =
        if (date.length >= 4) date.substring(0, 4) else ""

    private fun parseResults(json: JSONObject, defaultType: String): List<Title> {
        val results = json.optJSONArray("results") ?: return emptyList()
        val list = ArrayList<Title>()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val media = optStr(o, "media_type").ifEmpty { defaultType }
            if (media != "movie" && media != "tv") continue
            val name = optStr(o, "title").ifEmpty { optStr(o, "name") }
            if (name.isEmpty()) continue
            val date = optStr(o, "release_date").ifEmpty { optStr(o, "first_air_date") }
            val posterPath = optStr(o, "poster_path")
            list.add(
                Title(
                    id = o.optInt("id"),
                    isMovie = media == "movie",
                    name = name,
                    poster = if (posterPath.isEmpty()) null else IMG_W342 + posterPath,
                    rating = o.optDouble("vote_average", 0.0),
                    year = yearOf(date)
                )
            )
        }
        return list
    }

    private fun parseDetails(json: JSONObject, isMovie: Boolean): Details {
        val name = optStr(json, "title").ifEmpty { optStr(json, "name") }
        val date = optStr(json, "release_date").ifEmpty { optStr(json, "first_air_date") }
        val posterPath = optStr(json, "poster_path")
        val backdropPath = optStr(json, "backdrop_path")

        val runtime = if (isMovie) {
            val minutes = json.optInt("runtime", 0)
            if (minutes > 0) "$minutes min" else ""
        } else {
            val s = json.optInt("number_of_seasons", 0)
            if (s > 0) "$s season" + if (s > 1) "s" else "" else ""
        }

        val genreArray = json.optJSONArray("genres")
        val genres = StringBuilder()
        if (genreArray != null) {
            for (i in 0 until genreArray.length()) {
                val g = genreArray.optJSONObject(i) ?: continue
                val gName = optStr(g, "name")
                if (gName.isNotEmpty()) {
                    if (genres.isNotEmpty()) genres.append(" • ")
                    genres.append(gName)
                }
            }
        }

        // TV: list of season numbers + how many episodes each season has
        val seasons = ArrayList<Int>()
        val episodes = ArrayList<Int>()
        if (!isMovie) {
            val seasonArray = json.optJSONArray("seasons")
            if (seasonArray != null) {
                for (i in 0 until seasonArray.length()) {
                    val s = seasonArray.optJSONObject(i) ?: continue
                    val number = s.optInt("season_number", 0)
                    if (number < 1) continue // skip "specials"
                    seasons.add(number)
                    episodes.add(s.optInt("episode_count", 0).coerceAtLeast(1))
                }
            }
            if (seasons.isEmpty()) {
                seasons.add(1)
                episodes.add(1)
            }
        }

        val similarList = ArrayList<Title>()
        val similarResults = json.optJSONObject("similar")?.optJSONArray("results")
        if (similarResults != null) {
            for (i in 0 until similarResults.length()) {
                val o = similarResults.optJSONObject(i) ?: continue
                val t = optStr(o, "title").ifEmpty { optStr(o, "name") }
                if (t.isEmpty()) continue
                val d = optStr(o, "release_date").ifEmpty { optStr(o, "first_air_date") }
                val p = optStr(o, "poster_path")
                similarList.add(
                    Title(
                        id = o.optInt("id"),
                        isMovie = isMovie,
                        name = t,
                        poster = if (p.isEmpty()) null else IMG_W342 + p,
                        rating = o.optDouble("vote_average", 0.0),
                        year = yearOf(d)
                    )
                )
            }
        }

        return Details(
            id = json.optInt("id"),
            isMovie = isMovie,
            name = name,
            poster = if (posterPath.isEmpty()) null else IMG_W500 + posterPath,
            backdrop = if (backdropPath.isEmpty()) null else IMG_W780 + backdropPath,
            rating = json.optDouble("vote_average", 0.0),
            year = yearOf(date),
            runtime = runtime,
            genres = genres.toString(),
            overview = optStr(json, "overview"),
            seasons = seasons,
            episodesPerSeason = episodes,
            similar = similarList
        )
    }
}
