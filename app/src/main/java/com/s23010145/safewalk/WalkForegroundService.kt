package com.s23010145.safewalk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class WalkForegroundService : Service() {

    companion object {
        const val CHANNEL_ID      = "safewalk_walk_channel"
        const val NOTIFICATION_ID = 1001

        // Actions sent via startService(intent)
        const val ACTION_START = "ACTION_START_WALK"
        const val ACTION_STOP  = "ACTION_STOP_WALK"
    }

    private val binder = WalkBinder()

    inner class WalkBinder : Binder() {
        fun getService(): WalkForegroundService = this@WalkForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // Start / Stop commands
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startWalkTracking()
            ACTION_STOP  -> stopWalkTracking()
        }
        return START_STICKY   // restart if killed by the OS
    }

    private fun startWalkTracking() {
        createNotificationChannel()

        val notification = buildNotification()

        // API 34+ requires passing the foreground service type explicitly
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            // Matches android:foregroundServiceType="location" in the manifest
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            else
                0
        )
    }

    private fun stopWalkTracking() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // Notification
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Walk Tracking",
            NotificationManager.IMPORTANCE_LOW      // LOW = no sound, just icon
        ).apply {
            description = "Shows while SafeWalk is tracking your walk"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // Tap notification → open RouteActivity
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, RouteActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SafeWalk is active")
            .setContentText("Tracking your walk. Tap to return to navigation.")
            .setSmallIcon(R.drawable.ic_walk)
            .setContentIntent(pendingIntent)
            .setOngoing(true)           // user cannot swipe away
            .setSilent(true)
            .build()
    }
}