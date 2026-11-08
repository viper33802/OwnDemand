package dev.owndemand.app.ui.common

/*
 * QR Code encoder (ISO/IEC 18004), written for OwnDemand's TV sign-in
 * (no third-party library). Byte mode only — enough for a link — with
 * every version (1–40) and error-correction level, automatic version
 * choice and the standard mask selection. Pure, unit-tested.
 */

/** Error-correction level: share of the code that can be damaged or covered. */
enum class QrEcc(internal val formatBits: Int) {
    /** ~7 %. */
    L(1),

    /** ~15 %. */
    M(0),

    /** ~25 %. */
    Q(3),

    /** ~30 % — room for a logo in the middle. */
    H(2),
}

/** A square grid of dark / light modules; [get] is dark. */
class QrCode private constructor(
    val version: Int,
    val ecc: QrEcc,
    val mask: Int,
    private val modules: Array<BooleanArray>,
) {
    /** Modules per side (21 for version 1, +4 per version). */
    val size: Int get() = modules.size

    /** Dark module at column [x], row [y]. */
    operator fun get(x: Int, y: Int): Boolean = modules[y][x]

    /** One of the three 7 × 7 corner "eyes" (drawn separately when styled). */
    fun isFinder(x: Int, y: Int): Boolean =
        (x < 7 && y < 7) || (x >= size - 7 && y < 7) || (x < 7 && y >= size - 7)

    /** Rows as "1"/"0" strings (tests). */
    fun rows(): List<String> = modules.map { row -> row.joinToString("") { if (it) "1" else "0" } }

    companion object {
        /** Encodes [text] (UTF-8) in the smallest version that fits at [ecc]. */
        fun encode(text: String, ecc: QrEcc = QrEcc.M): QrCode = encodeBytes(text.toByteArray(Charsets.UTF_8), ecc)

        /** [forceMask] (0–7) skips mask selection — for tests. */
        fun encodeBytes(data: ByteArray, ecc: QrEcc, forceMask: Int? = null): QrCode {
            val version = (1..40).firstOrNull { v ->
                4 + charCountBits(v) + data.size * 8 <= dataCodewords(v, ecc) * 8
            } ?: throw IllegalArgumentException("Too long for a QR code: ${data.size} bytes")
            val codewords = interleaveWithEcc(dataCodewordsFor(data, version, ecc), version, ecc)
            val grid = Grid(version)
            grid.drawFunctionPatterns(ecc)
            grid.drawCodewords(codewords)
            val mask = forceMask ?: (0..7).minBy { m ->
                grid.applyMask(m)
                grid.drawFormatBits(ecc, m)
                grid.penalty().also { grid.applyMask(m) } // XOR again undoes it
            }
            grid.applyMask(mask)
            grid.drawFormatBits(ecc, mask)
            return QrCode(version, ecc, mask, grid.modules)
        }
    }
}

// ---- Capacity tables (ISO/IEC 18004 table 9), indexed [ecc][version] ----

private fun eccIndex(ecc: QrEcc) = when (ecc) {
    QrEcc.L -> 0
    QrEcc.M -> 1
    QrEcc.Q -> 2
    QrEcc.H -> 3
}

/** Error-correction codewords per block. */
private val ECC_PER_BLOCK = arrayOf(
    intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
    intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
    intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
    intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
)

/** Number of error-correction blocks. */
private val ECC_BLOCKS = arrayOf(
    intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
    intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
    intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
    intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
)

/** Byte-mode character-count field width. */
private fun charCountBits(version: Int) = if (version <= 9) 8 else 16

/** Modules left for data + error correction once the patterns are drawn. */
internal fun rawDataModules(version: Int): Int {
    var result = (16 * version + 128) * version + 64
    if (version >= 2) {
        val align = version / 7 + 2
        result -= (25 * align - 10) * align - 55
        if (version >= 7) result -= 36
    }
    return result
}

/** Data codewords (bytes) a version holds at [ecc]. */
internal fun dataCodewords(version: Int, ecc: QrEcc): Int {
    val e = eccIndex(ecc)
    return rawDataModules(version) / 8 - ECC_PER_BLOCK[e][version] * ECC_BLOCKS[e][version]
}

// ---- Data → codewords ----

/** Mode, length, data, terminator and padding, as the version's data codewords. */
private fun dataCodewordsFor(data: ByteArray, version: Int, ecc: QrEcc): ByteArray {
    val capacityBits = dataCodewords(version, ecc) * 8
    val bits = ArrayList<Boolean>(capacityBits)
    fun put(value: Int, count: Int) {
        for (i in count - 1 downTo 0) bits += (value ushr i) and 1 == 1
    }
    put(0b0100, 4) // byte mode
    put(data.size, charCountBits(version))
    data.forEach { put(it.toInt() and 0xFF, 8) }
    put(0, minOf(4, capacityBits - bits.size)) // terminator
    put(0, (8 - bits.size % 8) % 8)
    var pad = 0xEC
    while (bits.size < capacityBits) {
        put(pad, 8)
        pad = pad xor 0xEC xor 0x11
    }
    return ByteArray(bits.size / 8) { i ->
        var b = 0
        for (j in 0 until 8) b = (b shl 1) or (if (bits[i * 8 + j]) 1 else 0)
        b.toByte()
    }
}

/** Splits into blocks, appends Reed–Solomon codes, interleaves (ISO 18004 §7.6). */
private fun interleaveWithEcc(data: ByteArray, version: Int, ecc: QrEcc): ByteArray {
    val e = eccIndex(ecc)
    val numBlocks = ECC_BLOCKS[e][version]
    val eccLen = ECC_PER_BLOCK[e][version]
    val raw = rawDataModules(version) / 8
    val numShort = numBlocks - raw % numBlocks
    val shortLen = raw / numBlocks
    val divisor = reedSolomonDivisor(eccLen)
    val blocks = ArrayList<ByteArray>(numBlocks)
    var k = 0
    for (i in 0 until numBlocks) {
        val datLen = shortLen - eccLen + (if (i < numShort) 0 else 1)
        val dat = data.copyOfRange(k, k + datLen)
        k += datLen
        val block = dat.copyOf(shortLen + 1)
        reedSolomonRemainder(dat, divisor).copyInto(block, block.size - eccLen)
        blocks += block
    }
    val result = ByteArray(raw)
    var r = 0
    for (i in 0 until shortLen + 1) {
        for (j in 0 until numBlocks) {
            // Short blocks have one data byte less: skip their filler.
            if (i != shortLen - eccLen || j >= numShort) result[r++] = blocks[j][i]
        }
    }
    return result
}

/** GF(2^8) product, modulus x^8 + x^4 + x^3 + x^2 + 1. */
internal fun gfMultiply(x: Int, y: Int): Int {
    var z = 0
    for (i in 7 downTo 0) {
        z = (z shl 1) xor ((z ushr 7) * 0x11D)
        z = z xor (((y ushr i) and 1) * x)
    }
    return z
}

/** Generator polynomial of [degree], highest coefficient (1) dropped. */
internal fun reedSolomonDivisor(degree: Int): IntArray {
    val result = IntArray(degree)
    result[degree - 1] = 1
    var root = 1
    for (i in 0 until degree) {
        for (j in 0 until degree) {
            result[j] = gfMultiply(result[j], root)
            if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
        }
        root = gfMultiply(root, 0x02)
    }
    return result
}

/** Error-correction codewords for [data]. */
internal fun reedSolomonRemainder(data: ByteArray, divisor: IntArray): ByteArray {
    val result = IntArray(divisor.size)
    for (b in data) {
        val factor = (b.toInt() and 0xFF) xor result[0]
        for (i in 0 until result.size - 1) result[i] = result[i + 1]
        result[result.size - 1] = 0
        for (i in result.indices) result[i] = result[i] xor gfMultiply(divisor[i], factor)
    }
    return ByteArray(result.size) { result[it].toByte() }
}

/** The 15 format bits: level and mask, BCH-protected and masked (§7.9). */
internal fun qrFormatBits(ecc: QrEcc, mask: Int): Int {
    val data = ecc.formatBits shl 3 or mask
    var rem = data
    repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
    return (data shl 10 or rem) xor 0x5412
}

/** The 18 version bits for versions 7 and up, BCH-protected (§7.10). */
internal fun qrVersionBits(version: Int): Int {
    var rem = version
    repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
    return version shl 12 or rem
}

// ---- The grid ----

private class Grid(val version: Int) {
    val size = version * 4 + 17
    val modules = Array(size) { BooleanArray(size) }
    private val isFunction = Array(size) { BooleanArray(size) }

    private fun setFunction(x: Int, y: Int, dark: Boolean) {
        modules[y][x] = dark
        isFunction[y][x] = true
    }

    fun drawFunctionPatterns(ecc: QrEcc) {
        for (i in 0 until size) {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)
        val align = alignmentPositions()
        for (i in align.indices) for (j in align.indices) {
            val corner = (i == 0 && j == 0) || (i == 0 && j == align.size - 1) || (i == align.size - 1 && j == 0)
            if (!corner) drawAlignment(align[i], align[j])
        }
        drawFormatBits(ecc, 0) // reserves the area; redrawn with the real mask
        drawVersion()
    }

    private fun drawFinder(cx: Int, cy: Int) {
        for (dy in -4..4) for (dx in -4..4) {
            val x = cx + dx
            val y = cy + dy
            if (x in 0 until size && y in 0 until size) {
                val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                setFunction(x, y, dist != 2 && dist != 4)
            }
        }
    }

    private fun drawAlignment(cx: Int, cy: Int) {
        for (dy in -2..2) for (dx in -2..2) {
            setFunction(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
        }
    }

    private fun alignmentPositions(): IntArray {
        if (version == 1) return IntArray(0)
        val count = version / 7 + 2
        val step = (version * 8 + count * 3 + 5) / (count * 4 - 4) * 2
        val result = IntArray(count)
        result[0] = 6
        var pos = size - 7
        for (i in count - 1 downTo 1) {
            result[i] = pos
            pos -= step
        }
        return result
    }

    fun drawFormatBits(ecc: QrEcc, mask: Int) {
        val bits = qrFormatBits(ecc, mask)
        fun bit(i: Int) = (bits ushr i) and 1 == 1
        for (i in 0..5) setFunction(8, i, bit(i))
        setFunction(8, 7, bit(6))
        setFunction(8, 8, bit(7))
        setFunction(7, 8, bit(8))
        for (i in 9 until 15) setFunction(14 - i, 8, bit(i))
        for (i in 0 until 8) setFunction(size - 1 - i, 8, bit(i))
        for (i in 8 until 15) setFunction(8, size - 15 + i, bit(i))
        setFunction(8, size - 8, true) // the dark module
    }

    private fun drawVersion() {
        if (version < 7) return
        val bits = qrVersionBits(version)
        for (i in 0 until 18) {
            val dark = (bits ushr i) and 1 == 1
            val a = size - 11 + i % 3
            val b = i / 3
            setFunction(a, b, dark)
            setFunction(b, a, dark)
        }
    }

    /** Data bits in the standard two-column zigzag, right to left. */
    fun drawCodewords(data: ByteArray) {
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5 // skip the vertical timing column
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = (right + 1) and 2 == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y][x] && i < data.size * 8) {
                        modules[y][x] = (data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1 == 1
                        i++
                    }
                }
            }
            right -= 2
        }
    }

    /** XORs mask pattern [m] over the data modules (twice = undone). */
    fun applyMask(m: Int) {
        for (y in 0 until size) for (x in 0 until size) {
            val invert = when (m) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> x * y % 2 + x * y % 3 == 0
                6 -> (x * y % 2 + x * y % 3) % 2 == 0
                else -> ((x + y) % 2 + x * y % 3) % 2 == 0
            }
            if (invert && !isFunction[y][x]) modules[y][x] = !modules[y][x]
        }
    }

    /** ISO 18004 §7.8.3 penalty: lower reads more reliably. */
    fun penalty(): Int {
        var score = 0
        // Rule 1: runs of five or more of one colour, in rows and columns.
        for (horizontal in listOf(true, false)) {
            for (a in 0 until size) {
                var run = 1
                for (b in 1 until size) {
                    val cur = if (horizontal) modules[a][b] else modules[b][a]
                    val prev = if (horizontal) modules[a][b - 1] else modules[b - 1][a]
                    if (cur == prev) {
                        run++
                    } else {
                        if (run >= 5) score += 3 + (run - 5)
                        run = 1
                    }
                }
                if (run >= 5) score += 3 + (run - 5)
            }
        }
        // Rule 2: 2 × 2 blocks of one colour.
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = modules[y][x]
            if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) score += 3
        }
        // Rule 3: finder-like 1:1:3:1:1 with four light modules on a side.
        val p1 = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
        val p2 = p1.reversedArray()
        for (horizontal in listOf(true, false)) {
            for (a in 0 until size) for (b in 0..size - 11) {
                var m1 = true
                var m2 = true
                for (k in 0 until 11) {
                    val v = if (horizontal) modules[a][b + k] else modules[b + k][a]
                    if (v != p1[k]) m1 = false
                    if (v != p2[k]) m2 = false
                }
                if (m1) score += 40
                if (m2) score += 40
            }
        }
        // Rule 4: dark share away from 50 %.
        val dark = modules.sumOf { row -> row.count { it } }
        val total = size * size
        val k = (kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total - 1
        score += k * 10
        return score
    }
}
