package com.auto.classify.ui

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Width / height of this phone's screen: preview pages keep the real proportions. */
@Composable
internal fun screenRatio(): Float {
    val c = LocalConfiguration.current
    return (c.screenWidthDp.toFloat() / c.screenHeightDp).coerceIn(0.3f, 0.8f)
}

/** The phone's current wallpaper, scaled down; null when the system will not hand it over. */
@Composable
internal fun rememberWallpaper(): ImageBitmap? {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val d = WallpaperManager.getInstance(context).drawable ?: return@runCatching null
                val w = 360
                val h = (w * d.intrinsicHeight.toFloat() / d.intrinsicWidth.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    d.setBounds(0, 0, w, h)
                    d.draw(Canvas(it))
                }.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

/** Wallpaper cropped to fill, dimmed like the apps screen; a dusk gradient when there is no wallpaper. */
internal fun Modifier.launcherBackdrop(wallpaper: ImageBitmap?): Modifier = drawBehind {
    if (wallpaper != null) {
        val s = maxOf(size.width / wallpaper.width, size.height / wallpaper.height)
        val dw = (wallpaper.width * s).toInt()
        val dh = (wallpaper.height * s).toInt()
        clipRect {
            drawImage(
                wallpaper,
                dstOffset = IntOffset(((size.width - dw) / 2).toInt(), ((size.height - dh) / 2).toInt()),
                dstSize = IntSize(dw, dh),
            )
        }
        drawRect(Color.Black.copy(alpha = 0.28f))
    } else {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF262C34), Color(0xFF3B3722))))
    }
}

/** Where the apps grid sits on the page: below the status bar, above the page dots and the search bar. */
internal fun Modifier.gridArea(pageHeight: Dp): Modifier =
    padding(start = 4.dp, end = 4.dp, top = pageHeight * 0.095f, bottom = pageHeight * 0.17f)

/** The launcher's search pill at the foot of a page. */
@Composable
internal fun BoxScope.SearchPill(pageHeight: Dp) {
    Spacer(
        Modifier.align(Alignment.BottomCenter).padding(bottom = pageHeight * 0.035f)
            .fillMaxWidth(0.9f).height(pageHeight * 0.055f)
            .clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.2f)),
    )
}

/** Folder tile as the launcher draws it: a dark (or Liquid Glass) rounded square holding up to 3x3 icons. */
@Composable
internal fun LauncherFolderTile(size: Dp, color: Int?, pkgs: List<String>, modifier: Modifier = Modifier) {
    val glass = FolderStyle.mode == "glass"
    val shape = RoundedCornerShape(size * 0.3f)
    val fill = when {
        color != null -> Color(color).copy(alpha = if (glass) 0.35f else 0.6f)
        glass -> Color.White.copy(alpha = 0.18f)
        else -> Color.Black.copy(alpha = 0.4f)
    }
    val mini = size * 0.24f
    Column(
        modifier.size(size).clip(shape).background(fill)
            .then(if (glass) Modifier.border(0.8.dp, Color.White.copy(alpha = 0.4f), shape) else Modifier)
            .padding(size * 0.1f),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (r in 0 until 3) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (c in 0 until 3) {
                val pkg = pkgs.getOrNull(r * 3 + c)
                if (pkg != null) AppIcon(pkg, Modifier.size(mini)) else Spacer(Modifier.size(mini))
            }
        }
    }
}
