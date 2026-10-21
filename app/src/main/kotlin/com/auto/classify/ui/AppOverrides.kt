package com.auto.classify.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * User-set app names and icons, shown in AutoClassify only. Classification and the launcher keep the real values.
 * Names live in prefs; icons are 192px PNGs under filesDir.
 */
object AppOverrides {
    private const val PREFS = "app_overrides"
    private const val ICON_DIR = "app_icons"
    private const val ICON_PX = 192
    private const val CORNER_RATIO = 0.22f

    /** Bumped after every change so composables that read an override recompose. */
    var version by mutableIntStateOf(0)
        private set

    fun changed() {
        version++
    }

    /** Per-app icon change count. Icon caches key on this, so changing one icon reloads only that one. */
    private val iconStamps = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun iconStamp(pkg: String): Int = iconStamps[pkg] ?: 0

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun iconFile(ctx: Context, pkg: String) = File(File(ctx.filesDir, ICON_DIR), "$pkg.png")

    fun name(ctx: Context, pkg: String): String? = prefs(ctx).getString("name:$pkg", null)

    /** Every package with a name override, for the launcher hook. */
    fun names(ctx: Context): Map<String, String> =
        prefs(ctx).all.mapNotNull { (k, v) -> if (k.startsWith("name:") && v is String) k.removePrefix("name:") to v else null }.toMap()

    /** Every package with a custom icon, for the launcher hook. */
    fun iconPackages(ctx: Context): List<String> =
        File(ctx.filesDir, ICON_DIR).list()?.filter { it.endsWith(".png") }?.map { it.removeSuffix(".png") } ?: emptyList()

    /** Blank clears the override and returns to the real name. */
    fun setName(ctx: Context, pkg: String, name: String) {
        val value = name.trim()
        prefs(ctx).edit().apply {
            if (value.isEmpty()) remove("name:$pkg") else putString("name:$pkg", value)
        }.apply()
    }

    fun hasIcon(ctx: Context, pkg: String): Boolean = iconFile(ctx, pkg).exists()

    fun customIcon(ctx: Context, pkg: String): Bitmap? =
        iconFile(ctx, pkg).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    /** Decodes a picked image, downsampled so the longest side stays near 1024px. Call off the main thread. */
    fun decode(ctx: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (largest / (sample * 2) >= 1024) sample *= 2
        val load = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, load) }
    }

    /**
     * Saves a square bitmap as the icon at 192px, with the rounded corners of a launcher icon so it
     * sits with the others. Call off the main thread.
     */
    fun setIconBitmap(ctx: Context, pkg: String, square: Bitmap) {
        val scaled = Bitmap.createScaledBitmap(square, ICON_PX, ICON_PX, true)
        val masked = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val radius = ICON_PX * CORNER_RATIO
        Canvas(masked).apply {
            clipPath(Path().apply { addRoundRect(RectF(0f, 0f, ICON_PX.toFloat(), ICON_PX.toFloat()), radius, radius, Path.Direction.CW) })
            drawBitmap(scaled, 0f, 0f, null)
        }
        val file = iconFile(ctx, pkg).apply { parentFile?.mkdirs() }
        file.outputStream().use { masked.compress(Bitmap.CompressFormat.PNG, 100, it) }
        iconStamps.merge(pkg, 1, Int::plus)
    }

    fun resetIcon(ctx: Context, pkg: String) {
        iconFile(ctx, pkg).delete()
        iconStamps.merge(pkg, 1, Int::plus)
    }
}
