// Single activity: permissions in context, Storage Access Framework export/import,
// one-shot GPS for start selection, language-wrapped configuration (fa RTL / en).
package com.della.coveragenavigator

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import della.core.LonLat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val engine get() = NavigatorApp.engine
    private var pendingAfterPermission: (() -> Unit)? = null
    private var exportObservations = false
    private var exportGraph = true

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val fine = res[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasFineLocation()
        engine.onLocationStatus(permissionDenied = !fine, locationDisabled = false, noProvider = false)
        val action = pendingAfterPermission
        pendingAfterPermission = null
        if (fine) action?.invoke()
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val text = engine.exportText(exportObservations, exportGraph)
            val ok = text != null && withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
                } catch (e: Exception) {
                    false
                }
            }
            ui.showMessage(if (ok) getString(R.string.msg_exported) else getString(R.string.msg_export_failed))
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (e: Exception) {
                    null
                }
            }
            if (text == null) {
                ui.showMessage(getString(R.string.msg_import_unreadable))
                return@launch
            }
            val errors = engine.importText(text)
            ui.showMessage(if (errors.isEmpty()) getString(R.string.msg_imported) else getString(R.string.msg_import_rejected, errors.take(3).joinToString(", ")))
        }
    }

    private val ui = UiBridge()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Localized.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AppRoot(engine = engine, bridge = ui, actions = actions)
        }
        // "Where am I" must always be visible: ask for location once at launch.
        if (savedInstanceState == null && !hasFineLocation()) withLocationPermission { startPreviewLocation() }
    }

    // ---- always-on foreground position (all screens); never credits coverage ----
    private val previewListener = object : android.location.LocationListener {
        override fun onLocationChanged(l: android.location.Location) {
            engine.onPreviewFix(
                l.longitude, l.latitude,
                if (l.hasAccuracy()) l.accuracy.toDouble() else null,
                if (l.hasSpeed()) l.speed.toDouble() else null,
                if (l.hasBearing()) l.bearing.toDouble() else null,
            )
        }
        override fun onProviderDisabled(provider: String) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (lm.getProviders(true).isEmpty()) engine.onPreviewState("location_disabled")
        }
        override fun onProviderEnabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    private fun startPreviewLocation() {
        if (!hasFineLocation()) {
            engine.onPreviewState("permission_denied")
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.removeUpdates(previewListener)
        var any = false
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!lm.allProviders.contains(p)) continue
            try {
                lm.requestLocationUpdates(p, 1000L, 0f, previewListener, android.os.Looper.getMainLooper())
                lm.getLastKnownLocation(p)?.let { if (System.currentTimeMillis() - it.time < 120_000) previewListener.onLocationChanged(it) }
                any = true
            } catch (e: Exception) {
                // provider unavailable on this unit
            }
        }
        if (!any) engine.onPreviewState("location_disabled")
    }

    override fun onStart() {
        super.onStart()
        if (hasFineLocation()) startPreviewLocation()
    }

    override fun onStop() {
        super.onStop()
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(previewListener)
    }

    fun requestPreviewLocation() = withLocationPermission { startPreviewLocation() }

    private fun hasFineLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun withLocationPermission(action: () -> Unit) {
        if (hasFineLocation()) {
            action()
            return
        }
        pendingAfterPermission = action
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permissionLauncher.launch(perms.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun oneShotGps() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.allProviders.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.allProviders.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            ui.showMessage(getString(R.string.gps_no_provider))
            return
        }
        if (!lm.isProviderEnabled(provider)) {
            ui.showMessage(getString(R.string.gps_location_disabled))
            return
        }
        ui.showMessage(getString(R.string.msg_waiting_for_gps))
        val handler = Handler(Looper.getMainLooper())
        var done = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (done) return
                if (location.hasAccuracy() && location.accuracy > 50f) return
                done = true
                lm.removeUpdates(this)
                engine.setStartPoint(LonLat(location.longitude, location.latitude), "gps")
            }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}

            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(p: String?, status: Int, extras: Bundle?) {
            }
        }
        try {
            lm.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
        } catch (e: Exception) {
            ui.showMessage(getString(R.string.gps_permission_denied))
            return
        }
        handler.postDelayed({
            if (!done) {
                done = true
                lm.removeUpdates(listener)
                ui.showMessage(getString(R.string.msg_gps_timeout))
            }
        }, 45_000)
    }

    private val actions = object : AppActions {
        override fun showMyLocation() {
            if (hasFineLocation()) engine.centerOnMe() else requestPreviewLocation()
        }
        override fun startNavigation() = withLocationPermission {
            lifecycleScope.launch {
                if (engine.startSession() && !engine.isLabSession()) LocationService.start(this@MainActivity)
            }
        }

        override fun startLabNavigation() {
            lifecycleScope.launch { engine.startSession() }
        }

        override fun resume() {
            val go = {
                lifecycleScope.launch {
                    if (engine.resume() && !engine.isLabSession()) LocationService.start(this@MainActivity)
                }
                Unit
            }
            if (engine.isLabSession()) go() else withLocationPermission { go() }
        }

        override fun pause() {
            engine.pause()
            LocationService.stop(this@MainActivity)
        }

        override fun end() {
            engine.endSession()
            LocationService.stop(this@MainActivity)
        }

        override fun useGpsForStart() = withLocationPermission { oneShotGps() }

        override fun export(includeObservations: Boolean, includeGraph: Boolean) {
            exportObservations = includeObservations
            exportGraph = includeGraph
            exportLauncher.launch("coverage-session-${System.currentTimeMillis() / 1000}.json")
        }

        override fun import() = importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))

        override fun setLanguage(lang: String) {
            Localized.setLanguage(this@MainActivity, lang)
            recreate()
        }
    }
}

interface AppActions {
    fun startNavigation()
    fun startLabNavigation()
    fun resume()
    fun pause()
    fun end()
    fun useGpsForStart()
    fun export(includeObservations: Boolean, includeGraph: Boolean)
    fun import()
    fun setLanguage(lang: String)
    /** Ask for location (if needed) and center the map on the current position. */
    fun showMyLocation()
}

/** Lets the activity push transient messages into the Compose snackbar. */
class UiBridge {
    var sink: ((String) -> Unit)? = null
    fun showMessage(s: String) {
        sink?.invoke(s)
    }
}
