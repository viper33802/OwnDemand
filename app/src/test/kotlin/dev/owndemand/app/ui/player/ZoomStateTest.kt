package dev.owndemand.app.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomStateTest {

    private val screen = Size(1080f, 608f) // 16:9 player at 1080 px wide

    @Test
    fun `initial state is identity`() {
        val z = ZoomState()
        assertEquals(1f, z.scale, 0.0001f)
        assertEquals(0f, z.offsetX, 0.0001f)
        assertEquals(0f, z.offsetY, 0.0001f)
    }

    @Test
    fun `zoom about the center keeps the offset at zero`() {
        val z = ZoomState()
        z.applyZoom(2f, Offset(540f, 304f), screen)
        assertEquals(2f, z.scale, 0.0001f)
        assertEquals(0f, z.offsetX, 0.0001f)
        assertEquals(0f, z.offsetY, 0.0001f)
    }

    @Test
    fun `zoom about an edge point clamps so no black edge shows`() {
        val z = ZoomState()
        // Pinch spread anchored at the top-left corner: the ideal offset
        // would expose black beyond the right/bottom edges; clamp holds it.
        z.applyZoom(2f, Offset(0f, 0f), screen)
        assertEquals(2f, z.scale, 0.0001f)
        assertEquals(screen.width * 0.5f, z.offsetX, 0.0001f)
        assertEquals(screen.height * 0.5f, z.offsetY, 0.0001f)
    }

    @Test
    fun `scale is capped at four`() {
        val z = ZoomState()
        z.applyZoom(10f, Offset(540f, 304f), screen)
        assertEquals(ZoomState.MAX, z.scale, 0.0001f)
    }

    @Test
    fun `pan is clamped to the zoomed bounds`() {
        val z = ZoomState()
        z.applyZoom(2f, Offset(540f, 304f), screen)
        z.applyPan(Offset(5_000f, -5_000f), screen)
        assertEquals(screen.width / 2f, z.offsetX, 0.0001f)
        assertEquals(-screen.height / 2f, z.offsetY, 0.0001f)
    }

    @Test
    fun `zooming back to one recenters`() {
        val z = ZoomState()
        z.applyZoom(2f, Offset(0f, 0f), screen)
        z.applyZoom(0.5f, Offset(540f, 304f), screen)
        assertEquals(1f, z.scale, 0.0001f)
        assertEquals(0f, z.offsetX, 0.0001f)
        assertEquals(0f, z.offsetY, 0.0001f)
    }

    @Test
    fun `reset returns to identity from a zoomed panned state`() {
        val z = ZoomState()
        z.applyZoom(3f, Offset(100f, 100f), screen)
        z.applyPan(Offset(-40f, 20f), screen)
        z.reset()
        assertEquals(1f, z.scale, 0.0001f)
        assertEquals(0f, z.offsetX, 0.0001f)
        assertEquals(0f, z.offsetY, 0.0001f)
    }
}
