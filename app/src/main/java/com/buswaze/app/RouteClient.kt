package com.buswaze.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GeocodeResult(val displayName: String, val lat: Double, val lon: Double)

data class RouteResult(
    val points: List<Pair<Double, Double>>, // lat, lon
    val distanceKm: Double,
    val timeSeconds: Double,
    val instructions: List<String>
)

/**
 * Talks to free public services:
 *  - Nominatim (OpenStreetMap) for destination search
 *  - Valhalla (FOSSGIS) for bus-profile routing
 * All calls are blocking — run them on a background thread.
 */
object RouteClient {

    private const val USER_AGENT = "BusWaze/0.2 (personal project; contact: shovavitosh@gmail.com)"

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.inputStream.bufferedReader().use(BufferedReader::readText).let { return it }
    }

    private fun httpPost(urlStr: String, body: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.outputStream.use { it.write(body.toByteArray()) }
        conn.inputStream.bufferedReader().use(BufferedReader::readText).let { return it }
    }

    /** Search for a place in Israel. */
    fun geocode(query: String, hebrew: Boolean): List<GeocodeResult> {
        val q = URLEncoder.encode(query, "UTF-8")
        val lang = if (hebrew) "he" else "en"
        val url = "https://nominatim.openstreetmap.org/search" +
                "?q=$q&format=json&limit=5&countrycodes=il&accept-language=$lang"
        val arr = JSONArray(httpGet(url))
        val out = ArrayList<GeocodeResult>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                GeocodeResult(
                    o.getString("display_name"),
                    o.getString("lat").toDouble(),
                    o.getString("lon").toDouble()
                )
            )
        }
        return out
    }

    /** Route from A to B with the selected bus profile. */
    fun route(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        bus: BusType,
        hebrew: Boolean
    ): RouteResult {
        val body = JSONObject()
            .put("locations", JSONArray()
                .put(JSONObject().put("lat", fromLat).put("lon", fromLon))
                .put(JSONObject().put("lat", toLat).put("lon", toLon)))
            .put("costing", "bus")
            .put("costing_options", JSONObject()
                .put("bus", JSONObject()
                    .put("height", bus.height)
                    .put("width", bus.width)))
            .put("units", "kilometers")
            .put("language", if (hebrew) "he-IL" else "en-US")
            .toString()

        val resp = JSONObject(httpPost("https://valhalla1.openstreetmap.de/route", body))
        val trip = resp.getJSONObject("trip")
        val summary = trip.getJSONObject("summary")
        val leg = trip.getJSONArray("legs").getJSONObject(0)

        val instructions = ArrayList<String>()
        val maneuvers = leg.optJSONArray("maneuvers")
        if (maneuvers != null) {
            for (i in 0 until maneuvers.length()) {
                instructions.add(maneuvers.getJSONObject(i).optString("instruction"))
            }
        }

        return RouteResult(
            points = decodePolyline6(leg.getString("shape")),
            distanceKm = summary.getDouble("length"),
            timeSeconds = summary.getDouble("time"),
            instructions = instructions
        )
    }

    /** Valhalla returns shapes as a polyline encoded with 1e6 precision. */
    private fun decodePolyline6(encoded: String): List<Pair<Double, Double>> {
        val points = ArrayList<Pair<Double, Double>>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            var result = 0L
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)

            result = 0L
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lon += if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)

            points.add(Pair(lat / 1e6, lon / 1e6))
        }
        return points
    }
}
