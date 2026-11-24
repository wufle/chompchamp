package au.chompchamp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PointF
import android.graphics.RectF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var mapView: MapView
    private lateinit var zoneBanner: TextView
    private lateinit var fields: DataFieldsOverlay
    private lateinit var layers: MapLayers
    private lateinit var settings: Settings
    private lateinit var store: Store
    private lateinit var editBar: View
    private lateinit var modeButton: TextView
    private lateinit var look: Appearance
    private lateinit var panel: SettingsPanel
    private lateinit var controlButtons: List<ImageButton>
    private lateinit var modePanel: View
    private lateinit var modeSummary: TextView
    private lateinit var modeButtons: LinearLayout

    private var map: MapLibreMap? = null
    private val overlays = NavOverlays()
    private val hazards by lazy { Hazards(this) }
    private lateinit var navigator: Navigator
    private val drift = DriftTracker()
    private lateinit var track: Track
    private lateinit var marks: MutableList<Mark>
    private lateinit var plan: Route
    private lateinit var userData: UserData
    private lateinit var photos: Photos
    /** Mark waiting for a photo from the camera / gallery. */
    private var photoTarget: String? = null
    private var cameraFile: File? = null
    /** What the next "create document" result should contain: (isGpx, parts). */
    private var pendingExport: Pair<Boolean, Set<DataPart>>? = null

    private var mode = AppMode.CHART
    private var locationActive = false
    private var lastFix: Location? = null
    private var lastNav: NavData? = null
    private var currentZone: String? = null
    private var anchor: LatLon? = null
    private var lastAnchorBuzz = 0L
    private var movingWaypoint: Int? = null
    private var barInsets = androidx.core.graphics.Insets.NONE
    private val io = Executors.newSingleThreadExecutor()

    private val chartFile: File by lazy { File(filesDir, "charts/wa.pmtiles") }
    private val reliefFile: File by lazy { File(filesDir, "charts/relief.pmtiles") }

    private val importChart =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importChartFile(it) } }

    // Fallback picker for devices where ACTION_OPEN_DOCUMENT isn't available.
    private val importChartFallback =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { importChartFile(it) } }

    private val exportJsonFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { writeExport(it) } }
    private val exportGpxFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri -> uri?.let { writeExport(it) } }
    private val importDataFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { readImport(it) } }

    private val takePhoto =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val f = cameraFile
            cameraFile = null
            if (ok && f != null) attachPhoto(Uri.fromFile(f)) { f.delete() } else f?.delete()
        }
    private val pickPhoto =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { attachPhoto(it) } }

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

        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        settings = Settings(prefs)
        layers = MapLayers(prefs)
        store = Store(File(filesDir, "data"))
        navigator = Navigator(settings)
        track = Track(store.track())
        marks = store.marks()
        plan = store.plan()
        userData = UserData(File(filesDir, "data"), store, prefs)
        photos = Photos(this)

        mapView = findViewById(R.id.mapView)
        zoneBanner = findViewById(R.id.zoneBanner)
        look = Appearance(this, prefs)
        panel = SettingsPanel(findViewById(R.id.panelHost), look)
        fields = DataFieldsOverlay(findViewById<FrameLayout>(R.id.fieldsContainer), prefs, look) { f -> fieldOptions(f) }
        editBar = findViewById(R.id.editBar)
        modeButton = findViewById(R.id.btnMode)
        modePanel = findViewById(R.id.modePanel)
        modeSummary = findViewById(R.id.modeSummary)
        modeButtons = findViewById(R.id.modeButtons)
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

        controlButtons = listOf(
            findViewById(R.id.btnMenu), findViewById(R.id.btnLayers), findViewById(R.id.btnLocate))
        findViewById<View>(R.id.btnLocate).setOnClickListener { followBoat() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        findViewById<View>(R.id.btnLayers).setOnClickListener {
            if (panel.isOpen) panel.hide() else showLayersPanel()
        }
        layers.onReset = { showLayersPanel() }
        // Back closes the settings panel / layout editor before leaving the app.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    panel.isOpen -> panel.hide()
                    fields.editing -> setEditing(false)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })
        findViewById<View>(R.id.btnDoneEditing).setOnClickListener { setEditing(false) }
        modeButton.setOnClickListener { showModePicker(it) }
        applyAppearance()
        // Tap the alert banner for full zone details (with links to the legal orders).
        zoneBanner.setOnClickListener {
            lastFix?.let { showZoneInfo(LatLng(it.latitude, it.longitude), always = true) }
        }

        mapView.getMapAsync { m ->
            map = m
            applyMapUiMargins()
            m.cameraPosition = CameraPosition.Builder()
                .target(LatLng(-32.0, 115.6)) // Perth / Rottnest
                .zoom(8.0)
                .build()
            m.addOnMapClickListener { p -> onMapTap(p); true }
            m.addOnMapLongClickListener { p -> onMapLongPress(p); true }
            loadStyle()
        }

        setMode(AppMode.CHART)
        handleIncomingIntent(intent)
    }

    // =========================================================================
    // Modes
    // =========================================================================

    private fun showModePicker(anchorView: View) {
        val popup = PopupMenu(this, anchorView)
        AppMode.entries.forEachIndexed { i, m -> popup.menu.add(0, i, i, m.label) }
        popup.setOnMenuItemClickListener { setMode(AppMode.entries[it.itemId]); true }
        popup.show()
    }

    private fun setMode(m: AppMode) {
        if (m == AppMode.NAVIGATE && !navigator.isActive) {
            Toast.makeText(this, "Plan a route first (Route mode), or long-press the map → Navigate here",
                Toast.LENGTH_LONG).show()
            return
        }
        if (fields.editing) setEditing(false)
        mode = m
        movingWaypoint = null
        modeButton.text = "${m.label} ▾"
        fields.mode = m
        val lc = map?.locationComponent?.takeIf { locationActive }
        when (m) {
            AppMode.CHART -> Unit
            AppMode.ROUTE -> lc?.cameraMode = CameraMode.NONE
            AppMode.NAVIGATE -> followBoat()
            AppMode.FISH -> { drift.reset(); followBoat(settings.fishZoom) }
        }
        refreshOverlays()
        refreshModePanel()
    }

    private fun refreshOverlays() {
        when {
            mode == AppMode.ROUTE -> overlays.setRoute(plan)
            navigator.isActive -> overlays.setRoute(navigator.route, navigator.activeIndex)
            else -> overlays.setRoute(null)
        }
        if (!navigator.isActive) overlays.setNavLine(null, null)
        if (mode != AppMode.FISH) overlays.setDrift(null, null)
        showMarks()
        overlays.setAnchor(anchor, settings.anchorRadiusM)
        overlays.setTrack(visibleTrack())
    }

    private fun refreshModePanel() {
        val buttons: List<Pair<String, () -> Unit>>
        val summary: String
        when (mode) {
            AppMode.CHART -> { modePanel.visibility = View.GONE; return }
            AppMode.ROUTE -> {
                summary = routeSummary()
                buttons = listOf(
                    "Undo" to { undoWaypoint() },
                    "Clear" to { confirm("Clear the route?") { plan.waypoints.clear(); planChanged() } },
                    "Routes" to { showRoutesMenu(modeButtons) },
                    "Check" to { checkRoute() },
                    "Go ▶" to { startNavigation(plan) },
                )
            }
            AppMode.NAVIGATE -> {
                val n = lastNav
                val t = navigator.target
                summary = if (t != null) {
                    "→ ${t.name.ifBlank { "WP ${navigator.activeIndex + 1}" }}  (${navigator.activeIndex + 1} of ${navigator.route?.waypoints?.size})" +
                        (n?.let { "\n${Geo.fmtNm(it.dtwNm)} nm · ${Geo.fmtBearing(it.bearing)} · ${Geo.fmtNm(it.remainNm)} nm to go" } ?: "")
                } else "No active route"
                buttons = listOf(
                    "◀ Prev" to { if (navigator.previous(lastPos())) navChanged() },
                    "Skip ▶" to { if (navigator.skip()) navChanged() },
                    "Stop" to { confirm("Stop navigating?") { stopNavigation() } },
                )
            }
            AppMode.FISH -> {
                val d = drift.drift()
                summary = (d?.let {
                    "Drift ${String.format(Locale.US, "%.1f", it.first)} kn → ${Geo.fmtBearing(it.second)}  " +
                        "(line shows ${settings.driftProjectMin.toInt()} min)"
                } ?: "Measuring drift…") + (anchor?.let { "\nAnchor alarm ON · ${settings.anchorRadiusM.toInt()} m" } ?: "")
                buttons = listOf(
                    "★ Mark" to { addMarkAtBoat() },
                    (if (anchor == null) "⚓ Anchor" else "⚓ Off") to { toggleAnchor() },
                    "Reset drift" to { drift.reset(); refreshModePanel() },
                    "Marks" to { showMarksList() },
                )
            }
        }
        modePanel.visibility = View.VISIBLE
        modeSummary.text = summary
        if (modeButtons.childCount != buttons.size || modeButtons.tag != mode) {
            modeButtons.removeAllViews()
            modeButtons.tag = mode
            for ((label, _) in buttons) {
                modeButtons.addView(MaterialButton(this, null,
                    com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = label
                    setTextColor(look.accent)
                    textSize = 12.5f * look.controlScale
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(8, 0, 8, 0)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        buttons.forEachIndexed { i, (label, action) ->
            (modeButtons.getChildAt(i) as MaterialButton).apply {
                text = label
                setOnClickListener { action() }
            }
        }
    }

    // =========================================================================
    // Route planner
    // =========================================================================

    private fun routeSummary(): String {
        val n = plan.waypoints.size
        movingWaypoint?.let { return "Tap the new position for waypoint ${it + 1}" }
        if (n == 0) return "Tap the map to add waypoints. Tap a waypoint to edit it."
        val len = plan.lengthNm()
        val name = plan.name.ifBlank { "Unsaved route" }
        return "$name · $n waypoints · ${Geo.fmtNm(len)} nm · " +
            "${Geo.fmtDuration(len / settings.planSpeedKn)} @ ${settings.planSpeedKn.toInt()} kn"
    }

    private fun planChanged() {
        store.savePlan(plan)
        refreshOverlays()
        refreshModePanel()
    }

    private fun undoWaypoint() {
        if (plan.waypoints.isNotEmpty()) {
            plan.waypoints.removeAt(plan.waypoints.lastIndex)
            planChanged()
        }
    }

    private fun routeTap(point: LatLng) {
        movingWaypoint?.let { i ->
            plan.waypoints.getOrNull(i)?.apply { lat = point.latitude; lon = point.longitude }
            movingWaypoint = null
            planChanged()
            return
        }
        val hit = hitFeature(point, NavOverlays.ROUTE_WPTS)
        if (hit != null) {
            val i = hit.getNumberProperty("index")?.toInt() ?: return
            waypointMenu(i)
            return
        }
        plan.waypoints.add(Waypoint(point.latitude, point.longitude, "WP${plan.waypoints.size + 1}"))
        planChanged()
    }

    private fun waypointMenu(i: Int) {
        val w = plan.waypoints[i]
        val legInfo = if (i > 0) {
            val prev = plan.waypoints[i - 1]
            "\nLeg from ${prev.name}: ${Geo.fmtNm(Geo.distanceNm(prev.pos, w.pos))} nm @ ${Geo.fmtBearing(Geo.bearing(prev.pos, w.pos))}"
        } else ""
        MaterialAlertDialogBuilder(this)
            .setTitle("${w.name}  (${i + 1} of ${plan.waypoints.size})")
            .setMessage("${Geo.formatDdm(w.lat, true)}  ${Geo.formatDdm(w.lon, false)}$legInfo")
            .setPositiveButton("Move") { _, _ -> movingWaypoint = i; refreshModePanel() }
            .setNeutralButton("Rename") { _, _ ->
                askText("Rename waypoint", w.name) { w.name = it; planChanged() }
            }
            .setNegativeButton("Delete") { _, _ -> plan.waypoints.removeAt(i); planChanged() }
            .show()
    }

    private fun showRoutesMenu(anchorView: View) {
        val popup = PopupMenu(this, anchorView)
        val menu = listOf("Save route as…", "Open saved route…", "Reverse route", "New route", "Delete saved route…")
        menu.forEachIndexed { i, s -> popup.menu.add(0, i, i, s) }
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> askText("Route name", plan.name) { name ->
                    val routes = store.routes()
                    plan.name = name
                    val existing = routes.indexOfFirst { r -> r.name == name }
                    val saved = plan.duplicate(newId = existing < 0)
                    if (existing >= 0) routes[existing] = saved else routes.add(saved)
                    store.saveRoutes(routes)
                    planChanged()
                    Toast.makeText(this, "Saved \"$name\"", Toast.LENGTH_SHORT).show()
                }
                1 -> pickSavedRoute("Open route") { r -> plan = r.duplicate(); planChanged(); zoomToRoute(plan) }
                2 -> { plan.waypoints.reverse(); planChanged() }
                3 -> confirm("Start a new route? Unsaved changes are lost.") { plan = Route(); planChanged() }
                4 -> pickSavedRoute("Delete route") { r ->
                    store.saveRoutes(store.routes().filter { x -> x.id != r.id })
                    Toast.makeText(this, "Deleted \"${r.name}\"", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        popup.show()
    }

    private fun pickSavedRoute(title: String, onPick: (Route) -> Unit) {
        val routes = store.routes()
        if (routes.isEmpty()) { Toast.makeText(this, "No saved routes", Toast.LENGTH_SHORT).show(); return }
        val labels = routes.map { "${it.name} · ${it.waypoints.size} wpts · ${Geo.fmtNm(it.lengthNm())} nm" }
        MaterialAlertDialogBuilder(this).setTitle(title)
            .setItems(labels.toTypedArray()) { _, which -> onPick(routes[which]) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun zoomToRoute(r: Route) {
        val m = map ?: return
        if (r.waypoints.size < 2) return
        val b = LatLngBounds.Builder().includes(r.waypoints.map { LatLng(it.lat, it.lon) }).build()
        m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 160))
    }

    /**
     * Sample each leg against the chart for land, shallow water and no-fishing zones.
     * Uses rendered tiles, so we first fit the whole route on screen.
     */
    private fun checkRoute() {
        if (plan.waypoints.size < 2) { Toast.makeText(this, "Add at least two waypoints", Toast.LENGTH_SHORT).show(); return }
        if (!chartFile.exists()) { Toast.makeText(this, R.string.no_chart, Toast.LENGTH_SHORT).show(); return }
        zoomToRoute(plan)
        Toast.makeText(this, "Checking route…", Toast.LENGTH_SHORT).show()
        mapView.addOnDidBecomeIdleListener(object : MapView.OnDidBecomeIdleListener {
            override fun onDidBecomeIdle() {
                mapView.removeOnDidBecomeIdleListener(this)
                runRouteCheck()
            }
        })
    }

    private fun runRouteCheck() {
        val m = map ?: return
        val safety = settings.safetyDepthM
        val issues = mutableListOf<String>()
        plan.waypoints.zipWithNext().forEachIndexed { leg, (a, b) ->
            val pa = m.projection.toScreenLocation(LatLng(a.lat, a.lon))
            val pb = m.projection.toScreenLocation(LatLng(b.lat, b.lon))
            val steps = (Math.hypot((pb.x - pa.x).toDouble(), (pb.y - pa.y).toDouble()) / 3).toInt().coerceIn(2, 2000)
            var land = false
            var unknown = false
            var shallowest: Int? = null
            val zones = linkedSetOf<String>()
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                val p = PointF(pa.x + (pb.x - pa.x) * t, pa.y + (pb.y - pa.y) * t)
                val onLand = m.queryRenderedFeatures(p, "land").isNotEmpty()
                if (onLand) land = true
                val d = m.queryRenderedFeatures(p, "depth-fill").firstOrNull()?.getNumberProperty("dmin")?.toInt()
                if (d == null) { if (!onLand) unknown = true }
                else if (shallowest == null || d < shallowest!!) shallowest = d
                m.queryRenderedFeatures(p, "zones-notake-fill").forEach { f ->
                    f.getStringProperty("name")?.let { zones.add(it) }
                }
            }
            val label = "Leg ${leg + 1} (${a.name} → ${b.name})"
            if (land) issues.add("⛔ $label crosses LAND")
            shallowest?.let { if (it < safety) issues.add("⚠ $label: depth may be under ${safety.toInt()} m (shallowest band from $it m)") }
            if (unknown) issues.add("⚠ $label: no depth data for part of this leg")
            zones.forEach { issues.add("ℹ $label passes through no-fishing zone: $it (transit OK, no fishing)") }
        }
        val body = (if (issues.isEmpty()) "No land, shallow water (< ${safety.toInt()} m) or no-fishing zones found on the route."
        else issues.joinToString("\n\n")) +
            "\n\nDepths are survey data around Perth and a 250 m model elsewhere, measured from about mean sea level: " +
            "expect less water at low tide. Small reefs, rocks and bars can be missed. Check official charts."
        MaterialAlertDialogBuilder(this).setTitle("Route check").setMessage(body)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    // =========================================================================
    // Navigation
    // =========================================================================

    private fun startNavigation(r: Route) {
        if (r.waypoints.isEmpty()) { Toast.makeText(this, "Add waypoints first", Toast.LENGTH_SHORT).show(); return }
        navigator.start(r, lastPos())
        lastNav = null
        setMode(AppMode.NAVIGATE)
        lastFix?.let { onLocationChanged(it) }
    }

    private fun stopNavigation() {
        navigator.stop()
        lastNav = null
        setMode(AppMode.CHART)
    }

    private fun navChanged() {
        lastNav = null
        lastFix?.let { onLocationChanged(it) } ?: run { refreshOverlays(); refreshModePanel() }
    }

    // =========================================================================
    // Fishing: marks, anchor alarm
    // =========================================================================

    private fun addMarkAtBoat() {
        val loc = lastFix ?: run { Toast.makeText(this, "Waiting for GPS fix", Toast.LENGTH_SHORT).show(); return }
        addMark(LatLon(loc.latitude, loc.longitude), estimateDepthAt(LatLng(loc.latitude, loc.longitude)))
    }

    private fun showMarks() = overlays.setMarks(marks) { id -> photos.bubble(id) }

    private fun addMark(p: LatLon, depth: String?) {
        val name = "Mark ${marks.size + 1}"
        val mark = Mark(lat = p.lat, lon = p.lon, name = name,
            depth = depth?.takeIf { it != "--" && it != "off screen" })
        marks.add(mark)
        store.saveMarks(marks)
        showMarks()
        buzz(short = true)
        // Quick photo of the catch straight after marking.
        Snackbar.make(mapView, "Saved $name", Snackbar.LENGTH_LONG)
            .setAction("📷 Photo") { takePhotoFor(mark) }
            .setActionTextColor(look.accent)
            .show()
    }

    // ---- Mark photos -------------------------------------------------------------------

    private fun takePhotoFor(mark: Mark) {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        photoTarget = mark.id
        cameraFile = f
        try {
            takePhoto.launch(uri)
        } catch (e: Exception) {
            cameraFile = null
            Toast.makeText(this, "No camera app found", Toast.LENGTH_LONG).show()
        }
    }

    private fun pickPhotoFor(mark: Mark) {
        photoTarget = mark.id
        pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(
            ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun attachPhoto(uri: Uri, cleanup: () -> Unit = {}) {
        val markId = photoTarget ?: return
        photoTarget = null
        Toast.makeText(this, "Adding photo…", Toast.LENGTH_SHORT).show()
        io.execute {
            val result = runCatching { photos.import(uri) }
            cleanup()
            runOnUiThread {
                result.onSuccess { name ->
                    val mark = marks.firstOrNull { it.id == markId }
                    if (mark == null) { photos.delete(name); return@onSuccess }
                    mark.photos.add(name)
                    store.saveMarks(marks)
                    showMarks()
                    markMenu(mark)
                }.onFailure {
                    Toast.makeText(this, "Couldn't add photo: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Full-size photo viewer with previous / next / delete. */
    private fun showPhoto(mark: Mark, index: Int) {
        if (mark.photos.isEmpty()) return
        val i = index.coerceIn(0, mark.photos.lastIndex)
        val name = mark.photos[i]
        val maxPx = resources.displayMetrics.widthPixels
        val image = android.widget.ImageView(this).apply {
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setImageBitmap(photos.loadScaled(name, maxPx))
        }
        val taken = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault())
            .format(Date(photos.file(name).lastModified()))
        val b = MaterialAlertDialogBuilder(this)
            .setTitle("${mark.name} · ${i + 1} of ${mark.photos.size}")
            .setMessage(taken)
            .setView(image)
            .setPositiveButton("Close") { _, _ -> markMenu(mark) }
            .setNeutralButton("Delete") { _, _ ->
                confirm("Delete this photo?") {
                    mark.photos.removeAt(i)
                    photos.delete(name)
                    store.saveMarks(marks)
                    showMarks()
                    if (mark.photos.isNotEmpty()) showPhoto(mark, i) else markMenu(mark)
                }
            }
        if (mark.photos.size > 1) b.setNegativeButton("Next ▶") { _, _ -> showPhoto(mark, (i + 1) % mark.photos.size) }
        b.show()
    }

    private fun markMenu(mark: Mark) {
        val dp = resources.displayMetrics.density
        val time = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(Date(mark.time))
        val dist = lastPos()?.let { "\n${Geo.fmtNm(Geo.distanceNm(it, mark.pos))} nm @ ${Geo.fmtBearing(Geo.bearing(it, mark.pos))} from boat" } ?: ""
        val pad = (24 * dp).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (4 * dp).toInt(), pad, 0)
        }
        body.addView(TextView(this).apply {
            text = "$time\n${Geo.formatDdm(mark.lat, true)}  ${Geo.formatDdm(mark.lon, false)}" +
                (mark.depth?.let { "\nDepth (est.): $it m" } ?: "") + dist
            textSize = 14f
            setTextColor(look.muted)
        })
        lateinit var dialog: androidx.appcompat.app.AlertDialog

        // Photo strip
        if (mark.photos.isNotEmpty()) {
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val size = (72 * dp).toInt()
            mark.photos.forEachIndexed { i, name ->
                strip.addView(android.widget.ImageView(this).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(photos.loadScaled(name, 256))
                    background = look.surfaceBg(1f, 8f)
                    clipToOutline = true
                    setOnClickListener { dialog.dismiss(); showPhoto(mark, i) }
                }, LinearLayout.LayoutParams(size, size).apply { marginEnd = (6 * dp).toInt() })
            }
            body.addView(android.widget.HorizontalScrollView(this).apply {
                setPadding(0, (12 * dp).toInt(), 0, 0)
                addView(strip)
            })
        }

        // Actions
        val actions: List<Pair<String, () -> Unit>> = listOf(
            "Navigate to" to {
                startNavigation(Route(name = mark.name, waypoints = mutableListOf(Waypoint(mark.lat, mark.lon, mark.name))))
            },
            "📷  Take photo" to { takePhotoFor(mark) },
            "🖼  Add photo from gallery" to { pickPhotoFor(mark) },
            "Rename" to { askText("Rename mark", mark.name) { mark.name = it; store.saveMarks(marks); showMarks() } },
            "Delete mark" to {
                confirm("Delete ${mark.name}" + (if (mark.photos.isNotEmpty()) " and its ${mark.photos.size} photo(s)" else "") + "?") {
                    mark.photos.forEach { photos.delete(it) }
                    marks.remove(mark); store.saveMarks(marks); showMarks()
                }
            },
        )
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        for ((label, action) in actions) {
            list.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = label
                isAllCaps = false
                textSize = 15f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(if (label == "Delete mark") look.danger else look.accent)
                setOnClickListener { dialog.dismiss(); action() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        body.addView(list)
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(mark.name)
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
    }

    private fun showMarksList() {
        if (marks.isEmpty()) { Toast.makeText(this, "No marks yet", Toast.LENGTH_SHORT).show(); return }
        val here = lastPos()
        val sorted = if (here != null) marks.sortedBy { Geo.distanceNm(here, it.pos) } else marks.sortedByDescending { it.time }
        val labels = sorted.map { m ->
            m.name + (here?.let { " · ${Geo.fmtNm(Geo.distanceNm(it, m.pos))} nm" } ?: "") + (m.depth?.let { " · $it m" } ?: "")
        }
        MaterialAlertDialogBuilder(this).setTitle("Marks")
            .setItems(labels.toTypedArray()) { _, which ->
                val m = sorted[which]
                map?.locationComponent?.takeIf { locationActive }?.cameraMode = CameraMode.NONE
                map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(m.lat, m.lon)))
                markMenu(m)
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun toggleAnchor() {
        if (anchor != null) {
            anchor = null
        } else {
            anchor = lastPos() ?: run { Toast.makeText(this, "Waiting for GPS fix", Toast.LENGTH_SHORT).show(); return }
            Toast.makeText(this, "Anchor alarm set: ${settings.anchorRadiusM.toInt()} m", Toast.LENGTH_SHORT).show()
        }
        overlays.setAnchor(anchor, settings.anchorRadiusM)
        refreshModePanel()
        updateBanner()
    }

    // =========================================================================
    // Map interaction
    // =========================================================================

    private fun hitFeature(point: LatLng, layer: String): org.maplibre.geojson.Feature? {
        val m = map ?: return null
        val s = m.projection.toScreenLocation(point)
        val r = 24 * resources.displayMetrics.density
        return m.queryRenderedFeatures(RectF(s.x - r, s.y - r, s.x + r, s.y + r), layer).firstOrNull()
    }

    private fun onMapTap(point: LatLng) {
        if (fields.editing) return
        if (mode == AppMode.ROUTE) { routeTap(point); return }
        (hitFeature(point, NavOverlays.MARK_PHOTOS) ?: hitFeature(point, NavOverlays.MARKS))?.getStringProperty("id")?.let { id ->
            marks.firstOrNull { it.id == id }?.let { markMenu(it); return }
        }
        hitFeature(point, Hazards.LAYER)?.let { showHazard(it); return }
        showZoneInfo(point)
    }

    private fun onMapLongPress(point: LatLng) {
        if (fields.editing || mode == AppMode.ROUTE) return
        val depth = estimateDepthAt(point)
        val p = LatLon(point.latitude, point.longitude)
        val dist = lastPos()?.let { "\n${Geo.fmtNm(Geo.distanceNm(it, p))} nm @ ${Geo.fmtBearing(Geo.bearing(it, p))} from boat" } ?: ""
        MaterialAlertDialogBuilder(this)
            .setTitle("${Geo.formatDdm(p.lat, true)}  ${Geo.formatDdm(p.lon, false)}")
            .setMessage("Depth (est.): $depth m$dist")
            .setPositiveButton("Navigate here") { _, _ ->
                startNavigation(Route(name = "Go to", waypoints = mutableListOf(Waypoint(p.lat, p.lon, "Target"))))
            }
            .setNeutralButton("Add mark") { _, _ -> addMark(p, depth) }
            .setNegativeButton("Zones here") { _, _ -> showZoneInfo(point, always = true) }
            .show()
    }

    private fun showZoneInfo(point: LatLng, always: Boolean = false) {
        val m = map ?: return
        val screen = m.projection.toScreenLocation(point)
        val features = m.queryRenderedFeatures(screen, "zones-notake-fill", "zones-restricted-fill", "zones-park-fill")
        if (features.isEmpty()) {
            if (always) Toast.makeText(this, "No zones here", Toast.LENGTH_SHORT).show()
            return
        }
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
        showLinkDialog("Zones here", body)
    }

    /** Message dialog whose URLs are tappable (open in the browser / PDF viewer). */
    /** Details for a tapped navigation mark, wreck or rock. */
    private fun showHazard(f: org.maplibre.geojson.Feature) {
        val kind = f.getStringProperty("kind")
        val pt = f.geometry() as? org.maplibre.geojson.Point ?: return
        val p = LatLon(pt.latitude(), pt.longitude())
        val name = f.getStringProperty("name").orEmpty()
        val detail = f.getStringProperty("detail").orEmpty()
        val fromDot = f.getStringProperty("src") == "dot"
        val lines = listOfNotNull(
            Hazards.describe(kind),
            detail.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() },
            if (f.hasProperty("virtual")) "Virtual mark: AIS only, nothing physical in the water" else null,
            f.getStringProperty("owner")?.let { "Maintained by: $it" },
            "",
            "${Geo.formatDdm(p.lat, true)}  ${Geo.formatDdm(p.lon, false)}",
            lastPos()?.let { "${Geo.fmtNm(Geo.distanceNm(it, p))} nm @ ${Geo.fmtBearing(Geo.bearing(it, p))} from boat" },
            "",
            if (fromDot) "Source: WA Department of Transport, Navigation Aids (DOT-004), CC BY 4.0"
            else "Source: © OpenStreetMap contributors (OpenSeaMap), ODbL. Position may be approximate.",
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(name.ifBlank { Hazards.describe(kind).substringBefore(" (").substringBefore(":") })
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton("Navigate here") { _, _ ->
                startNavigation(Route(name = "Go to", waypoints = mutableListOf(
                    Waypoint(p.lat, p.lon, name.ifBlank { "Mark" }.take(24)))))
            }
            .show()
    }

    private fun showLinkDialog(title: String, body: String) {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            setText(body)
            textSize = 15f
            setPadding(pad, pad / 2, pad, 0)
            Linkify.addLinks(this, Linkify.WEB_URLS)
            movementMethod = LinkMovementMethod.getInstance()
        }
        MaterialAlertDialogBuilder(this).setTitle(title)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton(android.R.string.ok, null).show()
    }

    // =========================================================================
    // Menu & layout settings
    // =========================================================================

    private fun showMenu(anchorView: View) {
        val popup = PopupMenu(this, anchorView)
        val items: List<Pair<String, () -> Unit>> = listOf(
            "Charts & relief data…" to { showChartManager() },
            "Data fields for ${mode.label} mode…" to { showFieldsDialog() },
            "Group data fields…" to { showGroupingDialog() },
            "Edit layout (${mode.label})" to { setEditing(true) },
            "Appearance (size & transparency)…" to { showAppearancePanel() },
            "Reset ${mode.label} layout" to {
                fields.resetLayout(); Toast.makeText(this, R.string.layout_reset_done, Toast.LENGTH_SHORT).show()
            },
            "Settings…" to { settings.show(this) { onSettingsChanged() } },
            "Marks…" to { showMarksList() },
            "Data: save, load, export, import…" to { showDataMenu() },
            "Reset trip & clear track" to {
                confirm("Clear the track and reset the trip log?") {
                    track.clear(); store.saveTrack(track.points); overlays.setTrack(visibleTrack())
                }
            },
        )
        items.forEachIndexed { i, (label, _) -> popup.menu.add(0, i, i, label) }
        popup.setOnMenuItemClickListener { items[it.itemId].second(); true }
        popup.show()
    }

    private var fixCount = 0

    /** Track for the chosen history window, always joined up to the boat's current position. */
    private fun visibleTrack(): List<LatLon> {
        val pts = track.window(settings.trackWindowMin)
        val here = lastPos() ?: return pts
        return if (pts.isEmpty() || pts.last() != here) pts + here else pts
    }

    private fun onSettingsChanged() {
        overlays.setTrack(visibleTrack())
        overlays.setAnchor(anchor, settings.anchorRadiusM)
        refreshModePanel()
        if (mode == AppMode.NAVIGATE || mode == AppMode.FISH) {
            followBoat(if (mode == AppMode.FISH) settings.fishZoom else null)
        }
    }

    private fun showFieldsDialog() {
        val all = DataField.entries
        val checked = all.map { fields.isVisible(it) }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Data fields: ${mode.label} mode")
            .setMultiChoiceItems(all.map { it.label.replace("  ", " ") }.toTypedArray(), checked) { _, which, on ->
                fields.setVisible(all[which], on)
            }
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.menu_edit_layout) { _, _ -> setEditing(true) }
            .setNegativeButton("Group…") { _, _ -> showGroupingDialog() }
            .show()
    }

    /** Separate draggable cards, or all fields combined into one neat panel. */
    private fun showGroupingDialog() {
        val options = DataFieldsOverlay.Grouping.entries
        MaterialAlertDialogBuilder(this)
            .setTitle("Data fields: ${mode.label} mode")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), options.indexOf(fields.grouping)) { d, which ->
                fields.grouping = options[which]
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // =========================================================================
    // User data: snapshots, backup files, GPX
    // =========================================================================

    private fun stamp(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault()).format(Date())

    /** Flush in-memory routes, marks and track to storage before reading it back out. */
    private fun persistAll() {
        store.saveMarks(marks)
        store.savePlan(plan)
        store.saveTrack(track.points)
    }

    /** Reload everything from storage after an import or snapshot load. */
    private fun reloadAll() {
        marks = store.marks()
        plan = store.plan()
        track = Track(store.track())
        fields.mode = mode
        map?.style?.let { layers.apply(it) }
        applyAppearance()
        refreshOverlays()
        refreshModePanel()
    }

    private fun showDataMenu() {
        val items: List<Pair<String, () -> Unit>> = listOf(
            "Save snapshot on this phone…" to {
                askText("Snapshot name", "Snapshot ${stamp("d MMM HH:mm")}") { name ->
                    persistAll()
                    userData.saveSnapshot(name)
                    Toast.makeText(this, "Saved snapshot \"$name\"", Toast.LENGTH_SHORT).show()
                }
            },
            "Load snapshot…" to { showSnapshots() },
            "Export backup file (.json)…" to {
                pickParts("Export backup", DataPart.entries, DataPart.entries.toSet()) { parts ->
                    pendingExport = false to parts
                    exportJsonFile.launch("chompchamp-backup-${stamp("yyyyMMdd-HHmm")}.json")
                }
            },
            "Export GPX (marks, routes, track)…" to {
                val gpxParts = listOf(DataPart.MARKS, DataPart.ROUTES, DataPart.TRACK)
                pickParts("Export GPX", gpxParts, setOf(DataPart.MARKS, DataPart.ROUTES)) { parts ->
                    pendingExport = true to parts
                    exportGpxFile.launch("chompchamp-${stamp("yyyyMMdd-HHmm")}.gpx")
                }
            },
            "Import backup or GPX file…" to { importDataFile.launch(arrayOf("*/*")) },
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Data")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickParts(title: String, options: List<DataPart>, preselected: Set<DataPart>,
                          labels: (DataPart) -> String = { it.label }, onOk: (Set<DataPart>) -> Unit) {
        val checked = options.map { it in preselected }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMultiChoiceItems(options.map(labels).toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Continue") { _, _ ->
                val parts = options.filterIndexed { i, _ -> checked[i] }.toSet()
                if (parts.isEmpty()) Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show() else onOk(parts)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun writeExport(uri: Uri) {
        val (gpx, parts) = pendingExport ?: return
        pendingExport = null
        runCatching {
            persistAll()
            val text = if (gpx) userData.exportGpx(parts) else userData.export(parts)
            contentResolver.openOutputStream(uri, "wt").use { out ->
                requireNotNull(out) { "cannot write file" }.write(text.toByteArray())
            }
        }.onSuccess { Toast.makeText(this, "Exported", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun readImport(uri: Uri) {
        val bundle = runCatching {
            val text = contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "cannot open file" }.readBytes().also {
                    require(it.size < 64 * 1024 * 1024) { "file too large" }
                }.toString(Charsets.UTF_8)
            }
            val head = text.trimStart()
            when {
                head.startsWith("{") -> userData.parseBackup(text) to false
                head.contains("<gpx", ignoreCase = true) -> userData.parseGpx(text) to true
                else -> error("not a ChompChamp backup or GPX file")
            }
        }.getOrElse {
            Toast.makeText(this, "Import failed: ${it.message}", Toast.LENGTH_LONG).show()
            return
        }
        val (b, isGpx) = bundle
        val available = b.available()
        pickParts(if (isGpx) "Import GPX" else "Import backup", available, available.toSet(),
            labels = { "${it.label}: ${b.describe(it)}" }) { parts ->
            if (isGpx) {
                applyBundle(b, parts, merge = true)
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Merge or replace?")
                    .setMessage("Merge adds the imported items to what's already on this phone.\n\n" +
                        "Replace deletes the selected kinds of data on this phone first.")
                    .setPositiveButton("Merge") { _, _ -> applyBundle(b, parts, merge = true) }
                    .setNeutralButton("Replace") { _, _ -> applyBundle(b, parts, merge = false) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun applyBundle(b: DataBundle, parts: Set<DataPart>, merge: Boolean) {
        runCatching {
            persistAll()
            userData.apply(b, parts, merge)
            reloadAll()
        }.onSuccess { Toast.makeText(this, "Imported: " + parts.joinToString { b.describe(it) }, Toast.LENGTH_LONG).show() }
            .onFailure { Toast.makeText(this, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun showSnapshots() {
        val snaps = userData.snapshots()
        if (snaps.isEmpty()) { Toast.makeText(this, "No snapshots yet", Toast.LENGTH_SHORT).show(); return }
        val fmt = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault())
        MaterialAlertDialogBuilder(this)
            .setTitle("Snapshots")
            .setItems(snaps.map { "${it.first}\n   ${fmt.format(Date(it.second))}" }.toTypedArray()) { _, which ->
                val name = snaps[which].first
                MaterialAlertDialogBuilder(this)
                    .setTitle(name)
                    .setMessage("Loading replaces the routes, marks, track and settings on this phone with this snapshot.")
                    .setPositiveButton("Load") { _, _ ->
                        runCatching { userData.readSnapshot(name) }
                            .onSuccess { applyBundle(it, DataPart.entries.toSet(), merge = false) }
                            .onFailure { Toast.makeText(this, "Couldn't read snapshot: ${it.message}", Toast.LENGTH_LONG).show() }
                    }
                    .setNeutralButton("Delete") { _, _ ->
                        confirm("Delete snapshot \"$name\"?") { userData.deleteSnapshot(name) }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLayersPanel() {
        panel.show("Map layers", { rows ->
            layers.fillPanel(rows, map?.style?.takeIf { chartFile.exists() })
        })
    }

    private fun showAppearancePanel() {
        panel.show("Appearance", { rows ->
            look.fillPanel(rows, onChanged = { applyAppearance() }, onReset = { showAppearancePanel() })
        })
    }

    /** Tapping a field in Edit layout: size or hide just that field. */
    private fun fieldOptions(f: DataField) {
        val sizes = listOf(0.75f to "Small", 1f to "Normal", 1.35f to "Large", 1.75f to "Extra large")
        val current = sizes.indexOfFirst { it.first == fields.fieldSize(f) }.coerceAtLeast(1)
        MaterialAlertDialogBuilder(this)
            .setTitle(f.label.replace("  ", " "))
            .setSingleChoiceItems(sizes.map { it.second }.toTypedArray(), current) { d, which ->
                fields.setFieldSize(f, sizes[which].first)
                d.dismiss()
            }
            .setNegativeButton("Hide field") { _, _ -> fields.setVisible(f, false) }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** One palette for every control; sizes and transparency from Appearance settings. */
    private fun applyAppearance() {
        val dp = resources.displayMetrics.density
        val scale = look.controlScale
        val bgAlpha = look.controlAlpha
        val size = controlSizePx()
        for (b in controlButtons) {
            b.layoutParams = b.layoutParams.apply { width = size; height = size }
            b.background = look.circleBg(bgAlpha)
            b.imageAlpha = (255 * (0.55f + 0.45f * bgAlpha)).toInt()
            val pad = (10 * dp * scale).toInt()
            b.setPadding(pad, pad, pad, pad)
        }
        modeButton.apply {
            minHeight = size
            textSize = 13f * scale
            setTextColor(look.onSurface)
            background = look.surfaceBg(bgAlpha, 22f * scale)
        }
        modePanel.background = look.surfaceBg(bgAlpha, 14f)
        modeSummary.textSize = 13f * scale
        (modePanel.layoutParams as FrameLayout.LayoutParams).apply {
            marginStart = size + (20 * dp).toInt()
            marginEnd = size + (20 * dp).toInt()
        }
        modeButtons.tag = null   // rebuild buttons at the new size
        editBar.background = look.surfaceBg(0.9f, 22f)
        fields.applyStyle()
        applyMapUiMargins()
        refreshModePanel()
    }

    private fun setEditing(on: Boolean) {
        fields.setEditing(on)
        editBar.visibility = if (on) View.VISIBLE else View.GONE
        // The edit bar sits where the mode pill is.
        modeButton.visibility = if (on) View.GONE else View.VISIBLE
    }

    /** Keep MapLibre's compass and attribution clear of our buttons and the system bars. */
    private fun controlSizePx() = (44 * resources.displayMetrics.density * look.controlScale).toInt()

    private fun applyMapUiMargins() {
        val ui = map?.uiSettings ?: return
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val size = controlSizePx()
        // Compass under the menu button; data credits "i" above the layers button.
        ui.compassGravity = Gravity.TOP or Gravity.END
        ui.setCompassMargins(0, barInsets.top + size + (24 * dp).toInt(), barInsets.right + pad, 0)
        ui.isLogoEnabled = false
        ui.attributionGravity = Gravity.BOTTOM or Gravity.START
        ui.setAttributionMargins(barInsets.left + pad, 0, 0, barInsets.bottom + size + (20 * dp).toInt())
    }

    // =========================================================================
    // Chart style & import
    // =========================================================================

    private fun loadStyle() {
        val m = map ?: return
        val json = if (chartFile.exists()) buildStyleJson() else {
            Toast.makeText(this, R.string.no_chart, Toast.LENGTH_LONG).show()
            EMPTY_STYLE
        }
        m.setStyle(Style.Builder().fromJson(json)) { style ->
            hazards.install(style)
            overlays.install(style)
            layers.apply(style)
            refreshOverlays()
            startLocation(style)
        }
    }

    private fun buildStyleJson(): String {
        val style = JSONObject(
            assets.open("chart-style.json").bufferedReader().use { it.readText() }
                .replace("{{CHART_URL}}", "pmtiles://file://${chartFile.absolutePath}")
        )
        // Reef relief: offline pack if imported, otherwise straight from the DoT server.
        val relief = JSONObject().put("type", "raster").put("tileSize", 256)
        if (reliefFile.exists()) {
            relief.put("url", "pmtiles://file://${reliefFile.absolutePath}")
        } else {
            relief.put("tiles", JSONArray().put(RELIEF_ONLINE)).put("minzoom", 10).put("maxzoom", 18)
                .put("attribution", "Bathymetry imagery © WA Department of Transport (CC BY 3.0 AU)")
        }
        style.getJSONObject("sources").put("relief", relief)
        val src = style.getJSONArray("layers")
        val out = JSONArray()
        for (i in 0 until src.length()) {
            val layer = src.getJSONObject(i)
            out.put(layer)
            // Above land (so coarse land never hides surveyed water), below zones and contours.
            if (layer.getString("id") == "land") {
                out.put(JSONObject().put("id", "relief").put("type", "raster").put("source", "relief")
                    .put("paint", JSONObject().put("raster-opacity", 0.9)))
            }
        }
        style.put("layers", out)
        return style.toString()
    }

    // ---- Chart & relief data manager ------------------------------------------------

    private fun fileInfo(f: File): String {
        if (!f.exists()) return "not installed"
        val mb = f.length() / 1_000_000.0
        val date = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(f.lastModified()))
        return String.format(Locale.US, "%.0f MB · imported %s", mb, date)
    }

    private fun showChartManager() {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "Import chart or relief file…" to { openChartPicker() }
        if (chartFile.exists()) {
            items += "Remove chart\n   ${fileInfo(chartFile)}" to {
                confirm("Remove the chart (${fileInfo(chartFile)})?\n\nThe map will be blank until you import a chart again. " +
                    "Routes, marks and settings are kept.") { removeChartFile(chartFile, "Chart removed") }
            }
        }
        if (reliefFile.exists()) {
            items += "Remove offline reef relief pack\n   ${fileInfo(reliefFile)}" to {
                confirm("Remove the offline reef relief pack (${fileInfo(reliefFile)})?\n\n" +
                    "Reef relief will load from the internet instead (needs a connection).") {
                    removeChartFile(reliefFile, "Reef relief pack removed (using online relief)")
                }
            }
        }
        items += "Clear online map cache" to {
            confirm("Clear cached satellite, online relief and label tiles? They'll download again when needed.") {
                OfflineManager.getInstance(this).clearAmbientCache(object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() {
                        Toast.makeText(this@MainActivity, "Online cache cleared", Toast.LENGTH_SHORT).show()
                    }
                    override fun onError(message: String) {
                        Toast.makeText(this@MainActivity, "Couldn't clear cache: $message", Toast.LENGTH_LONG).show()
                    }
                })
            }
        }
        val title = when {
            !chartFile.exists() -> "Charts · no chart installed"
            reliefFile.exists() -> "Charts · reef relief offline"
            else -> "Charts · reef relief online"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeChartFile(f: File, message: String) {
        if (f.delete() || !f.exists()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            locationActive = false
            loadStyle()
        } else {
            Toast.makeText(this, "Couldn't remove file", Toast.LENGTH_LONG).show()
        }
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

    /** Vector tiles → main chart. Raster tiles → reef relief pack. */
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
                val header = ByteArray(127).also { b -> tmp.inputStream().use { it.read(b) } }
                if (String(header, 0, 7, Charsets.US_ASCII) != "PMTiles") {
                    tmp.delete()
                    error("that isn't a .pmtiles chart file")
                }
                // PMTiles v3 header byte 99 = tile type: 1 MVT, 2 PNG, 3 JPEG, 4 WebP
                val dest = if (header[99].toInt() == 1) chartFile else reliefFile
                if (!tmp.renameTo(dest)) error("could not save file")
                if (dest == chartFile) "Chart imported" else "Reef relief pack imported (works offline now)"
            }
            runOnUiThread {
                result.onSuccess { msg ->
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    locationActive = false
                    loadStyle()
                }.onFailure {
                    Toast.makeText(this, getString(R.string.import_failed, it.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // =========================================================================
    // GPS
    // =========================================================================

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
        lastFix?.let { lc.forceLocationUpdate(it) }
        if (mode == AppMode.NAVIGATE || mode == AppMode.FISH) {
            followBoat(if (mode == AppMode.FISH) settings.fishZoom else null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        if (!hasLocationPermission()) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        lm.removeUpdates(this)
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (lm.isProviderEnabled(provider)) lm.requestLocationUpdates(provider, 1000L, 0f, this)
        }
    }

    private fun lastPos() = lastFix?.let { LatLon(it.latitude, it.longitude) }

    override fun onLocationChanged(location: Location) {
        val m = map ?: return
        // Prefer GPS; ignore a coarse network fix if a GPS fix arrived recently.
        val prev = lastFix
        if (prev != null && prev !== location && location.provider == LocationManager.NETWORK_PROVIDER &&
            prev.provider == LocationManager.GPS_PROVIDER && location.time - prev.time < 10_000) return
        lastFix = location
        if (locationActive) m.locationComponent.forceLocationUpdate(location)

        val pos = LatLon(location.latitude, location.longitude)
        val sog = if (location.hasSpeed()) location.speed * Geo.KN_PER_MS else null
        val cog = if (location.hasBearing() && location.speed > 0.5f) location.bearing.toDouble() else null

        val added = track.add(pos, location.time, settings.trackSpacingM)
        // Redraw every few fixes: new points arrive and old ones age out of the time window.
        if (++fixCount % 5 == 0 || (added && settings.trackWindowMin == null && track.points.size % 5 == 0)) {
            overlays.setTrack(visibleTrack())
        }
        if (added) {
            if (track.points.size % 60 == 0) {
                val pts = ArrayList(track.points)
                io.execute { store.saveTrack(pts) }
            }
        }
        drift.add(location.time, pos, settings.driftWindowSec)

        // Navigation
        val nav = navigator.update(pos, sog, cog)
        if (nav != null) {
            nav.arrived?.let { name ->
                buzz()
                Toast.makeText(this, if (nav.finished) "Arrived: $name" else "Reached $name → ${nav.target.name}",
                    Toast.LENGTH_LONG).show()
            }
            if (nav.finished) {
                stopNavigation()
            } else {
                lastNav = nav
                if (mode != AppMode.ROUTE) overlays.setRoute(navigator.route, navigator.activeIndex)
                overlays.setNavLine(pos, nav.target.pos)
            }
        }

        // Drift projection (Fish mode)
        val d = drift.drift()
        if (mode == AppMode.FISH && d != null && d.first > 0.05) {
            overlays.setDrift(pos, Geo.destination(pos, d.second, d.first * settings.driftProjectMin / 60.0))
        } else if (mode == AppMode.FISH) {
            overlays.setDrift(null, null)
        }

        updateFields(location, pos, sog, cog, d)
        checkZone(location)
        updateBanner()
        if (mode == AppMode.NAVIGATE || mode == AppMode.FISH) refreshModePanel()
    }

    @Deprecated("Required on API < 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private fun followBoat(zoom: Double? = null) {
        val m = map ?: return
        if (!locationActive) {
            m.style?.let { startLocation(it) }
            return
        }
        val lc = m.locationComponent
        val courseUp = settings.courseUp && (mode == AppMode.NAVIGATE || mode == AppMode.FISH)
        lc.cameraMode = if (courseUp) CameraMode.TRACKING_GPS else CameraMode.TRACKING_GPS_NORTH
        lc.zoomWhileTracking(zoom ?: maxOf(m.cameraPosition.zoom, 12.0))
    }

    private fun updateFields(loc: Location, pos: LatLon, sog: Double?, cog: Double?, d: Pair<Double, Double>?) {
        fields.set(DataField.SOG, sog?.let { String.format(Locale.US, "%.1f", it) } ?: "--.-")
        fields.set(DataField.COG, cog?.let { Geo.fmtBearing(it) } ?: "---°")
        fields.set(DataField.POSITION, "${Geo.formatDdm(loc.latitude, true)}\n${Geo.formatDdm(loc.longitude, false)}")
        fields.set(DataField.DEPTH, estimateDepthAt(LatLng(loc.latitude, loc.longitude)))
        fields.set(DataField.TIME, SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
        fields.set(DataField.TRIP, Geo.fmtNm(track.tripNm))

        val n = lastNav
        if (n != null && navigator.isActive) {
            fields.set(DataField.WPT, "${n.target.name} (${n.targetIndex + 1}/${n.waypointCount})")
            fields.set(DataField.BRG, Geo.fmtBearing(n.bearing))
            fields.set(DataField.DTW, Geo.fmtNm(n.dtwNm))
            fields.set(DataField.XTE, Geo.fmtXte(n.xteNm))
            fields.set(DataField.VMG, n.vmgKn?.let { String.format(Locale.US, "%.1f", it) } ?: "--.-")
            fields.set(DataField.TTG, n.ttgHours?.let { Geo.fmtDuration(it) } ?: "--")
            fields.set(DataField.ETA_WPT, n.etaWpt?.let { Geo.fmtClock(it) } ?: "--")
            fields.set(DataField.ETA_DEST, n.etaDest?.let { Geo.fmtClock(it) } ?: "--")
            fields.set(DataField.REMAIN, Geo.fmtNm(n.remainNm))
        } else {
            for (f in listOf(DataField.WPT, DataField.BRG, DataField.DTW, DataField.XTE, DataField.VMG,
                DataField.TTG, DataField.ETA_WPT, DataField.ETA_DEST, DataField.REMAIN)) fields.set(f, f.placeholder)
        }

        fields.set(DataField.DRIFT, d?.let { String.format(Locale.US, "%.1f", it.first) } ?: "--.-")
        fields.set(DataField.DRIFT_DIR, d?.let { Geo.fmtBearing(it.second) } ?: "---°")
        val nearest = marks.minByOrNull { Geo.distanceNm(pos, it.pos) }
        fields.set(DataField.MARK, nearest?.let {
            "${it.name}\n${Geo.fmtNm(Geo.distanceNm(pos, it.pos))} nm ${Geo.fmtBearing(Geo.bearing(pos, it.pos))}"
        } ?: "--")
        fields.set(DataField.ANCHOR, anchor?.let { "${(Geo.distanceNm(it, pos) * Geo.M_PER_NM).toInt()}" } ?: "off")
    }

    /**
     * Depth band from the chart at a point, e.g. "20–25". Only works while the point
     * is on screen (we query rendered tiles), and is only as good as the 250 m model.
     */
    private fun estimateDepthAt(p: LatLng): String {
        val m = map ?: return "--"
        val screen = m.projection.toScreenLocation(p)
        if (screen.x < 0 || screen.y < 0 || screen.x > mapView.width || screen.y > mapView.height) return "off screen"
        if (m.queryRenderedFeatures(screen, "land").isNotEmpty()) return "land"
        val f = m.queryRenderedFeatures(screen, "depth-fill").firstOrNull() ?: return "--"
        val dmin = f.getNumberProperty("dmin")?.toInt() ?: return "--"
        val dmax = f.getNumberProperty("dmax")?.toInt()
        return if (dmax == null || dmax >= 2000) ">$dmin" else "$dmin–$dmax"
    }

    // =========================================================================
    // Alerts: no-fishing zones, anchor drag
    // =========================================================================

    private var zoneAlert: Pair<String, Int>? = null

    private fun checkZone(loc: Location) {
        val m = map ?: return
        val screen = m.projection.toScreenLocation(LatLng(loc.latitude, loc.longitude))
        val notake = m.queryRenderedFeatures(screen, "zones-notake-fill")
        val restricted = if (notake.isEmpty()) m.queryRenderedFeatures(screen, "zones-restricted-fill") else emptyList()
        val feature = notake.firstOrNull() ?: restricted.firstOrNull()
        val name = feature?.getStringProperty("name")
        zoneAlert = when {
            feature == null -> null
            notake.isNotEmpty() -> getString(R.string.no_fishing_banner, name ?: "") to look.danger
            else -> getString(R.string.restricted_banner, name ?: "") to look.warn
        }
        if (feature != null && name != currentZone && notake.isNotEmpty()) buzz()
        currentZone = name
    }

    private fun updateBanner() {
        val pos = lastPos()
        val a = anchor
        val anchorAlert = if (a != null && pos != null) {
            val dist = Geo.distanceNm(a, pos) * Geo.M_PER_NM
            if (dist > settings.anchorRadiusM) {
                val now = System.currentTimeMillis()
                if (now - lastAnchorBuzz > 5000) { buzz(); lastAnchorBuzz = now }
                "⚓ ANCHOR DRAGGING\n${dist.toInt()} m from anchor" to look.info
            } else null
        } else null
        val alert = anchorAlert ?: zoneAlert
        if (alert == null) {
            zoneBanner.visibility = View.GONE
        } else {
            zoneBanner.text = alert.first
            zoneBanner.background = look.surfaceBg(0.92f, 18f, color = alert.second)
            zoneBanner.visibility = View.VISIBLE
        }
    }

    private fun buzz(short: Boolean = false) {
        val v = getSystemService(Vibrator::class.java) ?: return
        v.vibrate(if (short) VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
        else VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
    }

    // =========================================================================
    // Small dialogs
    // =========================================================================

    private fun confirm(message: String, onYes: () -> Unit) {
        MaterialAlertDialogBuilder(this).setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onYes() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun askText(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(this).setTitle(title).setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk)
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume(); if (locationActive) requestUpdates() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() {
        super.onStop()
        mapView.onStop()
        val pts = ArrayList(track.points)
        io.execute { store.saveTrack(pts) }
    }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    override fun onDestroy() {
        getSystemService(LocationManager::class.java)?.removeUpdates(this)
        io.shutdown()
        mapView.onDestroy()
        super.onDestroy()
    }

    companion object {
        private const val RELIEF_ONLINE =
            "https://public-services.slip.wa.gov.au/public/rest/services/Landgate_Public_Maps/WA_Bathymetry/MapServer/export" +
                "?bbox={bbox-epsg-3857}&bboxSR=3857&imageSR=3857&size=256,256&format=png32&transparent=true&layers=show:2&f=image"
        private const val EMPTY_STYLE =
            """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#dfe9f2"}}]}"""
    }
}
