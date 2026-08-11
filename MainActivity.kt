package com.buswaze.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null

    private val locationPermissionCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Must be called before inflating the MapView
        MapLibre.getInstance(this)

        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)

        mapView.getMapAsync { maplibreMap ->
            map = maplibreMap

            // Start centered on Israel
            maplibreMap.cameraPosition = CameraPosition.Builder()
                .target(LatLng(31.8, 35.0))
                .zoom(7.0)
                .build()

            // Keep the camera inside Israel (with a small margin)
            maplibreMap.setLatLngBoundsForCameraTarget(
                LatLngBounds.Builder()
                    .include(LatLng(33.6, 36.3)) // north-east
                    .include(LatLng(29.2, 33.8)) // south-west
                    .build()
            )
            maplibreMap.setMinZoomPreference(6.0)

            maplibreMap.setStyle(
                Style.Builder().fromUri("asset://osm_style.json")
            ) { style ->
                enableLocationIfPermitted(style)
            }
        }

        findViewById<FloatingActionButton>(R.id.fabCenter).setOnClickListener {
            centerOnMe()
        }
    }

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
            Toast.makeText(this, getString(R.string.no_location_yet), Toast.LENGTH_SHORT).show()
            return
        }
        val last = locationComponent.lastKnownLocation
        if (last != null) {
            map?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(last.latitude, last.longitude), 16.0
                )
            )
            locationComponent.cameraMode = CameraMode.TRACKING
        } else {
            Toast.makeText(this, getString(R.string.no_location_yet), Toast.LENGTH_SHORT).show()
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

    // MapView lifecycle
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
