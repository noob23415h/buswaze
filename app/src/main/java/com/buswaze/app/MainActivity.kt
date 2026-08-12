package com.buswaze.app

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null

    private lateinit var topBar: View
    private lateinit var searchBox: EditText
    private lateinit var busTypeButton: MaterialButton
    private lateinit var lineButton: MaterialButton
    private lateinit var languageButton: MaterialButton
    private lateinit var restButton: MaterialButton
    private lateinit var suggestionsCard: View
    private lateinit var suggestionsList: ListView
    private lateinit var routeCard: View
    private lateinit var routeSummary: TextView
    private lateinit var driveButton: MaterialButton
    private lateinit var directionsButton: MaterialButton
    private lateinit var favoriteButton: MaterialButton
    private lateinit var clearRouteButton: MaterialButton
    private lateinit var driveCard: View
    private lateinit var driveInstruction: TextView
    private lateinit var driveDistance: TextView
    private lateinit var driveBottom: View
    private lateinit var driveRemaining: TextView
    private lateinit var muteButton: MaterialButton
    private lateinit var exitDriveButton: MaterialButton

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val locationPermissionCode = 1001

    private var busType = BusType.NORMAL
    private var destination: GeocodeResult? = null
    private var routeData: RouteResult? = null
    private var cumDist: DoubleArray = DoubleArray(0) // meters from start, per point

    private var suggestions: List<GeocodeResult> = emptyList()
    private var pendingSuggest: Runnable? = null
    private var suggestSeq = 0
    private var suppressWatcher = false

    private var driveMode = false
    private var offRouteCount = 0
    private var rerouting = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voiceMuted = false
    private var announcedManeuverIdx = -1
    private var announcedStage = 0

    private var activeLine: BusLine? = null
    private var activeStops: List<LineStop> = emptyList()

    private val companies = listOf(
        "אגד", "אגד תעבורה", "דן", "קווים", "מטרופולין", "סופרבוס",
        "אפיקים", "אלקטרה אפיקים", "נתיב אקספרס", "תנופה", "מטרודן",
        "גלים", "דן צפון", "דן דרום", "דן באר שבע"
    )

    private val driveListener = LocationListener { loc -> onDriveLocation(loc) }

    private val isHebrew: Boolean
        get() {
            val lang = resources.configuration.locales.get(0).language
            return lang == "he" || lang == "iw"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        busType = BusType.fromName(
            getPreferences(MODE_PRIVATE).getString("bus_type", null)
        )

        mapView = findViewById(R.id.mapView)
        topBar = findViewById(R.id.topBar)
        searchBox = findViewById(R.id.searchBox)
        busTypeButton = findViewById(R.id.busTypeButton)
        lineButton = findViewById(R.id.lineButton)
        languageButton = findViewById(R.id.languageButton)
        restButton = findViewById(R.id.restButton)
        suggestionsCard = findViewById(R.id.suggestionsCard)
        suggestionsList = findViewById(R.id.suggestionsList)
        routeCard = findViewById(R.id.routeCard)
        routeSummary = findViewById(R.id.routeSummary)
        driveButton = findViewById(R.id.driveButton)
        directionsButton = findViewById(R.id.directionsButton)
        favoriteButton = findViewById(R.id.favoriteButton)
        clearRouteButton = findViewById(R.id.clearRouteButton)
        driveCard = findViewById(R.id.driveCard)
        driveInstruction = findViewById(R.id.driveInstruction)
        driveDistance = findViewById(R.id.driveDistance)
        driveBottom = findViewById(R.id.driveBottom)
        driveRemaining = findViewById(R.id.driveRemaining)
        muteButton = findViewById(R.id.muteButton)
        exitDriveButton = findViewById(R.id.exitDriveButton)

        voiceMuted = getPreferences(MODE_PRIVATE).getBoolean("voice_muted", false)
        updateMuteButton()
        muteButton.setOnClickListener {
            voiceMuted = !voiceMuted
            getPreferences(MODE_PRIVATE).edit().putBoolean("voice_muted", voiceMuted).apply()
            updateMuteButton()
            if (voiceMuted) tts?.stop()
        }

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { maplibreMap ->
            map = maplibreMap

            maplibreMap.cameraPosition = CameraPosition.Builder()
                .target(LatLng(31.8, 35.0))
                .zoom(7.0)
                .build()

            maplibreMap.setLatLngBoundsForCameraTarget(
                LatLngBounds.Builder()
                    .include(LatLng(33.6, 36.3))
                    .include(LatLng(29.2, 33.8))
                    .build()
            )
            maplibreMap.setMinZoomPreference(6.0)

            maplibreMap.setStyle(
                Style.Builder().fromUri("asset://osm_style.json")
            ) { style ->
                enableLocationIfPermitted(style)
            }
        }

        findViewById<FloatingActionButton>(R.id.fabCenter).setOnClickListener { centerOnMe() }

        updateBusTypeButton()
        busTypeButton.setOnClickListener { showBusTypeDialog() }
        lineButton.setOnClickListener { onLineButton() }
        languageButton.setOnClickListener { showLanguageDialog() }
        restButton.setOnClickListener { findRestStops() }

        setupSearch()
        driveButton.setOnClickListener { enterDriveMode() }
        directionsButton.setOnClickListener { showDirectionsDialog() }
        favoriteButton.setOnClickListener { toggleFavorite() }
        clearRouteButton.setOnClickListener { clearRoute() }
        exitDriveButton.setOnClickListener { exitDriveMode() }
    }

    // ---------- Language ----------

    private fun showLanguageDialog() {
        val labels = arrayOf(getString(R.string.lang_auto), "עברית", "English")
        val currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val checked = when {
            currentTags.startsWith("he") || currentTags.startsWith("iw") -> 1
            currentTags.startsWith("en") -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.language_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                val locales = when (which) {
                    1 -> LocaleListCompat.forLanguageTags("he")
                    2 -> LocaleListCompat.forLanguageTags("en")
                    else -> LocaleListCompat.getEmptyLocaleList()
                }
                AppCompatDelegate.setApplicationLocales(locales)
            }
            .show()
    }

    // ---------- Bus type ----------

    private fun updateBusTypeButton() {
        busTypeButton.text = "${busType.emoji} ${getString(busType.labelRes)}"
    }

    private fun showBusTypeDialog() {
        val types = BusType.entries
        val labels = types.map { "${it.emoji} ${getString(it.labelRes)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_bus_type)
            .setSingleChoiceItems(labels, types.indexOf(busType)) { dialog, which ->
                busType = types[which]
                getPreferences(MODE_PRIVATE).edit()
                    .putString("bus_type", busType.name).apply()
                updateBusTypeButton()
                dialog.dismiss()
                destination?.let { requestRoute(it) }
            }
            .show()
    }

    // ---------- Search, suggestions, saved places ----------

    private fun setupSearch() {
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher) return
                pendingSuggest?.let { handler.removeCallbacks(it) }
                val text = s?.toString()?.trim() ?: ""
                if (text.length < 2) {
                    if (text.isEmpty()) showSavedPlaces() else hideSuggestions()
                    return
                }
                val r = Runnable { fetchSuggestions(text, routeOnSingle = false) }
                pendingSuggest = r
                handler.postDelayed(r, 350)
            }
        })

        searchBox.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && searchBox.text.toString().trim().isEmpty()) showSavedPlaces()
        }
        searchBox.setOnClickListener {
            if (searchBox.text.toString().trim().isEmpty()) showSavedPlaces()
        }

        searchBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val text = searchBox.text.toString().trim()
                if (text.isNotEmpty()) {
                    hideKeyboard()
                    if (suggestions.isNotEmpty()) {
                        pickSuggestion(suggestions[0])
                    } else {
                        fetchSuggestions(text, routeOnSingle = true)
                    }
                }
                true
            } else false
        }

        suggestionsList.setOnItemClickListener { _, _, position, _ ->
            suggestions.getOrNull(position)?.let { pickSuggestion(it) }
        }
    }

    private fun showSavedPlaces() {
        val prefs = getPreferences(MODE_PRIVATE)
        val favs = Places.favorites(prefs)
        val recents = Places.recents(prefs)
            .filter { r -> favs.none { it.displayName == r.displayName } }
        val combined = favs + recents
        if (combined.isEmpty()) {
            hideSuggestions()
            return
        }
        val labels = favs.map { "⭐ ${it.displayName}" } + recents.map { "🕘 ${it.displayName}" }
        showSuggestions(combined, labels)
    }

    private fun fetchSuggestions(query: String, routeOnSingle: Boolean) {
        val seq = ++suggestSeq
        val loc = safeLastLocation()
        val hebrew = isHebrew
        executor.execute {
            val results = try {
                RouteClient.suggest(query, hebrew, loc?.latitude, loc?.longitude)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (seq != suggestSeq) return@runOnUiThread
                when {
                    results == null -> if (routeOnSingle) toast(getString(R.string.network_error))
                    results.isEmpty() -> {
                        hideSuggestions()
                        if (routeOnSingle) toast(getString(R.string.no_results))
                    }
                    routeOnSingle -> pickSuggestion(results[0])
                    else -> showSuggestions(results, results.map { it.displayName })
                }
            }
        }
    }

    private fun showSuggestions(results: List<GeocodeResult>, labels: List<String>) {
        suggestions = results
        suggestionsList.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, labels
        )
        suggestionsCard.visibility = View.VISIBLE
    }

    private fun hideSuggestions() {
        suggestions = emptyList()
        suggestionsCard.visibility = View.GONE
    }

    private fun pickSuggestion(result: GeocodeResult) {
        suppressWatcher = true
        searchBox.setText(result.displayName)
        searchBox.setSelection(searchBox.text.length)
        suppressWatcher = false
        hideSuggestions()
        hideKeyboard()
        searchBox.clearFocus()
        requestRoute(result)
    }

    // ---------- Favorites ----------

    private fun toggleFavorite() {
        val dest = destination ?: return
        val nowFavorite = Places.toggleFavorite(getPreferences(MODE_PRIVATE), dest)
        updateFavoriteButton()
        toast(getString(if (nowFavorite) R.string.fav_saved else R.string.fav_removed))
    }

    private fun updateFavoriteButton() {
        val dest = destination
        val isFav = dest != null && Places.isFavorite(getPreferences(MODE_PRIVATE), dest)
        favoriteButton.text = if (isFav) "★" else "☆"
    }

    // ---------- Routing ----------

    private fun requestRoute(dest: GeocodeResult) {
        val loc = safeLastLocation()
        if (loc == null) {
            toast(getString(R.string.no_location_yet))
            return
        }
        destination = dest
        Places.addRecent(getPreferences(MODE_PRIVATE), dest)
        if (!driveMode) toast(getString(R.string.calculating_route))
        val hebrew = isHebrew
        rerouting = true
        executor.execute {
            try {
                val route = RouteClient.route(
                    loc.latitude, loc.longitude,
                    dest.lat, dest.lon,
                    busType, hebrew
                )
                runOnUiThread { rerouting = false; showRoute(route) }
            } catch (e: Exception) {
                runOnUiThread {
                    rerouting = false
                    toast(getString(R.string.route_error))
                    if (driveMode) exitDriveMode()
                }
            }
        }
    }

    private fun showRoute(route: RouteResult) {
        val style = map?.style ?: return

        routeData = route
        cumDist = buildCumulativeDistances(route.points)
        offRouteCount = 0

        val coords = JSONArray()
        route.points.forEach { (lat, lon) ->
            coords.put(JSONArray().put(lon).put(lat))
        }
        val geoJson = JSONObject()
            .put("type", "Feature")
            .put("properties", JSONObject())
            .put("geometry", JSONObject()
                .put("type", "LineString")
                .put("coordinates", coords))
            .toString()

        val existing = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)
        if (existing != null) {
            existing.setGeoJson(geoJson)
        } else {
            style.addSource(GeoJsonSource(ROUTE_SOURCE, geoJson))
            style.addLayer(
                LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor("#1565C0"),
                    PropertyFactory.lineWidth(6f),
                    PropertyFactory.lineOpacity(0.85f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
        }

        if (driveMode) {
            // Rerouted while driving: keep following, banner updates on next GPS fix
            return
        }

        if (route.points.size >= 2) {
            val b = LatLngBounds.Builder()
            route.points.forEach { (lat, lon) -> b.include(LatLng(lat, lon)) }
            map?.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 100))
        }

        val minutes = (route.timeSeconds / 60).toInt()
        routeSummary.text = getString(
            R.string.route_summary,
            route.distanceKm, minutes, "${busType.emoji} ${getString(busType.labelRes)}"
        )
        updateFavoriteButton()
        routeCard.visibility = View.VISIBLE
    }

    private fun clearRoute() {
        destination = null
        routeData = null
        cumDist = DoubleArray(0)
        routeCard.visibility = View.GONE
        suppressWatcher = true
        searchBox.text.clear()
        suppressWatcher = false
        hideSuggestions()
        map?.style?.let { style ->
            style.removeLayer(ROUTE_LAYER)
            style.removeSource(ROUTE_SOURCE)
            style.removeLayer(REST_LAYER)
            style.removeSource(REST_SOURCE)
        }
    }

    private fun showDirectionsDialog() {
        val maneuvers = routeData?.maneuvers ?: return
        if (maneuvers.isEmpty()) return
        val hebrew = isHebrew
        val text = maneuvers
            .mapIndexed { i, m -> "${i + 1}. ${Instructions.localize(m, hebrew)}" }
            .joinToString("\n\n")
        AlertDialog.Builder(this)
            .setTitle(R.string.directions_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---------- Drive mode ----------

    @Suppress("MissingPermission")
    private fun enterDriveMode() {
        val route = routeData ?: return
        if (!hasLocationPermission()) {
            toast(getString(R.string.no_location_yet))
            return
        }
        driveMode = true
        offRouteCount = 0

        topBar.visibility = View.GONE
        suggestionsCard.visibility = View.GONE
        routeCard.visibility = View.GONE
        driveCard.visibility = View.VISIBLE
        driveBottom.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        map?.locationComponent?.let { lc ->
            runCatching {
                lc.renderMode = RenderMode.GPS
                lc.cameraMode = CameraMode.TRACKING_GPS
                lc.zoomWhileTracking(16.5)
                lc.tiltWhileTracking(45.0)
            }
        }

        announcedManeuverIdx = -1
        announcedStage = 0
        initTts()

        // First banner: the first real maneuver (index 0 is "depart")
        val first = route.maneuvers.getOrNull(1) ?: route.maneuvers.firstOrNull()
        driveInstruction.text = first?.let { Instructions.localize(it, isHebrew) } ?: ""
        driveDistance.text = ""
        val minutes = (route.timeSeconds / 60).toInt()
        driveRemaining.text = getString(R.string.remaining, route.distanceKm, minutes)

        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, driveListener)
        } catch (e: Exception) {
            // GPS provider unavailable — banner still shows, camera still follows
        }
    }

    private fun exitDriveMode() {
        driveMode = false
        runCatching {
            (getSystemService(LOCATION_SERVICE) as LocationManager)
                .removeUpdates(driveListener)
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tts?.stop()
        driveCard.visibility = View.GONE
        driveBottom.visibility = View.GONE
        topBar.visibility = View.VISIBLE
        if (routeData != null) routeCard.visibility = View.VISIBLE
        map?.locationComponent?.let { lc ->
            runCatching {
                lc.renderMode = RenderMode.COMPASS
                lc.cameraMode = CameraMode.TRACKING
            }
        }
    }

    private fun onDriveLocation(loc: Location) {
        if (!driveMode) return
        // Push the fix straight into the map so the blue arrow moves instantly
        runCatching { map?.locationComponent?.forceLocationUpdate(loc) }
        val route = routeData ?: return
        val pts = route.points
        if (pts.size < 2 || cumDist.size != pts.size) return

        // Nearest route point to current position
        var bestIdx = 0
        var bestDist = Double.MAX_VALUE
        for (i in pts.indices) {
            val d = fastDistanceMeters(loc.latitude, loc.longitude, pts[i].first, pts[i].second)
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }

        // Off-route detection → automatic reroute
        if (bestDist > 80) {
            offRouteCount++
            if (offRouteCount >= 3 && !rerouting) {
                offRouteCount = 0
                toast(getString(R.string.recalculating))
                destination?.let { requestRoute(it) }
            }
            return
        }
        offRouteCount = 0

        val totalMeters = cumDist.last()
        val remainingMeters = totalMeters - cumDist[bestIdx]

        // Arrival
        if (remainingMeters < 40) {
            toast(getString(R.string.drive_arrived))
            exitDriveMode()
            clearRoute()
            return
        }

        // Next maneuver
        val next = route.maneuvers.firstOrNull { it.beginIdx > bestIdx }
        if (next != null) {
            val hebrew = isHebrew
            driveInstruction.text = Instructions.localize(next, hebrew)
            val metersToTurn = (cumDist[next.beginIdx] - cumDist[bestIdx]).coerceAtLeast(0.0)
            driveDistance.text = formatDistance(metersToTurn)

            // Voice announcements: once ~400m out, once ~150m out
            if (next.beginIdx != announcedManeuverIdx) {
                announcedManeuverIdx = next.beginIdx
                announcedStage = 0
            }
            if (announcedStage < 1 && metersToTurn in 160.0..470.0) {
                announcedStage = 1
                speak(Instructions.spoken(next, hebrew, metersToTurn))
            } else if (announcedStage < 2 && metersToTurn < 160.0) {
                announcedStage = 2
                speak(Instructions.spoken(next, hebrew, null))
            }
        }

        val remainingKm = remainingMeters / 1000.0
        val fraction = if (totalMeters > 0) remainingMeters / totalMeters else 0.0
        val remainingMin = (route.timeSeconds * fraction / 60).toInt()
        driveRemaining.text = getString(R.string.remaining, remainingKm, remainingMin)
    }

    // ---------- Company & line ----------

    private fun onLineButton() {
        if (activeLine == null) {
            showCompanyDialog()
            return
        }
        val items = arrayOf(
            getString(R.string.line_stops_item),
            getString(R.string.line_new_search),
            getString(R.string.line_clear)
        )
        AlertDialog.Builder(this)
            .setTitle("${activeLine?.shortName} · ${activeLine?.agency}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showStopsList()
                    1 -> showCompanyDialog()
                    2 -> clearLine()
                }
            }
            .show()
    }

    private fun showCompanyDialog() {
        val labels = (listOf(getString(R.string.all_companies)) + companies).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_company)
            .setItems(labels) { _, which ->
                val company = if (which == 0) null else companies[which - 1]
                askLineNumber(company)
            }
            .show()
    }

    private fun askLineNumber(company: String?) {
        val input = EditText(this)
        input.hint = "480"
        input.maxLines = 1
        AlertDialog.Builder(this)
            .setTitle(company ?: getString(R.string.line_number_title))
            .setMessage(R.string.line_number_title)
            .setView(input)
            .setPositiveButton(R.string.search) { _, _ ->
                val number = input.text.toString().trim()
                if (number.isNotEmpty()) searchLine(company, number)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun searchLine(company: String?, number: String) {
        toast(getString(R.string.loading_line))
        val today = LocalDate.now().toString()
        executor.execute {
            val all = try {
                RouteClient.busLines(number, today)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                when {
                    all == null -> toast(getString(R.string.network_error))
                    all.isEmpty() -> toast(getString(R.string.no_lines_found))
                    else -> {
                        val filtered = if (company == null) all
                        else all.filter { it.agency.contains(company) }.ifEmpty { all }
                        showLineVariants(filtered)
                    }
                }
            }
        }
    }

    private fun showLineVariants(variants: List<BusLine>) {
        val labels = variants.map {
            val pretty = it.longName.replace("<->", " ↔ ").take(70)
            "${it.shortName} · ${it.agency}\n$pretty"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.line_menu_title)
            .setItems(labels) { _, which -> loadLine(variants[which]) }
            .show()
    }

    private fun loadLine(line: BusLine) {
        toast(getString(R.string.loading_line))
        executor.execute {
            val stops = try {
                RouteClient.lineStops(line.routeId)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                when {
                    stops == null -> toast(getString(R.string.network_error))
                    stops.isEmpty() -> toast(getString(R.string.no_lines_found))
                    else -> {
                        activeLine = line
                        activeStops = stops
                        lineButton.text = "🚏 ${line.shortName}"
                        drawLine(stops)
                        toast(getString(R.string.stops_loaded, stops.size))
                    }
                }
            }
        }
    }

    private fun drawLine(stops: List<LineStop>) {
        val style = map?.style ?: return

        val lineCoords = JSONArray()
        stops.forEach { lineCoords.put(JSONArray().put(it.lon).put(it.lat)) }
        val lineJson = JSONObject()
            .put("type", "Feature")
            .put("properties", JSONObject())
            .put("geometry", JSONObject()
                .put("type", "LineString")
                .put("coordinates", lineCoords))
            .toString()

        val stopFeatures = JSONArray()
        stops.forEach {
            stopFeatures.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("properties", JSONObject())
                    .put("geometry", JSONObject()
                        .put("type", "Point")
                        .put("coordinates", JSONArray().put(it.lon).put(it.lat)))
            )
        }
        val stopsJson = JSONObject()
            .put("type", "FeatureCollection")
            .put("features", stopFeatures)
            .toString()

        val existingLine = style.getSourceAs<GeoJsonSource>(BUSLINE_SOURCE)
        if (existingLine != null) {
            existingLine.setGeoJson(lineJson)
            style.getSourceAs<GeoJsonSource>(STOPS_SOURCE)?.setGeoJson(stopsJson)
        } else {
            style.addSource(GeoJsonSource(BUSLINE_SOURCE, lineJson))
            style.addLayer(
                LineLayer(BUSLINE_LAYER, BUSLINE_SOURCE).withProperties(
                    PropertyFactory.lineColor("#7B1FA2"),
                    PropertyFactory.lineWidth(3f),
                    PropertyFactory.lineOpacity(0.7f),
                    PropertyFactory.lineDasharray(arrayOf(2f, 1.5f))
                )
            )
            style.addSource(GeoJsonSource(STOPS_SOURCE, stopsJson))
            style.addLayer(
                CircleLayer(STOPS_LAYER, STOPS_SOURCE).withProperties(
                    PropertyFactory.circleRadius(5f),
                    PropertyFactory.circleColor("#7B1FA2"),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(1.5f)
                )
            )
        }

        if (stops.size >= 2 && !driveMode) {
            val b = LatLngBounds.Builder()
            stops.forEach { b.include(LatLng(it.lat, it.lon)) }
            map?.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 100))
        }
    }

    private fun clearLine() {
        activeLine = null
        activeStops = emptyList()
        lineButton.text = "🚏"
        map?.style?.let { style ->
            style.removeLayer(STOPS_LAYER)
            style.removeSource(STOPS_SOURCE)
            style.removeLayer(BUSLINE_LAYER)
            style.removeSource(BUSLINE_SOURCE)
        }
    }

    private fun showStopsList() {
        if (activeStops.isEmpty()) return
        val labels = activeStops.mapIndexed { i, s ->
            "${i + 1}. ${s.name} (${s.city})"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.line_stops_item)
            .setItems(labels) { _, which ->
                val s = activeStops[which]
                requestRoute(GeocodeResult("${s.name} (${s.city})", s.lat, s.lon))
            }
            .show()
    }

    // ---------- Rest stops (coffee!) ----------

    private fun findRestStops() {
        val route = routeData ?: return
        toast(getString(R.string.rest_search))
        val pts = route.points
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        pts.forEach { (lat, lon) ->
            if (lat < minLat) minLat = lat
            if (lat > maxLat) maxLat = lat
            if (lon < minLon) minLon = lon
            if (lon > maxLon) maxLon = lon
        }
        val pad = 0.03
        executor.execute {
            val found = try {
                RouteClient.restStops(minLat - pad, minLon - pad, maxLat + pad, maxLon + pad)
            } catch (e: Exception) {
                null
            }
            if (found == null) {
                runOnUiThread { toast(getString(R.string.network_error)) }
                return@execute
            }
            // Keep only stops near the route, ordered by distance along it
            val step = (pts.size / 1500).coerceAtLeast(1)
            val nearRoute = ArrayList<Pair<RestStop, Double>>() // stop, km along route
            for (stop in found) {
                var best = Double.MAX_VALUE
                var bestIdx = 0
                var i = 0
                while (i < pts.size) {
                    val d = fastDistanceMeters(stop.lat, stop.lon, pts[i].first, pts[i].second)
                    if (d < best) { best = d; bestIdx = i }
                    i += step
                }
                if (best <= 400) nearRoute.add(Pair(stop, cumDist[bestIdx] / 1000.0))
            }
            nearRoute.sortBy { it.second }
            val top = nearRoute.take(25)
            runOnUiThread { showRestStops(top) }
        }
    }

    private fun showRestStops(stops: List<Pair<RestStop, Double>>) {
        if (stops.isEmpty()) {
            toast(getString(R.string.rest_none))
            return
        }
        val style = map?.style
        if (style != null) {
            val features = JSONArray()
            stops.forEach { (s, _) ->
                features.put(
                    JSONObject()
                        .put("type", "Feature")
                        .put("properties", JSONObject())
                        .put("geometry", JSONObject()
                            .put("type", "Point")
                            .put("coordinates", JSONArray().put(s.lon).put(s.lat)))
                )
            }
            val json = JSONObject()
                .put("type", "FeatureCollection")
                .put("features", features)
                .toString()
            val existing = style.getSourceAs<GeoJsonSource>(REST_SOURCE)
            if (existing != null) {
                existing.setGeoJson(json)
            } else {
                style.addSource(GeoJsonSource(REST_SOURCE, json))
                style.addLayer(
                    CircleLayer(REST_LAYER, REST_SOURCE).withProperties(
                        PropertyFactory.circleRadius(6f),
                        PropertyFactory.circleColor("#FF8F00"),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(1.5f)
                    )
                )
            }
        }

        val labels = stops.map { (s, km) ->
            val kind = if (s.isFuel) "⛽" else "☕"
            val name = s.name.ifEmpty {
                getString(if (s.isFuel) R.string.fuel_station else R.string.rest_area)
            }
            "$kind $name · ${getString(R.string.at_km, km.toInt())}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.rest_title)
            .setItems(labels) { _, which ->
                val s = stops[which].first
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(s.lat, s.lon), 15.0))
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---------- Voice ----------

    private fun initTts() {
        if (tts != null) {
            setTtsLanguage()
            return
        }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                setTtsLanguage()
            }
        }
    }

    private fun setTtsLanguage() {
        runCatching {
            tts?.language = if (isHebrew) Locale.forLanguageTag("he-IL") else Locale.US
        }
    }

    private fun speak(text: String) {
        if (voiceMuted || !ttsReady) return
        runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "buswaze-turn")
        }
    }

    private fun updateMuteButton() {
        muteButton.text = if (voiceMuted) "🔇" else "🔊"
    }

    private fun buildCumulativeDistances(pts: List<Pair<Double, Double>>): DoubleArray {
        val out = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            out[i] = out[i - 1] + fastDistanceMeters(
                pts[i - 1].first, pts[i - 1].second,
                pts[i].first, pts[i].second
            )
        }
        return out
    }

    /** Fast equirectangular approximation — fine for short distances. */
    private fun fastDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * 110_540.0
        val dLon = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians(lat1))
        return sqrt(dLat * dLat + dLon * dLon)
    }

    private fun formatDistance(meters: Double): String =
        if (meters < 1000) getString(R.string.dist_m, meters.toInt())
        else getString(R.string.dist_km, meters / 1000.0)

    // ---------- Location ----------

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun safeLastLocation(): Location? =
        if (hasLocationPermission())
            runCatching { map?.locationComponent?.lastKnownLocation }.getOrNull()
        else null

    private fun enableLocationIfPermitted(style: Style) {
        if (hasLocationPermission()) {
            enableLocationComponent(style)
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                locationPermissionCode
            )
        }
    }

    @Suppress("MissingPermission")
    private fun enableLocationComponent(style: Style) {
        val locationComponent = map?.locationComponent ?: return
        locationComponent.activateLocationComponent(
            LocationComponentActivationOptions.builder(this, style)
                .locationEngineRequest(
                    LocationEngineRequest.Builder(750L)
                        .setFastestInterval(500L)
                        .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
                        .build()
                )
                .build()
        )
        locationComponent.isLocationComponentEnabled = true
        locationComponent.cameraMode = CameraMode.TRACKING
        locationComponent.renderMode = RenderMode.COMPASS
    }

    private fun centerOnMe() {
        val last = safeLastLocation()
        if (last != null) {
            map?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(last.latitude, last.longitude), 16.0
                )
            )
            map?.locationComponent?.let { runCatching { it.cameraMode = CameraMode.TRACKING } }
        } else {
            toast(getString(R.string.no_location_yet))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locationPermissionCode &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            map?.style?.let { enableLocationComponent(it) }
        }
    }

    // ---------- Helpers ----------

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
    }

    companion object {
        private const val ROUTE_SOURCE = "route-src"
        private const val ROUTE_LAYER = "route-layer"
        private const val BUSLINE_SOURCE = "busline-src"
        private const val BUSLINE_LAYER = "busline-layer"
        private const val STOPS_SOURCE = "stops-src"
        private const val STOPS_LAYER = "stops-layer"
        private const val REST_SOURCE = "rest-src"
        private const val REST_LAYER = "rest-layer"
    }

    // ---------- MapView lifecycle ----------

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() {
        runCatching {
            (getSystemService(LOCATION_SERVICE) as LocationManager)
                .removeUpdates(driveListener)
        }
        runCatching { tts?.shutdown() }
        tts = null
        mapView.onDestroy()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
