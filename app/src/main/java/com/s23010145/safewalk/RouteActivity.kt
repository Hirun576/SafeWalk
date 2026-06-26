package com.s23010145.safewalk

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Looper
import android.view.LayoutInflater
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
import kotlin.math.sqrt

class RouteActivity : AppCompatActivity(), OnMapReadyCallback, SensorEventListener {

    companion object {
        const val EXTRA_DEST_LAT  = "dest_lat"
        const val EXTRA_DEST_LNG  = "dest_lng"
        const val EXTRA_DEST_NAME = "dest_name"

        // Fall-detection thresholds
        private const val ACCEL_FALL_THRESHOLD      = 25.0f
        private const val ACCEL_FREE_FALL_THRESHOLD = 3.0f
        private const val GYRO_THRESHOLD            = 6.0f
        private const val FALL_COUNTDOWN_MS         = 10_000L
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
    private var currentLatLng: LatLng?     = null
    private var routeFetched               = false
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var isFallDetectionEnabled = false
    private var sensorsCurrentlyRegistered = false
    private var freeFallDetected  = false
    private var freeFallTimestamp = 0L
    private var lastGyroMag       = 0f
    private var fallDialogShowing = false
    private var countdownTimer: CountDownTimer? = null
    private var fallAlertDialog: AlertDialog? = null

    // Fires the instant the preference changes from ANY screen
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SettingsActivity.KEY_FALL_DETECTION) {
            applyFallDetectionState(FallDetectionPrefs.isEnabled(this))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route)

        val lat = intent.getDoubleExtra(EXTRA_DEST_LAT, 0.0)
        val lng = intent.getDoubleExtra(EXTRA_DEST_LNG, 0.0)
        destinationName   = intent.getStringExtra(EXTRA_DEST_NAME) ?: "Destination"
        destinationLatLng = LatLng(lat, lng)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope     = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        initViews()
        setupMap()
        setupLocationUpdates()
        setClickListeners()
        setupBackHandler()
        startWalkService()

        // Pick up whatever the user already had set
        applyFallDetectionState(FallDetectionPrefs.isEnabled(this))
    }

    override fun onResume() {
        super.onResume()
        FallDetectionPrefs.registerListener(this, prefsListener)
        applyFallDetectionState(FallDetectionPrefs.isEnabled(this))
    }

    override fun onPause() {
        super.onPause()
        FallDetectionPrefs.unregisterListener(this, prefsListener)
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

    // FALL DETECTION
    private fun applyFallDetectionState(enabled: Boolean) {
        isFallDetectionEnabled = enabled
        updateFallDetectionUI()

        if (enabled && !sensorsCurrentlyRegistered) {
            registerSensors()
        } else if (!enabled && sensorsCurrentlyRegistered) {
            unregisterSensors()
        }
    }

    private fun registerSensors() {
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let     { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sensorsCurrentlyRegistered = true
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
        sensorsCurrentlyRegistered = false
        freeFallDetected = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
            Sensor.TYPE_GYROSCOPE     -> handleGyroscope(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }

    private fun handleAccelerometer(event: SensorEvent) {
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        if (!isFallDetectionEnabled || fallDialogShowing) return

        if (magnitude < ACCEL_FREE_FALL_THRESHOLD) {
            freeFallDetected  = true
            freeFallTimestamp = System.currentTimeMillis()
        }

        if (freeFallDetected &&
            System.currentTimeMillis() - freeFallTimestamp < 500 &&
            magnitude > ACCEL_FALL_THRESHOLD &&
            lastGyroMag > GYRO_THRESHOLD) {
            freeFallDetected = false
            triggerFallAlert()
        }

        if (System.currentTimeMillis() - freeFallTimestamp > 500) {
            freeFallDetected = false
        }
    }

    private fun handleGyroscope(event: SensorEvent) {
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        lastGyroMag = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
    }

    // Fall alert countdown
    private fun triggerFallAlert() {
        if (fallDialogShowing) return
        fallDialogShowing = true
        unregisterSensors()

        val dialogView  = LayoutInflater.from(this).inflate(R.layout.dialog_fall_alert, null)
        val tvCountdown = dialogView.findViewById<TextView>(R.id.tvCountdown)

        fallAlertDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("I'm OK — Stop Alert") { _, _ -> cancelFallAlert() }
            .create()
        fallAlertDialog!!.window?.setBackgroundDrawableResource(android.R.color.transparent)
        fallAlertDialog!!.show()

        countdownTimer = object : CountDownTimer(FALL_COUNTDOWN_MS, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                tvCountdown.text = (millisUntilFinished / 1000).toString()
            }
            override fun onFinish() {
                fallAlertDialog?.dismiss()
                launchEmergencyFromFall()
            }
        }.start()
    }

    private fun cancelFallAlert() {
        countdownTimer?.cancel()
        countdownTimer = null
        fallDialogShowing = false
        fallAlertDialog = null
        if (isFallDetectionEnabled) registerSensors()
    }

    private fun launchEmergencyFromFall() {
        fallDialogShowing = false
        startActivity(Intent(this, EmergencyActivity::class.java).apply {
            putExtra(EmergencyActivity.EXTRA_AUTO_TRIGGER, true)
        })
    }

    // Location updates
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

    private fun fetchOsrmRoute() {
        val origin = currentLatLng ?: return
        val dest   = destinationLatLng ?: return

        val urlString = "https://router.project-osrm.org/route/v1/foot/" +
                "${origin.longitude},${origin.latitude};" +
                "${dest.longitude},${dest.latitude}" +
                "?overview=full&geometries=polyline&steps=true"

        Thread {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(urlString)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout    = 10_000
                connection.requestMethod  = "GET"
                connection.setRequestProperty("User-Agent", "SafeWalk-Android/1.0")

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    runOnUiThread { Toast.makeText(this, "Route server returned ${connection.responseCode}", Toast.LENGTH_LONG).show() }
                    return@Thread
                }

                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                if (json.getString("code") != "Ok") {
                    runOnUiThread { Toast.makeText(this, "Routing error: ${json.getString("code")}", Toast.LENGTH_LONG).show() }
                    return@Thread
                }

                val route     = json.getJSONArray("routes").getJSONObject(0)
                val distanceM = route.getDouble("distance").roundToInt()
                val durationS = route.getDouble("duration").roundToInt()
                val distText  = if (distanceM >= 1000) "%.1f km".format(distanceM / 1000f) else "$distanceM m"
                val durText   = "${durationS / 60} min"
                val points    = decodePolyline(route.getString("geometry"))

                val leg   = route.getJSONArray("legs").getJSONObject(0)
                val steps = leg.getJSONArray("steps")
                val instruction = if (steps.length() > 0) {
                    val type = steps.getJSONObject(0).getJSONObject("maneuver")
                        .getString("type").replaceFirstChar { it.uppercase() }
                    "$type towards $destinationName"
                } else "Head towards $destinationName"

                runOnUiThread {
                    drawRoutePolyline(points)
                    tvNextInstruction.text = instruction
                    tvDistance.text = distText
                    tvETA.text = durText
                    if (points.size >= 2) {
                        val boundsBuilder = LatLngBounds.Builder()
                        points.forEach { boundsBuilder.include(it) }
                        try { map.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 120)) }
                        catch (e: Exception) { map.animateCamera(CameraUpdateFactory.newLatLngZoom(origin, 15f)) }
                    }
                }
            } catch (e: SocketTimeoutException) {
                runOnUiThread { Toast.makeText(this, "Route request timed out.", Toast.LENGTH_LONG).show() }
            } catch (e: UnknownHostException) {
                runOnUiThread {
                    Toast.makeText(this, "No internet connection. Route unavailable.", Toast.LENGTH_LONG).show()
                    currentLatLng?.let { o -> destinationLatLng?.let { d -> drawStraightLine(o, d) } }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Route failed: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun drawRoutePolyline(points: List<LatLng>) {
        if (points.isEmpty()) return
        map.clear()
        destinationLatLng?.let { map.addMarker(MarkerOptions().position(it).title(destinationName)) }
        map.addPolyline(PolylineOptions().addAll(points).width(12f).color(Color.parseColor("#2563EB")).geodesic(false))
    }

    private fun drawStraightLine(origin: LatLng, dest: LatLng) {
        map.clear()
        map.addMarker(MarkerOptions().position(dest).title(destinationName))
        map.addPolyline(PolylineOptions().add(origin, dest).width(8f).color(Color.parseColor("#94A3B8")).geodesic(true))
        tvNextInstruction.text = "Approximate route (offline)"
    }

    private fun decodePolyline(encoded: String): List<LatLng> {
        val points = mutableListOf<LatLng>()
        var index = 0; val len = encoded.length; var lat = 0; var lng = 0
        while (index < len) {
            var b: Int; var shift = 0; var result = 0
            do { b = encoded[index++].code - 63; result = result or (b and 0x1f shl shift); shift += 5 } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0; result = 0
            do { b = encoded[index++].code - 63; result = result or (b and 0x1f shl shift); shift += 5 } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            points.add(LatLng(lat / 1E5, lng / 1E5))
        }
        return points
    }

    private fun updateDistanceETA(location: Location) {
        val dest = destinationLatLng ?: return
        val results = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, dest.latitude, dest.longitude, results)
        val distanceM = results[0].roundToInt()
        if (tvDistance.text == "-- m") {
            tvDistance.text = if (distanceM >= 1000) "%.1f km".format(distanceM / 1000f) else "$distanceM m"
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
            startActivity(Intent(this, SensorActivity::class.java))
        }

        btnChangeDestination.setOnClickListener {
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

    private fun updateFallDetectionUI() {
        tvFallStatus.text = if (isFallDetectionEnabled) "ACTIVE" else "OFF"
        tvFallStatus.setTextColor(getColor(if (isFallDetectionEnabled) R.color.success else R.color.text_secondary))
    }

    private fun confirmStopWalk() {
        AlertDialog.Builder(this)
            .setTitle("Stop Walk")
            .setMessage("Are you sure you want to stop this walk?")
            .setPositiveButton("Stop") { _, _ ->
                unregisterSensors()
                stopLocationUpdates()
                stopWalkService()
                startActivity(Intent(this, HomeActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
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
        unregisterSensors()
        countdownTimer?.cancel()
        fallAlertDialog?.dismiss()
        stopLocationUpdates()
        super.onDestroy()
    }
}