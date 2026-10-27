package com.moviiea.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * My List — the user's saved movies & shows, stored on the device.
 *
 * Everything lives in one SharedPreferences entry as a JSON array,
 * newest first. A corrupted save can never crash the app: it just
 * comes back as an empty list.
 */
object FavoritesStore {

    private const val PREFS = "moviiea_favorites"
    private const val KEY = "my_list"

    /** All saved titles, newest first. */
    fun all(context: Context): List<Tmdb.Title> {
        val raw = prefs(context).getString(KEY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = ArrayList<Tmdb.Title>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                list.add(
                    Tmdb.Title(
                        id = o.optInt("id"),
                        isMovie = o.optBoolean("isMovie", true),
                        name = o.optString("name", ""),
                        poster = if (o.isNull("poster")) null else o.optString("poster"),
                        rating = o.optDouble("rating", 0.0),
                        year = o.optString("year", "")
                    )
                )
            }
            list
        } catch (e: Exception) {
            // a broken save must never crash the app
            emptyList()
        }
    }

    fun contains(context: Context, id: Int): Boolean =
        all(context).any { it.id == id }

    /** Adds/removes a title. Returns true when it is saved after the call. */
    fun toggle(context: Context, item: Tmdb.Title): Boolean {
        val current = all(context).toMutableList()
        val existing = current.indexOfFirst { it.id == item.id }
        return if (existing >= 0) {
            current.removeAt(existing)
            save(context, current)
            false
        } else {
            current.add(0, item)   // newest first
            save(context, current)
            true
        }
    }

    private fun save(context: Context, titles: List<Tmdb.Title>) {
        try {
            val array = JSONArray()
            for (t in titles) {
                val o = JSONObject()
                o.put("id", t.id)
                o.put("isMovie", t.isMovie)
                o.put("name", t.name)
                o.put("poster", t.poster ?: JSONObject.NULL)
                o.put("rating", t.rating)
                o.put("year", t.year)
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
