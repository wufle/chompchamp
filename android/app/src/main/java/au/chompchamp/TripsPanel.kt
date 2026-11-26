package au.chompchamp

import android.app.Activity
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The trip in progress, saved trips and their speed logs, docked so the map stays visible. */
class TripsPanel(
    private val ctx: Activity,
    private val panel: SettingsPanel,
    private val look: Appearance,
    private val log: TripLog,
    private val host: Host,
) {
    interface Host {
        val track: Track
        val savedTripLayerOn: Boolean
        /** Id of the saved trip drawn on the map. */
        val shownTrip: String?
        var allCatchesOnMap: Boolean
        fun beforeShow()
        fun currentCatches(): List<Catch>
        fun catchesOf(t: Trip): List<Catch>
        val recording: Boolean
        fun startTrip()
        /** Asks first. */
        fun stopTrip()
        /** Draw a saved trip (null = none), moving the map to it if [zoom]. */
        fun showTrip(t: Trip?, points: List<TrackPoint>, zoom: Boolean)
        fun setCursor(p: LatLon?)
        fun logCatch()
        fun showCatches(title: String, list: List<Catch>)
        fun exportText(fileName: String, csv: Boolean, text: String)
        fun confirm(message: String, onYes: () -> Unit)
        fun askText(title: String, initial: String, onOk: (String) -> Unit)
    }

    private fun nm(v: Double) = Geo.fmtDist(v)
    private fun kn(v: Double) = String.format(Locale.US, "%.1f kn", v)
    private fun dur(ms: Long) = Geo.fmtDuration(ms / 3_600_000.0)
    private fun clock(t: Long) = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(t))
    private fun catches(n: Int) = "$n catch" + if (n == 1) "" else "es"

    private var open = false
    private var showOlder = false

    /** Rebuild the list if it's the panel on screen. */
    fun refresh() { if (open && panel.isOpen && panel.current == TITLE) show() }

    fun show() {
        host.beforeShow()
        panel.show(TITLE, { rows ->
            val s = host.track.stats
            if (host.recording) {
                rows.section("Recording this trip")
                rows.note(if (host.track.points.size < 2) "Recording. Waiting for the boat to move…"
                else summary(s.start, s.end, s.durationMs, s.distanceNm, s.movingMs, s.avgMovingKn, s.maxKn, host.currentCatches().size))
                rows.actionRow("Mark a catch", "Add") { host.logCatch() }
                if (host.track.points.size >= 2) rows.actionRow("Speed log and details", "Open") { showDetails(null) }
                rows.actionRow("Stop recording", "Stop…") { host.stopTrip() }
            } else {
                rows.section("Not recording")
                rows.actionRow("Record a trip", "Start") { host.startTrip() }
                rows.actionRow("Mark a catch", "Add") { host.logCatch() }
            }
            rows.section("Saved trips")
            log.trip(host.shownTrip)?.let { t ->
                rows.actionRow("On the map: ${t.name}", "Hide") { host.showTrip(null, emptyList(), false); show() }
            }
            if (log.trips.isEmpty()) rows.note("No saved trips yet.")
            val list = if (showOlder) log.trips else log.trips.take(RECENT)
            for (t in list) {
                val n = host.catchesOf(t).size
                val line = listOfNotNull(
                    if (t.start > 0) Geo.fmtClock(t.start) + "–" + Geo.fmtClock(t.end) else null,
                    if (t.durationMs > 0) dur(t.durationMs) else null,
                    if (n > 0) catches(n) else null).joinToString(" · ")
                rows.actionRow(t.name + if (line.isEmpty()) "" else "\n$line", nm(t.distanceNm)) { showDetails(t) }
            }
            if (list.size < log.trips.size) rows.actionRow("Older trips", "${log.trips.size - list.size}") { showOlder = true; show() }

            rows.section("Catch log")
            rows.actionRow("All catches", "${log.catches.size}") { host.showCatches("All catches", log.catches.sortedByDescending { it.time }) }
            rows.actionRow("Catches on the map", if (host.allCatchesOnMap) "All" else "This trip") {
                host.allCatchesOnMap = !host.allCatchesOnMap
                show()
            }
            if (log.trips.isNotEmpty()) {
                rows.section("Totals")
                rows.note("${log.trips.size} trips · ${nm(log.trips.sumOf { it.distanceNm })} · ${dur2(log.trips.sumOf { it.durationMs })} · " +
                    "top speed ${kn(log.trips.maxOf { it.maxKn })}\n${catches(log.catches.sumOf { it.count })} (fish), " +
                    "${log.catches.filter { it.released }.sumOf { it.count }} released")
            }
            rows.note("Recording starts when you tap Record, start a route, or mark a catch (Settings: Start recording a " +
                "trip). While it records, the Record button on the map shows REC and the time; press and hold it to stop. A trip left with no GPS for longer than the break " +
                "in Settings is saved and stopped by itself.")
        }, onClose = { open = false })
        open = true
    }

    /** Totals can pass the 99 hours that [Geo.fmtDuration] allows. */
    private fun dur2(ms: Long) = "${ms / 3_600_000} h"

    private fun summary(start: Long, end: Long, duration: Long, dist: Double, moving: Long, avg: Double?, max: Double, n: Int): String {
        val first = if (start > 0) "${clock(start)} to ${Geo.fmtClock(end)} · ${dur(duration)}\n" else ""
        val speeds = listOfNotNull(avg?.let { "average ${kn(it)} under way" }, if (max > 0) "top ${kn(max)}" else null).joinToString(" · ")
        return first + nm(dist) + (if (moving > 0) " · ${dur(moving)} under way" else "") +
            (if (speeds.isEmpty()) "" else "\n$speeds") + if (n > 0) "\n${catches(n)} logged" else ""
    }

    /** [t] null = the trip in progress. */
    private fun showDetails(t: Trip?) {
        host.beforeShow()
        val points = if (t == null) host.track.points.toList() else log.points(t.id)
        val fish = if (t == null) host.currentCatches() else host.catchesOf(t)
        if (t != null) host.showTrip(t, points, zoom = true)
        val time = SimpleDateFormat("EEE HH:mm:ss", Locale.getDefault())
        panel.show(t?.name ?: "This trip", { rows ->
            if (t == null) {
                val s = TripStats.of(points)
                rows.note(summary(s.start, s.end, s.durationMs, s.distanceNm, s.movingMs, s.avgMovingKn, s.maxKn, fish.size))
            } else {
                rows.note(summary(t.start, t.end, t.durationMs, t.distanceNm, t.movingMs, t.avgMovingKn, t.maxKn, fish.size))
                if (!host.savedTripLayerOn) rows.note("Saved trip is switched off in Layers, so it isn't showing on the map.")
            }
            val chart = SpeedChartView(ctx, look)
            if (chart.setPoints(points)) {
                val reading = TextView(ctx).apply {
                    text = "Drag along the chart to see when, where and how fast"
                    textSize = 12f
                    setTextColor(look.accent)
                }
                val speeds = TripStats.speeds(points)
                chart.onPick = { i ->
                    val p = points[i]
                    reading.text = listOfNotNull(time.format(Date(p.t)), speeds[i]?.let { kn(it) },
                        "${Geo.formatDdm(p.lat, true)} ${Geo.formatDdm(p.lon, false)}").joinToString("  ")
                    host.setCursor(p.pos)
                }
                rows.custom(reading, 22)
                rows.custom(chart, 130)
            } else {
                rows.note("No times were recorded, so there's no speed log.")
            }
            if (fish.isNotEmpty()) rows.actionRow("Catches", "${fish.size}") { host.showCatches(t?.name ?: "This trip", fish) }
            rows.actionRow("Export track (GPX) or speed log (CSV)", "Export") { export(t ?: Trip.of(points), points, fish) }
            if (t != null) {
                val shown = host.shownTrip == t.id
                rows.actionRow("On the map", if (shown) "Hide" else "Show") {
                    if (shown) { host.showTrip(null, emptyList(), false); show() } else showDetails(t)
                }
                rows.actionRow("Name", "Rename") { host.askText("Trip name", t.name) { log.rename(t.id, it); showDetails(log.trip(t.id)) } }
                rows.actionRow("Delete this trip", "Delete") {
                    host.confirm("Delete \"${t.name}\" and its track? This can't be undone. Catches are kept.") {
                        if (host.shownTrip == t.id) host.showTrip(null, emptyList(), false)
                        log.delete(t.id)
                        show()
                    }
                }
            }
            rows.actionRow("All trips", "Back") { show() }
        }, onClose = { host.setCursor(null) })
    }

    private fun export(t: Trip, points: List<TrackPoint>, fish: List<Catch>) {
        val base = "chompchamp-" + (if (t.start > 0) SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(t.start)) else "trip")
        val items = mutableListOf<Pair<String, () -> Unit>>(
            "Track and catches (.gpx)" to { host.exportText("$base.gpx", false, TripLog.gpx(t, points, fish)) },
            "Speed log (.csv)" to { host.exportText("$base-speed.csv", true, TripLog.csv(points)) },
        )
        if (fish.isNotEmpty()) items += "Catches (.csv)" to { host.exportText("$base-catches.csv", true, TripLog.catchesCsv(fish)) }
        MaterialAlertDialogBuilder(ctx).setTitle("Export")
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private companion object {
        const val TITLE = "Trips & catches"
        const val RECENT = 30
    }
}
