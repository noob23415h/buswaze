package com.buswaze.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Favorites and recent destinations, stored as JSON in SharedPreferences. */
object Places {

    private const val KEY_FAVORITES = "favorites"
    private const val KEY_RECENTS = "recents"
    private const val MAX_RECENTS = 10

    private fun load(prefs: SharedPreferences, key: String): MutableList<GeocodeResult> {
        val out = ArrayList<GeocodeResult>()
        try {
            val arr = JSONArray(prefs.getString(key, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    GeocodeResult(
                        o.getString("label"),
                        o.getDouble("lat"),
                        o.getDouble("lon")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun save(prefs: SharedPreferences, key: String, list: List<GeocodeResult>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("label", it.displayName)
                    .put("lat", it.lat)
                    .put("lon", it.lon)
            )
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    fun favorites(prefs: SharedPreferences): List<GeocodeResult> = load(prefs, KEY_FAVORITES)

    fun recents(prefs: SharedPreferences): List<GeocodeResult> = load(prefs, KEY_RECENTS)

    fun isFavorite(prefs: SharedPreferences, place: GeocodeResult): Boolean =
        favorites(prefs).any { it.displayName == place.displayName }

    /** Returns true if the place is now a favorite. */
    fun toggleFavorite(prefs: SharedPreferences, place: GeocodeResult): Boolean {
        val favs = load(prefs, KEY_FAVORITES)
        val existing = favs.indexOfFirst { it.displayName == place.displayName }
        val nowFavorite = if (existing >= 0) {
            favs.removeAt(existing); false
        } else {
            favs.add(0, place); true
        }
        save(prefs, KEY_FAVORITES, favs)
        return nowFavorite
    }

    fun addRecent(prefs: SharedPreferences, place: GeocodeResult) {
        val recents = load(prefs, KEY_RECENTS)
        recents.removeAll { it.displayName == place.displayName }
        recents.add(0, place)
        while (recents.size > MAX_RECENTS) recents.removeAt(recents.size - 1)
        save(prefs, KEY_RECENTS, recents)
    }
}
