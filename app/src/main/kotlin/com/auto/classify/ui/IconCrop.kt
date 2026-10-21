package com.auto.classify.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.min
import kotlin.math.roundToInt

private val CROP_VIEW = 280.dp

/** Pan and zoom a picked image inside a square frame; [onDone] gets the square crop. */
@Composable
fun IconCropDialog(src: Bitmap, onDismiss: () -> Unit, onDone: (Bitmap) -> Unit) {
    val img = remember(src) { src.asImageBitmap() }
    var zoom by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    var view by remember { mutableFloatStateOf(0f) }
    val w = src.width
    val h = src.height

    WindowDialog(show = true, title = "아이콘 자르기", summary = "드래그와 두 손가락 확대로 영역을 맞춰요",
        onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(
                Modifier.size(CROP_VIEW).onSizeChanged { view = it.width.toFloat() }
                    .pointerInput(src) {
                        detectTransformGestures { _, pan, change, _ ->
                            if (view <= 0f) return@detectTransformGestures
                            val s = view / min(w, h) * (zoom * change).coerceIn(1f, 6f)
                            zoom = (zoom * change).coerceIn(1f, 6f)
                            val mx = ((w * s - view) / 2).coerceAtLeast(0f)
                            val my = ((h * s - view) / 2).coerceAtLeast(0f)
                            ox = (ox + pan.x).coerceIn(-mx, mx)
                            oy = (oy + pan.y).coerceIn(-my, my)
                        }
                    },
            ) {
                val base = size.width / min(w, h)
                val s = base * zoom
                val left = (size.width - w * s) / 2 + ox
                val top = (size.height - h * s) / 2 + oy
                drawImage(
                    img,
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize((w * s).roundToInt(), (h * s).roundToInt()),
                )
                drawRect(Color.White, style = Stroke(2.dp.toPx()))
            }
            Spacer(Modifier.height(12.dp))
            SheetActions("취소", onDismiss, "저장", {
                if (view > 0f) onDone(cropSquare(src, view, zoom, ox, oy))
            })
        }
    }
}

/** Crops the square frame currently shown in [IconCropDialog] out of [src]. */
private fun cropSquare(src: Bitmap, view: Float, zoom: Float, ox: Float, oy: Float): Bitmap {
    val w = src.width
    val h = src.height
    val s = view / min(w, h) * zoom
    val left = (view - w * s) / 2 + ox
    val top = (view - h * s) / 2 + oy
    val side = min(min(w, h), (view / s).roundToInt())
    val x = (-left / s).roundToInt().coerceIn(0, w - side)
    val y = (-top / s).roundToInt().coerceIn(0, h - side)
    return Bitmap.createBitmap(src, x, y, side, side)
}
