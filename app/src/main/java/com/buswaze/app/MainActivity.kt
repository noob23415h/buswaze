package com.buswaze.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
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
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null

    private lateinit var searchBox: EditText
    private lateinit var busTypeButton: MaterialButton
    private lateinit var languageButton: MaterialButton
    private lateinit var suggestionsCard: View
    private lateinit var suggestionsList: ListView
    private lateinit var routeCard: View
    private lateinit var routeSummary: TextView
    private lateinit var directionsButton: MaterialButton
    private lateinit var clearRouteButton: MaterialButton

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val locationPermissionCode = 1001

    private var busType = BusType.NORMAL
    private var destination: GeocodeResult? = null
    private var lastInstructions: List<String> = emptyList()

    private var suggestions: List<GeocodeResult> = emptyList()
    private var pendingSuggest: Runnable? = null
    private var suggestSeq = 0
    private var suppressWatcher = false

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
        searchBox = findViewById(R.id.searchBox)
        busTypeButton = findViewById(R.id.busTypeButton)
        languageButton = findViewById(R.id.languageButton)
        suggestionsCard = findViewById(R.id.suggestionsCard)
        suggestionsList = findViewById(R.id.suggestionsList)
        routeCard = findViewById(R.id.routeCard)
        routeSummary = findViewById(R.id.routeSummary)
        directionsButton = findViewById(R.id.directionsButton)
        clearRouteButton = findViewById(R.id.clearRouteButton)

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
        languageButton.setOnClickListener { showLanguageDialog() }

        setupSearch()
        directionsButton.setOnClickListener { showDirectionsDialog() }
        clearRouteButton.setOnClickListener { clearRoute() }
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

    // ---------- Search with live suggestions ----------

    private fun setupSearch() {
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher) return
                pendingSuggest?.let { handler.removeCallbacks(it) }
                val text = s?.toString()?.trim() ?: ""
                if (text.length < 2) {
                    hideSuggestions()
                    return
                }
                val r = Runnable { fetchSuggestions(text, routeOnSingle = false) }
                pendingSuggest = r
                handler.postDelayed(r, 350)
            }
        })

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

    private fun fetchSuggestions(query: String, routeOnSingle: Boolean) {
        val seq = ++suggestSeq
        val loc = if (hasLocationPermission())
            runCatching { map?.locationComponent?.lastKnownLocation }.getOrNull()
        else null
        val hebrew = isHebrew
        executor.execute {
            val results = try {
                RouteClient.suggest(query, hebrew, loc?.latitude, loc?.longitude)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (seq != suggestSeq) return@runOnUiThread // stale response
                when {
                    results == null -> if (routeOnSingle) toast(getString(R.string.network_error))
                    results.isEmpty() -> {
                        hideSuggestions()
                        if (routeOnSingle) toast(getString(R.string.no_results))
                    }
                    routeOnSingle -> pickSuggestion(results[0])
                    else -> showSuggestions(results)
                }
            }
        }
    }

    private fun showSuggestions(results: List<GeocodeResult>) {
        suggestions = results
        suggestionsList.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            results.map { it.displayName }
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
        requestRoute(result)
    }

    // ---------- Routing ----------

    private fun requestRoute(dest: GeocodeResult) {
        val loc = if (hasLocationPermission())
            runCatching { map?.locationComponent?.lastKnownLocation }.getOrNull()
        else null
        if (loc == null) {
            toast(getString(R.string.no_location_yet))
            return
        }
        destination = dest
        toast(getString(R.string.calculating_route))
        val hebrew = isHebrew
        executor.execute {
            try {
                val route = RouteClient.route(
                    loc.latitude, loc.longitude,
                    dest.lat, dest.lon,
                    busType, hebrew
                )
                runOnUiThread { showRoute(route) }
            } catch (e: Exception) {
                runOnUiThread { toast(getString(R.string.route_error)) }
            }
        }
    }

    private fun showRoute(route: RouteResult) {
        val style = map?.style ?: return

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

        if (route.points.size >= 2) {
            val b = LatLngBounds.Builder()
            route.points.forEach { (lat, lon) -> b.include(LatLng(lat, lon)) }
            map?.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 100))
        }

        lastInstructions = route.instructions
        val minutes = (route.timeSeconds / 60).toInt()
        routeSummary.text = getString(
            R.string.route_summary,
            route.distanceKm, minutes, "${busType.emoji} ${getString(busType.labelRes)}"
        )
        routeCard.visibility = View.VISIBLE
    }

    private fun clearRoute() {
        destination = null
        lastInstructions = emptyList()
        routeCard.visibility = View.GONE
        suppressWatcher = true
        searchBox.text.clear()
        suppressWatcher = false
        hideSuggestions()
        map?.style?.let { style ->
            style.removeLayer(ROUTE_LAYER)
            style.removeSource(ROUTE_SOURCE)
        }
    }

    private fun showDirectionsDialog() {
        if (lastInstructions.isEmpty()) return
        val text = lastInstructions
            .mapIndexed { i, s -> "${i + 1}. $s" }
            .joinToString("\n\n")
        AlertDialog.Builder(this)
            .setTitle(R.string.directions_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---------- Location ----------

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

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
            LocationComponentActivationOptions.builder(this, style).build()
        )
        locationComponent.isLocationComponentEnabled = true
        locationComponent.cameraMode = CameraMode.TRACKING
        locationComponent.renderMode = RenderMode.COMPASS
    }

    private fun centerOnMe() {
        val locationComponent = map?.locationComponent
        if (locationComponent == null || !hasLocationPermission()) {
            toast(getString(R.string.no_location_yet))
            return
        }
        val last = runCatching { locationComponent.lastKnownLocation }.getOrNull()
        if (last != null) {
            map?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(last.latitude, last.longitude), 16.0
                )
            )
            locationComponent.cameraMode = CameraMode.TRACKING
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
    }

    // ---------- MapView lifecycle ----------

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
