package au.chompchamp

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * An online-only map layer the user sets up: Esri World Imagery with their own API key, or any tile
 * address they paste. Unlike everything else on the map it needs internet and is never saved for offline
 * use (Esri's terms don't allow storing its tiles). The key and address stay on the device: pref keys
 * starting with [SECRET] are left out of settings backups.
 */
class OnlineMap(private val prefs: SharedPreferences) {

    enum class Kind(val key: String, val label: String) {
        NONE("none", "Not set up"),
        ESRI("esri", "Esri World Imagery"),
        CUSTOM("custom", "Your own tile address"),
    }

    val kind get() = Kind.entries.firstOrNull { it.key == prefs.getString(K_KIND, null) } ?: Kind.NONE
    val esriKey get() = prefs.getString(K_ESRI_KEY, null).orEmpty()
    val customUrl get() = prefs.getString(K_URL, null).orEmpty()
    val customName get() = prefs.getString(K_NAME, null).orEmpty()

    fun setKind(k: Kind) = prefs.edit().putString(K_KIND, k.key).apply()
    fun setEsriKey(v: String) = prefs.edit().putString(K_ESRI_KEY, v.trim()).apply()
    fun setCustomUrl(v: String) = prefs.edit().putString(K_URL, v.trim()).apply()
    fun setCustomName(v: String) = prefs.edit().putString(K_NAME, v.trim()).apply()

    /** What the user sees for the source, e.g. in Layers. */
    val label get() = when (kind) {
        Kind.NONE -> "not set up"
        Kind.ESRI -> if (esriKey.isBlank()) "Esri, needs a key" else "Esri World Imagery"
        Kind.CUSTOM -> if (!validUrl(customUrl)) "needs an address" else customName.ifBlank { "your own" }
    }

    /** The tile address MapLibre loads, or null until the source is set up. */
    fun tileUrl(): String? = when (kind) {
        Kind.NONE -> null
        Kind.ESRI -> esriKey.takeIf { it.isNotBlank() }?.let { "$ESRI_TILES?token=" + URLEncoder.encode(it, "UTF-8") }
        Kind.CUSTOM -> customUrl.takeIf { validUrl(it) }
    }

    val ready get() = tileUrl() != null

    /** The style source, or null when not set up. */
    fun source(): JSONObject? {
        val url = tileUrl() ?: return null
        return JSONObject().put("type", "raster").put("tileSize", 256)
            .put("tiles", JSONArray().put(url))
            .put("maxzoom", if (kind == Kind.ESRI) 19 else 20)
            .put("attribution", if (kind == Kind.ESRI) ESRI_ATTRIBUTION else customName.ifBlank { "Online map" })
    }

    /** Text that must stay on screen while the layer shows (Esri requires it), or null. */
    val credit get() = if (kind == Kind.ESRI && ready) ESRI_CREDIT else null

    /**
     * Fetch one tile off Perth and say what went wrong, or null if an image came back.
     * Blocking: call it off the main thread.
     */
    fun test(userAgent: String): String? {
        val template = tileUrl() ?: return "Not set up yet."
        val z = 10
        val n = 1 shl z
        val lat = -31.95 * PI / 180
        val x = floor((115.86 + 180) / 360 * n).toInt()
        val y = floor((1 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2 * n).toInt()
        val size = 2 * PI * 6378137.0 / n
        val minX = -PI * 6378137.0 + x * size
        val maxY = PI * 6378137.0 - y * size
        val url = template.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")
            .replace("{bbox-epsg-3857}", "$minX,${maxY - size},${minX + size},$maxY")
            .replace("{quadkey}", quadkey(x, y, z))
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", userAgent)
            try {
                val code = c.responseCode
                val type = c.contentType.orEmpty()
                val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
                when {
                    code < 400 && type.startsWith("image") -> null
                    // Esri answers a bad key with HTTP 200 and a JSON error.
                    else -> errorText(String(body, Charsets.UTF_8))?.let { "The server said: $it" }
                        ?: "The server answered $code ${type.substringBefore(';')}, not a map image."
                }
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            "Couldn't reach the server (${e.javaClass.simpleName}). Check the internet connection and the address."
        }
    }

    private fun errorText(body: String): String? = runCatching {
        val e = JSONObject(body).getJSONObject("error")
        listOfNotNull(e.optString("message").takeIf { it.isNotBlank() },
            e.optJSONArray("details")?.optString(0)?.takeIf { it.isNotBlank() }).joinToString(". ")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun quadkey(x: Int, y: Int, z: Int) = buildString {
        for (i in z downTo 1) {
            val m = 1 shl (i - 1)
            append(((if (x and m != 0) 1 else 0) + (if (y and m != 0) 2 else 0)).toString())
        }
    }

    companion object {
        /** Pref keys starting with this are left out of exported settings and kept when settings are restored. */
        const val SECRET = "secret_"
        private const val K_KIND = "online_map_kind"
        private const val K_NAME = "online_map_name"
        private const val K_ESRI_KEY = SECRET + "online_map_esri_key"
        private const val K_URL = SECRET + "online_map_url"

        /** World Imagery through ArcGIS Location Platform's basemap service; needs the user's API key. */
        private const val ESRI_TILES =
            "https://ibasemaps-api.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        private const val ESRI_ATTRIBUTION =
            "Powered by Esri. Imagery: Esri, Vantor, Earthstar Geographics, and the GIS User Community"
        private const val ESRI_CREDIT = "Powered by Esri · Esri, Vantor, Earthstar Geographics"

        /** https only, with tile placeholders MapLibre understands. */
        fun validUrl(u: String) = u.startsWith("https://") &&
            (("{z}" in u && "{x}" in u && "{y}" in u) || "{bbox-epsg-3857}" in u || "{quadkey}" in u)
    }
}
