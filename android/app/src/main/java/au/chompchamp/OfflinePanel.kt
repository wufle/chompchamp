package au.chompchamp

import android.content.Context
import android.content.SharedPreferences
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Docked panel to save satellite and reef relief imagery for the area on screen, and to manage
 * saved areas. Docked rather than a dialog so the user can pan and zoom while it's open.
 */
class OfflinePanel(
    private val ctx: Context,
    private val panel: SettingsPanel,
    private val look: Appearance,
    private val prefs: SharedPreferences,
    private val areas: OfflineAreas,
    private val host: Host,
) {
    interface Host {
        /** West, south, east, north of the map on screen, or null before the map is ready. */
        fun screenBox(): DoubleArray?
        fun templates(): Map<OfflineLayer, String>
        fun zoomTo(a: OfflineArea)
        /** A saved area was added or removed: reload the style. */
        fun savedChanged()
        fun beforeShow()
        fun confirm(message: String, onYes: () -> Unit)
        fun askText(title: String, initial: String, onOk: (String) -> Unit)
    }

    private var estimate: TextView? = null
    private var progress: TextView? = null

    val isOpen get() = panel.isOpen && panel.current == TITLE

    private fun chosen(l: OfflineLayer) = prefs.getBoolean("offline_layer_${l.key}", l.defaultOn)
    private fun chosenLayers() = OfflineLayer.entries.filter { chosen(it) }

    fun show() {
        host.beforeShow()
        panel.show(TITLE, { rows -> build(rows) }, onClose = { estimate = null; progress = null })
    }

    private fun refresh() { if (isOpen) show() }

    /** The map stopped moving: the estimate follows the area on screen. */
    fun onCameraIdle() { if (isOpen) updateEstimate() }

    private fun mb(bytes: Long) = when {
        bytes < 1_000_000 -> "under 1 MB"
        else -> String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
    }

    private fun build(rows: SettingsPanel.Rows) {
        rows.section("Save the area on screen")
        rows.note("Move and zoom the map until the area you want fills the screen, then download it. " +
            "Saved imagery shows with no signal. The chart, zones and hazards already work offline.")
        for (l in OfflineLayer.entries) {
            rows.actionRow(l.label, if (chosen(l)) "On" else "Off") {
                prefs.edit().putBoolean("offline_layer_${l.key}", !chosen(l)).apply()
                refresh()
            }
        }
        estimate = TextView(ctx).apply {
            textSize = 13f
            setTextColor(look.onSurface)
            setPadding(0, (8 * ctx.resources.displayMetrics.density).toInt(), 0, 0)
        }.also { rows.view(it) }
        updateEstimate()
        val job = areas.job
        if (job != null) {
            progress = TextView(ctx).apply {
                textSize = 13f
                setTextColor(look.accent)
            }.also { rows.view(it) }
            showProgress(job)
            rows.button("Cancel download") { job.cancelled = true }
        } else {
            rows.button("Download this area") { startDownload() }
        }

        val saved = synchronized(areas.areas) { areas.areas.toList() }
        if (saved.isNotEmpty()) {
            rows.section("Saved areas")
            val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
            for (a in saved.sortedByDescending { it.created }) {
                val what = a.layers.joinToString(", ") { it.label.substringBefore(" (").lowercase() }
                    .replaceFirstChar { it.uppercase() }
                rows.actionRow("${a.name}\n$what · ${mb(a.bytes)} · ${fmt.format(Date(a.created))}", "⋯") { areaMenu(a) }
            }
            rows.note("Total ${mb(saved.sumOf { it.bytes })}.")
        }
        rows.note("Satellite: Digital Earth Australia, Geoscience Australia (low-tide Sentinel-2 coast; Landsat inland). " +
            "Reef relief: WA Department of Transport. Both CC BY 4.0.")
    }

    private fun updateEstimate() {
        val t = estimate ?: return
        val box = host.screenBox()
        val layers = chosenLayers()
        t.text = when {
            box == null -> ""
            layers.isEmpty() -> "Turn on at least one layer."
            else -> {
                val tiles = areas.totalTiles(layers, box[0], box[1], box[2], box[3])
                if (tiles > MAX_TILES) "This area is too big to save in one go. Zoom in a bit."
                else "This area: up to ${mb(areas.estimateBytes(layers, box[0], box[1], box[2], box[3]))} " +
                    "(${String.format(Locale.US, "%,d", tiles)} tiles). Empty sea and land take less."
            }
        }
    }

    private fun showProgress(j: OfflineAreas.Job) {
        progress?.text = String.format(Locale.US, "Downloading… %,d of %,d tiles (%s)",
            j.done.get(), j.total, mb(j.bytes.get()))
    }

    private fun startDownload() {
        val box = host.screenBox() ?: return
        val layers = chosenLayers()
        if (layers.isEmpty()) { toast("Turn on at least one layer"); return }
        val tiles = areas.totalTiles(layers, box[0], box[1], box[2], box[3])
        if (tiles > MAX_TILES) { toast("Too big to save in one go. Zoom in a bit."); return }
        val n = areas.areas.size + 1
        host.askText("Name this area", "Area $n") { name ->
            if (areas.job != null) return@askText
            areas.download(name, box[0], box[1], box[2], box[3], layers, host.templates(),
                onProgress = { showProgress(it) },
                onDone = { r ->
                    r.onSuccess { a ->
                        toast("Saved \"${a.name}\" (${mb(a.bytes)}). It shows when Satellite or Reef relief is on in Layers.")
                        host.savedChanged()
                    }.onFailure { toast("Area not saved: ${it.message}") }
                    refresh()
                })
            toast("Downloading. You can close this panel and keep using the map.")
            refresh()
        }
    }

    private fun areaMenu(a: OfflineArea) {
        val items = listOf<Pair<String, () -> Unit>>(
            "Show on map" to { host.zoomTo(a) },
            "Rename" to { host.askText("Rename area", a.name) { areas.rename(a, it); refresh() } },
            "Delete" to {
                host.confirm("Delete the saved imagery for \"${a.name}\" (${mb(a.bytes)})?") {
                    areas.delete(a)
                    host.savedChanged()
                    refresh()
                }
            },
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle(a.name)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    companion object {
        const val TITLE = "Offline areas"
        /** About 300-500 MB at most; bigger areas are best saved in parts. */
        private const val MAX_TILES = 25_000L
    }
}
