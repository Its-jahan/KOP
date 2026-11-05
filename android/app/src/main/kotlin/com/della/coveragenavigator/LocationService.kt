// Foreground service (type location) started only from the user's Start/Resume action.
// Uses android.location.LocationManager (no Google Play Services): GPS_PROVIDER, falling
// back to NETWORK_PROVIDER when no GPS provider exists. Never restarts itself after a
// process death or reboot (START_NOT_STICKY): resuming is always a user action.
package com.della.coveragenavigator

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import della.core.LocationObservation

class LocationService : Service() {
    private var lm: LocationManager? = null
    private var provider: String? = null
    private val wallAnchor = System.currentTimeMillis()
    private val elapsedAnchor = SystemClock.elapsedRealtime()

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = deliver(location)
        override fun onProviderEnabled(p: String) = reportStatus()
        override fun onProviderDisabled(p: String) = reportStatus()

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(p: String?, status: Int, extras: Bundle?) {
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val notification = buildNotification(this, null, "active")
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            // e.g. permission revoked between the user's tap and service start.
            NavigatorApp.engine.onLocationStatus(permissionDenied = true, locationDisabled = false, noProvider = false)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        startUpdates()
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            NavigatorApp.engine.onLocationStatus(permissionDenied = true, locationDisabled = false, noProvider = false)
            return
        }
        val m = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = m
        val all = m.allProviders
        provider = when {
            all.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            all.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        val p = provider
        if (p == null) {
            NavigatorApp.engine.onLocationStatus(permissionDenied = false, locationDisabled = false, noProvider = true)
            return
        }
        try {
            m.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            NavigatorApp.engine.onLocationStatus(permissionDenied = true, locationDisabled = false, noProvider = false)
            return
        } catch (e: IllegalArgumentException) {
            NavigatorApp.engine.onLocationStatus(permissionDenied = false, locationDisabled = false, noProvider = true)
            return
        }
        reportStatus()
    }

    private fun reportStatus() {
        val m = lm ?: return
        val p = provider ?: return
        val enabled = try {
            m.isProviderEnabled(p)
        } catch (e: Exception) {
            false
        }
        NavigatorApp.engine.onLocationStatus(permissionDenied = false, locationDisabled = !enabled, noProvider = false)
    }

    private fun deliver(l: Location) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val fixElapsedMs = l.elapsedRealtimeNanos / 1_000_000
        val age = (nowElapsed - fixElapsedMs).coerceAtLeast(0)
        // Timestamp on a monotonic wall-clock anchor: immune to head-unit clock jumps.
        val ts = wallAnchor + (fixElapsedMs - elapsedAnchor)
        val obs = LocationObservation(
            observationId = "o$ts",
            timestamp = ts,
            monotonicMs = fixElapsedMs.toDouble(),
            lon = l.longitude,
            lat = l.latitude,
            horizontalAccuracyMeters = if (l.hasAccuracy()) l.accuracy.toDouble() else null,
            speedMps = if (l.hasSpeed()) l.speed.toDouble() else null,
            speedAccuracyMps = if (Build.VERSION.SDK_INT >= 26 && l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond.toDouble() else null,
            bearingDeg = if (l.hasBearing()) l.bearing.toDouble().let { if (it >= 360.0) it - 360.0 else it } else null,
            bearingAccuracyDeg = if (Build.VERSION.SDK_INT >= 26 && l.hasBearingAccuracy()) l.bearingAccuracyDegrees.toDouble() else null,
            altitudeMeters = if (l.hasAltitude()) l.altitude else null,
            altitudeAccuracyMeters = if (Build.VERSION.SDK_INT >= 26 && l.hasVerticalAccuracy()) l.verticalAccuracyMeters.toDouble() else null,
            source = if (l.provider == LocationManager.GPS_PROVIDER) "gps" else "network",
            ageMs = age.toDouble(),
        )
        NavigatorApp.engine.onFix(obs, l.provider ?: provider ?: "?", nowElapsed - age)
    }

    override fun onDestroy() {
        running = false
        try {
            lm?.removeUpdates(listener)
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.della.coveragenavigator.START"
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 42

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationService::class.java))
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val ctx = Localized.wrap(context)
                    nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, ctx.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
                }
            }
        }

        private fun buildNotification(context: Context, percent: Double?, status: String): Notification {
            val ctx = Localized.wrap(context)
            val pi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0),
            )
            val text = if (percent == null) ctx.getString(R.string.notif_text_starting)
            else ctx.getString(if (status == "active") R.string.notif_text_active else R.string.notif_text_paused, percent)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.notif_title))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build()
        }

        fun updateNotification(context: Context, percent: Double, status: String) {
            if (!running) return
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(context, percent, status))
            } catch (e: Exception) {
                // Notification permission may be denied on 33+; the service keeps running.
            }
        }
    }
}
