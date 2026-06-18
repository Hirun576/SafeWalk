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
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME            = "safewalk_prefs"
        const val KEY_DARK_MODE         = "dark_mode"
        const val KEY_FALL_DETECTION    = "fall_detection_enabled"

        // Call this from Application.onCreate or any Activity to apply
        // the saved theme before the layout inflates
        fun applyTheme(context: Context) {
            val prefs    = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val darkMode = prefs.getBoolean(KEY_DARK_MODE, false)
            AppCompatDelegate.setDefaultNightMode(
                if (darkMode) AppCompatDelegate.MODE_NIGHT_YES
                else          AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )
        }
    }

    private lateinit var prefs: SharedPreferences

    private lateinit var btnBack: ImageButton
    private lateinit var cardLightMode: CardView
    private lateinit var cardDarkMode: CardView
    private lateinit var ivDarkModeIcon: ImageView
    private lateinit var tvDarkModeLabel: TextView
    private lateinit var tvCurrentTheme: TextView
    private lateinit var switchFallDetection: SwitchMaterial
    private lateinit var tvFallDetectionStatus: TextView

    private var isDarkMode         = false
    private var isFallDetectionOn  = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        initViews()
        loadSavedSettings()
        setClickListeners()
        setupBackHandler()
    }


    // Init
    private fun initViews() {
        btnBack               = findViewById(R.id.btnBack)
        cardLightMode         = findViewById(R.id.cardLightMode)
        cardDarkMode          = findViewById(R.id.cardDarkMode)
        ivDarkModeIcon        = findViewById(R.id.ivDarkModeIcon)
        tvDarkModeLabel       = findViewById(R.id.tvDarkModeLabel)
        tvCurrentTheme        = findViewById(R.id.tvCurrentTheme)
        switchFallDetection   = findViewById(R.id.switchFallDetection)
        tvFallDetectionStatus = findViewById(R.id.tvFallDetectionStatus)
    }


    // Load saved preferences and apply to UI
    private fun loadSavedSettings() {
        isDarkMode        = prefs.getBoolean(KEY_DARK_MODE, false)
        isFallDetectionOn = prefs.getBoolean(KEY_FALL_DETECTION, false)

        applyThemeUI(isDarkMode, animate = false)
        applyFallDetectionUI(isFallDetectionOn)

        switchFallDetection.isChecked = isFallDetectionOn
    }


    // Click listeners
    private fun setClickListeners() {

        btnBack.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }

        cardLightMode.setOnClickListener {
            if (isDarkMode) {
                isDarkMode = false
                prefs.edit().putBoolean(KEY_DARK_MODE, false).apply()
                applyThemeUI(false, animate = true)
                // Apply system-wide — recreates all activities
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }

        cardDarkMode.setOnClickListener {
            if (!isDarkMode) {
                isDarkMode = true
                prefs.edit().putBoolean(KEY_DARK_MODE, true).apply()
                applyThemeUI(true, animate = true)
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
        }

        switchFallDetection.setOnCheckedChangeListener { _, isChecked ->
            isFallDetectionOn = isChecked
            prefs.edit().putBoolean(KEY_FALL_DETECTION, isChecked).apply()
            applyFallDetectionUI(isChecked)
        }
    }

    // Theme UI — highlight active card
    private fun applyThemeUI(darkMode: Boolean, animate: Boolean) {
        val activeColor   = getColor(R.color.primary)
        val inactiveColor = getColor(R.color.surface)
        val activeText    = getColor(R.color.white)
        val inactiveText  = getColor(R.color.text_secondary)

        cardLightMode.setCardBackgroundColor(if (!darkMode) activeColor else inactiveColor)
        cardDarkMode.setCardBackgroundColor (if ( darkMode) activeColor else inactiveColor)
        ivDarkModeIcon.setColorFilter       (if ( darkMode) activeText  else inactiveText)
        tvDarkModeLabel.setTextColor        (if ( darkMode) activeText  else inactiveText)

        tvCurrentTheme.text = if (darkMode) "Dark" else "Light"
    }


    // Fall detection UI
    private fun applyFallDetectionUI(enabled: Boolean) {
        tvFallDetectionStatus.text = if (enabled)
            "ON — Active during walks"
        else
            "OFF — Enable during walks"
        tvFallDetectionStatus.setTextColor(
            getColor(if (enabled) R.color.success else R.color.text_secondary)
        )
    }


    // Back handler
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