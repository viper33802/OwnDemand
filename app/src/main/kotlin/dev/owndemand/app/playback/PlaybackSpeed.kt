package dev.owndemand.app.playback

import kotlin.math.roundToInt

/**
 * Playback-speed rules, kept pure for JVM tests. Speeds move in 5 % steps
 * between 0.5× and 2×, so small boosts (1.05×–1.2×, the useful range for
 * speech) are reachable exactly. Media3 keeps the pitch when it speeds up.
 */
object PlaybackSpeed {
    const val MIN = 0.5f
    const val MAX = 2f
    const val STEP = 0.05f

    /** Stops between [MIN] and [MAX] for a Slider's `steps` (end points excluded). */
    val SLIDER_STEPS: Int = ((MAX - MIN) / STEP).roundToInt() - 1

    /** One-tap choices in the phone/tablet speed panel. */
    val PRESETS = listOf(1f, 1.1f, 1.25f, 1.5f, 2f)

    /** The TV list: 5 % steps where they matter for speech, coarser beyond. */
    val TV_OPTIONS = listOf(0.5f, 0.75f, 0.9f, 0.95f, 1f, 1.05f, 1.1f, 1.15f, 1.2f, 1.25f, 1.5f, 1.75f, 2f)

    /** [speed] on the nearest 5 % stop, inside the range (removes float drift such as 1.1000001). */
    fun snap(speed: Float): Float =
        ((speed.coerceIn(MIN, MAX) / STEP).roundToInt() * STEP).let { (it * 100).roundToInt() / 100f }

    /** [speed] moved by [steps] 5 % stops, kept in range. */
    fun step(speed: Float, steps: Int): Float = snap(snap(speed) + steps * STEP)

    /** "1", "1.1", "1.05": at most two decimals, no trailing zeros, locale-independent. */
    fun number(speed: Float): String {
        val hundredths = (snap(speed) * 100).roundToInt()
        val whole = hundredths / 100
        val frac = hundredths % 100
        return when {
            frac == 0 -> "$whole"
            frac % 10 == 0 -> "$whole.${frac / 10}"
            else -> "$whole.${frac.toString().padStart(2, '0')}"
        }
    }

    fun same(a: Float, b: Float): Boolean = snap(a) == snap(b)
}
