package com.buswaze.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GeocodeResult(val displayName: String, val lat: Double, val lon: Double)

data class BusLine(
    val routeId: Long,
    val shortName: String,
    val agency: String,
    val longName: String,
    val direction: String
)

data class LineStop(
    val name: String,
    val city: String,
    val lat: Double,
    val lon: Double,
    val sequence: Int
)

data class RestStop(
    val name: String,
    val lat: Double,
    val lon: Double,
    val isFuel: Boolean
)

data class Maneuver(
    val instruction: String,
    val beginIdx: Int, // index into RouteResult.points where this maneuver starts
    val lengthKm: Double,
    val type: Int,
    val street: String?,
    val roundaboutExit: Int
)

data class RouteResult(
    val points: List<Pair<Double, Double>>, // lat, lon
    val distanceKm: Double,
    val timeSeconds: Double,
    val maneuvers: List<Maneuver>
) {
    val instructions: List<String> get() = maneuvers.map { it.instruction }
}

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

    private fun httpPost(
        urlStr: String,
        body: String,
        contentType: String = "application/json"
    ): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Content-Type", contentType)
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.outputStream.use { it.write(body.toByteArray()) }
        conn.inputStream.bufferedReader().use(BufferedReader::readText).let { return it }
    }

    /**
     * Live search suggestions from Photon (komoot) — built for as-you-type search.
     * Results limited to Israel's bounding box and biased toward the user's location.
     */
    fun suggest(query: String, hebrew: Boolean, lat: Double?, lon: Double?): List<GeocodeResult> {
        val q = URLEncoder.encode(query, "UTF-8")
        var url = "https://photon.komoot.io/api/?q=$q&limit=6&bbox=33.8,29.2,36.3,33.6"
        if (!hebrew) url += "&lang=en" // default (no lang) returns local names = Hebrew
        if (lat != null && lon != null) url += "&lat=$lat&lon=$lon"

        val feats = JSONObject(httpGet(url)).getJSONArray("features")
        val out = ArrayList<GeocodeResult>()
        val seen = HashSet<String>()
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i)
            val p = f.getJSONObject("properties")
            val coords = f.getJSONObject("geometry").getJSONArray("coordinates")

            val name = p.optString("name").ifEmpty {
                listOf(p.optString("street"), p.optString("housenumber"))
                    .filter { it.isNotEmpty() }.joinToString(" ")
            }
            if (name.isEmpty()) continue
            val area = listOf(p.optString("city"), p.optString("district"), p.optString("state"))
                .firstOrNull { it.isNotEmpty() && it != name } ?: ""
            val label = if (area.isEmpty()) name else "$name — $area"

            if (seen.add(label)) {
                out.add(GeocodeResult(label, coords.getDouble(1), coords.getDouble(0)))
            }
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
        // The armored bus is heavy enough that weight limits matter, so it uses
        // the "truck" profile (respects maxweight/bridge limits). Other buses use
        // the "bus" profile (keeps bus access rights on restricted roads).
        val costing = if (bus == BusType.ARMORED) "truck" else "bus"
        val options = JSONObject()
            .put("height", bus.height)
            .put("width", bus.width)
        if (costing == "truck") {
            options.put("length", bus.length)
            options.put("weight", bus.weightTons)
        }

        val body = JSONObject()
            .put("locations", JSONArray()
                .put(JSONObject().put("lat", fromLat).put("lon", fromLon))
                .put(JSONObject().put("lat", toLat).put("lon", toLon)))
            .put("costing", costing)
            .put("costing_options", JSONObject().put(costing, options))
            .put("units", "kilometers")
            .put("language", if (hebrew) "he-IL" else "en-US")
            .toString()

        val resp = JSONObject(httpPost("https://valhalla1.openstreetmap.de/route", body))
        val trip = resp.getJSONObject("trip")
        val summary = trip.getJSONObject("summary")
        val leg = trip.getJSONArray("legs").getJSONObject(0)

        val maneuvers = ArrayList<Maneuver>()
        val mans = leg.optJSONArray("maneuvers")
        if (mans != null) {
            for (i in 0 until mans.length()) {
                val m = mans.getJSONObject(i)
                maneuvers.add(
                    Maneuver(
                        instruction = m.optString("instruction"),
                        beginIdx = m.optInt("begin_shape_index"),
                        lengthKm = m.optDouble("length", 0.0),
                        type = m.optInt("type"),
                        street = m.optJSONArray("street_names")?.optString(0)
                            ?.takeIf { it.isNotEmpty() },
                        roundaboutExit = m.optInt("roundabout_exit_count", 0)
                    )
                )
            }
        }

        return RouteResult(
            points = decodePolyline6(leg.getString("shape")),
            distanceKm = summary.getDouble("length"),
            timeSeconds = summary.getDouble("time"),
            maneuvers = maneuvers
        )
    }

    // ---------- Israel bus lines (open-bus stride API, MOT open data) ----------

    private const val STRIDE = "https://open-bus-stride-api.hasadna.org.il"

    /** All route variants matching a line number today (all companies). */
    fun busLines(lineNumber: String, date: String): List<BusLine> {
        val q = URLEncoder.encode(lineNumber, "UTF-8")
        val url = "$STRIDE/gtfs_routes/list?limit=30&date_from=$date&date_to=$date&route_short_name=$q"
        val arr = JSONArray(httpGet(url))
        val out = ArrayList<BusLine>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                BusLine(
                    routeId = o.getLong("id"),
                    shortName = o.optString("route_short_name"),
                    agency = o.optString("agency_name"),
                    longName = o.optString("route_long_name"),
                    direction = o.optString("route_direction")
                )
            )
        }
        return out
    }

    /** Ordered stops of a line variant (via one of today's rides). */
    fun lineStops(routeId: Long): List<LineStop> {
        val rides = JSONArray(httpGet("$STRIDE/gtfs_rides/list?limit=1&gtfs_route_id=$routeId"))
        if (rides.length() == 0) return emptyList()
        val rideId = rides.getJSONObject(0).getLong("id")

        val arr = JSONArray(httpGet("$STRIDE/gtfs_ride_stops/list?limit=500&gtfs_ride_ids=$rideId"))
        val out = ArrayList<LineStop>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val lat = o.optDouble("gtfs_stop__lat")
            val lon = o.optDouble("gtfs_stop__lon")
            if (lat.isNaN() || lon.isNaN()) continue
            out.add(
                LineStop(
                    name = o.optString("gtfs_stop__name"),
                    city = o.optString("gtfs_stop__city"),
                    lat = lat,
                    lon = lon,
                    sequence = o.optInt("stop_sequence")
                )
            )
        }
        return out.sortedBy { it.sequence }
    }

    // ---------- Rest stops along the way (OpenStreetMap / Overpass) ----------

    /** Fuel stations and rest areas inside a bounding box. */
    fun restStops(
        minLat: Double, minLon: Double,
        maxLat: Double, maxLon: Double
    ): List<RestStop> {
        val bbox = "$minLat,$minLon,$maxLat,$maxLon"
        val query = "[out:json][timeout:20];(" +
                "node[\"amenity\"=\"fuel\"]($bbox);" +
                "node[\"highway\"=\"rest_area\"]($bbox);" +
                "node[\"highway\"=\"services\"]($bbox);" +
                ");out 300;"
        val resp = httpPost(
            "https://overpass-api.de/api/interpreter",
            "data=" + URLEncoder.encode(query, "UTF-8"),
            contentType = "application/x-www-form-urlencoded"
        )
        val elements = JSONObject(resp).getJSONArray("elements")
        val out = ArrayList<RestStop>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: JSONObject()
            val isFuel = tags.optString("amenity") == "fuel"
            val name = tags.optString("name").ifEmpty {
                tags.optString("brand").ifEmpty {
                    tags.optString("operator")
                }
            }
            out.add(
                RestStop(
                    name = name,
                    lat = e.getDouble("lat"),
                    lon = e.getDouble("lon"),
                    isFuel = isFuel
                )
            )
        }
        return out
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
