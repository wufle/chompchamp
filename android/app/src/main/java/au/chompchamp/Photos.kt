package au.chompchamp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Photo storage for marks. Each photo is saved as <id>.jpg (max 1600 px) plus a ready-made
 * map bubble <id>_bubble.png, so the map never has to decode full-size photos.
 */
class Photos(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "photos").apply { mkdirs() }
    private val dp = ctx.resources.displayMetrics.density

    fun file(name: String) = File(dir, "$name.jpg")
    private fun bubbleFile(name: String) = File(dir, "${name}_bubble.png")

    /** Copy, rotate upright, shrink and store a photo. Returns its id. Call off the UI thread. */
    fun import(uri: Uri): String {
        val name = UUID.randomUUID().toString()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0) { "not an image" }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PX) sample *= 2
        val decoded = ctx.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("couldn't read image")

        val rotation = runCatching {
            ctx.contentResolver.openInputStream(uri).use { ExifInterface(requireNotNull(it)).rotationDegrees }
        }.getOrDefault(0)
        val scale = min(1f, MAX_PX.toFloat() / max(decoded.width, decoded.height))
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
        val photo = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)

        file(name).outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bubbleFile(name).outputStream().use { makeBubble(photo).compress(Bitmap.CompressFormat.PNG, 100, it) }
        return name
    }

    /** The map bubble for a photo, at screen density. */
    fun bubble(name: String): Bitmap? {
        val f = bubbleFile(name)
        if (!f.exists()) {
            // Rebuild if missing (e.g. photo copied in without its bubble).
            val photo = loadScaled(name, 256) ?: return null
            runCatching { f.outputStream().use { makeBubble(photo).compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        return BitmapFactory.decodeFile(f.path)?.also { it.density = ctx.resources.displayMetrics.densityDpi }
    }

    /** Decode a photo no larger than [maxPx] on its long side (for thumbnails and the viewer). */
    fun loadScaled(name: String, maxPx: Int): Bitmap? {
        val f = file(name)
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun delete(name: String) {
        file(name).delete()
        bubbleFile(name).delete()
    }

    /** Round photo with a white rim and a small pointer at the bottom. */
    private fun makeBubble(src: Bitmap): Bitmap {
        val d = (46 * dp)
        val rim = 2.5f * dp
        val tail = 7 * dp
        val w = (d + 2).toInt()
        val h = (d + tail + 2).toInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val cx = w / 2f
        val cy = d / 2f + 1

        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        // Pointer
        c.drawPath(Path().apply {
            moveTo(cx - tail, cy + d / 2f - tail)
            lineTo(cx + tail, cy + d / 2f - tail)
            lineTo(cx, cy + d / 2f + tail)
            close()
        }, white)
        c.drawCircle(cx, cy, d / 2f, white)

        // Centre-cropped photo inside the rim
        val side = min(src.width, src.height)
        val crop = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val inner = d / 2f - rim
        val shader = BitmapShader(crop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                val s = (inner * 2) / side
                setScale(s, s)
                postTranslate(cx - inner, cy - inner)
            })
        }
        c.drawCircle(cx, cy, inner, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        c.drawCircle(cx, cy, d / 2f - rim / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = 0x33000000
        })
        return out
    }

    companion object {
        const val MAX_PX = 1600
    }
}
