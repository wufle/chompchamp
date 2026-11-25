package au.chompchamp

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Updates from the project's GitHub releases, with no app store: checks the latest release,
 * downloads the APK and chart files with Android's download manager, then installs the APK
 * (Android asks the user, and refuses an APK not signed with the same key) or imports the chart.
 */
class Updater(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val userAgent: String,
    private val io: Executor,
    private val host: Host,
) {
    interface Host {
        val chartFile: File
        val reliefFile: File
        /** Import a downloaded .pmtiles file (it works out chart or relief), then delete it. */
        fun importDownloaded(f: File, onDone: (Boolean) -> Unit)
    }

    data class Asset(val id: Long, val name: String, val size: Long, val url: String)
    data class Release(val tag: String, val body: String, val page: String, val assets: List<Asset>) {
        val version get() = tag.removePrefix("v")
        val apk get() = assets.firstOrNull { it.name.endsWith(".apk") && !it.name.contains("tempkey") }
        val chart get() = assets.firstOrNull { it.name == "wa.pmtiles" }
        val relief get() = assets.firstOrNull { it.name == "wa-relief.pmtiles" }
    }

    private enum class Kind(val key: String) { APK("apk"), CHART("chart"), RELIEF("relief") }

    private val dm = activity.getSystemService(DownloadManager::class.java)
    private val dir get() = File(activity.getExternalFilesDir(null), "updates")
    private val main = Handler(Looper.getMainLooper())
    private var polling = false

    private val installedVersion: String = runCatching {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
    }.getOrNull() ?: "0"
    private val installedCode: Long = runCatching {
        PackageInfoCompat.getLongVersionCode(activity.packageManager.getPackageInfo(activity.packageName, 0))
    }.getOrDefault(0L)

    // ---- Checking ---------------------------------------------------------------------

    private fun fetchLatest(): Release {
        val c = URL(API).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode == 404) error("no release has been published yet")
            if (c.responseCode != 200) error("GitHub answered HTTP ${c.responseCode}")
            val o = JSONObject(c.inputStream.use { it.readBytes().decodeToString() })
            val arr = o.getJSONArray("assets")
            val assets = (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                Asset(a.getLong("id"), a.getString("name"), a.getLong("size"), a.getString("browser_download_url"))
            }
            return Release(o.getString("tag_name"), o.optString("body"), o.optString("html_url", PAGE), assets)
        } finally {
            c.disconnect()
        }
    }

    /** Is [a] a newer dotted version than [b]? */
    private fun newer(a: String, b: String): Boolean {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d > 0
        }
        return false
    }

    /** Same file as the one installed: downloaded from this asset before, or the same size. */
    private fun hasFile(a: Asset?, f: File) =
        a == null || (f.exists() && (prefs.getLong("upd_got_${a.name}", -1) == a.id || f.length() == a.size))

    private fun appNewer(r: Release) = r.apk != null && newer(r.version, installedVersion)
    private fun chartNewer(r: Release) = host.chartFile.exists().not() || !hasFile(r.chart, host.chartFile)
    private fun reliefNewer(r: Release) = host.reliefFile.exists() && !hasFile(r.relief, host.reliefFile)

    /** Menu: check now and show the result. */
    fun checkNow() {
        Toast.makeText(activity, "Checking GitHub for updates…", Toast.LENGTH_SHORT).show()
        io.execute {
            val r = runCatching { fetchLatest() }
            activity.runOnUiThread {
                prefs.edit().putLong("upd_last_check", System.currentTimeMillis()).apply()
                r.onSuccess { show(it) }.onFailure {
                    Toast.makeText(activity, "Couldn't check for updates: ${it.message ?: "no connection"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** App start: if the user turned on automatic checks and one is due, check quietly. */
    fun checkIfDue(days: Int, onAvailable: (String, () -> Unit) -> Unit) {
        if (days <= 0) return
        val last = prefs.getLong("upd_last_check", 0L)
        if (System.currentTimeMillis() - last < days * 86_400_000L) return
        io.execute {
            val r = runCatching { fetchLatest() }.getOrNull() ?: return@execute
            activity.runOnUiThread {
                prefs.edit().putLong("upd_last_check", System.currentTimeMillis()).apply()
                val what = listOfNotNull(
                    if (appNewer(r)) "app ${r.version}" else null,
                    if (r.chart != null && chartNewer(r) && host.chartFile.exists()) "new chart" else null,
                ).joinToString(" and ")
                if (what.isNotEmpty()) onAvailable("Update available: $what") { show(r) }
            }
        }
    }

    private fun mb(bytes: Long) = String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)

    private fun show(r: Release) {
        val lines = mutableListOf<String>()
        lines += when {
            r.apk == null -> "App: this release has no app file."
            appNewer(r) -> "App: ${r.version} is ready (you have $installedVersion)."
            newer(installedVersion, r.version) -> "App: you have $installedVersion, newer than the latest release."
            else -> "App: $installedVersion is up to date."
        }
        r.chart?.let { lines += if (chartNewer(r)) "Chart: a different chart is available (${mb(it.size)})." else "Chart: up to date." }
        r.relief?.let {
            lines += when {
                !host.reliefFile.exists() -> "Reef relief pack: optional, not installed (${mb(it.size)})."
                reliefNewer(r) -> "Reef relief pack: a different pack is available (${mb(it.size)})."
                else -> "Reef relief pack: up to date."
            }
        }
        val notes = whatsNew(r.body)
        val msg = lines.joinToString("\n") + if (notes.isNotEmpty()) "\n\nWhat's new in ${r.version}:\n$notes" else ""
        val b = MaterialAlertDialogBuilder(activity)
            .setTitle("Updates · release ${r.version}")
            .setMessage(msg)
            .setNegativeButton("Close", null)
            .setNeutralButton("Release page") { _, _ ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.page))) }
            }
        if (r.apk != null || r.chart != null || r.relief != null) b.setPositiveButton("Download…") { _, _ -> pick(r) }
        b.show()
    }

    /** The "What's new" list from the release notes, as plain text. */
    private fun whatsNew(body: String): String {
        val out = mutableListOf<String>()
        var inside = false
        for (raw in body.lines()) {
            val line = raw.trim()
            if (line.startsWith("#")) {
                if (inside) break
                inside = line.contains("what's new", ignoreCase = true) || line.contains("what’s new", ignoreCase = true)
                continue
            }
            // Only the list itself: notes after it ("The seabed imagery is the same…") aren't news.
            if (inside && line.startsWith("- ")) {
                out += "• " + line.drop(2).replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1").replace(Regex("\\*\\*|`"), "")
            } else if (inside && line.isNotEmpty() && out.isNotEmpty()) break

        }
        return out.joinToString("\n").take(1500)
    }

    private fun pick(r: Release) {
        val options = listOfNotNull(
            // Never offer an older app: Android would refuse it anyway.
            r.apk?.takeIf { appNewer(r) }?.let { Triple(Kind.APK, it, "App ${r.version} (${mb(it.size)})") to true },
            r.chart?.let { Triple(Kind.CHART, it, "Chart (${mb(it.size)})") to chartNewer(r) },
            r.relief?.let { Triple(Kind.RELIEF, it, "Reef relief pack (${mb(it.size)})") to reliefNewer(r) },
        )
        val checked = options.map { it.second }.toBooleanArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle("Download")
            .setMultiChoiceItems(options.map { it.first.third }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Download") { _, _ ->
                val chosen = options.filterIndexed { i, _ -> checked[i] }.map { it.first }
                if (chosen.isEmpty()) return@setPositiveButton
                chosen.forEach { (kind, asset, _) -> enqueue(kind, asset) }
                Toast.makeText(activity, "Downloading. Progress is in the notifications; keep ChompChamp open " +
                    "or come back to it when it's done.", Toast.LENGTH_LONG).show()
                startPolling()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Downloading ------------------------------------------------------------------

    private fun enqueue(kind: Kind, a: Asset) {
        prefs.getString("upd_dl_${kind.key}", null)?.substringBefore('|')?.toLongOrNull()?.let { dm.remove(it) }
        dir.mkdirs()
        File(dir, a.name).delete()
        val req = DownloadManager.Request(Uri.parse(a.url))
            .setTitle("ChompChamp: ${a.name}")
            .setDestinationInExternalFilesDir(activity, "updates", a.name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .addRequestHeader("User-Agent", userAgent)
        val id = dm.enqueue(req)
        prefs.edit().putString("upd_dl_${kind.key}", "$id|${a.name}|${a.id}").apply()
    }

    fun startPolling() {
        if (polling) return
        polling = true
        main.post(poll)
    }

    fun stopPolling() {
        polling = false
        main.removeCallbacks(poll)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            var pending = false
            for (kind in Kind.entries) {
                val v = prefs.getString("upd_dl_${kind.key}", null) ?: continue
                val parts = v.split('|')
                val id = parts[0].toLongOrNull() ?: continue
                val name = parts.getOrElse(1) { "" }
                val assetId = parts.getOrElse(2) { "" }.toLongOrNull() ?: -1L
                when (status(id)) {
                    DownloadManager.STATUS_SUCCESSFUL -> finished(kind, File(dir, name), assetId)
                    DownloadManager.STATUS_FAILED, null -> {
                        prefs.edit().remove("upd_dl_${kind.key}").apply()
                        dm.remove(id)
                        Toast.makeText(activity, "Download of $name failed or was cancelled", Toast.LENGTH_LONG).show()
                    }
                    else -> pending = true
                }
            }
            if (pending) main.postDelayed(this, 2000) else polling = false
        }
    }

    private fun status(id: Long): Int? =
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) null else c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }

    private fun finished(kind: Kind, f: File, assetId: Long) {
        // Forget the download first, so the file is handled once; the file stays until used.
        val key = "upd_dl_${kind.key}"
        val id = prefs.getString(key, null)?.substringBefore('|')?.toLongOrNull()
        prefs.edit().remove(key).apply()
        if (!f.exists()) {
            Toast.makeText(activity, "Downloaded file is missing", Toast.LENGTH_LONG).show()
            return
        }
        when (kind) {
            Kind.APK -> {
                prefs.edit().putString("upd_apk", f.path).apply()
                install()
            }
            else -> host.importDownloaded(f) { ok ->
                if (ok) prefs.edit().putLong("upd_got_${f.name}", assetId).apply()
                // DownloadManager would delete the file anyway once removed.
                id?.let { dm.remove(it) }
            }
        }
    }

    // ---- Installing -------------------------------------------------------------------

    /** Hand a downloaded APK to Android's installer. Also called on resume after granting permission. */
    fun install(fromResume: Boolean = false) {
        val path = prefs.getString("upd_apk", null) ?: return
        val f = File(path)
        val info = if (f.exists()) activity.packageManager.getPackageArchiveInfo(f.path, 0) else null
        if (info == null || info.packageName != activity.packageName ||
            PackageInfoCompat.getLongVersionCode(info) <= installedCode) {
            // Already installed, or not a usable APK.
            prefs.edit().remove("upd_apk").remove("upd_wait_permission").apply()
            f.delete()
            if (!fromResume && info != null) Toast.makeText(activity, "This version is already installed", Toast.LENGTH_SHORT).show()
            else if (!fromResume) Toast.makeText(activity, "The downloaded app file isn't usable", Toast.LENGTH_LONG).show()
            return
        }
        if (!activity.packageManager.canRequestPackageInstalls()) {
            if (fromResume) return
            MaterialAlertDialogBuilder(activity)
                .setTitle("Allow updates")
                .setMessage("Android needs your OK before ChompChamp can install its own updates. On the next screen, " +
                    "turn on \"Allow from this source\", then come back.")
                .setPositiveButton("Continue") { _, _ ->
                    prefs.edit().putBoolean("upd_wait_permission", true).apply()
                    runCatching {
                        activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")))
                    }
                }
                .setNegativeButton("Not now", null)
                .show()
            return
        }
        prefs.edit().remove("upd_wait_permission").apply()
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", f)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { activity.startActivity(intent) }.onFailure {
            Toast.makeText(activity, "Couldn't open the installer: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** On resume: finish downloads that completed while away, and an install waiting for permission. */
    fun resume() {
        if (Kind.entries.any { prefs.contains("upd_dl_${it.key}") }) startPolling()
        if (prefs.getBoolean("upd_wait_permission", false) && activity.packageManager.canRequestPackageInstalls()) {
            install(fromResume = true)
        } else if (prefs.contains("upd_apk")) {
            // Clean up once the new version is installed.
            val f = File(prefs.getString("upd_apk", "")!!)
            val info = if (f.exists()) activity.packageManager.getPackageArchiveInfo(f.path, 0) else null
            if (info == null || PackageInfoCompat.getLongVersionCode(info) <= installedCode) {
                prefs.edit().remove("upd_apk").apply()
                f.delete()
            }
        }
    }

    companion object {
        private const val PAGE = "https://github.com/wufle/chompchamp/releases/latest"
        private const val API = "https://api.github.com/repos/wufle/chompchamp/releases/latest"
    }
}
