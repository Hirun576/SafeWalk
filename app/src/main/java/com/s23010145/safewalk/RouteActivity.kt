package com.s23010145.safewalk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.math.roundToInt

class RouteActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        const val EXTRA_DEST_LAT               = "dest_lat"
        const val EXTRA_DEST_LNG               = "dest_lng"
        const val EXTRA_DEST_NAME              = "dest_name"
        const val EXTRA_FALL_DETECTION_ENABLED = "fall_detection_enabled"
        const val REQUEST_SENSOR               = 1001
        private const val TAG                  = "RouteActivity"
    }

    private lateinit var map: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private lateinit var tvNextInstruction: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvETA: TextView
    private lateinit var tvFallStatus: TextView
    private lateinit var fallDetectionRow: LinearLayout
    private lateinit var btnChangeDestination: MaterialButton
    private lateinit var btnSOS: MaterialButton
    private lateinit var btnStopWalk: MaterialButton

    private var destinationLatLng: LatLng? = null
    private var destinationName: String    = ""
    private var isFallDetectionEnabled     = false
    private var currentLatLng: LatLng?     = null
    private var routeFetched               = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route)

        val lat = intent.getDoubleExtra(EXTRA_DEST_LAT, 0.0)
        val lng = intent.getDoubleExtra(EXTRA_DEST_LNG, 0.0)
        destinationName   = intent.getStringExtra(EXTRA_DEST_NAME) ?: "Destination"
        destinationLatLng = LatLng(lat, lng)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        initViews()
        setupMap()
        setupLocationUpdates()
        setClickListeners()
        setupBackHandler()
        startWalkService()
    }


    // Foreground service
    private fun startWalkService() {
        startForegroundService(Intent(this, WalkForegroundService::class.java).apply {
            action = WalkForegroundService.ACTION_START
        })
    }

    private fun stopWalkService() {
        startService(Intent(this, WalkForegroundService::class.java).apply {
            action = WalkForegroundService.ACTION_STOP
        })
    }


    // Init
    private fun initViews() {
        tvNextInstruction    = findViewById(R.id.tvNextInstruction)
        tvDistance           = findViewById(R.id.tvDistance)
        tvETA                = findViewById(R.id.tvETA)
        tvFallStatus         = findViewById(R.id.tvFallStatus)
        fallDetectionRow     = findViewById(R.id.fallDetectionRow)
        btnChangeDestination = findViewById(R.id.btnChangeDestination)
        btnSOS               = findViewById(R.id.btnSOS)
        btnStopWalk          = findViewById(R.id.btnStopWalk)

        tvNextInstruction.text = "Navigating to $destinationName"
    }

    private fun setupMap() {
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.routeMapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }


    // Map ready
    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map.uiSettings.isZoomControlsEnabled = false
        map.uiSettings.isMapToolbarEnabled   = false

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            map.isMyLocationEnabled = true
        }

        destinationLatLng?.let {
            map.addMarker(MarkerOptions().position(it).title(destinationName))
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 15f))
        }
    }


    // Location updates — fetch route on first fix
    private fun setupLocationUpdates() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    currentLatLng = LatLng(location.latitude, location.longitude)

                    if (!routeFetched) {
                        routeFetched = true
                        fetchOsrmRoute()
                    }

                    updateDistanceETA(location)
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateIntervalMillis(2000L)
                .build()
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        }
    }


    // OSRM routing
    private fun fetchOsrmRoute() {
        val origin = currentLatLng ?: return
        val dest   = destinationLatLng ?: return

        // OSRM expects lng,lat order (opposite of Google's lat,lng)
        val urlString = "https://router.project-osrm.org/route/v1/foot/" +
                "${origin.longitude},${origin.latitude};" +
                "${dest.longitude},${dest.latitude}" +
                "?overview=full&geometries=polyline&steps=true"

        Log.d(TAG, "Fetching route: $urlString")

        Thread {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(urlString)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000   // 10s connect timeout
                connection.readTimeout    = 10_000   // 10s read timeout
                connection.requestMethod  = "GET"
                connection.setRequestProperty("User-Agent", "SafeWalk-Android/1.0")

                val httpCode = connection.responseCode
                Log.d(TAG, "OSRM HTTP status: $httpCode")

                if (httpCode != HttpURLConnection.HTTP_OK) {
                    val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "no body"
                    Log.e(TAG, "OSRM error body: $errorBody")
                    runOnUiThread {
                        Toast.makeText(this, "Route server returned $httpCode", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                val response = connection.inputStream.bufferedReader().readText()
                Log.d(TAG, "OSRM response (first 300): ${response.take(300)}")

                val json = JSONObject(response)
                val code = json.getString("code")

                if (code != "Ok") {
                    Log.e(TAG, "OSRM code not Ok: $code")
                    runOnUiThread {
                        Toast.makeText(this, "Routing error: $code", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                val route      = json.getJSONArray("routes").getJSONObject(0)
                val distanceM  = route.getDouble("distance").roundToInt()
                val durationS  = route.getDouble("duration").roundToInt()
                val distText   = if (distanceM >= 1000) "%.1f km".format(distanceM / 1000f)
                else "$distanceM m"
                val durText    = "${durationS / 60} min"

                val encodedPolyline = route.getString("geometry")
                val points          = decodePolyline(encodedPolyline)
                Log.d(TAG, "Decoded ${points.size} polyline points")

                val leg         = route.getJSONArray("legs").getJSONObject(0)
                val steps       = leg.getJSONArray("steps")
                val instruction = if (steps.length() > 0) {
                    val maneuver  = steps.getJSONObject(0).getJSONObject("maneuver")
                    val type      = maneuver.getString("type").replaceFirstChar { it.uppercase() }
                    "$type towards $destinationName"
                } else {
                    "Head towards $destinationName"
                }

                runOnUiThread {
                    drawRoutePolyline(points)
                    tvNextInstruction.text = instruction
                    tvDistance.text        = distText
                    tvETA.text             = durText

                    if (points.size >= 2) {
                        val boundsBuilder = LatLngBounds.Builder()
                        points.forEach { boundsBuilder.include(it) }
                        try {
                            map.animateCamera(
                                CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 120)
                            )
                        } catch (e: Exception) {
                            map.animateCamera(CameraUpdateFactory.newLatLngZoom(origin, 15f))
                        }
                    }
                }

            } catch (e: SocketTimeoutException) {
                Log.e(TAG, "OSRM timeout", e)
                runOnUiThread {
                    Toast.makeText(this, "Route request timed out. Check your internet connection.", Toast.LENGTH_LONG).show()
                }
            } catch (e: UnknownHostException) {
                Log.e(TAG, "OSRM DNS failure — no internet?", e)
                runOnUiThread {
                    Toast.makeText(this, "No internet connection. Route unavailable.", Toast.LENGTH_LONG).show()
                    // Still show straight-line so the user has some guidance
                    currentLatLng?.let { origin ->
                        destinationLatLng?.let { dest ->
                            drawStraightLine(origin, dest)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "OSRM unexpected error", e)
                runOnUiThread {
                    Toast.makeText(this, "Route failed: ${e.javaClass.simpleName} — ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }


    // Draw road-following polyline
    private fun drawRoutePolyline(points: List<LatLng>) {
        if (points.isEmpty()) return
        map.clear()
        destinationLatLng?.let {
            map.addMarker(MarkerOptions().position(it).title(destinationName))
        }
        map.addPolyline(
            PolylineOptions()
                .addAll(points)
                .width(12f)
                .color(Color.parseColor("#2563EB"))
                .geodesic(false)
        )
    }

    // Fallback when no internet — draws a straight line so user isn't left with nothing
    private fun drawStraightLine(origin: LatLng, dest: LatLng) {
        map.clear()
        map.addMarker(MarkerOptions().position(dest).title(destinationName))
        map.addPolyline(
            PolylineOptions()
                .add(origin, dest)
                .width(8f)
                .color(Color.parseColor("#94A3B8"))  // grey — indicates approximate
                .geodesic(true)
        )
        tvNextInstruction.text = "Approximate route (offline)"
    }


    // Google / OSRM encoded polyline decoder
    private fun decodePolyline(encoded: String): List<LatLng> {
        val points = mutableListOf<LatLng>()
        var index  = 0
        val len    = encoded.length
        var lat    = 0
        var lng    = 0

        while (index < len) {
            var b: Int; var shift = 0; var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            shift = 0; result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            points.add(LatLng(lat / 1E5, lng / 1E5))
        }
        return points
    }


    // Distance / ETA from live GPS (shown before OSRM responds)
    private fun updateDistanceETA(location: Location) {
        val dest = destinationLatLng ?: return
        val results = FloatArray(1)
        Location.distanceBetween(
            location.latitude, location.longitude,
            dest.latitude, dest.longitude,
            results
        )
        val distanceM = results[0].roundToInt()

        // Only overwrite placeholder values — don't clobber OSRM values
        if (tvDistance.text == "-- m") {
            tvDistance.text = if (distanceM >= 1000)
                "%.1f km".format(distanceM / 1000f)
            else "$distanceM m"
            tvETA.text = "${(distanceM / 80.0).roundToInt()} min"
        }

        if (distanceM <= 30) {
            tvNextInstruction.text = "You have arrived at $destinationName"
            stopLocationUpdates()
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }


    // Click listeners
    private fun setClickListeners() {

        fallDetectionRow.setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(this, SensorActivity::class.java).apply {
                    putExtra(SensorActivity.EXTRA_FALL_DETECTION_ENABLED, isFallDetectionEnabled)
                },
                REQUEST_SENSOR
            )
        }

        btnChangeDestination.setOnClickListener {
            stopFallDetection()
            stopLocationUpdates()
            stopWalkService()
            startActivity(Intent(this, MapActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }

        btnSOS.setOnClickListener {
            startActivity(Intent(this, EmergencyActivity::class.java))
        }

        btnStopWalk.setOnClickListener { confirmStopWalk() }
    }


    // Fall detection result from SensorActivity
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SENSOR) {
            isFallDetectionEnabled = data?.getBooleanExtra(
                SensorActivity.EXTRA_FALL_DETECTION_ENABLED, false
            ) ?: false
            updateFallDetectionUI()
        }
    }

    private fun updateFallDetectionUI() {
        tvFallStatus.text = if (isFallDetectionEnabled) "ACTIVE" else "OFF"
        tvFallStatus.setTextColor(
            getColor(if (isFallDetectionEnabled) R.color.success else R.color.text_secondary)
        )
    }

    private fun stopFallDetection() {
        isFallDetectionEnabled = false
        updateFallDetectionUI()
    }


    // Stop walk
    private fun confirmStopWalk() {
        AlertDialog.Builder(this)
            .setTitle("Stop Walk")
            .setMessage("Are you sure you want to stop this walk?")
            .setPositiveButton("Stop") { _, _ ->
                stopFallDetection()
                stopLocationUpdates()
                stopWalkService()
                startActivity(
                    Intent(this, HomeActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
                finish()
            }
            .setNegativeButton("Continue", null)
            .show()
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { confirmStopWalk() }
        })
    }

    override fun onDestroy() {
        stopLocationUpdates()
        super.onDestroy()
    }
}