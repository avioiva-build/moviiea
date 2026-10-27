package com.moviiea.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * My List — the user's saved movies & shows.
 *
 * Everything is stored on the phone only (SharedPreferences as JSON),
 * so the list survives app restarts and works fully offline.
 * Newest additions appear first in the grid.
 */
object FavoritesStore {

    private const val PREFS = "moviiea_favorites"
    private const val KEY = "items"

    /** In-memory copy, loaded once per app start. */
    private val items = LinkedHashMap<Int, Tmdb.Title>()
    private var loaded = false

    private fun loadIfNeeded(context: Context) {
        if (loaded) return
        loaded = true
        val raw = prefs(context).getString(KEY, null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.isEmpty() || name == "null") continue
                val poster = o.optString("poster")
                items[o.optInt("id")] = Tmdb.Title(
                    id = o.optInt("id"),
                    isMovie = o.optBoolean("isMovie", true),
                    name = name,
                    poster = if (poster.isEmpty() || poster == "null") null else poster,
                    rating = o.optDouble("rating", 0.0),
                    year = o.optString("year")
                )
            }
        } catch (e: Exception) {
            // Corrupted save -> start with an empty list rather than crash.
            items.clear()
        }
    }

    /** All favorites, newest first. */
    fun all(context: Context): List<Tmdb.Title> {
        loadIfNeeded(context)
        return items.values.reversed()
    }

    fun contains(context: Context, id: Int): Boolean {
        loadIfNeeded(context)
        return items.containsKey(id)
    }

    /** Toggles one item. Returns true if it is now a favorite, false if removed. */
    fun toggle(context: Context, item: Tmdb.Title): Boolean {
        loadIfNeeded(context)
        val nowFav = if (items.containsKey(item.id)) {
            items.remove(item.id)
            false
        } else {
            items[item.id] = item
            true
        }
        save(context)
        return nowFav
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        for (t in items.values) {
            val o = JSONObject()
            o.put("id", t.id)
            o.put("isMovie", t.isMovie)
            o.put("name", t.name)
            o.put("poster", t.poster ?: JSONObject.NULL)
            o.put("rating", t.rating)
            o.put("year", t.year)
            arr.put(o)
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
