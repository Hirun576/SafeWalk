package com.s23010145.safewalk

import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.sqrt

class SensorActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    private lateinit var btnBack: ImageButton
    private lateinit var switchFallDetection: SwitchMaterial
    private lateinit var tvFallDetectionStatus: TextView
    private lateinit var fallStatusBar: LinearLayout
    private lateinit var tvFallStatusBar: TextView
    private lateinit var tvAccelX: TextView
    private lateinit var tvAccelY: TextView
    private lateinit var tvAccelZ: TextView
    private lateinit var tvAccelMag: TextView
    private lateinit var tvGyroX: TextView
    private lateinit var tvGyroY: TextView
    private lateinit var tvGyroZ: TextView
    private lateinit var tvGyroMag: TextView
    private lateinit var tvImpactForce: TextView

    private var isFallDetectionEnabled = false
    private var localSensorsRegistered = false

    // Reacts instantly if the toggle changes from another screen
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SettingsActivity.KEY_FALL_DETECTION) {
            syncSwitchFromPrefs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sensor)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope     = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        initViews()
        setupBackHandler()
        syncSwitchFromPrefs()
    }

    override fun onResume() {
        super.onResume()
        FallDetectionPrefs.registerListener(this, prefsListener)
        syncSwitchFromPrefs()
        // Show a LIVE preview of the live sensor numbers on this screen too,
        // purely cosmetic — actual fall-trigger logic happens in RouteActivity.
        registerPreviewSensors()
    }

    override fun onPause() {
        super.onPause()
        FallDetectionPrefs.unregisterListener(this, prefsListener)
        unregisterPreviewSensors()
    }

    // Init
    private fun initViews() {
        btnBack               = findViewById(R.id.btnBack)
        switchFallDetection   = findViewById(R.id.switchFallDetection)
        tvFallDetectionStatus = findViewById(R.id.tvFallDetectionStatus)
        fallStatusBar         = findViewById(R.id.fallStatusBar)
        tvFallStatusBar       = findViewById(R.id.tvFallStatusBar)
        tvAccelX              = findViewById(R.id.tvAccelX)
        tvAccelY              = findViewById(R.id.tvAccelY)
        tvAccelZ              = findViewById(R.id.tvAccelZ)
        tvAccelMag            = findViewById(R.id.tvAccelMag)
        tvGyroX                = findViewById(R.id.tvGyroX)
        tvGyroY                = findViewById(R.id.tvGyroY)
        tvGyroZ                = findViewById(R.id.tvGyroZ)
        tvGyroMag               = findViewById(R.id.tvGyroMag)
        tvImpactForce          = findViewById(R.id.tvImpactForce)

        // Toggling here writes straight to the shared preference —
        // RouteActivity's listener picks this up the instant it changes,
        // and starts/stops its own sensor registration accordingly.
        switchFallDetection.setOnCheckedChangeListener { _, isChecked ->
            FallDetectionPrefs.setEnabled(this, isChecked)
            isFallDetectionEnabled = isChecked
            updateFallDetectionUI(isChecked)
        }

        btnBack.setOnClickListener { finish() }
    }

    // Keep switch + status text matching the shared preference
    private fun syncSwitchFromPrefs() {
        val enabled = FallDetectionPrefs.isEnabled(this)
        isFallDetectionEnabled = enabled
        switchFallDetection.setOnCheckedChangeListener(null) // avoid re-trigger loop
        switchFallDetection.isChecked = enabled
        switchFallDetection.setOnCheckedChangeListener { _, isChecked ->
            FallDetectionPrefs.setEnabled(this, isChecked)
            isFallDetectionEnabled = isChecked
            updateFallDetectionUI(isChecked)
        }
        updateFallDetectionUI(enabled)
    }

    private fun registerPreviewSensors() {
        if (!isFallDetectionEnabled || localSensorsRegistered) return
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyroscope?.let     { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        localSensorsRegistered = true
    }

    private fun unregisterPreviewSensors() {
        if (!localSensorsRegistered) return
        sensorManager.unregisterListener(this)
        localSensorsRegistered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                val mag = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                tvAccelX.text = "%.2f".format(x)
                tvAccelY.text = "%.2f".format(y)
                tvAccelZ.text = "%.2f".format(z)
                tvAccelMag.text = "%.2f m/s²".format(mag)
                tvImpactForce.text = when {
                    mag > 25f -> "HIGH"
                    mag > 15f -> "Moderate"
                    else      -> "Normal"
                }
                tvImpactForce.setTextColor(getColor(when {
                    mag > 25f -> R.color.error
                    mag > 15f -> R.color.warning
                    else      -> R.color.success
                }))
            }
            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                val mag = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                tvGyroX.text = "%.2f".format(x)
                tvGyroY.text = "%.2f".format(y)
                tvGyroZ.text = "%.2f".format(z)
                tvGyroMag.text = "%.2f rad/s".format(mag)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }


    // UI helpers
    private fun updateFallDetectionUI(enabled: Boolean) {
        if (enabled) {
            tvFallDetectionStatus.text = "ON — Active while navigating"
            tvFallDetectionStatus.setTextColor(getColor(R.color.success))
            tvFallStatusBar.text = "● Fall Detection is ON"
            tvFallStatusBar.setTextColor(getColor(R.color.success))
            fallStatusBar.setBackgroundResource(R.drawable.bg_status_on)
            registerPreviewSensors()
        } else {
            tvFallDetectionStatus.text = "OFF — Tap to activate"
            tvFallDetectionStatus.setTextColor(getColor(R.color.text_secondary))
            tvFallStatusBar.text = "● Fall Detection is OFF"
            tvFallStatusBar.setTextColor(getColor(R.color.text_secondary))
            fallStatusBar.setBackgroundResource(R.drawable.bg_status_off)
            unregisterPreviewSensors()
        }
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }

    override fun onDestroy() {
        unregisterPreviewSensors()
        super.onDestroy()
    }
}