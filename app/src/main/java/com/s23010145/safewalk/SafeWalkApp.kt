package com.s23010145.safewalk

import android.app.Application

class SafeWalkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply saved theme (dark/light) on every app start
        SettingsActivity.applyTheme(this)
    }
}