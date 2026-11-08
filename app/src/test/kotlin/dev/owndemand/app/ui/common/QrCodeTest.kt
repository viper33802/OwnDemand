package dev.owndemand.app.ui.common

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checked against values published in ISO/IEC 18004 and its worked examples. */
class QrCodeTest {

    @Test
    fun `capacity tables`() {
        assertEquals(19, dataCodewords(1, QrEcc.L))
        assertEquals(16, dataCodewords(1, QrEcc.M))
        assertEquals(13, dataCodewords(1, QrEcc.Q))
        assertEquals(9, dataCodewords(1, QrEcc.H))
        assertEquals(122, dataCodewords(10, QrEcc.H))
        assertEquals(2956, dataCodewords(40, QrEcc.L))
        assertEquals(1276, dataCodewords(40, QrEcc.H))
        assertEquals(3706, rawDataModules(40) / 8)
    }

    @Test
    fun `reed-solomon matches the standard's version 1-M example`() {
        // "01234567", version 1-M: data codewords and their 10 EC codewords.
        val data = intArrayOf(0x10, 0x20, 0x0C, 0x56, 0x61, 0x80, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11, 0xEC, 0x11)
            .map { it.toByte() }.toByteArray()
        val expected = intArrayOf(0xA5, 0x24, 0xD4, 0xC1, 0xED, 0x36, 0xC7, 0x87, 0x2C, 0x55)
            .map { it.toByte() }.toByteArray()
        assertArrayEquals(expected, reedSolomonRemainder(data, reedSolomonDivisor(10)))
    }

    @Test
    fun `format and version bits`() {
        assertEquals(0b111011111000100, qrFormatBits(QrEcc.L, 0))
        assertEquals(0b101010000010010, qrFormatBits(QrEcc.M, 0))
        assertEquals(0b011010101011111, qrFormatBits(QrEcc.Q, 0))
        assertEquals(0b001011010001001, qrFormatBits(QrEcc.H, 0))
        assertEquals(0b100000011001110, qrFormatBits(QrEcc.M, 5))
        assertEquals(0x07C94, qrVersionBits(7))
        assertEquals(0x28C69, qrVersionBits(40))
    }

    @Test
    fun `smallest version that fits is chosen`() {
        assertEquals(1, QrCode.encode("a".repeat(7), QrEcc.H).version)
        assertEquals(2, QrCode.encode("a".repeat(8), QrEcc.H).version)
        // A sign-in link (145 characters) at H: version 12, 65 × 65.
        val link = QrCode.encode("x".repeat(145), QrEcc.H)
        assertEquals(12, link.version)
        assertEquals(65, link.size)
    }

    @Test
    fun `finder patterns, timing and the dark module are in place`() {
        val qr = QrCode.encode("https://plex.tv/link", QrEcc.H)
        val n = qr.size
        for ((ox, oy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
            for (y in 0 until 7) for (x in 0 until 7) {
                val ring = maxOf(kotlin.math.abs(x - 3), kotlin.math.abs(y - 3))
                assertEquals("finder at $ox,$oy", ring != 2, qr[ox + x, oy + y])
                assertTrue(qr.isFinder(ox + x, oy + y))
            }
        }
        for (i in 8 until n - 8) {
            assertEquals(i % 2 == 0, qr[i, 6])
            assertEquals(i % 2 == 0, qr[6, i])
        }
        assertTrue(qr[8, n - 8])
    }

    @Test
    fun `logo area is odd-sized and follows the logo's shape`() {
        assertEquals(15, qrLogoModules(65))
        assertEquals(11, qrLogoHeight(15, 604f / 454f)) // the 4:3 tape
        assertEquals(15, qrLogoHeight(15, 1f))
        assertEquals(9, qrLogoHeight(15, 1.6f)) // 9.4 → 9
        assertEquals(7, qrLogoHeight(15, 1.9f)) // 7.9 → 8 → odd 7
    }

    @Test(expected = IllegalArgumentException::class)
    fun `too long is refused`() {
        QrCode.encode("x".repeat(3000), QrEcc.H)
    }
}
