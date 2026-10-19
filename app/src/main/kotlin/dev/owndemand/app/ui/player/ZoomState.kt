package dev.owndemand.app.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.toSize
import kotlin.math.abs

/**
 * Pinch-zoom + pan state for the player surface (spec §3). The surface is
 * transformed with a graphicsLayer: scale about the center pivot, then
 * translation. [applyZoom] keeps the pinch centroid stationary on screen;
 * translation is clamped so frame edges never show black. Pure math, so
 * the transform rules are unit-testable.
 */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set

    fun reset() {
        scale = MIN
        offsetX = 0f
        offsetY = 0f
    }

    /** Multiply the scale by [factor], anchored so [centroid] stays put. */
    fun applyZoom(factor: Float, centroid: Offset, containerSize: Size) {
        val newScale = (scale * factor).coerceIn(MIN, MAX)
        if (newScale == scale) return
        val ratio = newScale / scale
        // graphicsLayer maps local p to pivot + (p - pivot)·s + t; solving
        // for the translation that keeps the centroid fixed gives:
        //   t' = (c - pivot)·(1 - s'/s) + t·s'/s
        val pivotX = containerSize.width / 2f
        val pivotY = containerSize.height / 2f
        offsetX = (centroid.x - pivotX) * (1f - ratio) + offsetX * ratio
        offsetY = (centroid.y - pivotY) * (1f - ratio) + offsetY * ratio
        scale = newScale
        clamp(containerSize)
    }

    /** Move the zoomed frame by [delta] pixels, clamped to the edges. */
    fun applyPan(delta: Offset, containerSize: Size) {
        offsetX += delta.x
        offsetY += delta.y
        clamp(containerSize)
    }

    private fun clamp(containerSize: Size) {
        if (abs(scale - MIN) < 0.001f) {
            scale = MIN
            offsetX = 0f
            offsetY = 0f
            return
        }
        val maxX = containerSize.width * (scale - 1f) / 2f
        val maxY = containerSize.height * (scale - 1f) / 2f
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }

    companion object {
        const val MIN = 1f
        const val MAX = 4f
    }
}

/**
 * Pinch to zoom (1×–4×) about the centroid; while zoomed, a two-finger
 * drag pans the frame (spreading still zooms) and a single-finger drag
 * pans too — matching the reference app (user direction 2026-10-16).
 * Single-finger panning only starts after the finger actually moves so
 * taps and double-taps still reach downstream tap detectors (spec §3).
 * Consumed in the Initial pass so two-finger gestures never look like taps.
 */
fun Modifier.playerZoomGestures(zoom: ZoomState): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var zooming = false
        var panning = false
        var dragTotal = 0f
        var lastSpan = 0f
        var lastCentroid: Offset? = null
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            when {
                pressed.size >= 2 -> {
                    zooming = true
                    val a = pressed[0].position
                    val b = pressed[1].position
                    val span = (a - b).getDistance()
                    val centroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                    // Two-finger drag pans while zoomed (reference app);
                    // at 1x the clamp drops the offset anyway.
                    lastCentroid?.let { prev ->
                        zoom.applyPan(centroid - prev, size.toSize())
                    }
                    lastCentroid = centroid
                    if (lastSpan > 0f && span > 0f) {
                        zoom.applyZoom(span / lastSpan, centroid, size.toSize())
                    }
                    lastSpan = span
                    pressed.forEach { it.consume() }
                }
                pressed.size == 1 && zooming -> {
                    // Second finger lifted; swallow until it is up.
                    lastCentroid = null
                    lastSpan = 0f
                    pressed.forEach { it.consume() }
                }
                pressed.size == 1 && zoom.scale > 1f -> {
                    val change = pressed[0]
                    val drag = change.position - change.previousPosition
                    if (panning) {
                        zoom.applyPan(drag, size.toSize())
                        change.consume()
                    } else {
                        dragTotal += drag.getDistance()
                        if (dragTotal > 12f) {
                            panning = true
                            zoom.applyPan(drag, size.toSize())
                            change.consume()
                        }
                    }
                }
                else -> break // plain single-finger at 1x: other handlers
            }
            if (event.changes.none { it.pressed }) break
        }
    }
}
