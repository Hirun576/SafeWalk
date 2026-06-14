package com.s23010145.safewalk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.chip.Chip
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

class MapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var map: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var geocoder: Geocoder

    private lateinit var btnBack: ImageButton
    private lateinit var btnMyLocation: ImageButton
    private lateinit var tilDestination: TextInputLayout
    private lateinit var etDestination: TextInputEditText
    private lateinit var btnStartNavigation: MaterialButton
    private lateinit var chipPolice: Chip
    private lateinit var chipHospital: Chip
    private lateinit var chipFireStation: Chip
    private lateinit var chipPharmacy: Chip

    private var currentLatLng: LatLng? = null
    private var destinationLatLng: LatLng? = null
    private var destinationName: String = ""
    private var destinationMarker: Marker? = null
    private val nearbyMarkersByType = mutableMapOf<String, MutableList<Marker>>()


    // Permission launcher
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
                || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) enableMyLocation()
        else Toast.makeText(this, "Location permission is required.", Toast.LENGTH_LONG).show()
    }


    // onCreate
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        geocoder = Geocoder(this, Locale.getDefault())

        initViews()
        setupMap()
        setClickListeners()
        setupBackHandler()
    }

    // Init
    private fun initViews() {
        btnBack            = findViewById(R.id.btnBack)
        btnMyLocation      = findViewById(R.id.btnMyLocation)
        tilDestination     = findViewById(R.id.tilDestination)
        etDestination      = findViewById(R.id.etDestination)
        btnStartNavigation = findViewById(R.id.btnStartNavigation)
        chipPolice         = findViewById(R.id.chipPolice)
        chipHospital       = findViewById(R.id.chipHospital)
        chipFireStation    = findViewById(R.id.chipFireStation)
        chipPharmacy       = findViewById(R.id.chipPharmacy)
    }

    private fun setupMap() {
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }


    // Map ready
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isCompassEnabled      = true
        map.uiSettings.isMapToolbarEnabled   = false

        // Tap anywhere on the map → set as destination
        map.setOnMapClickListener { latLng ->
            reverseGeocode(latLng)
        }

        // Tap a labelled POI on the map → set as destination
        map.setOnPoiClickListener { poi ->
            destinationLatLng = poi.latLng
            destinationName   = poi.name
            etDestination.setText(poi.name)
            placeDestinationMarker(poi.latLng, poi.name)
        }

        checkLocationPermissionAndEnable()

        map.setOnMarkerClickListener { marker ->

            destinationLatLng = marker.position
            destinationName = marker.title ?: "Selected Location"

            etDestination.setText(destinationName)

            marker.showInfoWindow()

            Toast.makeText(
                this,
                "Destination selected: $destinationName",
                Toast.LENGTH_SHORT
            ).show()

            true
        }
    }


    // Location permission + enable
    private fun checkLocationPermissionAndEnable() {
        val fine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            enableMyLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun enableMyLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        map.isMyLocationEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = false
        moveToCurrentLocation()
    }

    private fun moveToCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let {
                currentLatLng = LatLng(it.latitude, it.longitude)
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng!!, 16f))
            }
        }
    }


    // Click listeners
    private fun setClickListeners() {

        btnBack.setOnClickListener { finish() }

        btnMyLocation.setOnClickListener { moveToCurrentLocation() }

        // User types an address and presses Search on keyboard
        etDestination.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                geocodeTypedAddress()
                true
            } else false
        }

        btnStartNavigation.setOnClickListener {
            when {
                destinationLatLng == null ->
                    tilDestination.error = "Please tap the map or type a destination"
                currentLatLng == null ->
                    Toast.makeText(this, "Waiting for your location…", Toast.LENGTH_SHORT).show()
                else -> {
                    tilDestination.error = null
                    launchRouteActivity()
                }
            }
        }

        // Nearby chips
        chipPolice.setOnCheckedChangeListener      { _, checked -> toggleNearby("police",       checked) }
        chipHospital.setOnCheckedChangeListener    { _, checked -> toggleNearby("hospital",     checked) }
        chipFireStation.setOnCheckedChangeListener { _, checked -> toggleNearby("fire_station", checked) }
        chipPharmacy.setOnCheckedChangeListener    { _, checked -> toggleNearby("pharmacy",     checked) }
    }


    // Geocode address typed in the search box
    private fun geocodeTypedAddress() {
        val query = etDestination.text.toString().trim()
        if (query.isEmpty()) {
            tilDestination.error = "Please enter a destination"
            return
        }
        tilDestination.error = null
        hideKeyboard()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+ — listener-based (non-deprecated)
            geocoder.getFromLocationName(query, 1) { addresses ->
                runOnUiThread {
                    if (addresses.isNotEmpty()) {
                        val address = addresses[0]
                        val latLng  = LatLng(address.latitude, address.longitude)
                        val name    = address.getAddressLine(0) ?: query
                        destinationLatLng = latLng
                        destinationName   = name
                        etDestination.setText(name)
                        placeDestinationMarker(latLng, name)
                    } else {
                        Toast.makeText(this, "Location not found.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            // API 30–32 — synchronous call (must run off main thread)
            Thread {
                try {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocationName(query, 1)
                    runOnUiThread {
                        if (!addresses.isNullOrEmpty()) {
                            val address = addresses[0]
                            val latLng  = LatLng(address.latitude, address.longitude)
                            val name    = address.getAddressLine(0) ?: query
                            destinationLatLng = latLng
                            destinationName   = name
                            etDestination.setText(name)
                            placeDestinationMarker(latLng, name)
                        } else {
                            Toast.makeText(this, "Location not found.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Search error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }

    // Reverse geocode a tapped LatLng → address string
    private fun reverseGeocode(latLng: LatLng) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1) { addresses ->
                runOnUiThread {
                    val name = if (addresses.isNotEmpty())
                        addresses[0].getAddressLine(0) ?: "Selected location"
                    else
                        "Selected location"
                    applyDestination(latLng, name)
                }
            }
        } else {
            Thread {
                try {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                    runOnUiThread {
                        val name = if (!addresses.isNullOrEmpty())
                            addresses[0].getAddressLine(0) ?: "Selected location"
                        else
                            "Selected location"
                        applyDestination(latLng, name)
                    }
                } catch (e: Exception) {
                    runOnUiThread { applyDestination(latLng, "Selected location") }
                }
            }.start()
        }
    }

    private fun applyDestination(latLng: LatLng, name: String) {
        destinationLatLng = latLng
        destinationName   = name
        etDestination.setText(name)
        placeDestinationMarker(latLng, name)
    }


    // Place / update destination marker
    private fun placeDestinationMarker(latLng: LatLng, title: String) {
        destinationMarker?.remove()
        destinationMarker = map.addMarker(
            MarkerOptions()
                .position(latLng)
                .title(title)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )
        destinationMarker?.showInfoWindow()
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
    }

    // Nearby places
    private fun toggleNearby(type: String, show: Boolean) {
        if (show) searchNearby(type) else clearNearbyMarkers(type)
    }

    private fun searchNearby(type: String) {

        val origin = currentLatLng ?: run {
            Toast.makeText(this, "Waiting for your location...", Toast.LENGTH_SHORT).show()
            return
        }

        val amenity = when(type) {
            "police" -> "police"
            "hospital" -> "hospital"
            "pharmacy" -> "pharmacy"
            "fire_station" -> "fire_station"
            else -> return
        }

        val query = """
        [out:json];
        (
          node["amenity"="$amenity"](around:2000,${origin.latitude},${origin.longitude});
        );
        out;
    """.trimIndent()

        Thread {
            try {

                val url = java.net.URL("https://overpass-api.de/api/interpreter")

                val connection = url.openConnection() as java.net.HttpURLConnection

                connection.requestMethod = "POST"
                connection.doOutput = true

                connection.outputStream.use {
                    it.write(query.toByteArray())
                }

                val response = connection.inputStream.bufferedReader().readText()

                val json = org.json.JSONObject(response)

                val elements = json.getJSONArray("elements")

                runOnUiThread {

                    clearNearbyMarkers(type)

                    val markers = mutableListOf<Marker>()

                    for (i in 0 until elements.length()) {

                        val item = elements.getJSONObject(i)

                        val lat = item.getDouble("lat")
                        val lng = item.getDouble("lon")

                        val name = item.optJSONObject("tags")
                            ?.optString("name", amenity)
                            ?: amenity

                        val marker = map.addMarker(
                            MarkerOptions()
                                .position(LatLng(lat, lng))
                                .title(name)
                        )

                        marker?.let { markers.add(it) }
                    }

                    nearbyMarkersByType[type] = markers

                    Toast.makeText(
                        this,
                        "Found ${markers.size} nearby",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (e: Exception) {

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Search failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun clearNearbyMarkers(type: String) {
        nearbyMarkersByType[type]?.forEach { it.remove() }
        nearbyMarkersByType.remove(type)
    }

    // Launch RouteActivity
    private fun launchRouteActivity() {
        startActivity(Intent(this, RouteActivity::class.java).apply {
            putExtra(RouteActivity.EXTRA_DEST_LAT,  destinationLatLng!!.latitude)
            putExtra(RouteActivity.EXTRA_DEST_LNG,  destinationLatLng!!.longitude)
            putExtra(RouteActivity.EXTRA_DEST_NAME, destinationName)
        })
    }

    // Helpers
    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }
}