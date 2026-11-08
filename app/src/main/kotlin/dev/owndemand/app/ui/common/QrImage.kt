package dev.owndemand.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import kotlin.math.roundToInt

/** Modules left blank around the code (the standard asks for 4; the card adds more). */
private const val QUIET_MODULES = 3

/**
 * Width, in modules, of the blank area in the middle that holds the logo:
 * about a quarter of the code's width, odd so it sits on the centre module.
 * Level H error correction absorbs the modules it covers.
 */
fun qrLogoModules(size: Int): Int = ((size * 0.24f).toInt() / 2) * 2 + 1

/** Height for a blank area [width] modules wide holding a logo of [aspect] (w / h); odd. */
fun qrLogoHeight(width: Int, aspect: Float): Int {
    val h = (width / aspect).roundToInt().coerceIn(1, width)
    return if (h % 2 == 0) h - 1 else h
}

/**
 * A QR code drawn as round dots, with rounded corner "eyes" and an
 * optional [logo] in a blank area in the middle. Use [QrEcc.H] when
 * there is a logo.
 */
@Composable
fun QrImage(
    text: String,
    color: Color,
    background: Color,
    modifier: Modifier = Modifier,
    logo: Painter? = null,
    ecc: QrEcc = QrEcc.H,
) {
    val qr = remember(text, ecc) { QrCode.encode(text, ecc) }
    Canvas(modifier.aspectRatio(1f)) {
        drawRect(background)
        val n = qr.size
        val m = size.minDimension / (n + QUIET_MODULES * 2)
        val origin = QUIET_MODULES * m
        // The blank area takes the logo's shape (a 4:3 tape clears fewer
        // modules than a square would).
        val aspect = logo?.intrinsicSize
            ?.takeIf { it.isSpecified && it.width > 0f && it.height > 0f }
            ?.let { it.width / it.height } ?: 1f
        val logoW = if (logo != null) qrLogoModules(n) else 0
        val logoH = if (logo != null) qrLogoHeight(logoW, aspect) else 0
        val logoX = (n - logoW) / 2
        val logoY = (n - logoH) / 2
        fun inLogo(x: Int, y: Int) = logoW > 0 && x in logoX until logoX + logoW && y in logoY until logoY + logoH

        val dotRadius = m * 0.42f
        for (y in 0 until n) for (x in 0 until n) {
            if (!qr[x, y] || qr.isFinder(x, y) || inLogo(x, y)) continue
            drawCircle(color, dotRadius, Offset(origin + (x + 0.5f) * m, origin + (y + 0.5f) * m))
        }
        for ((fx, fy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
            drawEye(Offset(origin + fx * m, origin + fy * m), m, color)
        }
        if (logo != null) {
            // The logo, half a module clear of the nearest dots, centred.
            val w = logoW * m - m
            val h = w / aspect
            translate(origin + logoX * m + m / 2, origin + (logoY + logoH / 2f) * m - h / 2) {
                with(logo) { draw(Size(w, h)) }
            }
        }
    }
}

/** A finder pattern: a rounded 7 × 7 ring around a rounded 3 × 3 square. */
private fun DrawScope.drawEye(topLeft: Offset, m: Float, color: Color) {
    val stroke = m
    drawRoundRect(
        color = color,
        topLeft = topLeft + Offset(stroke / 2, stroke / 2),
        size = Size(7 * m - stroke, 7 * m - stroke),
        cornerRadius = CornerRadius(m * 1.8f),
        style = Stroke(width = stroke),
    )
    drawRoundRect(
        color = color,
        topLeft = topLeft + Offset(2 * m, 2 * m),
        size = Size(3 * m, 3 * m),
        cornerRadius = CornerRadius(m * 0.9f),
    )
}
