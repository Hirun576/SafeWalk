package com.s23010145.safewalk

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.sqrt

class SensorActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        const val EXTRA_FALL_DETECTION_ENABLED = "fall_detection_enabled"

        // Thresholds — tune these for your device / use case
        private const val ACCEL_FALL_THRESHOLD  = 25.0f   // m/s² — sudden spike (impact)
        private const val ACCEL_FREE_FALL_THRESHOLD = 3.0f // m/s² — near zero (air time)
        private const val GYRO_THRESHOLD        = 6.0f    // rad/s — rapid rotation
        private const val FALL_COUNTDOWN_MS     = 10_000L // 10-second countdown
    }

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    // UI
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

    // State
    private var isFallDetectionEnabled = false
    private var fallDialogShowing = false
    private var countdownTimer: CountDownTimer? = null
    private var fallAlertDialog: AlertDialog? = null

    // Fall detection two-phase logic
    private var freeFallDetected = false
    private var freeFallTimestamp = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sensor)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope     = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // Restore state passed from RouteActivity
        isFallDetectionEnabled = intent.getBooleanExtra(EXTRA_FALL_DETECTION_ENABLED, false)

        initViews()
        setupBackHandler()

        // Apply restored state
        switchFallDetection.isChecked = isFallDetectionEnabled
        updateFallDetectionUI(isFallDetectionEnabled)
        if (isFallDetectionEnabled) registerSensors()
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
        tvGyroX               = findViewById(R.id.tvGyroX)
        tvGyroY               = findViewById(R.id.tvGyroY)
        tvGyroZ               = findViewById(R.id.tvGyroZ)
        tvGyroMag             = findViewById(R.id.tvGyroMag)
        tvImpactForce         = findViewById(R.id.tvImpactForce)

        switchFallDetection.setOnCheckedChangeListener { _, isChecked ->
            isFallDetectionEnabled = isChecked
            updateFallDetectionUI(isChecked)
            if (isChecked) registerSensors() else unregisterSensors()
        }

        btnBack.setOnClickListener { returnToRoute() }
    }

    // Sensor registration
    private fun registerSensors() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
        freeFallDetected = false
    }

    // SensorEventListener
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
            Sensor.TYPE_GYROSCOPE     -> handleGyroscope(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }


    // Accelerometer — display + fall detection
    private var lastGyroMag = 0f

    private fun handleAccelerometer(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        // Update UI
        tvAccelX.text   = "%.2f".format(x)
        tvAccelY.text   = "%.2f".format(y)
        tvAccelZ.text   = "%.2f".format(z)
        tvAccelMag.text = "%.2f m/s²".format(magnitude)

        // Impact force label
        tvImpactForce.text = when {
            magnitude > ACCEL_FALL_THRESHOLD  -> "HIGH"
            magnitude > 15f                   -> "Moderate"
            else                              -> "Normal"
        }
        tvImpactForce.setTextColor(
            getColor(when {
                magnitude > ACCEL_FALL_THRESHOLD -> R.color.error
                magnitude > 15f                  -> R.color.warning
                else                             -> R.color.success
            })
        )

        if (!isFallDetectionEnabled || fallDialogShowing) return

        // Phase 1: free-fall (near-zero acceleration)
        if (magnitude < ACCEL_FREE_FALL_THRESHOLD) {
            freeFallDetected  = true
            freeFallTimestamp = System.currentTimeMillis()
        }

        // Phase 2: impact spike within 500ms of free-fall
        if (freeFallDetected &&
            System.currentTimeMillis() - freeFallTimestamp < 500 &&
            magnitude > ACCEL_FALL_THRESHOLD &&
            lastGyroMag > GYRO_THRESHOLD) {
            freeFallDetected = false
            triggerFallAlert()
        }

        // Reset free-fall if too much time passed
        if (System.currentTimeMillis() - freeFallTimestamp > 500) {
            freeFallDetected = false
        }
    }


    // Gyroscope — display
    private fun handleGyroscope(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        lastGyroMag = magnitude

        tvGyroX.text   = "%.2f".format(x)
        tvGyroY.text   = "%.2f".format(y)
        tvGyroZ.text   = "%.2f".format(z)
        tvGyroMag.text = "%.2f rad/s".format(magnitude)
    }


    // Fall alert dialog with countdown
    private fun triggerFallAlert() {
        if (fallDialogShowing) return
        fallDialogShowing = true
        unregisterSensors()

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_fall_alert, null)
        val tvCountdown = dialogView.findViewById<TextView>(R.id.tvCountdown)

        fallAlertDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("I'm OK — Stop Alert") { _, _ ->
                cancelFallAlert()
            }
            .create()

        fallAlertDialog!!.window?.setBackgroundDrawableResource(android.R.color.transparent)
        fallAlertDialog!!.show()

        // 10-second countdown
        countdownTimer = object : CountDownTimer(FALL_COUNTDOWN_MS, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                tvCountdown.text = seconds.toString()
            }
            override fun onFinish() {
                fallAlertDialog?.dismiss()
                // Countdown finished — send to EmergencyActivity
                //launchEmergency()
            }
        }.start()
    }

    private fun cancelFallAlert() {
        countdownTimer?.cancel()
        countdownTimer = null
        fallDialogShowing = false
        fallAlertDialog = null
        // Re-register sensors if fall detection still on
        if (isFallDetectionEnabled) registerSensors()
    }

//    private fun launchEmergency() {
//        fallDialogShowing = false
//        val intent = Intent(this, EmergencyActivity::class.java).apply {
//            putExtra(EmergencyActivity.EXTRA_AUTO_TRIGGER, true)
//        }
//        startActivity(intent)
//        finish()
//    }


    // UI helpers
    private fun updateFallDetectionUI(enabled: Boolean) {
        if (enabled) {
            tvFallDetectionStatus.text = "ON — Monitoring for falls"
            tvFallDetectionStatus.setTextColor(getColor(R.color.success))
            tvFallStatusBar.text = "● Fall Detection is ACTIVE"
            tvFallStatusBar.setTextColor(getColor(R.color.success))
            fallStatusBar.setBackgroundResource(R.drawable.bg_status_on)
        } else {
            tvFallDetectionStatus.text = "OFF — Tap to activate"
            tvFallDetectionStatus.setTextColor(getColor(R.color.text_secondary))
            tvFallStatusBar.text = "● Fall Detection is OFF"
            tvFallStatusBar.setTextColor(getColor(R.color.text_secondary))
            fallStatusBar.setBackgroundResource(R.drawable.bg_status_off)
        }
    }


    // Back → return state to RouteActivity
    private fun returnToRoute() {
        val result = Intent().apply {
            putExtra(EXTRA_FALL_DETECTION_ENABLED, isFallDetectionEnabled)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                returnToRoute()
            }
        })
    }


    // Lifecycle — unregister sensors to save battery
    override fun onPause() {
        super.onPause()
        // Keep sensors running in background only if fall detection is on
        // and we're going back to RouteActivity (not finishing)
        if (!isFallDetectionEnabled) unregisterSensors()
    }

    override fun onResume() {
        super.onResume()
        if (isFallDetectionEnabled && !fallDialogShowing) registerSensors()
    }

    override fun onDestroy() {
        countdownTimer?.cancel()
        fallAlertDialog?.dismiss()
        unregisterSensors()
        super.onDestroy()
    }
}