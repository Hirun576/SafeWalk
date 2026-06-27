package com.s23010145.safewalk

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME         = "safewalk_prefs"
        const val KEY_DARK_MODE      = "dark_mode"
        const val KEY_FALL_DETECTION = "fall_detection_enabled"

        fun applyTheme(context: Context) {
            val prefs    = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val darkMode = prefs.getBoolean(KEY_DARK_MODE, false)
            AppCompatDelegate.setDefaultNightMode(
                if (darkMode) AppCompatDelegate.MODE_NIGHT_YES
                else          AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )
        }
    }

    private lateinit var btnBack: ImageButton
    private lateinit var cardLightMode: CardView
    private lateinit var cardDarkMode: CardView
    private lateinit var ivLightModeIcon: ImageView
    private lateinit var ivDarkModeIcon: ImageView
    private lateinit var tvLightModeLabel: TextView
    private lateinit var tvDarkModeLabel: TextView
    private lateinit var tvCurrentTheme: TextView
    private lateinit var switchFallDetection: SwitchMaterial
    private lateinit var tvFallDetectionStatus: TextView

    private var isDarkMode = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_FALL_DETECTION) syncFallDetectionSwitch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initViews()
        loadSavedTheme()
        syncFallDetectionSwitch()
        setClickListeners()
        setupBackHandler()
    }

    override fun onResume() {
        super.onResume()
        FallDetectionPrefs.registerListener(this, prefsListener)
        syncFallDetectionSwitch()
    }

    override fun onPause() {
        super.onPause()
        FallDetectionPrefs.unregisterListener(this, prefsListener)
    }

    // Init
    private fun initViews() {
        btnBack               = findViewById(R.id.btnBack)
        cardLightMode         = findViewById(R.id.cardLightMode)
        cardDarkMode          = findViewById(R.id.cardDarkMode)
        ivLightModeIcon       = findViewById(R.id.ivLightModeIcon)
        ivDarkModeIcon        = findViewById(R.id.ivDarkModeIcon)
        tvLightModeLabel      = findViewById(R.id.tvLightModeLabel)
        tvDarkModeLabel       = findViewById(R.id.tvDarkModeLabel)
        tvCurrentTheme        = findViewById(R.id.tvCurrentTheme)
        switchFallDetection   = findViewById(R.id.switchFallDetection)
        tvFallDetectionStatus = findViewById(R.id.tvFallDetectionStatus)
    }

    // Theme
    private fun loadSavedTheme() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        isDarkMode = if (prefs.contains(KEY_DARK_MODE)) {
            prefs.getBoolean(KEY_DARK_MODE, false)
        } else {
            val nightModeFlags = resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK

            nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }

        applyThemeUI(isDarkMode)
    }

    private fun setClickListeners() {

        btnBack.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }

        cardLightMode.setOnClickListener {
            if (isDarkMode) {
                isDarkMode = false
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_DARK_MODE, false).apply()
                applyThemeUI(false)
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }

        cardDarkMode.setOnClickListener {
            if (!isDarkMode) {
                isDarkMode = true
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_DARK_MODE, true).apply()
                applyThemeUI(true)
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
        }

        switchFallDetection.setOnCheckedChangeListener { _, isChecked ->
            FallDetectionPrefs.setEnabled(this, isChecked)
            updateFallDetectionStatusText(isChecked)
        }
    }

    private fun applyThemeUI(darkMode: Boolean) {
        val selectedBg     = ContextCompat.getColor(this, R.color.primary)
        val unselectedBg   = ContextCompat.getColor(this, R.color.divider)
        val selectedFg     = ContextCompat.getColor(this, R.color.white)
        val unselectedFg   = ContextCompat.getColor(this, R.color.text_primary)

        // Light Mode card
        cardLightMode.setCardBackgroundColor(if (!darkMode) selectedBg else unselectedBg)
        ivLightModeIcon.setColorFilter(if (!darkMode) selectedFg else unselectedFg)
        tvLightModeLabel.setTextColor(if (!darkMode) selectedFg else unselectedFg)

        // Dark Mode card
        cardDarkMode.setCardBackgroundColor(if (darkMode) selectedBg else unselectedBg)
        ivDarkModeIcon.setColorFilter(if (darkMode) selectedFg else unselectedFg)
        tvDarkModeLabel.setTextColor(if (darkMode) selectedFg else unselectedFg)

        tvCurrentTheme.text = if (darkMode) "Dark" else "Light"
    }

    // Fall detection
    private fun syncFallDetectionSwitch() {
        val enabled = FallDetectionPrefs.isEnabled(this)
        switchFallDetection.setOnCheckedChangeListener(null)
        switchFallDetection.isChecked = enabled
        switchFallDetection.setOnCheckedChangeListener { _, isChecked ->
            FallDetectionPrefs.setEnabled(this, isChecked)
            updateFallDetectionStatusText(isChecked)
        }
        updateFallDetectionStatusText(enabled)
    }

    private fun updateFallDetectionStatusText(enabled: Boolean) {
        tvFallDetectionStatus.text = if (enabled)
            "ON — Active during walks"
        else
            "OFF — Enable to activate during walks"
        tvFallDetectionStatus.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.success else R.color.text_secondary)
        )
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                startActivity(Intent(this@SettingsActivity, HomeActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
        })
    }
}