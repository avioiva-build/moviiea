package com.moviiea.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Continue Watching — remembers the titles the user actually played.
 *
 * One SharedPreferences entry holds a JSON array, newest first, capped
 * at 20 entries. For TV shows the season and episode are stored too,
 * so the details screen can jump straight back to that episode.
 * A corrupted save can never crash the app: it just comes back empty.
 */
object HistoryStore {

    private const val PREFS = "moviiea_history"
    private const val KEY = "continue_watching"
    private const val MAX = 20

    data class Entry(
        val title: Tmdb.Title,
        val season: Int,   // 0 for movies
        val episode: Int   // 0 for movies
    )

    /** All history entries, newest first. */
    fun all(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = ArrayList<Entry>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                list.add(
                    Entry(
                        title = Tmdb.Title(
                            id = o.optInt("id"),
                            isMovie = o.optBoolean("isMovie", true),
                            name = o.optString("name", ""),
                            poster = if (o.isNull("poster")) null else o.optString("poster"),
                            rating = o.optDouble("rating", 0.0),
                            year = o.optString("year", "")
                        ),
                        season = o.optInt("season", 0),
                        episode = o.optInt("episode", 0)
                    )
                )
            }
            list
        } catch (e: Exception) {
            // a broken save must never crash the app
            emptyList()
        }
    }

    /**
     * Puts a title at the top (or moves an existing one up, refreshing
     * its season/episode). Older entries beyond [MAX] fall off the end.
     */
    fun record(context: Context, item: Tmdb.Title, season: Int, episode: Int) {
        try {
            val current = all(context).toMutableList()
            current.removeAll { it.title.id == item.id }
            current.add(0, Entry(item, season, episode))
            while (current.size > MAX) current.removeAt(current.size - 1)

            val array = JSONArray()
            for (e in current) {
                val o = JSONObject()
                o.put("id", e.title.id)
                o.put("isMovie", e.title.isMovie)
                o.put("name", e.title.name)
                o.put("poster", e.title.poster ?: JSONObject.NULL)
                o.put("rating", e.title.rating)
                o.put("year", e.title.year)
                o.put("season", e.season)
                o.put("episode", e.episode)
                array.put(o)
            }
            prefs(context).edit().putString(KEY, array.toString()).apply()
        } catch (e: Exception) {
            // saving must never crash the app
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
