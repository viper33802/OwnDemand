package dev.owndemand.app.ui.player

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end gesture check for [playerZoomGestures]: real two-pointer
 * MotionEvents injected through UiAutomation, asserting the ZoomState the
 * modifier drives. (adb/console cannot inject multi-touch, so this is the
 * automated path for the pinch.)
 */
@RunWith(AndroidJUnit4::class)
class PinchZoomInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private fun inject(event: MotionEvent) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .injectInputEvent(event, true)
        event.recycle()
    }

    private fun motion(
        downTime: Long,
        eventTime: Long,
        action: Int,
        vararg points: Pair<Float, Float>,
    ): MotionEvent {
        val props = points.indices.map { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = points.map { (x, y) ->
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                orientation = 0f
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            downTime, eventTime, action, points.size,
            props.toTypedArray(), coords.toTypedArray(),
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    @Test
    fun pinchSpreadZoomsAboutTheCentroid() {
        val zoom = ZoomState()
        compose.setContent {
            Box(Modifier.fillMaxSize().playerZoomGestures(zoom))
        }
        compose.waitForIdle()

        val t = SystemClock.uptimeMillis()
        inject(motion(t, t, MotionEvent.ACTION_DOWN, 400f to 300f))
        inject(
            motion(
                t, t + 10, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                400f to 300f, 600f to 300f,
            ),
        )
        for (i in 1..4) {
            inject(
                motion(
                    t, t + 30L * i, MotionEvent.ACTION_MOVE,
                    400f - 25f * i to 300f, 600f + 25f * i to 300f,
                ),
            )
        }
        inject(
            motion(
                t, t + 200, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                300f to 300f, 700f to 300f,
            ),
        )
        inject(motion(t, t + 220, MotionEvent.ACTION_UP, 300f to 300f))
        compose.waitForIdle()

        // 200 px span -> 400 px span = 2x.
        assertEquals(2f, zoom.scale, 0.25f)
    }

    @Test
    fun dragPansOnlyAfterZooming() {
        val zoom = ZoomState()
        compose.setContent {
            Box(Modifier.fillMaxSize().playerZoomGestures(zoom))
        }
        compose.waitForIdle()

        // Single-finger drag at 1x must NOT pan (it belongs to other handlers).
        val t = SystemClock.uptimeMillis()
        inject(motion(t, t, MotionEvent.ACTION_DOWN, 540f to 300f))
        for (i in 1..4) {
            inject(motion(t, t + 20L * i, MotionEvent.ACTION_MOVE, 540f - 40f * i to 300f))
        }
        inject(motion(t, t + 120, MotionEvent.ACTION_UP, 380f to 300f))
        compose.waitForIdle()
        assertEquals(1f, zoom.scale, 0.001f)
        assertEquals(0f, zoom.offsetX, 0.001f)

        // Zoom in, then the same drag pans.
        inject(motion(t, t + 300, MotionEvent.ACTION_DOWN, 440f to 300f))
        inject(
            motion(
                t, t + 310, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                440f to 300f, 640f to 300f,
            ),
        )
        for (i in 1..4) {
            inject(
                motion(
                    t, t + 340L + 20L * i, MotionEvent.ACTION_MOVE,
                    440f - 25f * i to 300f, 640f + 25f * i to 300f,
                ),
            )
        }
        inject(
            motion(
                t, t + 460, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                340f to 300f, 740f to 300f,
            ),
        )
        inject(motion(t, t + 480, MotionEvent.ACTION_UP, 340f to 300f))
        compose.waitForIdle()
        assertTrue("expected zoom > 1, was ${zoom.scale}", zoom.scale > 1.5f)

        val before = zoom.offsetX
        val t2 = SystemClock.uptimeMillis()
        inject(motion(t2, t2, MotionEvent.ACTION_DOWN, 540f to 300f))
        for (i in 1..4) {
            inject(motion(t2, t2 + 20L * i, MotionEvent.ACTION_MOVE, 540f - 40f * i to 300f))
        }
        inject(motion(t2, t2 + 120, MotionEvent.ACTION_UP, 380f to 300f))
        compose.waitForIdle()
        assertTrue(
            "expected pan from $before, got ${zoom.offsetX}",
            zoom.offsetX < before - 50f,
        )
    }
}
