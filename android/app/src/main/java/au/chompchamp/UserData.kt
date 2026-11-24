package au.chompchamp

import android.content.SharedPreferences
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Which kinds of user data a backup/import covers. */
enum class DataPart(val key: String, val label: String) {
    ROUTES("routes", "Routes (saved + current plan)"),
    MARKS("marks", "Marks"),
    TRACK("track", "Track"),
    SETTINGS("settings", "Settings, layers & layouts"),
}

/** Contents of a backup or GPX file, before it's applied. */
class DataBundle(
    val routes: List<Route> = emptyList(),
    val plan: Route? = null,
    val marks: List<Mark> = emptyList(),
    val track: List<LatLon> = emptyList(),
    val settings: JSONObject? = null,
) {
    fun available(): List<DataPart> = buildList {
        if (routes.isNotEmpty() || plan?.waypoints?.isNotEmpty() == true) add(DataPart.ROUTES)
        if (marks.isNotEmpty()) add(DataPart.MARKS)
        if (track.isNotEmpty()) add(DataPart.TRACK)
        if (settings != null && settings.length() > 0) add(DataPart.SETTINGS)
    }

    fun describe(part: DataPart) = when (part) {
        DataPart.ROUTES -> "${routes.size} route(s)" + if (plan?.waypoints?.isNotEmpty() == true) " + current plan" else ""
        DataPart.MARKS -> "${marks.size} mark(s)"
        DataPart.TRACK -> "${track.size} track point(s)"
        DataPart.SETTINGS -> "${settings?.length() ?: 0} setting(s)"
    }
}

/**
 * Snapshots (named copies kept on the phone), JSON backups and GPX import/export.
 * Everything goes through [Store] and SharedPreferences, so callers should reload their
 * in-memory copies after [apply].
 */
class UserData(dir: File, private val store: Store, private val prefs: SharedPreferences) {

    private val snapshotDir = File(dir, "snapshots").apply { mkdirs() }

    // ---- JSON backup ---------------------------------------------------------------

    fun export(parts: Set<DataPart>): String {
        val o = JSONObject()
            .put("app", "ChompChamp")
            .put("format", 1)
            .put("exported", isoNow())
        if (DataPart.ROUTES in parts) {
            o.put("routes", JSONArray().apply { store.routes().forEach { put(it.toJson()) } })
            o.put("plan", store.plan().toJson())
        }
        if (DataPart.MARKS in parts) o.put("marks", JSONArray().apply { store.marks().forEach { put(it.toJson()) } })
        if (DataPart.TRACK in parts) o.put("track", JSONArray().apply { store.track().forEach { put(it.lat); put(it.lon) } })
        if (DataPart.SETTINGS in parts) o.put("settings", prefsToJson())
        return o.toString(1)
    }

    fun parseBackup(text: String): DataBundle {
        val o = JSONObject(text)
        require(o.optString("app") == "ChompChamp") { "not a ChompChamp backup" }
        val routes = o.optJSONArray("routes")?.let { a -> List(a.length()) { Route.fromJson(a.getJSONObject(it)) } }.orEmpty()
        val plan = o.optJSONObject("plan")?.let { Route.fromJson(it) }
        val marks = o.optJSONArray("marks")?.let { a -> List(a.length()) { Mark.fromJson(a.getJSONObject(it)) } }.orEmpty()
        val track = o.optJSONArray("track")?.let { a -> List(a.length() / 2) { LatLon(a.getDouble(it * 2), a.getDouble(it * 2 + 1)) } }.orEmpty()
        return DataBundle(routes, plan, marks, track, o.optJSONObject("settings"))
    }

    /**
     * Write a bundle into the app's storage.
     * merge = true: add routes/marks (same id → replaced), append track, overwrite only the
     * settings present. merge = false: replace each selected part entirely.
     */
    fun apply(b: DataBundle, parts: Set<DataPart>, merge: Boolean) {
        if (DataPart.ROUTES in parts) {
            val routes = if (merge) store.routes() else mutableListOf()
            for (r in b.routes) {
                val i = routes.indexOfFirst { it.id == r.id }
                if (i >= 0) routes[i] = r else routes.add(r)
            }
            store.saveRoutes(routes)
            if (b.plan != null && (!merge || store.plan().waypoints.isEmpty())) store.savePlan(b.plan)
        }
        if (DataPart.MARKS in parts) {
            val marks = if (merge) store.marks() else mutableListOf()
            for (m in b.marks) {
                val i = marks.indexOfFirst { it.id == m.id }
                if (i >= 0) marks[i] = m else marks.add(m)
            }
            store.saveMarks(marks)
        }
        if (DataPart.TRACK in parts) {
            store.saveTrack(if (merge) store.track() + b.track else b.track)
        }
        if (DataPart.SETTINGS in parts && b.settings != null) {
            jsonToPrefs(b.settings, clearFirst = !merge)
        }
    }

    // ---- Snapshots on the phone -------------------------------------------------------

    fun snapshots(): List<Pair<String, Long>> =
        snapshotDir.listFiles { f -> f.extension == "json" }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { it.nameWithoutExtension to it.lastModified() }

    fun saveSnapshot(name: String) {
        File(snapshotDir, safeName(name) + ".json").writeText(export(DataPart.entries.toSet()))
    }

    fun readSnapshot(name: String): DataBundle = parseBackup(File(snapshotDir, "$name.json").readText())

    fun deleteSnapshot(name: String) { File(snapshotDir, "$name.json").delete() }

    private fun safeName(s: String) = s.trim().replace(Regex("[^A-Za-z0-9 ._-]"), "_").take(60).ifBlank { "snapshot" }

    // ---- Settings <-> JSON -------------------------------------------------------------

    private fun prefsToJson(): JSONObject {
        val o = JSONObject()
        for ((k, v) in prefs.all) {
            val typed = when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is String -> JSONObject().put("t", "s").put("v", v)
                else -> null
            }
            typed?.let { o.put(k, it) }
        }
        return o
    }

    private fun jsonToPrefs(o: JSONObject, clearFirst: Boolean) {
        val e = prefs.edit()
        if (clearFirst) e.clear()
        for (k in o.keys()) {
            val t = o.getJSONObject(k)
            when (t.getString("t")) {
                "b" -> e.putBoolean(k, t.getBoolean("v"))
                "f" -> e.putFloat(k, t.getDouble("v").toFloat())
                "i" -> e.putInt(k, t.getInt("v"))
                "l" -> e.putLong(k, t.getLong("v"))
                "s" -> e.putString(k, t.getString("v"))
            }
        }
        e.apply()
    }

    // ---- GPX ------------------------------------------------------------------------

    fun exportGpx(parts: Set<DataPart>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="ChompChamp" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        sb.append("  <metadata><time>${isoNow()}</time></metadata>\n")
        if (DataPart.MARKS in parts) for (m in store.marks()) {
            sb.append("""  <wpt lat="${m.lat}" lon="${m.lon}">""")
            sb.append("<time>${iso(m.time)}</time><name>${esc(m.name)}</name>")
            m.depth?.let { sb.append("<desc>Depth (est.) ${esc(it)} m</desc>") }
            sb.append("<sym>Fishing Area</sym></wpt>\n")
        }
        if (DataPart.ROUTES in parts) {
            val routes = store.routes().toMutableList()
            val plan = store.plan()
            if (plan.waypoints.isNotEmpty() && routes.none { it.id == plan.id }) {
                routes.add(plan.duplicate().also { if (it.name.isBlank()) it.name = "Current plan" })
            }
            for (r in routes) {
                sb.append("  <rte><name>${esc(r.name.ifBlank { "Route" })}</name>\n")
                for (w in r.waypoints) {
                    sb.append("""    <rtept lat="${w.lat}" lon="${w.lon}"><name>${esc(w.name)}</name></rtept>""").append('\n')
                }
                sb.append("  </rte>\n")
            }
        }
        if (DataPart.TRACK in parts) {
            val t = store.track()
            if (t.isNotEmpty()) {
                sb.append("  <trk><name>ChompChamp track</name><trkseg>\n")
                for (p in t) sb.append("""    <trkpt lat="${p.lat}" lon="${p.lon}"/>""").append('\n')
                sb.append("  </trkseg></trk>\n")
            }
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /** wpt → marks, rte → routes, trk → track. New ids so nothing gets overwritten. */
    fun parseGpx(text: String): DataBundle {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(text))
        val marks = mutableListOf<Mark>()
        val routes = mutableListOf<Route>()
        val track = mutableListOf<LatLon>()
        var route: Route? = null
        var pt: LatLon? = null
        var ptName: String? = null
        var ptTime: Long? = null
        val path = ArrayDeque<String>()
        var text0 = ""

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    path.addLast(tag)
                    text0 = ""
                    when (tag) {
                        "wpt", "rtept", "trkpt" -> {
                            pt = LatLon(parser.getAttributeValue(null, "lat").toDouble(),
                                parser.getAttributeValue(null, "lon").toDouble())
                            ptName = null
                            ptTime = null
                        }
                        "rte" -> route = Route(name = "")
                    }
                }
                XmlPullParser.TEXT -> text0 += parser.text
                XmlPullParser.END_TAG -> {
                    val tag = path.removeLastOrNull() ?: ""
                    val parent = path.lastOrNull()
                    when {
                        tag == "name" && parent in setOf("wpt", "rtept") -> ptName = text0.trim()
                        tag == "name" && parent == "rte" -> route?.name = text0.trim()
                        tag == "time" && parent == "wpt" -> ptTime = parseIso(text0.trim())
                        tag == "wpt" -> pt?.let {
                            marks.add(Mark(UUID.randomUUID().toString(), it.lat, it.lon,
                                ptName ?: "Mark ${marks.size + 1}", ptTime ?: System.currentTimeMillis()))
                        }
                        tag == "rtept" -> pt?.let { p ->
                            route?.waypoints?.add(Waypoint(p.lat, p.lon, ptName ?: "WP${(route?.waypoints?.size ?: 0) + 1}"))
                        }
                        tag == "trkpt" -> pt?.let { track.add(it) }
                        tag == "rte" -> route?.let {
                            if (it.waypoints.isNotEmpty()) routes.add(it.also { r -> if (r.name.isBlank()) r.name = "Imported route" })
                            route = null
                        }
                    }
                    text0 = ""
                }
            }
        }
        require(marks.isNotEmpty() || routes.isNotEmpty() || track.isNotEmpty()) { "no waypoints, routes or tracks found" }
        return DataBundle(routes, null, marks, track, null)
    }

    // ---- helpers ----------------------------------------------------------------------

    private fun isoFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun iso(t: Long) = isoFormat().format(Date(t))
    private fun isoNow() = iso(System.currentTimeMillis())
    private fun parseIso(s: String): Long? = runCatching { isoFormat().parse(s.take(19) + "Z")?.time }.getOrNull()
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
