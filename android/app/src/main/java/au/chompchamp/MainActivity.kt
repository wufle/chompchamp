package au.chompchamp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var mapView: MapView
    private lateinit var navText: TextView
    private lateinit var zoneBanner: TextView
    private var map: MapLibreMap? = null
    private var locationActive = false
    private var currentZone: String? = null
    private var barInsets = androidx.core.graphics.Insets.NONE
    private val io = Executors.newSingleThreadExecutor()

    private val chartFile: File by lazy { File(filesDir, "charts/wa.pmtiles") }

    private val importChart =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importChartFile(it) }
        }

    // Fallback picker for devices where ACTION_OPEN_DOCUMENT isn't available.
    private val importChartFallback =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { importChartFile(it) }
        }

    private val requestLocation =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it }) {
                map?.style?.let { startLocation(it) }
            } else {
                Toast.makeText(this, R.string.location_needed, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mapView = findViewById(R.id.mapView)
        navText = findViewById(R.id.navText)
        zoneBanner = findViewById(R.id.zoneBanner)
        mapView.onCreate(savedInstanceState)

        // targetSdk 35 forces edge-to-edge on Android 15+: keep controls clear of
        // the status/navigation bars or taps on them go to the system instead.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.overlay)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            barInsets = bars
            applyMapUiMargins()
            insets
        }

        findViewById<FloatingActionButton>(R.id.btnLocate).setOnClickListener { followBoat() }
        findViewById<FloatingActionButton>(R.id.btnImport).setOnClickListener { openChartPicker() }

        mapView.getMapAsync { m ->
            map = m
            applyMapUiMargins()
            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(-32.0, 115.6)) // Perth / Rottnest
                .zoom(8.0)
                .build()
            m.addOnMapClickListener { point -> showZoneInfo(point); true }
            loadStyle()
        }

        showDisclaimerIfNeeded()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    /** "Open with ChompChamp" / "Share to ChompChamp" from a file manager or browser. */
    private fun handleIncomingIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri != null) {
            intent?.action = null // don't re-import on rotation
            importChartFile(uri)
        }
    }

    private fun openChartPicker() {
        Toast.makeText(this, R.string.opening_picker, Toast.LENGTH_SHORT).show()
        try {
            importChart.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            try {
                importChartFallback.launch("*/*")
            } catch (e2: Exception) {
                Toast.makeText(this, getString(R.string.import_failed, e2.message ?: e2.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Keep MapLibre's compass, logo and attribution clear of our buttons and the system bars. */
    private fun applyMapUiMargins() {
        val ui = map?.uiSettings ?: return
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        ui.compassGravity = Gravity.TOP or Gravity.END
        // Sit below the top-right + button (mini FAB 40dp + 16dp margins).
        ui.setCompassMargins(0, barInsets.top + (72 * dp).toInt(), barInsets.right + pad, 0)
        ui.setLogoMargins(barInsets.left + pad, 0, 0, barInsets.bottom + pad)
        ui.setAttributionMargins(barInsets.left + (100 * dp).toInt(), 0, 0, barInsets.bottom + pad)
    }

    // ---- Chart style -------------------------------------------------------

    private fun loadStyle() {
        val m = map ?: return
        val json = if (chartFile.exists()) {
            assets.open("chart-style.json").bufferedReader().use { it.readText() }
                .replace("{{CHART_URL}}", "pmtiles://file://${chartFile.absolutePath}")
        } else {
            Toast.makeText(this, R.string.no_chart, Toast.LENGTH_LONG).show()
            EMPTY_STYLE
        }
        m.setStyle(Style.Builder().fromJson(json)) { style -> startLocation(style) }
    }

    private fun importChartFile(uri: Uri) {
        Toast.makeText(this, R.string.importing, Toast.LENGTH_SHORT).show()
        io.execute {
            val result = runCatching {
                chartFile.parentFile?.mkdirs()
                val tmp = File(chartFile.parentFile, "import.tmp")
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "cannot open file" }
                    tmp.outputStream().use { input.copyTo(it, 1 shl 20) }
                }
                require(tmp.length() > 0) { "file is empty" }
                val magic = ByteArray(7).also { b -> tmp.inputStream().use { it.read(b) } }
                if (String(magic, Charsets.US_ASCII) != "PMTiles") {
                    tmp.delete()
                    error("that isn't a .pmtiles chart file")
                }
                if (!tmp.renameTo(chartFile)) error("could not save chart")
            }
            runOnUiThread {
                result.onSuccess {
                    Toast.makeText(this, R.string.import_done, Toast.LENGTH_SHORT).show()
                    locationActive = false
                    loadStyle()
                }.onFailure {
                    Toast.makeText(this, getString(R.string.import_failed, it.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---- GPS ---------------------------------------------------------------

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocation(style: Style) {
        val m = map ?: return
        if (!hasLocationPermission()) {
            requestLocation.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }
        val lc = m.locationComponent
        lc.activateLocationComponent(
            LocationComponentActivationOptions.builder(this, style)
                .useDefaultLocationEngine(false) // we feed fixes from LocationManager ourselves
                .build()
        )
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.GPS
        locationActive = true
        requestUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        if (!hasLocationPermission()) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        lm.removeUpdates(this)
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (lm.isProviderEnabled(provider)) {
                lm.requestLocationUpdates(provider, 1000L, 0f, this)
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        val m = map ?: return
        if (locationActive) m.locationComponent.forceLocationUpdate(location)
        updateNav(location)
        checkZone(location)
    }

    @Deprecated("Required on API < 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private fun followBoat() {
        val m = map ?: return
        if (!locationActive) {
            m.style?.let { startLocation(it) }
            return
        }
        m.locationComponent.cameraMode = CameraMode.TRACKING_GPS
        m.locationComponent.zoomWhileTracking(13.0)
    }

    private fun updateNav(loc: Location) {
        val sog = if (loc.hasSpeed()) String.format(Locale.US, "%4.1f", loc.speed * MS_TO_KNOTS) else "--.-"
        val cog = if (loc.hasBearing() && loc.speed > 0.5f) String.format(Locale.US, "%03.0f", loc.bearing) else "---"
        navText.text = "SOG $sog kn\nCOG $cog°\n${formatDdm(loc.latitude, true)}\n${formatDdm(loc.longitude, false)}"
    }

    // ---- Fishing zone alerts -------------------------------------------------

    private fun checkZone(loc: Location) {
        val m = map ?: return
        val screen = m.projection.toScreenLocation(LatLng(loc.latitude, loc.longitude))
        val notake = m.queryRenderedFeatures(screen, "zones-notake-fill")
        val restricted = if (notake.isEmpty()) m.queryRenderedFeatures(screen, "zones-restricted-fill") else emptyList()
        val feature = notake.firstOrNull() ?: restricted.firstOrNull()
        val name = feature?.getStringProperty("name")

        if (feature == null) {
            zoneBanner.visibility = View.GONE
            currentZone = null
            return
        }
        zoneBanner.text = if (notake.isNotEmpty()) {
            getString(R.string.no_fishing_banner, name ?: "")
        } else {
            getString(R.string.restricted_banner, name ?: "")
        }
        zoneBanner.setBackgroundColor(if (notake.isNotEmpty()) 0xE6C62828.toInt() else 0xE6EF6C00.toInt())
        zoneBanner.visibility = View.VISIBLE
        if (name != currentZone && notake.isNotEmpty()) buzz()
        currentZone = name
    }

    private fun showZoneInfo(point: LatLng) {
        val m = map ?: return
        val screen = m.projection.toScreenLocation(point)
        val features = m.queryRenderedFeatures(
            screen, "zones-notake-fill", "zones-restricted-fill", "zones-park-fill"
        )
        if (features.isEmpty()) return
        val body = features.joinToString("\n\n") { f ->
            val cls = when (f.getStringProperty("zone_class")) {
                "notake" -> "NO FISHING"
                "restricted" -> "Restricted"
                else -> "Marine park"
            }
            listOfNotNull(
                "$cls: ${f.getStringProperty("name") ?: "?"}",
                f.getStringProperty("detail"),
                f.getStringProperty("source")?.let { "Source: $it" },
                f.getStringProperty("link"),
            ).joinToString("\n")
        }
        AlertDialog.Builder(this).setTitle("Zones here").setMessage(body)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun buzz() {
        val v = getSystemService(Vibrator::class.java) ?: return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
    }

    // ---- Disclaimer ----------------------------------------------------------

    private fun showDisclaimerIfNeeded() {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean(DISCLAIMER_KEY, false)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.disclaimer_title)
            .setMessage(R.string.disclaimer_body)
            .setCancelable(false)
            .setPositiveButton(R.string.disclaimer_accept) { _, _ ->
                prefs.edit().putBoolean(DISCLAIMER_KEY, true).apply()
            }
            .show()
    }

    // ---- Lifecycle -----------------------------------------------------------

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume(); if (locationActive) requestUpdates() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    override fun onDestroy() {
        getSystemService(LocationManager::class.java)?.removeUpdates(this)
        io.shutdown()
        mapView.onDestroy()
        super.onDestroy()
    }

    companion object {
        private const val MS_TO_KNOTS = 1.943844f
        private const val DISCLAIMER_KEY = "disclaimer_v1"
        private const val EMPTY_STYLE =
            """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#dfe9f2"}}]}"""

        fun formatDdm(value: Double, isLat: Boolean): String {
            val hemi = if (isLat) (if (value < 0) "S" else "N") else (if (value < 0) "W" else "E")
            val a = abs(value)
            val deg = a.toInt()
            val min = (a - deg) * 60.0
            return if (isLat) String.format(Locale.US, "%02d°%06.3f'%s", deg, min, hemi)
            else String.format(Locale.US, "%03d°%06.3f'%s", deg, min, hemi)
        }
    }
}
