package au.chompchamp

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Waypoint(var lat: Double, var lon: Double, var name: String) {
    val pos get() = LatLon(lat, lon)
}

data class Route(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    val waypoints: MutableList<Waypoint> = mutableListOf(),
) {
    fun lengthNm() = Geo.routeLengthNm(waypoints.map { it.pos })

    fun duplicate(newId: Boolean = false) = Route(
        if (newId) UUID.randomUUID().toString() else id, name,
        waypoints.map { it.copy() }.toMutableList(),
    )

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("waypoints", JSONArray().apply {
            waypoints.forEach { put(JSONObject().put("lat", it.lat).put("lon", it.lon).put("name", it.name)) }
        })

    companion object {
        fun fromJson(o: JSONObject) = Route(
            o.getString("id"), o.optString("name"),
            o.getJSONArray("waypoints").let { a ->
                MutableList(a.length()) { i ->
                    a.getJSONObject(i).let { Waypoint(it.getDouble("lat"), it.getDouble("lon"), it.optString("name")) }
                }
            },
        )
    }
}

/** A saved spot: fish caught, a reef, a wreck... */
data class Mark(
    val id: String = UUID.randomUUID().toString(),
    val lat: Double,
    val lon: Double,
    var name: String,
    val time: Long = System.currentTimeMillis(),
    val depth: String? = null,
) {
    val pos get() = LatLon(lat, lon)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("lat", lat).put("lon", lon).put("name", name).put("time", time)
        .apply { depth?.let { put("depth", it) } }

    companion object {
        fun fromJson(o: JSONObject) = Mark(
            o.getString("id"), o.getDouble("lat"), o.getDouble("lon"), o.optString("name"),
            o.optLong("time"), if (o.has("depth")) o.getString("depth") else null,
        )
    }
}

/** Tiny JSON-file store; everything here is small enough to rewrite on each change. */
class Store(dir: File) {
    private val routesFile = File(dir, "routes.json")
    private val planFile = File(dir, "plan.json")
    private val marksFile = File(dir, "marks.json")
    private val trackFile = File(dir, "track.json")

    init { dir.mkdirs() }

    fun routes(): MutableList<Route> = readArray(routesFile).map { Route.fromJson(it) }.toMutableList()
    fun saveRoutes(list: List<Route>) = writeArray(routesFile, list.map { it.toJson() })

    fun plan(): Route = runCatching { Route.fromJson(JSONObject(planFile.readText())) }.getOrElse { Route() }
    fun savePlan(r: Route) = write(planFile, r.toJson().toString())

    fun marks(): MutableList<Mark> = readArray(marksFile).map { Mark.fromJson(it) }.toMutableList()
    fun saveMarks(list: List<Mark>) = writeArray(marksFile, list.map { it.toJson() })

    fun track(): MutableList<LatLon> = runCatching {
        val a = JSONArray(trackFile.readText())
        MutableList(a.length() / 2) { i -> LatLon(a.getDouble(i * 2), a.getDouble(i * 2 + 1)) }
    }.getOrElse { mutableListOf() }

    fun saveTrack(points: List<LatLon>) = write(trackFile, JSONArray().apply {
        points.forEach { put(it.lat); put(it.lon) }
    }.toString())

    private fun readArray(f: File): List<JSONObject> = runCatching {
        val a = JSONArray(f.readText())
        List(a.length()) { a.getJSONObject(it) }
    }.getOrElse { emptyList() }

    private fun writeArray(f: File, items: List<JSONObject>) =
        write(f, JSONArray().apply { items.forEach { put(it) } }.toString())

    private fun write(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }
}
