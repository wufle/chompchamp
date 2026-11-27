package au.chompchamp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import android.view.ViewGroup
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
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
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnLocationCameraTransitionListener
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
    private lateinit var scaleBar: ScaleBarView
    private lateinit var modeSummary: TextView
    private lateinit var modeButtons: LinearLayout

    private var map: MapLibreMap? = null
    private val overlays by lazy { NavOverlays(resources.displayMetrics.density) }
    private val hazards by lazy { Hazards(this) }
    private val places by lazy { Places(this) }
    private lateinit var navigator: Navigator
    private val drift = DriftTracker()
    private lateinit var track: Track
    private lateinit var marks: MutableList<Mark>
    private lateinit var groups: MarkGroups
    private lateinit var marksPanel: MarksPanel
    private lateinit var plan: Route
    private lateinit var userData: UserData
    private lateinit var photos: Photos
    private lateinit var tripLog: TripLog
    private lateinit var tripsPanel: TripsPanel
    private lateinit var catchDialogs: CatchDialogs
    private lateinit var bagPanel: BagLimitsPanel
    private lateinit var placesPanel: PlacesPanel
    private lateinit var bagButton: MovableButton
    private lateinit var recButton: MovableButton
    private lateinit var ais: Ais
    private lateinit var aisLabel: TextView
    private val boatIcons by lazy { BoatIcons(this) }
    private lateinit var wind: Wind
    private lateinit var windView: WindView
    private lateinit var waveView: WaveView
    private lateinit var windCredit: TextView
    /** The wind time picked on the Wind & tide panel, or null for now. */
    private var windTime: Long? = null
    /** Tide predictions; null if the asset couldn't be read. */
    private val tides by lazy { runCatching { Tides(this) }.getOrNull() }
    private lateinit var offlineAreas: OfflineAreas
    private lateinit var offlinePanel: OfflinePanel
    private lateinit var updater: Updater
    /** Name, version and project link, for servers we ask for data. Keep the name the same across releases. */
    private val userAgent by lazy {
        "chompchamp/" + (runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "0") +
            " ($GITHUB_URL)"
    }
    /** The automatic update check runs once per launch. */
    private var updateChecked = false
    private lateinit var prefs: android.content.SharedPreferences
    /** A trip is being recorded. Only the user, a started route or a marked catch turns it on. */
    private var recording: Boolean
        get() = prefs.getBoolean("trip_recording", false)
        set(v) { prefs.edit().putBoolean("trip_recording", v).apply() }
    /** When Record was tapped: the clock runs from here, even before the boat moves and the track gets a point. */
    private var recordingSince: Long
        get() = prefs.getLong("trip_recording_since", 0L)
        set(v) { prefs.edit().putLong("trip_recording_since", v).apply() }
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    /** Every second while recording, so the REC clock visibly runs; otherwise twice a minute. */
    private val tick = object : Runnable {
        override fun run() { updateModeLabel(); ticker.postDelayed(this, if (recording) 1_000 else 30_000) }
    }
    /** The saved trip drawn on the map. */
    private var shownTrip: String? = null
    /** Between onStart and onStop. */
    private var started = false
    private var lastTrackSave = 0L
    /** Id of the mark or catch waiting for a photo from the camera / gallery. */
    private var photoTarget: String? = null
    private var cameraFile: File? = null
    /** What the next "create document" result should contain: (isGpx, parts). */
    private var pendingExport: Pair<Boolean, Set<DataPart>>? = null
    /** Or this text as it is (a trip or the catch log). */
    private var pendingText: String? = null

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
    /** Depth and zones from the chart file, for anywhere, on screen or not. */
    private val chartReader by lazy { ChartReader(chartFile) }
    private val chartExec by lazy { Executors.newSingleThreadExecutor() }
    private var lookingUp = false

    private val importChart =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importChartFile(it) } }

    // Fallback picker for devices where ACTION_OPEN_DOCUMENT isn't available.
    private val importChartFallback =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { importChartFile(it) } }

    private val exportJsonFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { writeExport(it) } }
    private val exportGpxFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri -> uri?.let { writeExport(it) } }
    private val exportCsvFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { writeExport(it) } }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
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

        prefs = getSharedPreferences("app", MODE_PRIVATE)
        settings = Settings(prefs)
        layers = MapLayers(prefs)
        store = Store(File(filesDir, "data"))
        navigator = Navigator(settings)
        track = Track(store.track())
        marks = store.marks()
        groups = MarkGroups(store).also { it.reload(marks) }
        plan = store.plan()
        tripLog = TripLog(File(filesDir, "data"))
        if (!prefs.contains("trip_recording")) {
            // 0.16 and older recorded whenever the app was open. Save what's there the way they would have
            // (a track that stayed within 200 m with no catches or marks is dropped) and start not recording.
            if (track.points.size >= 2) runCatching {
                tripLog.archive(track.points.toList(), settings.tripSplitMs, keepAll = false) { t ->
                    tripLog.catchesIn(t.start, t.end, tripLog.trips.map { it.start to it.end }).isNotEmpty() ||
                        marks.any { it.time in t.start..t.end }
                }
            }
            track.clear()
            store.saveTrack(emptyList())
            recording = false
        }
        userData = UserData(File(filesDir, "data"), store, tripLog, prefs)
        photos = Photos(this)

        mapView = findViewById(R.id.mapView)
        zoneBanner = findViewById(R.id.zoneBanner)
        look = Appearance(this, prefs)
        panel = SettingsPanel(findViewById(R.id.panelHost), look)
        // The mode buttons sit under a docked panel and would show through it: hide them while one is open.
        panel.onOpenChanged = { if (::modePanel.isInitialized) refreshModePanel() }
        marksPanel = createMarksPanel()
        tripsPanel = createTripsPanel()
        catchDialogs = createCatchDialogs()
        bagPanel = createBagPanel()
        placesPanel = createPlacesPanel()
        offlineAreas = OfflineAreas(File(filesDir, "offline"), userAgent)
        offlinePanel = createOfflinePanel(prefs)
        updater = createUpdater(prefs)
        bagButton = MovableButton(findViewById(R.id.btnBag), prefs, "ui_bag")
        bagButton.button.setOnClickListener { bagPanel.toggle() }
        TooltipCompat.setTooltipText(bagButton.button, getString(R.string.bag_limits))
        // "ui_record": 0.17.5 kept its place under "ui_rec", at the top right; it starts at the top left now.
        recButton = MovableButton(findViewById(R.id.btnRecord), prefs, "ui_record")
        recButton.button.setOnClickListener { onRecordTap() }
        // Stopping takes a press and hold, then a confirm, so a knock on the screen can't end a trip.
        recButton.button.setOnLongClickListener { if (recording) askStopTrip() else startTrip(null); true }
        // The Record button has the top left, so until moved the bag button sits beside the menu button,
        // and goes back there if it would hide under Record (it used to live at the top left).
        bagButton.avoid = recButton.button
        bagButton.defaultAt = {
            val menu = findViewById<View>(R.id.btnMenu)
            val b = bagButton.button
            if (menu.width == 0 || b.width == 0) null
            else (menu.x - b.width - 10 * resources.displayMetrics.density) to menu.y
        }
        aisLabel = findViewById(R.id.aisLabel)
        aisLabel.setOnClickListener { showAisInfo() }
        ais = createAis()
        fields = DataFieldsOverlay(findViewById<FrameLayout>(R.id.fieldsContainer), prefs, look) { f -> fieldOptions(f) }
        editBar = findViewById(R.id.editBar)
        modeButton = findViewById(R.id.btnMode)
        modePanel = findViewById(R.id.modePanel)
        scaleBar = findViewById(R.id.scaleBar)
        // The vessel count sits on the scale bar, so it needs the bar's height: 0 until the bar is first laid out.
        scaleBar.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) scaleBar.post { placeScaleBar() } }
        modePanel.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) scaleBar.post { placeScaleBar() } }
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
            recButton.button.post { recButton.place(); bagButton.place() }
            // Keep a panel with a search box above the keyboard.
            val host = findViewById<View>(R.id.panelHost)
            val keyboard = (insets.getInsets(WindowInsetsCompat.Type.ime()).bottom - bars.bottom).coerceAtLeast(0)
            host.layoutParams = (host.layoutParams as FrameLayout.LayoutParams).apply {
                bottomMargin = (8 * resources.displayMetrics.density).toInt() + keyboard
            }
            insets
        }

        controlButtons = listOf(
            findViewById(R.id.btnMenu), findViewById(R.id.btnLayers), findViewById(R.id.btnLocate), findViewById(R.id.btnBag))
        findViewById<View>(R.id.btnLocate).setOnClickListener { followBoat() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        findViewById<View>(R.id.btnLayers).setOnClickListener {
            if (panel.isOpen) panel.hide() else showLayersPanel()
        }
        layers.onReset = { showLayersPanel(); updateAis() }
        layers.onToggled = { if (it == MapOverlay.AIS) updateAis(); if (it == MapOverlay.WIND || it == MapOverlay.WAVES) updateWind() }
        setUpWind()
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
            m.addOnCameraMoveListener { updateScaleBar(); if (waveView.showing) waveView.invalidate() }
            m.addOnCameraIdleListener { updateScaleBar(); offlinePanel.onCameraIdle(); fetchWindForView() }
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
        val trip = AppMode.entries.size
        // Only here when the Record button is turned off.
        if (!look.showRecordButton) popup.menu.add(1, trip, trip, if (recording) "■  Stop recording trip…" else "●  Record a trip")
        popup.setOnMenuItemClickListener {
            if (it.itemId == trip) { if (recording) askStopTrip() else startTrip(null) }
            else setMode(AppMode.entries[it.itemId])
            true
        }
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
        updateModeLabel()
        fields.mode = m
        val lc = map?.locationComponent?.takeIf { locationActive }
        when (m) {
            AppMode.CHART -> Unit
            AppMode.ROUTE -> lc?.cameraMode = CameraMode.NONE
            AppMode.NAVIGATE -> followBoat()
            AppMode.FISH -> { drift.reset(); followBoat(settings.fishZoom) }
        }
        updateBagButton()
        refreshOverlays()
        refreshModePanel()
        refreshDisplayPanel()   // its data field rows belong to the current mode
    }

    private fun updateBagButton() {
        val on = mode == AppMode.FISH && look.showBagButton
        bagButton.button.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun createBagPanel() = BagLimitsPanel(this, panel, look, object : BagLimitsPanel.Host {
        override fun boat() = lastPos()
        override fun mapCentre() = map?.cameraPosition?.target?.let { LatLon(it.latitude, it.longitude) }
    })

    private val clearPlaceHit = Runnable { map?.style?.let { places.showHit(it, null) } }

    private fun createPlacesPanel() = PlacesPanel(this, panel, look, places, object : PlacesPanel.Host {
        override fun boat() = lastPos()
        override fun mapCentre() = map?.cameraPosition?.target?.let { LatLon(it.latitude, it.longitude) }
        override fun goTo(p: Places.Place) {
            val m = map ?: return
            // Stop following the boat, or the map would jump straight back.
            m.locationComponent.takeIf { locationActive }?.cameraMode = CameraMode.NONE
            val zoom = maxOf(m.cameraPosition.zoom, Places.labelZoom(p.rank) + 1.0).coerceAtMost(15.0)
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.pos.lat, p.pos.lon), zoom), 1200)
            // Ring the place for a while, as its label may give way to a nearby one.
            m.style?.let { places.showHit(it, p) }
            mapView.removeCallbacks(clearPlaceHit)
            mapView.postDelayed(clearPlaceHit, 20_000)
            if (!layers.isOn(MapOverlay.PLACES)) Toast.makeText(this@MainActivity,
                "${p.name}. Place names are off in Layers.", Toast.LENGTH_SHORT).show()
        }
    })

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
        showCatchesOnMap()
    }

    /** A round action button in the bottom row with one [word] under it. Long-press shows [label]. */
    private class ModeAction(val icon: Int, val word: String, val label: String, val active: Boolean = false, val onTap: () -> Unit)

    private fun refreshModePanel() {
        val actions: List<ModeAction>
        val summary: String
        when (mode) {
            AppMode.CHART -> { modePanel.visibility = View.GONE; placeScaleBar(); return }
            AppMode.ROUTE -> {
                summary = routeSummary()
                actions = listOf(
                    ModeAction(R.drawable.ic_undo, "Undo", "Undo last waypoint") { undoWaypoint() },
                    ModeAction(R.drawable.ic_delete, "Clear", "Clear route") {
                        confirm("Clear the route?") { plan.waypoints.clear(); planChanged() }
                    },
                    ModeAction(R.drawable.ic_folder, "Routes", "Saved routes") { showRoutesMenu(modeButtons) },
                    ModeAction(R.drawable.ic_check, "Check", "Check route") { checkRoute() },
                    ModeAction(R.drawable.ic_play, "Go", "Start navigating") { startNavigation(plan) },
                )
            }
            AppMode.NAVIGATE -> {
                val n = lastNav
                val t = navigator.target
                summary = if (t != null) {
                    "→ ${t.name.ifBlank { "WP ${navigator.activeIndex + 1}" }}  (${navigator.activeIndex + 1} of ${navigator.route?.waypoints?.size})" +
                        (n?.let { "\n${Geo.fmtDist(it.dtwNm)} · ${Geo.fmtBearing(it.bearing)} · ${Geo.fmtDist(it.remainNm)} to go" } ?: "")
                } else "No active route"
                actions = listOf(
                    ModeAction(R.drawable.ic_skip_prev, "Back", "Previous waypoint") { if (navigator.previous(lastPos())) navChanged() },
                    ModeAction(R.drawable.ic_stop, "Stop", "Stop navigating") { confirm("Stop navigating?") { stopNavigation() } },
                    ModeAction(R.drawable.ic_skip_next, "Next", "Skip to next waypoint") { if (navigator.skip()) navChanged() },
                )
            }
            AppMode.FISH -> {
                val d = drift.drift()
                summary = (d?.let {
                    if (it.first < 0.05) "Not drifting"
                    else "Drift ${String.format(Locale.US, "%.1f", it.first)} kn → ${Geo.fmtBearing(it.second)}  " +
                        "(line shows ${settings.driftProjectMin.toInt()} min)"
                } ?: "Measuring drift…") + (anchor?.let { "\nAnchor alarm ON · ${settings.anchorRadiusM.toInt()} m" } ?: "")
                actions = listOf(
                    ModeAction(R.drawable.ic_star, "Mark", "Mark a catch here") { markCatch() },
                    ModeAction(R.drawable.ic_anchor, "Anchor", if (anchor == null) "Set anchor alarm" else "Turn anchor alarm off",
                        active = anchor != null) { toggleAnchor() },
                    ModeAction(R.drawable.ic_refresh, "Reset", "Reset drift") { drift.reset(); refreshModePanel() },
                    ModeAction(R.drawable.ic_list, "List", "My marks") { showMarksList() },
                )
            }
        }
        modePanel.visibility = if (panel.isOpen) View.GONE else View.VISIBLE
        modeSummary.text = summary
        placeScaleBar()

        // Same size as the layers and position buttons; wraps to a second row if they don't fit.
        val dp = resources.displayMetrics.density
        val size = controlSizePx()
        val gap = (8 * dp).toInt()
        val avail = resources.displayMetrics.widthPixels - barInsets.left - barInsets.right - 2 * (size + (20 * dp).toInt())
        val perRow = ((avail + gap) / (size + gap)).coerceIn(1, actions.size)
        val key = "$mode/${actions.size}/$size/$perRow"
        if (modeButtons.tag != key) {
            modeButtons.removeAllViews()
            modeButtons.tag = key
            actions.indices.chunked(perRow).forEach { idx ->
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
                idx.forEachIndexed { k, _ ->
                    val b = ImageButton(this)
                    val cell = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(b, LinearLayout.LayoutParams(size, size))
                        addView(TextView(this@MainActivity).apply {
                            styleButtonLabel(this)
                            setOnClickListener { b.performClick() }   // or the tap would land on the map
                        }, LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT))
                    }
                    row.addView(cell, LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        if (k > 0) marginStart = gap
                    })
                }
                modeButtons.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
            }
        }
        actions.forEachIndexed { i, a ->
            val cell = (modeButtons.getChildAt(i / perRow) as LinearLayout).getChildAt(i % perRow) as LinearLayout
            val b = cell.getChildAt(0) as ImageButton
            b.setOnClickListener { a.onTap() }
            // Called on every GPS fix: only touch the look when it changes.
            val state = "${a.icon}/${a.label}/${a.active}/${look.controlAlpha}"
            if (b.tag != state) {
                b.tag = state
                b.setImageResource(a.icon)
                (cell.getChildAt(1) as TextView).text = a.word
                b.contentDescription = a.label
                TooltipCompat.setTooltipText(b, a.label)
                styleCircle(b, a.active)
            }
        }
    }

    /** Small word under a round button: no background, readable on any map thanks to a soft halo. */
    private fun styleButtonLabel(t: TextView) {
        val scale = look.controlScale
        t.textSize = 10f * scale
        t.setTextColor(look.surface)
        t.gravity = Gravity.CENTER
        t.maxLines = 1
        t.includeFontPadding = false
        t.alpha = 0.55f + 0.45f * look.controlAlpha
        t.setShadowLayer(3f * resources.displayMetrics.density, 0f, 0f, look.onSurface)
        t.setPadding(0, (2 * resources.displayMetrics.density).toInt(), 0, 0)
        t.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO   // the button already has a name
    }

    /** Shared look of every round button. [active] = switched on (accent fill, dark icon). */
    private fun styleCircle(b: ImageButton, active: Boolean = false) {
        val a = look.controlAlpha
        b.scaleType = ImageView.ScaleType.CENTER_INSIDE
        b.background = if (active) look.circleBg(1f, look.accent) else look.circleBg(a)
        b.imageTintList = if (active) ColorStateList.valueOf(look.surface) else null
        b.imageAlpha = if (active) 255 else (255 * (0.55f + 0.45f * a)).toInt()
        val pad = (10 * resources.displayMetrics.density * look.controlScale).toInt()
        b.setPadding(pad, pad, pad, pad)
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
        return "$name · $n waypoints · ${Geo.fmtDist(len)} · " +
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
            "\nLeg from ${prev.name}: ${Geo.fmtDist(Geo.distanceNm(prev.pos, w.pos))} @ ${Geo.fmtBearing(Geo.bearing(prev.pos, w.pos))}"
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
        val hasRoute = plan.waypoints.isNotEmpty()
        val hasSaved = store.routes().isNotEmpty()
        menu.forEachIndexed { i, s ->
            // Only what makes sense now: no saving or reversing an empty route, no opening with nothing saved.
            val show = when (i) { 0, 2 -> hasRoute; 1, 4 -> hasSaved; else -> true }
            if (show) popup.menu.add(0, i, i, s)
        }
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
        val labels = routes.map { "${it.name} · ${it.waypoints.size} wpts · ${Geo.fmtDist(it.lengthNm())}" }
        MaterialAlertDialogBuilder(this).setTitle(title)
            .setItems(labels.toTypedArray()) { _, which -> onPick(routes[which]) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun zoomToRoute(r: Route) {
        val m = map ?: return
        if (r.waypoints.size < 2) return
        val b = LatLngBounds.Builder().includes(r.waypoints.map { LatLng(it.lat, it.lon) }).build()
        m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 160))
        if (m.cameraPosition.zoom > MAX_FIT_ZOOM) m.moveCamera(CameraUpdateFactory.zoomTo(MAX_FIT_ZOOM))
    }

    /**
     * Sample each leg against the chart for land, shallow water and no-fishing zones.
     * Uses rendered tiles, so we first fit the whole route on screen.
     */
    private fun checkRoute() {
        if (plan.waypoints.size < 2) { Toast.makeText(this, "Add at least two waypoints", Toast.LENGTH_SHORT).show(); return }
        if (!chartFile.exists()) { Toast.makeText(this, R.string.no_chart, Toast.LENGTH_SHORT).show(); return }
        zoomToRoute(plan)
        snack("Checking route…").setDuration(1500).show()
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
        // Heading to a spot from Fish mode: back to Fish when it ends. Planned in Route or Chart: back to Chart.
        if (mode != AppMode.NAVIGATE) modeAfterNav = if (mode == AppMode.FISH) AppMode.FISH else AppMode.CHART
        setMode(AppMode.NAVIGATE)
        if (settings.autoStartTrip && !recording) startTrip("Recording this trip")
        lastFix?.let { onLocationChanged(it) }
    }

    private fun stopNavigation() {
        navigator.stop()
        lastNav = null
        setMode(modeAfterNav)
    }

    private var modeAfterNav = AppMode.CHART

    private fun navChanged() {
        lastNav = null
        lastFix?.let { onLocationChanged(it) } ?: run { refreshOverlays(); refreshModePanel() }
    }

    // =========================================================================
    // Fishing: marks, anchor alarm
    // =========================================================================

    /** Marks whose group is switched on. */
    private fun visibleMarks(): List<Mark> {
        val shown = groups.all().filter { it.visible }.map { it.name }.toSet()
        return marks.filter { it.group in shown }
    }

    private fun showMarks() {
        val colors = groups.all().associate { it.name to it.color }
        val caught = tripLog.catches.filter { it.markId != null }.groupBy { it.markId }
        overlays.setMarks(visibleMarks(), { colors[it.group] ?: MarkGroup.DEFAULT_COLOR },
            photo = { m -> newestPhoto(m.photos + caught[m.id].orEmpty().flatMap { it.photos }) }) { id -> photos.bubble(id) }
    }

    private fun newestPhoto(names: List<String>) =
        if (names.size < 2) names.firstOrNull() else names.maxByOrNull { photos.file(it).lastModified() }

    /** Catches logged at this mark, newest first. */
    private fun catchesAt(m: Mark) = tripLog.catches.filter { it.markId == m.id }.sortedByDescending { it.time }

    private fun deleteMark(m: Mark) {
        m.photos.forEach { photos.delete(it) }
        marks.removeAll { it.id == m.id }
        store.saveMarks(marks)
        showMarks()
        showCatchesOnMap()
        marksPanel.refresh()
    }

    private fun createMarksPanel() = MarksPanel(this, panel, look, groups, object : MarksPanel.Host {
        override val marks get() = this@MainActivity.marks
        override val marksLayerOn get() = layers.isOn(MapOverlay.MARKS)
        override fun beforeShow() { displayPanelOpen = false }
        override fun changed() { store.saveMarks(marks); showMarks() }
        override fun sorted(list: List<Mark>) = sortedMarks(list)
        override fun label(m: Mark) = markLabel(m)
        override fun open(m: Mark) = goToMark(m)
        override fun delete(list: List<Mark>) {
            list.forEach { m -> m.photos.forEach { photos.delete(it) } }
            marks.removeAll(list.toSet())
            changed()
            showCatchesOnMap()   // their catches stay in the log
        }
        override fun confirm(message: String, onYes: () -> Unit) = this@MainActivity.confirm(message, onYes = onYes)
        override fun askText(title: String, initial: String, onOk: (String) -> Unit) = this@MainActivity.askText(title, initial, onOk)
        override fun showAllMarks() = showMarksList()
    })

    private fun nextMarkName() = "Mark ${marks.size + 1}"

    private fun storeMark(mark: Mark) {
        marks.add(mark)
        store.saveMarks(marks)
        // A new mark must never be invisible.
        if (!groups.isVisible(mark.group)) groups.setVisible(mark.group, true)
        showMarks()
        marksPanel.refresh()
    }

    private fun addMark(p: LatLon, depth: String?) {
        val name = nextMarkName()
        val mark = Mark(lat = p.lat, lon = p.lon, name = name,
            depth = depth?.takeIf { it != "--" && it != "off screen" })
        storeMark(mark)
        buzz(short = true)
        // Quick photo of the catch straight after marking.
        snack("Saved $name")
            .setAction("📷 Photo") { takePhotoFor(mark.id) }
            .show()
    }

    // ---- Photos of marks and catches -----------------------------------------------------

    private fun photosOf(id: String): MutableList<String>? =
        marks.firstOrNull { it.id == id }?.photos ?: tripLog.catches.firstOrNull { it.id == id }?.photos

    private fun photosChanged(id: String) {
        if (marks.any { it.id == id }) store.saveMarks(marks) else { tripLog.saveCatches(); showCatchesOnMap() }
        showMarks()
    }

    /** Back to the mark or catch a photo belongs to. */
    private fun openPhotoOwner(id: String) {
        marks.firstOrNull { it.id == id }?.let { markMenu(it); return }
        tripLog.catches.firstOrNull { it.id == id }?.let { catchDialogs.view(it) }
    }

    private fun takePhotoFor(id: String) {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        photoTarget = id
        cameraFile = f
        try {
            takePhoto.launch(uri)
        } catch (e: Exception) {
            cameraFile = null
            Toast.makeText(this, "No camera app found", Toast.LENGTH_LONG).show()
        }
    }

    private fun pickPhotoFor(id: String) {
        photoTarget = id
        pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(
            ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun attachPhoto(uri: Uri, cleanup: () -> Unit = {}) {
        val ownerId = photoTarget ?: return
        photoTarget = null
        Toast.makeText(this, "Adding photo…", Toast.LENGTH_SHORT).show()
        io.execute {
            val result = runCatching { photos.import(uri) }
            cleanup()
            runOnUiThread {
                result.onSuccess { name ->
                    val list = photosOf(ownerId)
                    if (list == null) { photos.delete(name); return@onSuccess }
                    list.add(name)
                    photosChanged(ownerId)
                    openPhotoOwner(ownerId)
                }.onFailure {
                    Toast.makeText(this, "Couldn't add photo: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Full-size photo viewer with previous / next / delete. */
    private fun showPhoto(title: String, ownerId: String, index: Int) {
        val list = photosOf(ownerId) ?: return
        if (list.isEmpty()) return
        val i = index.coerceIn(0, list.lastIndex)
        val name = list[i]
        val maxPx = resources.displayMetrics.widthPixels
        val image = android.widget.ImageView(this).apply {
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setImageBitmap(photos.loadScaled(name, maxPx))
        }
        val taken = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault())
            .format(Date(photos.file(name).lastModified()))
        val b = MaterialAlertDialogBuilder(this)
            .setTitle("$title · ${i + 1} of ${list.size}")
            .setMessage(taken)
            .setView(image)
            .setPositiveButton("Close") { _, _ -> openPhotoOwner(ownerId) }
            .setNeutralButton("Delete") { _, _ ->
                confirm("Delete this photo?") {
                    list.removeAt(i)
                    photos.delete(name)
                    photosChanged(ownerId)
                    if (list.isNotEmpty()) showPhoto(title, ownerId, i) else openPhotoOwner(ownerId)
                }
            }
        if (list.size > 1) b.setNegativeButton("Next ▶") { _, _ -> showPhoto(title, ownerId, (i + 1) % list.size) }
        b.show()
    }

    private fun markMenu(mark: Mark) {
        val dp = resources.displayMetrics.density
        val time = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(Date(mark.time))
        val dist = lastPos()?.let { "\n" + Geo.fromBoat(it, mark.pos) } ?: ""
        val pad = (24 * dp).toInt()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (4 * dp).toInt(), pad, 0)
        }
        body.addView(TextView(this).apply {
            text = "$time\n${Geo.formatDdm(mark.lat, true)}  ${Geo.formatDdm(mark.lon, false)}" +
                (mark.depth?.let { "\nDepth (est.): ${Geo.depthText(it)}" } ?: "") + dist +
                "\nGroup: ${groups.find(mark.group)?.label ?: "Ungrouped"}" +
                if (groups.isVisible(mark.group)) "" else " (hidden on the map)"
            textSize = 14f
            setTextColor(look.muted)
        })
        lateinit var dialog: androidx.appcompat.app.AlertDialog

        // Photo strip: the mark's own, then those of its catches
        val caught = catchesAt(mark)
        val shots = mark.photos.mapIndexed { i, name -> Triple(name, i, null as Catch?) } +
            caught.flatMap { c -> c.photos.mapIndexed { i, name -> Triple(name, i, c) } }
        if (shots.isNotEmpty()) {
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val size = (72 * dp).toInt()
            shots.forEach { (name, i, c) ->
                strip.addView(android.widget.ImageView(this).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(photos.loadScaled(name, 256))
                    background = look.surfaceBg(1f, 8f)
                    clipToOutline = true
                    setOnClickListener { dialog.dismiss(); showPhoto(c?.title ?: mark.name, c?.id ?: mark.id, i) }
                }, LinearLayout.LayoutParams(size, size).apply { marginEnd = (6 * dp).toInt() })
            }
            body.addView(android.widget.HorizontalScrollView(this).apply {
                setPadding(0, (12 * dp).toInt(), 0, 0)
                addView(strip)
            })
        }

        // Actions
        val only = caught.singleOrNull()
        val actions: List<Pair<String, () -> Unit>> = (if (caught.size <= SHOWN_CATCHES) caught else caught.take(SHOWN_CATCHES - 1)).map { c ->
            "🐟  ${c.title} · ${c.details()}" to { catchDialogs.view(c) }
        } + (if (caught.size <= SHOWN_CATCHES) emptyList() else
            listOf<Pair<String, () -> Unit>>("All ${caught.size} catches here" to { catchDialogs.list(mark.name, caught) })) + listOf(
            "Add a catch here" to { markCatch(mark) },
            // With one catch, a photo belongs to it; otherwise it's of the spot.
            "📷  Take photo" to { takePhotoFor(only?.id ?: mark.id) },
            "🖼  Add photo from gallery" to { pickPhotoFor(only?.id ?: mark.id) },
            "Navigate to" to {
                startNavigation(Route(name = mark.name, waypoints = mutableListOf(Waypoint(mark.lat, mark.lon, mark.name))))
            },
            "Move to group…" to { marksPanel.pickGroup("Move ${mark.name} to") { g -> g?.let { marksPanel.move(listOf(mark), it) } } },
            "Rename" to { askText("Rename mark", mark.name) { mark.name = it; store.saveMarks(marks); showMarks() } },
            "Delete mark" to {
                val what = "Delete ${mark.name}" + (if (mark.photos.isNotEmpty()) " and its ${mark.photos.size} photo(s)" else "") + "?"
                if (caught.isEmpty()) confirm(what) { deleteMark(mark) }
                else MaterialAlertDialogBuilder(this)
                    .setMessage("$what\n\nIt has ${if (caught.size == 1) "a catch" else "${caught.size} catches"} in the catch log.")
                    .setPositiveButton(if (caught.size == 1) "Delete mark and catch" else "Delete mark and catches") { _, _ ->
                        caught.forEach { c -> c.photos.forEach { photos.delete(it) } }
                        tripLog.catches.removeAll { it.markId == mark.id }
                        tripLog.saveCatches()
                        deleteMark(mark)
                        tripsPanel.refresh()
                    }
                    .setNeutralButton("Mark only") { _, _ -> deleteMark(mark) }
                    .setNegativeButton(android.R.string.cancel, null).show()
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
        val sorted = sortedMarks(marks)
        MaterialAlertDialogBuilder(this).setTitle("Marks")
            .setItems(sorted.map { markLabel(it) }.toTypedArray()) { _, which -> goToMark(sorted[which]) }
            .setNeutralButton("Groups…") { _, _ -> marksPanel.show() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    /** Nearest first with a GPS fix, otherwise newest first. */
    private fun sortedMarks(list: List<Mark>): List<Mark> =
        lastPos()?.let { here -> list.sortedBy { Geo.distanceNm(here, it.pos) } } ?: list.sortedByDescending { it.time }

    private fun markLabel(m: Mark): String {
        val dist = lastPos()?.let { " · " + Geo.fmtDist(Geo.distanceNm(it, m.pos)) }.orEmpty()
        return m.name + dist + (m.depth?.let { " · ${Geo.depthText(it)}" } ?: "")
    }

    private fun goToMark(m: Mark) {
        map?.locationComponent?.takeIf { locationActive }?.cameraMode = CameraMode.NONE
        map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(m.lat, m.lon)))
        markMenu(m)
    }

    private fun toggleAnchor() {
        if (anchor != null) {
            anchor = null
        } else {
            anchor = lastPos() ?: run { Toast.makeText(this, "Waiting for GPS fix", Toast.LENGTH_SHORT).show(); return }
        }
        overlays.setAnchor(anchor, settings.anchorRadiusM)
        refreshModePanel()
        // Above the mode buttons, not over them as a toast would be.
        snack(if (anchor != null) "Anchor alarm set: ${settings.anchorRadiusM.toInt()} m" else "Anchor alarm off").setDuration(2500).show()
        updateBanner()
    }

    // =========================================================================
    // Trips and catches
    // =========================================================================

    private fun createTripsPanel() = TripsPanel(this, panel, look, tripLog, object : TripsPanel.Host {
        override val track get() = this@MainActivity.track
        override val savedTripLayerOn get() = layers.isOn(MapOverlay.SAVED_TRIP)
        override val shownTrip get() = this@MainActivity.shownTrip
        override var allCatchesOnMap: Boolean
            get() = getSharedPreferences("app", MODE_PRIVATE).getBoolean("trips_all_catches", false)
            set(v) {
                getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("trips_all_catches", v).apply()
                showCatchesOnMap()
            }
        override fun beforeShow() { displayPanelOpen = false }
        override fun currentCatches() = this@MainActivity.currentCatches()
        override fun catchesOf(t: Trip) = this@MainActivity.catchesOf(t)
        override val recording get() = this@MainActivity.recording
        override val recordingStart get() = recStart()
        override fun startTrip() = this@MainActivity.startTrip(null)
        override fun stopTrip() = askStopTrip()
        override fun showTrip(t: Trip?, points: List<TrackPoint>, zoom: Boolean) = showTripOnMap(t, points, zoom)
        override fun setCursor(p: LatLon?) = overlays.setTripCursor(p)
        override fun logCatch() = markCatch()
        override fun showCatches(title: String, list: List<Catch>) = catchDialogs.list(title, list)
        override fun exportText(fileName: String, csv: Boolean, text: String) = this@MainActivity.exportText(fileName, csv, text)
        override fun confirm(message: String, onYes: () -> Unit) = this@MainActivity.confirm(message, onYes = onYes)
        override fun askText(title: String, initial: String, onOk: (String) -> Unit) = this@MainActivity.askText(title, initial, onOk)
    })

    private fun createCatchDialogs() = CatchDialogs(this, look, tripLog, photos, object : CatchDialogs.Host {
        override fun changed() { showCatchesOnMap(); showMarks(); tripsPanel.refresh() }
        override fun goTo(p: LatLon) {
            map?.locationComponent?.takeIf { locationActive }?.cameraMode = CameraMode.NONE
            map?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(p.lat, p.lon)))
        }
        override fun takePhoto(id: String) = takePhotoFor(id)
        override fun pickPhoto(id: String) = pickPhotoFor(id)
        override fun showPhoto(title: String, ownerId: String, index: Int) = this@MainActivity.showPhoto(title, ownerId, index)
        override fun markOf(c: Catch) = c.markId?.let { id -> marks.firstOrNull { it.id == id } }
        override fun catchesAt(m: Mark) = this@MainActivity.catchesAt(m)
        override fun openMark(m: Mark) = goToMark(m)
        override fun deleteMark(m: Mark) = this@MainActivity.deleteMark(m)
        override fun adding(c: Catch) {
            val p = c.pos ?: return   // no fix: it goes in the catch log only
            if (markOf(c) != null) return
            val mark = Mark(lat = p.lat, lon = p.lon, name = if (c.species.isBlank()) nextMarkName() else c.title, depth = c.depth)
            c.markId = mark.id
            storeMark(mark)
        }
        override fun edited(c: Catch, oldTitle: String) {
            // Keep a name the user chose; follow the species otherwise.
            val m = markOf(c) ?: return
            if (c.species.isBlank() || catchesAt(m).size > 1) return
            if (m.name != oldTitle && !AUTO_NAME.matches(m.name)) return
            m.name = c.title
            store.saveMarks(marks)
            marksPanel.refresh()
        }
        override fun added(c: Catch, photo: Boolean) {
            buzz(short = true)
            val started = settings.autoStartTrip && !recording && c.pos != null
            if (started) startTrip(null, quiet = true)
            if (photo) {
                if (started) Toast.makeText(this@MainActivity, "Trip recording started", Toast.LENGTH_SHORT).show()
                takePhotoFor(c.id)
                return
            }
            snack((if (c.markId == null) "No GPS fix, so logged without a mark: " else "Marked ") + c.title +
                if (started) ". Trip recording started." else "")
                .setAction("📷 Photo") { takePhotoFor(c.id) }
                .show()
        }
        override fun exportText(fileName: String, csv: Boolean, text: String) = this@MainActivity.exportText(fileName, csv, text)
        override fun confirm(message: String, onYes: () -> Unit) = this@MainActivity.confirm(message, onYes = onYes)
    })

    /** A message along the bottom in the app's colours, above the mode buttons so it never hides them. */
    private fun snack(text: CharSequence): Snackbar =
        Snackbar.make(mapView, text, Snackbar.LENGTH_LONG).apply {
            setBackgroundTint(ContextCompat.getColor(this@MainActivity, R.color.ui_surface))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ui_on_surface))
            setActionTextColor(look.accent)
            if (modePanel.visibility == View.VISIBLE) anchorView = modePanel
        }

    /** Mark a catch at the boat, or add one to [mark]. Without a GPS fix it's only logged. */
    private fun markCatch(mark: Mark? = null) {
        val at = mark?.pos ?: lastPos()
        val depth = mark?.depth ?: at?.let { estimateDepthAt(LatLng(it.lat, it.lon)) }
        catchDialogs.edit(Catch(lat = at?.lat, lon = at?.lon, markId = mark?.id,
            depth = depth?.takeIf { it != "--" && it != "off screen" && it != "land" }), isNew = true)
    }

    private fun tripSpans() = tripLog.trips.map { it.start to it.end }
    private fun currentCatches() = track.stats.let { tripLog.catchesIn(it.start, it.end, tripSpans()) }
    private fun catchesOf(t: Trip) = tripLog.catchesIn(t.start, t.end, tripSpans() + (track.stats.start to track.stats.end))

    private fun showCatchesOnMap() {
        val all = getSharedPreferences("app", MODE_PRIVATE).getBoolean("trips_all_catches", false)
        // Anything recent is always shown, so a new catch is never invisible.
        val recent = System.currentTimeMillis() - 12 * 3_600_000L
        val list = if (all) tripLog.catches else
            (currentCatches() + (tripLog.trip(shownTrip)?.let { catchesOf(it) } ?: emptyList()) +
                tripLog.catches.filter { it.time >= recent }).distinctBy { it.id }
        // One symbol per spot: a catch with a mark is shown by the mark.
        val marked = marks.map { it.id }.toSet()
        overlays.setCatches(list.filter { it.markId !in marked }) { id -> photos.bubble(id) }
    }

    private fun showTripOnMap(t: Trip?, points: List<TrackPoint>, zoom: Boolean) {
        shownTrip = t?.id
        overlays.setTrip(if (t == null) emptyList() else TripStats.split(points, TripStats.GAP_MS).map { part -> part.map { it.pos } })
        showCatchesOnMap()
        val m = map ?: return
        if (t == null || !zoom || points.size < 2) return
        if (locationActive) m.locationComponent.cameraMode = CameraMode.NONE
        runCatching {
            val b = LatLngBounds.Builder().includes(points.map { LatLng(it.lat, it.lon) }).build()
            // Leave room for the panel at the bottom. A trip that hardly moved would zoom in to the
            // map's limit, so stop at a useful scale.
            val fit = m.getCameraForLatLngBounds(b, intArrayOf(80, 160, 80, (mapView.height * 0.45).toInt()))
            if (fit != null) m.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(fit).zoom(minOf(fit.zoom, MAX_FIT_ZOOM)).build()))
        }
    }

    /** Save the track as a finished trip and start afresh. Returns how many trips, or null if saving failed. */
    private fun archiveTrack(keepAll: Boolean): Int? {
        val pts = track.points.toList()
        val saved = runCatching {
            tripLog.archive(pts, settings.tripSplitMs, keepAll) { t ->
                tripLog.catchesIn(t.start, t.end, tripSpans()).isNotEmpty() || marks.any { it.time in t.start..t.end }
            }
        }.getOrNull() ?: return null
        clearTrack()
        return saved.size
    }

    private fun clearTrack() {
        track.clear()
        // After any save that is still waiting, or that would bring the old track back.
        io.execute { store.saveTrack(emptyList()) }
        overlays.setTrack(visibleTrack())
        showCatchesOnMap()
    }

    /** Add a fix to the trip being recorded. Returns true if a point was added. */
    private fun recordTrack(loc: Location, pos: LatLon, sog: Double?): Boolean {
        if (!recording) return false
        if (loc.hasAccuracy() && loc.accuracy > 100f) return false   // too rough for a log
        if (finishStaleTrip(loc.time)) return false
        val last = track.points.lastOrNull()
        if (last != null) {
            if (loc.time <= last.t && last.t - loc.time < 60_000) return false
            if (track.isFull) archiveTrack(keepAll = true)   // carries on as a new trip
        }
        return track.add(pos, loc.time, sog, settings.trackSpacingM)
    }

    /**
     * A trip left recording with no fixes for longer than the break in Settings (phone off, app closed):
     * save it and stop, rather than join it to whatever happens next. Returns true if it did.
     */
    private fun finishStaleTrip(now: Long = System.currentTimeMillis()): Boolean {
        val gap = settings.tripSplitMs ?: return false
        val last = track.points.lastOrNull() ?: return false
        if (!recording || now - last.t <= gap) return false
        val n = archiveTrack(keepAll = true)
        recording = false
        recordingChanged()
        if (n != null) Toast.makeText(this, "Your last trip was saved and recording stopped: nothing was recorded for " +
            "over ${Geo.fmtDuration(gap / 3_600_000.0)}.", Toast.LENGTH_LONG).show()
        return true
    }

    /** Start recording a trip. [message] is shown with an Undo; [quiet] shows nothing (the caller says it). */
    private fun startTrip(message: String?, quiet: Boolean = false) {
        if (recording) return
        if (!hasLocationPermission()) {
            Toast.makeText(this, R.string.location_needed, Toast.LENGTH_LONG).show()
            return
        }
        // A track left over while not recording (from a loaded backup, say) is its own trip, not the start of this one.
        if (track.points.size >= 2) archiveTrack(keepAll = true) else if (track.points.isNotEmpty()) clearTrack()
        recording = true
        recordingSince = System.currentTimeMillis()
        recordingChanged(ask = true)
        ticker.removeCallbacks(tick); ticker.post(tick)
        lastFix?.let { recordTrack(it, LatLon(it.latitude, it.longitude), if (it.hasSpeed()) it.speed * Geo.KN_PER_MS else null) }
        if (quiet) return
        snack(message ?: if (look.showRecordButton) "Recording a trip. To stop, press and hold the red light." else "Recording a trip.")
            .setAction("Undo") { recording = false; clearTrack(); recordingChanged() }
            .show()
    }

    /** Stopping always asks: save, keep going, or throw the track away. */
    private fun askStopTrip() {
        if (!recording) return
        val s = track.stats
        if (track.points.size < 2) {
            confirm("Stop recording? The boat hasn't moved yet, so there's no track to save. " +
                "Catches and marks are kept.", yes = "Stop", no = "Keep recording") {
                recording = false; clearTrack(); recordingChanged()
            }
            return
        }
        val n = currentCatches().size
        val what = listOfNotNull(
            Geo.fmtDuration((System.currentTimeMillis() - recStart()) / 3_600_000.0),
            Geo.fmtDist(s.distanceNm),
            if (n > 0) "$n catch" + (if (n == 1) "" else "es") else null).joinToString(" · ")
        MaterialAlertDialogBuilder(this)
            .setTitle("Stop recording this trip?")
            .setMessage("$what\n\nIt's saved in Trips & catches, where you can look back at it or export it.")
            .setPositiveButton("Stop and save") { _, _ ->
                val saved = archiveTrack(keepAll = true)
                if (saved == null) {
                    Toast.makeText(this, "Couldn't save the trip, so it's still recording.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                recording = false
                recordingChanged()
                Toast.makeText(this, "Trip saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Keep recording", null)
            .setNeutralButton("Discard…") { _, _ ->
                confirm("Throw away the track of this trip? This can't be undone. Catches and marks are kept.") {
                    recording = false; clearTrack(); recordingChanged()
                }
            }
            .show()
    }

    /** Everything that shows or depends on whether a trip is recording. */
    private fun recordingChanged(ask: Boolean = false) {
        updateRecording(ask)
        updateModeLabel()
        overlays.setTrack(visibleTrack())
        tripsPanel.refresh()
    }

    /** "● REC 1:23 · CHART ▾" while recording if the Record button is off, so it's always obvious. */
    private fun updateModeLabel() {
        if (!::modeButton.isInitialized) return
        updateRecordButton()
        val label = "${mode.label} ▾"
        if (!recording || look.showRecordButton) { modeButton.text = label; modeButton.contentDescription = "Mode: ${mode.label}"; return }
        val time = recTime()
        val rec = "●  REC $time"
        val text = android.text.SpannableString("$rec   $label")
        text.setSpan(android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, R.color.ui_danger_text)),
            0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        modeButton.text = text
        modeButton.contentDescription = "Mode: ${mode.label}. Recording a trip, $time"
    }

    /** Time since recording started: "4:07" (minutes and seconds), then "1:04:07". */
    /** When this trip started: the tap on Record, or its first point if that's earlier. */
    private fun recStart(): Long {
        val now = System.currentTimeMillis()
        val first = track.points.firstOrNull()?.t
        // Trips recorded before 0.17.4 have no start time: go by the track, or start the clock now.
        if (recordingSince <= 0L || recordingSince > now) recordingSince = first ?: now
        return minOf(recordingSince, first ?: recordingSince)
    }

    private fun recTime(): String {
        val sec = ((System.currentTimeMillis() - recStart()) / 1000).coerceAtLeast(0)
        return if (sec < 3600) String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
        else String.format(Locale.US, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
    }

    /** Fades the red light in and out while recording and the app is on screen. */
    private var recPulse: android.animation.ObjectAnimator? = null

    /**
     * "Record a trip" with a grey dot when not recording. While recording: a red light that slowly fades in
     * and out, and the time. Quiet on purpose; press and hold to stop.
     */
    private fun updateRecordButton() {
        if (!::recButton.isInitialized) return
        val b = recButton.button
        val dot = findViewById<View>(R.id.recDot)
        val label = findViewById<TextView>(R.id.recText)
        val on = look.showRecordButton
        b.visibility = if (on) View.VISIBLE else View.GONE
        val pulse = on && recording && started
        if (pulse && recPulse == null) {
            recPulse = android.animation.ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.15f).apply {
                duration = 1400
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                start()
            }
        } else if (!pulse && recPulse != null) {
            recPulse?.cancel(); recPulse = null; dot.alpha = 1f
        }
        if (!on) return
        label.text = if (recording) recTime() else "Record"
        b.contentDescription = if (recording) "Recording a trip, ${recTime()}. Press and hold to stop." else "Record a trip"
        // The clock ticks every second: restyle only when something else changed.
        val scale = look.controlScale
        val key = "$recording ${look.controlAlpha} $scale"
        if (b.tag == key) return
        b.tag = key
        val dp = resources.displayMetrics.density
        dot.background = look.circleBg(1f, if (recording) ContextCompat.getColor(this, R.color.ui_danger_text) else look.muted)
        dot.layoutParams = dot.layoutParams.apply { width = (10 * dp * scale).toInt(); height = width }
        label.fontFeatureSettings = "tnum"   // same-width digits, so the button doesn't twitch as the seconds change
        label.textSize = 13f * scale
        label.setTextColor(if (recording) look.muted else look.onSurface)
        label.setTypeface(null, if (recording) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
        b.minimumHeight = controlSizePx()
        b.background = look.surfaceBg(look.controlAlpha, 22f * scale)
        b.post { recButton.place(); bagButton.place() }
    }

    private fun onRecordTap() {
        if (!recording) { startTrip(null); return }
        val s = track.stats
        snack("Recording ${recTime()} · ${Geo.fmtDist(s.distanceNm)}. To stop, press and hold the red light.").show()
    }

    /** Keep GPS coming with the screen off while recording, if the user wants that. */
    private fun updateRecording(ask: Boolean) {
        if (!recording || !settings.recordInBackground || !hasLocationPermission()) { TrackService.stop(this); return }
        if (ask && android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (started) TrackService.start(this)
    }

    private fun exportText(fileName: String, csv: Boolean, text: String) {
        pendingExport = null
        pendingText = text
        (if (csv) exportCsvFile else exportGpxFile).launch(fileName)
    }

    // =========================================================================
    // Offline areas and updates
    // =========================================================================

    private fun createOfflinePanel(prefs: android.content.SharedPreferences) =
        OfflinePanel(this, panel, look, prefs, offlineAreas, object : OfflinePanel.Host {
            override fun screenBox(): DoubleArray? {
                val m = map ?: return null
                if (mapView.width == 0) return null
                val b = m.projection.visibleRegion.latLngBounds
                return doubleArrayOf(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth)
            }
            override fun templates() = offlineTemplates()
            override fun zoomTo(a: OfflineArea) {
                map?.locationComponent?.takeIf { locationActive }?.cameraMode = CameraMode.NONE
                map?.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(a.north, a.east, a.south, a.west), 40))
            }
            override fun savedChanged() { locationActive = false; loadStyle() }
            override fun beforeShow() { displayPanelOpen = false }
            override fun confirm(message: String, onYes: () -> Unit) = this@MainActivity.confirm(message, onYes = onYes)
            override fun askText(title: String, initial: String, onOk: (String) -> Unit) = this@MainActivity.askText(title, initial, onOk)
        })

    /** Tile URL of each layer that can be saved, as the online map uses it. */
    private fun offlineTemplates(): Map<OfflineLayer, String> {
        val sources = JSONObject(assets.open("chart-style.json").bufferedReader().use { it.readText() }).getJSONObject("sources")
        fun tiles(id: String) = sources.getJSONObject(id).getJSONArray("tiles").getString(0)
        return mapOf(
            OfflineLayer.SATELLITE to tiles("satellite"),
            OfflineLayer.SATELLITE_LAND to tiles("satellite-land"),
            OfflineLayer.RELIEF to RELIEF_ONLINE,
        )
    }

    private fun createUpdater(prefs: android.content.SharedPreferences) =
        Updater(this, prefs, userAgent, io, object : Updater.Host {
            override val chartFile get() = this@MainActivity.chartFile
            override val reliefFile get() = this@MainActivity.reliefFile
            override fun importDownloaded(f: File, onDone: (Boolean) -> Unit) =
                importChartFile(Uri.fromFile(f)) { ok -> f.delete(); onDone(ok) }
        })

    // =========================================================================
    // AIS vessels
    // =========================================================================

    private fun createAis() = Ais(
        // Open Waters wants a name, version and contact link.
        userAgent = userAgent,
        maxAgeMin = { settings.aisMaxAgeMin },
        view = {
            map?.takeIf { mapView.width > 0 }?.projection?.visibleRegion?.latLngBounds?.let { b ->
                AisBox(b.southWest.latitude, b.southWest.longitude, b.northEast.latitude, b.northEast.longitude)
            }?.takeIf { it.s.isFinite() && it.w.isFinite() && it.n > it.s && it.e > it.w }
        },
        onChanged = { if (aisLabel.visibility == View.VISIBLE) aisLabel.text = ais.status() },
    )

    private fun updateAis() {
        val on = layers.isOn(MapOverlay.AIS)
        aisLabel.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) { ais.clear(); return }
        aisLabel.text = ais.status()
        if (started) ais.start()
    }

    private fun showVessel(v: Vessel) {
        val here = lastPos()
        val min = (System.currentTimeMillis() - v.seen) / 60_000
        val size = if (v.lengthM > 0) "${v.lengthM} m" + (if (v.beamM > 0) " × ${v.beamM} m" else "") else null
        val lines = listOfNotNull(
            listOfNotNull(v.typeName, size, v.statusName).joinToString(" · "),
            "Last heard " + if (min < 1) "less than a minute ago" else "$min min ago",
            v.sog?.let { s -> String.format(Locale.US, "%.1f kn", s) + (v.cog?.takeIf { v.moving }?.let { " → ${Geo.fmtBearing(it)}" } ?: "") },
            here?.let { Geo.fromBoat(it, v.pos) },
            v.destination.takeIf { it.isNotBlank() }?.let { "Destination: $it" },
            listOfNotNull("MMSI ${v.mmsi}", v.callsign.takeIf { it.isNotBlank() }, v.flag.takeIf { it.isNotBlank() }).joinToString(" · "),
            "${Geo.formatDdm(v.lat, true)}  ${Geo.formatDdm(v.lon, false)}",
            "",
            "Open Waters AIS https://openwaters.io/ais/\n" +
                if (v.source == "aisstream") "Received by aisstream.io" else "Data: AISHub contributors https://www.aishub.net",
        )
        showLinkDialog(v.label, lines.joinToString("\n"))
    }

    private fun showAisInfo() = showLinkDialog("Vessels (AIS)",
        "Boats that transmit AIS, picked up by volunteer shore stations and passed on over the internet. Positions run a minute " +
            "or more behind, and boats without AIS or out of range of a station aren't shown.\n\n" +
            "Arrow: moving (the line shows 6 minutes ahead). Dot: stopped. Grey: not heard for 10 minutes. " +
            "Change how long they stay in Settings, the size in Display and the opacity in Layers.\n\n" +
            "Open Waters AIS https://openwaters.io/ais/\nData: AISHub contributors https://www.aishub.net\naisstream.io https://aisstream.io")

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
        (hitFeature(point, NavOverlays.CATCH_PHOTOS) ?: hitFeature(point, NavOverlays.CATCHES))?.getStringProperty("id")?.let { id ->
            tripLog.catches.firstOrNull { it.id == id }?.let { catchDialogs.view(it); return }
        }
        hitFeature(point, Ais.LAYER)?.getStringProperty("mmsi")?.toLongOrNull()?.let { mmsi ->
            ais.find(mmsi)?.let { showVessel(it); return }
        }
        hitFeature(point, Hazards.LAYER)?.let { showHazard(it); return }
        showZoneInfo(point)
    }

    private fun onMapLongPress(point: LatLng) {
        if (fields.editing || mode == AppMode.ROUTE) return
        val chart = chartAt(point.latitude, point.longitude)
        val depth = chart?.let { depthOf(it) } ?: renderedDepthAt(point)
        val p = LatLon(point.latitude, point.longitude)
        val dist = lastPos()?.let { "\n" + Geo.fromBoat(it, p) } ?: ""
        // Where the depth came from and how good it is (charts from 0.17.12 on). Not for land.
        val source = chart?.takeIf { !it.land }?.let { ChartReader.sourceLines(it.source) }.orEmpty()
        val about = if (source.isEmpty()) "" else "\n\n" + source.joinToString("\n") +
            "\nDepths are from about mean sea level: less water at low tide."
        val tide = tideNow(p)?.let { (h, rising) ->
            "\n\nTide now (est.): " + String.format(Locale.US, "%+.2f m, %s", h, if (rising) "rising" else "falling") +
                (chart?.let { depthNow(it, p) }?.takeIf { it != "--" && it != "land" }?.let { ". Depth now about $it m" } ?: "")
        }.orEmpty()
        val windHere = wind.forecast?.at(p.lat, p.lon, windTime ?: System.currentTimeMillis())?.let {
            "\nWind " + (if (windTime == null) "now" else windLabel(windTime!!)) + " (forecast): " + it.text()
        }.orEmpty() + wind.forecast?.wavesAt(p.lat, p.lon, windTime ?: System.currentTimeMillis())?.let {
            "\nWaves (forecast): " + it.text()
        }.orEmpty()
        MaterialAlertDialogBuilder(this)
            .setTitle("${Geo.formatDdm(p.lat, true)}  ${Geo.formatDdm(p.lon, false)}")
            .setMessage("Depth (est.): ${Geo.depthText(depth)}$dist$about$tide$windHere")
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
            lastPos()?.let { Geo.fromBoat(it, p) },
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
            "Search places…" to { placesPanel.open() },
            "Wind, waves & tide…" to { showWindTidePanel() },
            "Marks…" to { marksPanel.show() },
            "Trips & catches…" to { tripsPanel.show() },
            "Bag & size limits…" to { bagPanel.open() },
            "Display…" to { showDisplayPanel() },
            "Settings…" to { settings.show(this) { onSettingsChanged() } },
            "Charts, offline areas & updates…" to { showChartManager() },
            "Data: save, load, export, import…" to { showDataMenu() },
            "About" to { showAbout() },
        )
        items.forEachIndexed { i, (label, _) -> popup.menu.add(0, i, i, label) }
        popup.setOnMenuItemClickListener { items[it.itemId].second(); true }
        popup.show()
    }

    private var fixCount = 0

    /** Track for the chosen history window, always joined up to the boat's current position. */
    private fun visibleTrack(): List<LatLon> {
        val pts = track.window(settings.trackWindowMin)
        if (!recording) return pts
        val here = lastPos() ?: return pts
        return if (pts.isEmpty() || pts.last() != here) pts + here else pts
    }

    private fun onSettingsChanged() {
        overlays.setTrack(visibleTrack())
        overlays.setAnchor(anchor, settings.anchorRadiusM)
        updateRecording(ask = true)
        ais.redraw()
        refreshModePanel()
        if (mode == AppMode.NAVIGATE || mode == AppMode.FISH) {
            followBoat(if (mode == AppMode.FISH) settings.fishZoom else null)
        }
    }

    private fun showFieldsDialog() {
        val all = DataField.entries
        val checked = all.map { fields.isVisible(it) }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Fields shown: ${mode.label} mode")
            .setMultiChoiceItems(all.map { it.label.replace("  ", " ") }.toTypedArray(), checked) { _, which, on ->
                fields.setVisible(all[which], on)
            }
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { refreshDisplayPanel() }
            .show()
    }

    /** Separate draggable cards, or all fields combined into one neat panel. */
    private fun showGroupingDialog() {
        val options = DataFieldsOverlay.Grouping.entries
        MaterialAlertDialogBuilder(this)
            .setTitle("Arrangement: ${mode.label} mode")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), options.indexOf(fields.grouping)) { d, which ->
                fields.grouping = options[which]
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { refreshDisplayPanel() }
            .show()
    }

    private fun showAbout() {
        val version = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName} (build ${PackageInfoCompat.getLongVersionCode(info)})"
        }.getOrDefault("unknown")
        val chart = if (chartFile.exists()) fileInfo(chartFile) else "not installed"
        MaterialAlertDialogBuilder(this)
            .setIcon(R.mipmap.ic_launcher)
            .setTitle(R.string.app_name)
            .setMessage("Version $version\nChart: $chart\n\nFree offline fishing chartplotter for Western Australia.\n" +
                "Source code, data credits and licences are on GitHub:\n$GITHUB_URL")
            .setPositiveButton("Open GitHub") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))) }
                    .onFailure { Toast.makeText(this, "No app found to open the link", Toast.LENGTH_SHORT).show() }
            }
            .setNeutralButton("Updates") { _, _ -> updater.checkNow() }
            .setNegativeButton("Close", null)
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
        groups.reload(marks)
        marksPanel.refresh()
        plan = store.plan()
        track = Track(store.track())
        if (tripLog.trip(shownTrip) == null) showTripOnMap(null, emptyList(), false)
        tripsPanel.refresh()
        updateRecording(ask = false)
        updateAis()
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
                    pendingText = null
                    exportJsonFile.launch("chompchamp-backup-${stamp("yyyyMMdd-HHmm")}.json")
                }
            },
            "Export GPX (marks, routes, track)…" to {
                val gpxParts = listOf(DataPart.MARKS, DataPart.ROUTES, DataPart.TRACK)
                pickParts("Export GPX", gpxParts, setOf(DataPart.MARKS, DataPart.ROUTES)) { parts ->
                    pendingExport = true to parts
                    pendingText = null
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
        val ready = pendingText
        val (gpx, parts) = pendingExport ?: (false to emptySet<DataPart>())
        if (ready == null && parts.isEmpty()) return
        pendingExport = null
        pendingText = null
        runCatching {
            persistAll()
            val text = ready ?: if (gpx) userData.exportGpx(parts) else userData.export(parts)
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
            if (isGpx && DataPart.MARKS in parts) {
                // Keeping imports in their own group makes them easy to hide or delete again.
                val named = b.marks.map { it.group }.filter { it.isNotEmpty() }.distinct().size
                marksPanel.pickGroup("Put ${b.marks.size} marks in", suggestion = fileBaseName(uri),
                    keep = if (named > 0) "Groups from the file ($named)" else null) { target ->
                    if (target != null) b.marks.forEach { it.group = target }
                    applyBundle(b, parts, merge = true)
                }
            } else if (isGpx) {
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

    private fun fileBaseName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull().orEmpty().substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()

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
                    .setMessage("Loading replaces the routes, marks, current track and settings on this phone with this snapshot. " +
                        "Saved trips and the catch log stay as they are.")
                    .setPositiveButton("Load") { _, _ ->
                        runCatching { userData.readSnapshot(name) }
                            .onSuccess { applyBundle(it, userData.snapshotParts, merge = false) }
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
        displayPanelOpen = false
        panel.show("Map layers", { rows ->
            layers.fillPanel(rows, map?.style?.takeIf { chartFile.exists() })
        })
    }

    private var displayPanelOpen = false

    /** Everything about how the screen looks, in one docked panel that previews live. */
    private fun showDisplayPanel() {
        panel.show("Display", { rows ->
            rows.section("Data fields · ${mode.label} mode")
            rows.actionRow("Fields shown", "${DataField.entries.count { fields.isVisible(it) }}") { showFieldsDialog() }
            rows.actionRow("Arrangement", fields.grouping.short) { showGroupingDialog() }
            rows.actionRow("Move or resize on screen", "Edit") { panel.hide(); setEditing(true) }
            look.fillPanel(rows, onChanged = { applyAppearance() })
            rows.section("Map")
            rows.actionRow("Scale bar", if (look.showScaleBar) "On" else "Off") {
                look.showScaleBar = !look.showScaleBar
                applyAppearance()
                updateScaleBar()
                refreshDisplayPanel()
            }
            rows.actionRow("AIS vessel colour", look.aisColours.first { it.first == look.aisColourKey }.second) {
                val opts = look.aisColours
                MaterialAlertDialogBuilder(this).setTitle("AIS vessel colour")
                    .setSingleChoiceItems(opts.map { it.second }.toTypedArray(), opts.indexOfFirst { it.first == look.aisColourKey }) { d, i ->
                        look.aisColourKey = opts[i].first
                        ais.setColour(look.aisColour)
                        d.dismiss()
                        refreshDisplayPanel()
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            boatPicker(rows)
            rows.actionRow("Record button", if (look.showRecordButton) "On" else "Off") {
                look.showRecordButton = !look.showRecordButton
                updateModeLabel()
                refreshDisplayPanel()
            }
            if (mode == AppMode.FISH) rows.actionRow("Bag limits button", if (look.showBagButton) "On" else "Off") {
                look.showBagButton = !look.showBagButton
                updateBagButton()
                refreshDisplayPanel()
            }
            rows.section("Reset")
            rows.actionRow("Layout · ${mode.label} mode", "Reset") {
                confirm("Are you sure you want to reset the ${mode.label} layout?\n\nData fields go back to their default " +
                    "position and size. Which fields are shown, and how they're arranged, stays the same.") {
                    fields.resetLayout()
                    recButton.reset()
                    if (mode == AppMode.FISH) bagButton.reset()
                    Toast.makeText(this, R.string.layout_reset_done, Toast.LENGTH_SHORT).show()
                }
            }
            rows.actionRow("Sizes and transparency · all modes", "Reset") {
                confirm("Are you sure you want to reset all sizes and transparency to their defaults?") {
                    look.reset()
                    applyAppearance()
                    refreshDisplayPanel()
                }
            }
        }, onClose = { displayPanelOpen = false })
        displayPanelOpen = true
    }

    /** A strip of boats to tap, and the hull colour. Size and opacity are under Map symbols. */
    private fun boatPicker(rows: SettingsPanel.Rows) {
        val dp = resources.displayMetrics.density
        rows.section("Your boat")
        val name = TextView(this).apply {
            text = look.boatShape.label
            textSize = 13f
            setTextColor(look.muted)
        }
        val tiles = mutableMapOf<BoatShape, View>()
        fun mark() = tiles.forEach { (s, v) -> v.background = if (s == look.boatShape) look.pillBg() else null }
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (shape in BoatShape.entries) {
            val tile = android.widget.ImageView(this).apply {
                setImageBitmap(boatIcons.draw(shape, look.boatColourKey, (44 * dp).toInt()))
                contentDescription = shape.label
                val pad = (6 * dp).toInt()
                setPadding(pad, pad, pad, pad)
                setOnClickListener {
                    look.boatShape = shape
                    name.text = shape.label
                    mark()
                    updateBoatIcon()
                }
            }
            tiles[shape] = tile
            strip.addView(tile, LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt()))
        }
        mark()
        val scroller = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
        rows.view(scroller)
        rows.view(name)
        // Keep the chosen boat in view when the panel opens.
        scroller.post { tiles[look.boatShape]?.let { scroller.scrollTo(maxOf(0, it.left - (56 * dp).toInt()), 0) } }
        val colours = boatIcons.colours
        rows.actionRow("Boat colour", colours.firstOrNull { it.first == look.boatColourKey }?.second ?: colours[0].second) {
            MaterialAlertDialogBuilder(this).setTitle("Boat colour")
                .setSingleChoiceItems(colours.map { it.second }.toTypedArray(),
                    colours.indexOfFirst { it.first == look.boatColourKey }.coerceAtLeast(0)) { d, i ->
                    look.boatColourKey = colours[i].first
                    updateBoatIcon()
                    d.dismiss()
                    refreshDisplayPanel()
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun refreshDisplayPanel() {
        if (displayPanelOpen && panel.isOpen) showDisplayPanel()
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
            styleCircle(b)
        }
        for ((label, button) in listOf(R.id.lblLayers to R.id.btnLayers, R.id.lblLocate to R.id.btnLocate)) {
            findViewById<TextView>(label).apply {
                styleButtonLabel(this)
                setOnClickListener { this@MainActivity.findViewById<View>(button).performClick() }
            }
        }
        modeButton.apply {
            minHeight = size
            textSize = 13f * scale
            setTextColor(look.onSurface)
            background = look.surfaceBg(bgAlpha, 22f * scale)
        }
        modeSummary.background = look.surfaceBg(bgAlpha, 14f)
        modeSummary.textSize = 13f * scale
        (modePanel.layoutParams as FrameLayout.LayoutParams).apply {
            marginStart = size + (20 * dp).toInt()
            marginEnd = size + (20 * dp).toInt()
        }
        modeButtons.tag = null   // rebuild buttons at the new size
        editBar.background = look.surfaceBg(0.9f, 22f)
        scaleBar.setLook(look.surface, scale, bgAlpha)
        scaleBar.visibility = if (look.showScaleBar) View.VISIBLE else View.GONE
        styleButtonLabel(aisLabel)
        windCredit.apply {
            textSize = 10f * scale
            setTextColor(look.onSurface)
            background = look.surfaceBg(bgAlpha, 6f * scale)
            setPadding((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
        }
        aisLabel.maxLines = 4
        aisLabel.gravity = Gravity.END
        aisLabel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        ais.setScale(look.aisScale)
        updateBoatIcon()
        ais.setColour(look.aisColour)
        overlays.setCatchScale(look.catchScale)
        map?.style?.let { places.setScale(it, look.placeScale) }
        updateBagButton()
        updateRecordButton()
        recButton.place()
        bagButton.place()
        fields.applyStyle()
        applyMapUiMargins()
        refreshModePanel()
    }

    /** Above the Follow button, or above the mode panel when that is taller. */
    private fun placeScaleBar() {
        val dp = resources.displayMetrics.density
        val aboveButton = controlSizePx() + ((14 + 14 * look.controlScale) * dp).toInt()
        val abovePanel = if (modePanel.visibility == View.VISIBLE) modePanel.height + (12 * dp).toInt() else 0
        val lp = scaleBar.layoutParams as FrameLayout.LayoutParams
        val margin = maxOf(aboveButton, abovePanel)
        if (lp.bottomMargin != margin) {
            lp.bottomMargin = margin
            scaleBar.layoutParams = lp
        }
        // Vessel status sits on top of the scale bar.
        val al = aisLabel.layoutParams as FrameLayout.LayoutParams
        val above = margin + if (scaleBar.visibility == View.VISIBLE) scaleBar.height + (2 * dp).toInt() else 0
        if (al.bottomMargin != above) {
            al.bottomMargin = above
            aisLabel.layoutParams = al
        }
    }

    private fun updateScaleBar() {
        val m = map ?: return
        if (scaleBar.visibility != View.VISIBLE || mapView.width == 0) return
        // Measured across the middle of the screen, so it's right at any zoom and latitude.
        val x = mapView.width / 2f
        val y = mapView.height / 2f
        val span = mapView.width / 4f
        val a = m.projection.fromScreenLocation(PointF(x - span / 2, y))
        val b = m.projection.fromScreenLocation(PointF(x + span / 2, y))
        val nm = Geo.distanceNm(LatLon(a.latitude, a.longitude), LatLon(b.latitude, b.longitude))
        scaleBar.setMetresPerPixel(nm * Geo.M_PER_NM / span)
    }

    private fun setEditing(on: Boolean) {
        fields.setEditing(on)
        bagButton.setEditing(on)
        recButton.setEditing(on)
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
        val attributionBottom = barInsets.bottom + size + ((20 + 16 * look.controlScale) * dp).toInt()
        ui.setAttributionMargins(barInsets.left + pad, 0, 0, attributionBottom)
        // The wind credit and time sit just right of the "i".
        (windCredit.layoutParams as FrameLayout.LayoutParams).let { lp ->
            lp.marginStart = barInsets.left + pad + (28 * dp).toInt()
            lp.bottomMargin = attributionBottom + (2 * dp).toInt()
            windCredit.layoutParams = lp
        }
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
            places.install(style, look.placeScale)
            hazards.install(style)
            ais.setColour(look.aisColour)
            ais.install(style, resources.displayMetrics.density)
            overlays.install(style)
            layers.apply(style)
            refreshOverlays()
            tripLog.trip(shownTrip)?.let { showTripOnMap(it, tripLog.points(it.id), zoom = false) }
            startLocation(style)
        }
    }

    private fun buildStyleJson(): String {
        val style = JSONObject(
            assets.open("chart-style.json").bufferedReader().use { it.readText() }
                .replace("{{CHART_URL}}", "pmtiles://file://${chartFile.absolutePath}")
        )
        // Label fonts ship with the app, so labels work offline from the first launch.
        style.put("glyphs", "asset://glyphs/{fontstack}/{range}.pbf")
        // Reef relief: offline pack if imported, otherwise straight from the DoT server.
        val relief = JSONObject().put("type", "raster").put("tileSize", 256)
        if (reliefFile.exists()) {
            relief.put("url", "pmtiles://file://${reliefFile.absolutePath}")
        } else {
            relief.put("tiles", JSONArray().put(RELIEF_ONLINE)).put("minzoom", 10).put("maxzoom", 18)
                .put("attribution", "Bathymetry imagery © WA Department of Transport (CC BY 4.0)")
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
        offlineAreas.addToStyle(style)
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
        items += "Save an area for offline use…" to { offlinePanel.show() }
        items += "Check for updates…" to { updater.checkNow() }
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
            confirm("Clear cached online satellite and reef relief tiles? They'll download again when needed. Areas you saved for offline use are kept.") {
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
            reliefFile.exists() -> "Charts · reef relief pack installed"
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
            // Comes from another app or a web page, so never replace the chart without asking.
            confirm("Import this file into ChompChamp?\n\nIt replaces the chart or relief pack you have installed. " +
                "Only continue if you trust where it came from.") { importChartFile(uri) }
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
    private fun importChartFile(uri: Uri, then: ((Boolean) -> Unit)? = null) {
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
                then?.invoke(result.isSuccess)
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
        addBoatImage(style)
        lc.activateLocationComponent(
            LocationComponentActivationOptions.builder(this, style)
                .useDefaultLocationEngine(false) // we feed fixes from LocationManager ourselves
                .locationComponentOptions(boatOptions())
                .build()
        )
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.GPS
        locationActive = true
        requestUpdates()
        updateRecording(ask = false)
        lastFix?.let { lc.forceLocationUpdate(it) }
        if (mode == AppMode.NAVIGATE || mode == AppMode.FISH) {
            followBoat(if (mode == AppMode.FISH) settings.fishZoom else null)
        }
    }

    /** Your boat, drawn by MapLibre's location component from a style image (it turns it to the heading). */
    private fun boatOptions() = LocationComponentOptions.builder(this)
        .gpsName(BOAT_IMAGE).foregroundName(BOAT_IMAGE).foregroundStaleName(BOAT_IMAGE)
        .elevation(0f)
        .build()

    private fun addBoatImage(style: Style) {
        val px = (56 * resources.displayMetrics.density * look.boatScale).toInt()
        style.addImage(BOAT_IMAGE, boatIcons.draw(look.boatShape, look.boatColourKey, px, look.boatAlpha))
    }

    /** Redraw the boat after a change of shape, colour, size or opacity. */
    private fun updateBoatIcon() {
        val style = map?.style?.takeIf { it.isFullyLoaded } ?: return
        addBoatImage(style)
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
        // With the screen off there's no map to update, only the trip log, guidance and anchor alarm.
        if (locationActive && started) m.locationComponent.forceLocationUpdate(location)

        val pos = LatLon(location.latitude, location.longitude)
        val sog = if (location.hasSpeed()) location.speed * Geo.KN_PER_MS else null
        val cog = if (location.hasBearing() && location.speed > 0.5f) location.bearing.toDouble() else null

        val added = prev !== location && recordTrack(location, pos, sog)
        // Redraw every few fixes: new points arrive and old ones age out of the time window.
        if (started && (++fixCount % 5 == 0 || (added && settings.trackWindowMin == null && track.points.size % 5 == 0))) {
            overlays.setTrack(visibleTrack())
        }
        if (added && (track.points.size % 60 == 0 || location.time - lastTrackSave > 120_000)) {
            lastTrackSave = location.time
            val pts = ArrayList(track.points)
            io.execute { store.saveTrack(pts) }
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

        if (started) updateFields(location, pos, sog, cog, d)
        lookUpHere(pos)   // depth field and zone alerts, also with the screen off
        updateBanner()
        if (started && (mode == AppMode.NAVIGATE || mode == AppMode.FISH)) refreshModePanel()
        if (started) bagPanel.moved()
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
        val z = zoom ?: maxOf(m.cameraPosition.zoom, 12.0)
        // zoomWhileTracking is ignored while the camera is still moving to the boat, so zoom as part of
        // that move, and again after it for when there's no move to make (already following).
        lc.setCameraMode(if (courseUp) CameraMode.TRACKING_GPS else CameraMode.TRACKING_GPS_NORTH, 750L, z, null, null,
            object : OnLocationCameraTransitionListener {
                override fun onLocationCameraTransitionFinished(cameraMode: Int) { lc.zoomWhileTracking(z) }
                override fun onLocationCameraTransitionCanceled(cameraMode: Int) = Unit
            })
    }

    private fun updateFields(loc: Location, pos: LatLon, sog: Double?, cog: Double?, d: Pair<Double, Double>?) {
        fields.set(DataField.SOG, sog?.let { String.format(Locale.US, "%.1f", it) } ?: "--.-")
        fields.set(DataField.SOG_KMH, sog?.let { String.format(Locale.US, "%.1f", it * 1.852) } ?: "--.-")
        fields.set(DataField.COG, cog?.let { Geo.fmtBearing(it) } ?: "---°")
        fields.set(DataField.POSITION, "${Geo.formatDdm(loc.latitude, true)}\n${Geo.formatDdm(loc.longitude, false)}")
        fields.set(DataField.TIME, SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
        fields.set(DataField.TRIP, Geo.fmtNm(track.tripNm))
        fields.set(DataField.WIND, wind.forecast?.at(pos.lat, pos.lon, System.currentTimeMillis())?.short() ?: "--")
        fields.set(DataField.WAVES, wind.forecast?.wavesAt(pos.lat, pos.lon, System.currentTimeMillis())?.short() ?: "--")
        fields.set(DataField.TIDE, tideNow(pos)?.let { (h, rising) ->
            String.format(Locale.US, "%+.2f %s", h, if (rising) "↑" else "↓") } ?: "--")
        val trip = track.stats
        fields.set(DataField.TRIP_TIME, if (recording) Geo.fmtDuration((System.currentTimeMillis() - recStart()) / 3_600_000.0) else "--")
        fields.set(DataField.TRIP_AVG, trip.avgMovingKn?.let { String.format(Locale.US, "%.1f", it) } ?: "--.-")
        fields.set(DataField.TRIP_MAX, if (trip.maxKn > 0) String.format(Locale.US, "%.1f", trip.maxKn) else "--.-")

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
        fields.set(DataField.DRIFT_DIR, d?.takeIf { it.first >= 0.05 }?.let { Geo.fmtBearing(it.second) } ?: "---°")
        val nearest = visibleMarks().minByOrNull { Geo.distanceNm(pos, it.pos) }
        fields.set(DataField.MARK, nearest?.let {
            val nm = Geo.distanceNm(pos, it.pos)
            // Within GPS wobble the direction just spins: say it's here.
            "${it.name}\n" + if (nm * Geo.M_PER_NM < 10) "here" else "${Geo.fmtDist(nm)} ${Geo.fmtBearing(Geo.bearing(pos, it.pos))}"
        } ?: "--")
        fields.set(DataField.ANCHOR, anchor?.let { "${(Geo.distanceNm(it, pos) * Geo.M_PER_NM).toInt()}" } ?: "off")
    }

    /**
     * Depth band from the chart at a point, e.g. "20–25", "land" or "--" (not charted). Read from the chart
     * file, so it works anywhere; only as good as the 250 m model outside the survey areas.
     */
    private fun estimateDepthAt(p: LatLng): String {
        chartAt(p.latitude, p.longitude)?.let { return depthOf(it) }
        return renderedDepthAt(p)
    }

    // ---- Wind and tide ----------------------------------------------------------------

    private fun setUpWind() {
        wind = Wind(File(filesDir, "wind"), userAgent, io)
        wind.post = { r -> runOnUiThread(r) }
        wind.onChanged = { updateWind(fetch = false); refreshWindTidePanel() }
        windView = WindView(this)
        windView.host = object : WindView.Host {
            override fun toLatLon(x: Float, y: Float) =
                map?.projection?.fromScreenLocation(android.graphics.PointF(x, y))?.let { it.latitude to it.longitude }
            override fun forecast() = wind.forecast
            override fun time() = windTime ?: System.currentTimeMillis()
        }
        waveView = WaveView(this)
        waveView.host = object : WaveView.Host {
            override fun toScreen(lat: Double, lon: Double) =
                map?.projection?.toScreenLocation(LatLng(lat, lon))?.let { it.x to it.y }
            override fun forecast() = wind.forecast
            override fun time() = windTime ?: System.currentTimeMillis()
        }
        // Just above the map (waves, then wind), under every control. Not clickable, so touches reach the map.
        val root = mapView.parent as FrameLayout
        val full = { FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        root.addView(waveView, root.indexOfChild(mapView) + 1, full())
        root.addView(windView, root.indexOfChild(waveView) + 1, full())
        windCredit = TextView(this).apply {
            visibility = View.GONE
            maxLines = 1
            setOnClickListener { showWindTidePanel() }
        }
        findViewById<FrameLayout>(R.id.overlay).addView(windCredit,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START))
    }

    /** Start or stop the wind streaks, and fetch a forecast for the view if needed. */
    private fun updateWind(fetch: Boolean = true) {
        val ok = started && chartFile.exists()
        val windOn = layers.isOn(MapOverlay.WIND) && ok
        val wavesOn = layers.isOn(MapOverlay.WAVES) && ok
        val on = windOn || wavesOn
        windView.opacity = layers.opacity(MapOverlay.WIND)
        if (windView.running != windOn) windView.running = windOn
        waveView.opacity = layers.opacity(MapOverlay.WAVES)
        waveView.showing = wavesOn
        windCredit.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            val what = if (windOn && wavesOn) "Wind & waves" else if (windOn) "Wind" else "Waves"
            val by = listOfNotNull("ECMWF".takeIf { windOn }, "Copernicus Marine".takeIf { wavesOn }).joinToString(", ")
            val f = wind.forecast
            val t = windTime ?: System.currentTimeMillis()
            windCredit.text = when {
                f == null && wind.busy -> "$what: getting the forecast…"
                f == null -> "$what: no forecast yet (needs internet) · tap"
                t > f.tEnd -> "$what: forecast ran out · go online"
                wavesOn && f.waves == null -> "Waves: not in this forecast yet · go online"
                else -> "$what ${if (windTime == null) "now" else windLabel(t)} · $by via Open-Meteo.com"
            }
            if (fetch) fetchWindForView()
        }
    }

    private fun fetchWindForView(force: Boolean = false) {
        if (!force && !((layers.isOn(MapOverlay.WIND) || layers.isOn(MapOverlay.WAVES)) && started)) return
        val b = map?.projection?.visibleRegion?.latLngBounds ?: return
        if (!b.latitudeSouth.isFinite() || b.latitudeNorth <= b.latitudeSouth) return
        wind.ensure(b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth, force,
            wantWaves = layers.isOn(MapOverlay.WAVES))
        if (wind.busy) updateWind(fetch = false)
    }

    private fun windLabel(t: Long) = SimpleDateFormat("EEE h a", Locale.getDefault()).format(Date(t))

    /** Tide height above mean sea level now, and whether it's rising; null outside the model. */
    private fun tideNow(p: LatLon): Pair<Double, Boolean>? {
        val td = tides ?: return null
        val st = td.nearest(p.lat, p.lon) ?: return null
        val now = System.currentTimeMillis()
        val h = td.height(st, now)
        return h to (td.height(st, now + 10 * 60_000L) > h)
    }

    /** The chart's depth band with the tide added: how much water there is about now. */
    private fun depthNow(r: ChartReader.Result, p: LatLon): String {
        val d = r.depth
        if (r.land) return "land"
        if (d == null || d.second >= 2000) return depthOf(r)
        val h = tideNow(p)?.first ?: return "--"
        return String.format(Locale.US, "%.1f–%.1f", maxOf(0.0, d.first + h), maxOf(0.0, d.second + h))
    }

    private var windTidePanelOpen = false

    private fun refreshWindTidePanel() {
        if (windTidePanelOpen && panel.isOpen && panel.current == WIND_PANEL) showWindTidePanel()
    }

    /** Forecast time and status, the wind colours, and the tide where the boat (or the map centre) is. */
    private fun showWindTidePanel() {
        displayPanelOpen = false
        windTidePanelOpen = true
        val dp = resources.displayMetrics.density
        val here = lastPos() ?: map?.cameraPosition?.target?.let { LatLon(it.latitude, it.longitude) }
        panel.show(WIND_PANEL, { rows ->
            rows.section("Wind, sea & swell forecast")
            val f = wind.forecast
            val now = System.currentTimeMillis()
            // On/off and opacity for each, as in Layers. Off hides it from the map; the charts stay here.
            for ((o, name) in listOf(MapOverlay.WIND to "Wind on the map", MapOverlay.WAVES to "Sea & swell on the map")) {
                rows.sliderRow(
                    label = name,
                    percent = kotlin.math.round(layers.opacity(o) * 100).toInt(),
                    checked = layers.isOn(o),
                    onChecked = { on ->
                        layers.setOnFromPanel(o, on)
                        map?.style?.let { layers.apply(it) }
                        updateWind()
                    },
                    onPercent = { p -> layers.setOpacityFromPanel(o, p / 100f); updateWind(fetch = false) },
                )
            }
            if (f == null) {
                rows.note(if (wind.busy) "Getting the forecast…" else
                    "No forecast yet. With internet, it downloads for the area on screen.")
            } else {
                val hoursAhead = ((f.tEnd - now) / WindForecast.HOUR).toInt()
                val pick = windTime
                val label = TextView(this).apply { textSize = 14f; setTextColor(look.onSurface) }
                fun describe(t: Long?) {
                    val at = t ?: now
                    val w = here?.let { f.at(it.lat, it.lon, at) }
                    val wv = here?.let { f.wavesAt(it.lat, it.lon, at) }
                    label.text = (if (t == null) "Now" else windLabel(at)) +
                        (if (lastPos() != null) " at the boat" else " at the map centre") +
                        (w?.let { "\nWind " + it.text() } ?: "") + (wv?.let { "\n" + it.text() } ?: "")
                }
                describe(pick)
                rows.view(label)
                // Wind and wave charts for the week, at the boat or map centre, one point an hour from now.
                val charts = mutableListOf<ForecastChartView>()
                var slider: com.google.android.material.slider.Slider? = null
                // One time for the slider, both charts, the wind streaks and the sea and swell on the map.
                fun pickTime(t: Long?, fromSlider: Boolean) {
                    windTime = t
                    describe(t)
                    charts.forEach { it.selected = t ?: now }
                    if (!fromSlider) slider?.value = (((t ?: now) - now) / WindForecast.HOUR).toFloat()
                        .coerceIn(0f, hoursAhead.toFloat())
                    updateWind(fetch = false)
                    waveView.invalidate()
                }
                if (hoursAhead > 0) {
                    rows.note("Forecast time: moves the wind, sea and swell on the map and on the charts below.")
                    slider = com.google.android.material.slider.Slider(this).apply {
                        valueFrom = 0f
                        valueTo = hoursAhead.toFloat()
                        stepSize = 1f
                        value = (((pick ?: now) - now) / WindForecast.HOUR).toFloat().coerceIn(0f, hoursAhead.toFloat())
                            .let { kotlin.math.round(it) }
                        setLabelFormatter { v -> if (v == 0f) "Now" else windLabel(now + (v * WindForecast.HOUR).toLong()) }
                        addOnChangeListener { _, v, fromUser ->
                            if (fromUser) pickTime(if (v == 0f) null else now + (v * WindForecast.HOUR).toLong(), true)
                        }
                    }
                    rows.view(slider)
                }
                if (pick != null) rows.button("Back to now") { pickTime(null, false); showWindTidePanel() }
                if (here != null && hoursAhead > 0) {
                    val hrs = (0..hoursAhead).map { now + it * WindForecast.HOUR }
                    val windPts = hrs.map { t ->
                        val w = f.at(here.lat, here.lon, t)
                        ForecastChartView.Point(t, w?.kn?.toFloat() ?: Float.NaN, w?.gustKn?.toFloat() ?: Float.NaN,
                            w?.fromDeg?.toFloat() ?: Float.NaN)
                    }
                    if (windPts.any { it.main.isFinite() }) {
                        rows.note("Wind (kn): colour is the mean speed, the dashed line gusts, arrows the way it blows. Tap or drag to pick a time.")
                        val ids = intArrayOf(R.color.wind_0, R.color.wind_1, R.color.wind_2, R.color.wind_3,
                            R.color.wind_4, R.color.wind_5, R.color.wind_6, R.color.wind_7)
                        val chart = ForecastChartView(this, look)
                        chart.set(windPts, floatArrayOf(0f, 5f, 10f, 15f, 20f, 25f, 30f, 40f),
                            IntArray(ids.size) { ContextCompat.getColor(this, ids[it]) }, "kn", 20f, 10f, 0, now)
                        chart.selected = pick ?: now
                        chart.onPick = { t -> pickTime(if (t <= now) null else t, false) }
                        charts += chart
                        rows.custom(chart, 170)
                    } else rows.note("The wind forecast doesn't reach here: download it for this area.")
                    val wavePts = hrs.map { t ->
                        val w = f.wavesAt(here.lat, here.lon, t)
                        ForecastChartView.Point(t, w?.total?.toFloat() ?: Float.NaN, w?.swellM?.toFloat() ?: Float.NaN,
                            w?.swellFrom?.toFloat() ?: Float.NaN)
                    }
                    if (wavePts.any { it.main.isFinite() }) {
                        rows.note("Sea & swell (m): colour is the combined height, the dashed line the swell, arrows the way the swell runs.")
                        val ids = intArrayOf(R.color.wave_0, R.color.wave_1, R.color.wave_2, R.color.wave_3,
                            R.color.wave_4, R.color.wave_5, R.color.wave_6, R.color.wave_7)
                        val chart = ForecastChartView(this, look)
                        chart.set(wavePts, floatArrayOf(0f, 0.5f, 1f, 1.5f, 2f, 3f, 4f, 6f),
                            IntArray(ids.size) { ContextCompat.getColor(this, ids[it]) }, "m", 2f, 1f, 1, now)
                        chart.selected = pick ?: now
                        chart.onPick = { t -> pickTime(if (t <= now) null else t, false) }
                        charts += chart
                        rows.custom(chart, 150)
                    } else if (f.waves != null) rows.note("No sea & swell forecast here (land, or too close to the coast for the model).")
                }
                val ago = ((now - f.fetched) / 60_000).toInt()
                rows.note("ECMWF IFS 9 km forecast via Open-Meteo.com (CC BY 4.0), downloaded " +
                    (if (ago < 60) "$ago min ago" else "${ago / 60} h ago") +
                    String.format(Locale.US, " for %.1f–%.1f° S, %.1f–%.1f° E. ", -f.n, -f.s, f.w, f.e) +
                    "It's kept for when you have no signal: open the map over where you're going while online. " +
                    "It updates by itself when you're online, it's over 3 hours old, or you move outside it.")
            }
            wind.lastError?.let { rows.note(it) }
            rows.button(if (wind.busy) "Downloading…" else "Download for this area now") {
                fetchWindForView(force = true); showWindTidePanel()
            }
            rows.view(windLegend(dp))
            rows.view(waveLegend(dp))
            rows.note("Waves: colour is the combined height of sea and swell; arrows show which way the swell runs, " +
                "with its height in metres. MFWAM 8 km model, generated using E.U. Copernicus Marine Service Information, " +
                "via Open-Meteo.com. Close to the coast and inside reefs it's only a guide: the model's cells are 8 km.")

            rows.section("Tide")
            val td = tides
            val st = here?.let { td?.nearest(it.lat, it.lon) }
            if (td == null || st == null || here == null) {
                rows.note("No tide prediction here: it covers the WA coast, within about 60 km of a model cell.")
            } else {
                val (h, rising) = tideNow(here)!!
                val km = Geo.distanceNm(here, LatLon(st.lat, st.lon)) * 1.852
                rows.view(TextView(this).apply {
                    textSize = 14f; setTextColor(look.onSurface)
                    text = String.format(Locale.US, "Now %+.2f m, %s", h, if (rising) "rising" else "falling") +
                        " · near ${st.near}" + (if (km >= 1) String.format(Locale.US, " (model point %.0f km away)", km) else "")
                })
                val chart = TideChartView(this, look)
                val from = now - 6 * WindForecast.HOUR
                chart.set(td, st, from, now + 42 * WindForecast.HOUR, now)
                rows.custom(chart, 150)
                val fmt = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
                val next = td.turns(st, now, now + 48 * WindForecast.HOUR)
                rows.note(next.joinToString("\n") {
                    (if (it.high) "High  " else "Low   ") + fmt.format(Date(it.time)) + String.format(Locale.US, "   %+.2f m", it.metres)
                })
                rows.note("Heights are above mean sea level, the same level as the chart's depths, so the water is about " +
                    "chart depth + tide. A global model (EOT20, CC BY 4.0) worked out on this tablet: roughly ±0.1–0.2 m " +
                    "and a few minutes on the open coast. Not right inside rivers and estuaries (Swan, Peel–Harvey), and " +
                    "weather (wind, pressure) can move the sea by more than the tide here.")
            }
        }, onClose = { windTidePanelOpen = false })
    }

    /** The wave height colours. */
    private fun waveLegend(dp: Float): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, (6 * dp).toInt(), 0, (2 * dp).toInt())
        val stops = floatArrayOf(0f, 0.5f, 1f, 1.5f, 2f, 3f, 4f, 6f)
        for ((i, l) in WaveView.KEY.withIndex()) addView(TextView(this@MainActivity).apply {
            text = l
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ui_surface))
            setBackgroundColor(waveView.colourFor(stops[i]))
        }, LinearLayout.LayoutParams(0, (20 * dp).toInt(), 1f))
        addView(TextView(this@MainActivity).apply { text = " m"; textSize = 11f; setTextColor(look.muted) })
    }

    /** The wind colours and what speeds they mean. */
    private fun windLegend(dp: Float): View {
        val ids = intArrayOf(R.color.wind_0, R.color.wind_1, R.color.wind_2, R.color.wind_3,
            R.color.wind_4, R.color.wind_5, R.color.wind_6, R.color.wind_7)
        val labels = listOf("0", "5", "10", "15", "20", "25", "30", "40+")
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (6 * dp).toInt(), 0, (2 * dp).toInt())
            for (i in ids.indices) addView(TextView(this@MainActivity).apply {
                text = labels[i]
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ui_surface))
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, ids[i]))
            }, LinearLayout.LayoutParams(0, (20 * dp).toInt(), 1f))
            addView(TextView(this@MainActivity).apply {
                text = " kn"; textSize = 11f; setTextColor(look.muted)
            })
        }
    }

    private fun chartAt(lat: Double, lon: Double): ChartReader.Result? =
        runCatching { chartReader.at(lat, lon) }.getOrNull()?.takeIf { it.known }

    private fun depthOf(r: ChartReader.Result): String {
        val d = r.depth
        return when {
            r.land -> "land"
            d == null -> "--"
            d.second >= 2000 -> ">${d.first}"
            else -> "${d.first}–${d.second}"
        }
    }

    /** Fallback for when the chart file can't be read: the map tiles on screen. */
    private fun renderedDepthAt(p: LatLng): String {
        val m = map ?: return "--"
        if (!started) return "--"
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

    /** A zone the boat is in: no fishing, or else restricted. */
    private class ZoneHit(val notake: Boolean, val name: String?)

    /** Reads the chart off the main thread, then updates the depth field and zone alert. Skips fixes while busy. */
    private fun lookUpHere(pos: LatLon) {
        if (lookingUp) return
        lookingUp = true
        chartExec.execute {
            val r = chartAt(pos.lat, pos.lon)
            runOnUiThread {
                lookingUp = false
                if (isDestroyed) return@runOnUiThread
                val here = LatLng(pos.lat, pos.lon)
                if (started) fields.set(DataField.DEPTH, r?.let { depthOf(it) } ?: renderedDepthAt(here))
                if (started) fields.set(DataField.DEPTH_NOW, r?.let { depthNow(it, pos) } ?: "--")
                setZone(if (r != null) zoneIn(r) else renderedZone(here))
                updateBanner()
            }
        }
    }

    private fun zoneIn(r: ChartReader.Result): ZoneHit? {
        r.zones.firstOrNull { it["zone_class"] == "notake" }?.let { return ZoneHit(true, it["name"] as? String) }
        r.zones.firstOrNull { it["zone_class"] == "restricted" }?.let { return ZoneHit(false, it["name"] as? String) }
        return null
    }

    /** Fallback for when the chart file can't be read: the map tiles on screen. */
    private fun renderedZone(p: LatLng): ZoneHit? {
        val m = map ?: return null
        if (!started) return null
        val screen = m.projection.toScreenLocation(p)
        m.queryRenderedFeatures(screen, "zones-notake-fill").firstOrNull()?.let { return ZoneHit(true, it.getStringProperty("name")) }
        m.queryRenderedFeatures(screen, "zones-restricted-fill").firstOrNull()?.let { return ZoneHit(false, it.getStringProperty("name")) }
        return null
    }

    private fun setZone(z: ZoneHit?) {
        zoneAlert = when {
            z == null -> null
            z.notake -> getString(R.string.no_fishing_banner, z.name ?: "") to look.danger
            else -> getString(R.string.restricted_banner, z.name ?: "") to look.warn
        }
        if (z != null && z.notake && z.name != currentZone) buzz()
        currentZone = z?.name
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

    private fun confirm(message: String, yes: String? = null, no: String? = null, onYes: () -> Unit) {
        MaterialAlertDialogBuilder(this).setMessage(message)
            .setPositiveButton(yes ?: getString(android.R.string.ok)) { _, _ -> onYes() }
            .setNegativeButton(no ?: getString(android.R.string.cancel), null).show()
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

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        started = true
        finishStaleTrip()
        overlays.setTrack(visibleTrack())   // may have grown while the screen was off
        updateAis()
        updateWind()
        updateModeLabel()
        ticker.post(tick)
        if (!updateChecked) {
            updateChecked = true
            updater.checkIfDue(settings.updateCheckDays) { message, open ->
                snack(message).setAction("View") { open() }.show()
            }
        }
    }
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (locationActive) { requestUpdates(); updateRecording(ask = false) }
        updater.resume()
    }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() {
        super.onStop()
        started = false
        ais.stop()
        windView.running = false
        updater.stopPolling()
        ticker.removeCallbacks(tick)
        updateRecordButton()   // stops the light's animation while off screen
        mapView.onStop()
        val pts = ArrayList(track.points)
        io.execute { store.saveTrack(pts) }
    }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    override fun onDestroy() {
        getSystemService(LocationManager::class.java)?.removeUpdates(this)
        TrackService.stop(this)
        ais.close()
        io.shutdown()
        mapView.onDestroy()
        super.onDestroy()
    }

    companion object {
        private val AUTO_NAME = Regex("Mark \\d+")
        /** Catches listed in a mark's window before they fold into one row. */
        private const val SHOWN_CATCHES = 4
        private const val GITHUB_URL = "https://github.com/wufle/chompchamp"
        /** Style image for your boat (BoatIcons). */
        private const val BOAT_IMAGE = "own-boat"
        private const val WIND_PANEL = "Wind, waves & tide"
        /** Zooming to fit a trip, route or area stops here (about 1:2000), however small it is. */
        private const val MAX_FIT_ZOOM = 16.0
        private const val RELIEF_ONLINE =
            "https://public-services.slip.wa.gov.au/public/rest/services/Landgate_Public_Maps/WA_Bathymetry/MapServer/export" +
                "?bbox={bbox-epsg-3857}&bboxSR=3857&imageSR=3857&size=256,256&format=png32&transparent=true&layers=show:2&f=image"
        private const val EMPTY_STYLE =
            """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#dfe9f2"}}]}"""
    }
}
