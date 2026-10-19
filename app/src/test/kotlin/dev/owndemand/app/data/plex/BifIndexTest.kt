package dev.owndemand.app.data.plex

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Builds the exact layout verified against a live server's BIF files (2026-10-16). */
class BifIndexTest {

    private fun u32(v: Long): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())

    /** frames: (timestamp seconds, jpeg payload); frame 0 is implicit at ts 0. */
    private fun bif(frames: List<Pair<Int, ByteArray>>): ByteArray {
        val frame0 = byteArrayOf(1, 2, 3, 4, 5) // implicit first frame
        val streamStart = 72 + 8 * (frames.size + 1)
        val out = ArrayList<ByteArray>()
        out += u32(0x46494289)
        out += u32(0x0A0D0A1A)
        out += u32(0)
        out += u32((frames.size + 1).toLong()) // entry count incl. sentinel
        out += ByteArray(72 - 16 - 4)
        out += u32(streamStart.toLong())
        var off = streamStart + frame0.size
        frames.forEach { (ts, jpg) ->
            out += u32(ts.toLong()); out += u32(off.toLong())
            off += jpg.size
        }
        out += u32(-1L); out += u32(off.toLong()) // sentinel = file length
        out += frame0
        frames.forEach { out += it.second }
        return out.fold(ByteArray(0)) { acc, b -> acc + b }
    }

    @Test
    fun `parses frames and finds the last frame at or before a time`() {
        val a = byteArrayOf(-1, -2, -3)
        val b = byteArrayOf(9, 9)
        val c = byteArrayOf(7)
        val bytes = bif(listOf(2 to a, 4 to b))
        val index = BifIndex.parse(bytes)!!
        assertEquals(3, index.frameCount)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), index.frameAt(0))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), index.frameAt(1_999))
        assertArrayEquals(a, index.frameAt(2_000))
        assertArrayEquals(a, index.frameAt(3_500))
        assertArrayEquals(b, index.frameAt(4_000))
        assertArrayEquals(b, index.frameAt(999_999)) // clamps to last
    }

    @Test
    fun `rejects non-BIF and truncated input`() {
        assertNull(BifIndex.parse(ByteArray(10)))
        assertNull(BifIndex.parse(ByteArray(200)))
        val good = bif(listOf(2 to byteArrayOf(1)))
        assertNull(BifIndex.parse(good.copyOfRange(0, good.size / 2)))
    }
}
