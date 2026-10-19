package dev.owndemand.app.data.plex

/**
 * Parsed Plex BIF (Binary Index File) — the whole scrub-preview strip in
 * memory, so scrubbing never touches the network (user report 2026-10-16:
 * per-frame `/indexes/sd/{ms}` fetches were slow on first scrub).
 *
 * Format (verified byte-by-byte against a live server 2026-10-16):
 * - 72-byte header: magic `0x46494289` ("\x89BIF"), version, stream type,
 *   frame count (u32 @12), JPEG stream start (u32 @68).
 * - `frame count` entries of (u32 timestamp seconds, u32 absolute file
 *   offset). The first entry describes the SECOND frame — frame 0 (ts 0)
 *   starts at the header's stream start — and the last entry is a
 *   `0xFFFFFFFF` sentinel whose offset is the file length.
 * - Frames are plain JPEG payloads between consecutive offsets.
 */
class BifIndex private constructor(
    private val data: ByteArray,
    private val timesSec: IntArray,
    private val starts: IntArray,
    private val ends: IntArray,
) {
    val frameCount: Int get() = timesSec.size

    /** JPEG of the last frame at or before [timeMs]; null when empty. */
    fun frameAt(timeMs: Long): ByteArray? {
        if (timesSec.isEmpty()) return null
        val sec = (timeMs / 1000).coerceAtLeast(0)
        var lo = 0
        var hi = timesSec.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (timesSec[mid] <= sec) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (best < 0) return null
        return data.copyOfRange(starts[best], ends[best])
    }

    companion object {
        private const val MAGIC = 0x46494289L

        /** Null on anything that is not a sane BIF (caller keeps URL mode). */
        fun parse(bytes: ByteArray): BifIndex? {
            if (bytes.size < 72) return null
            fun u32(off: Int): Long =
                (bytes[off].toLong() and 0xFF) or
                    ((bytes[off + 1].toLong() and 0xFF) shl 8) or
                    ((bytes[off + 2].toLong() and 0xFF) shl 16) or
                    ((bytes[off + 3].toLong() and 0xFF) shl 24)
            if (u32(0) != MAGIC) return null
            val count = u32(12).toInt()
            val streamStart = u32(68).toInt()
            if (count <= 0 || streamStart <= 0 || streamStart >= bytes.size) return null

            val times = ArrayList<Int>(count + 1)
            val startsList = ArrayList<Int>(count + 2)
            times += 0
            startsList += streamStart
            var sentinel = -1
            for (i in 0 until count) {
                val off = 72 + 8 * i
                if (off + 8 > bytes.size) return null
                val t = u32(off)
                val o = u32(off + 4)
                if (t == 0xFFFFFFFFL) {
                    sentinel = o.toInt()
                    break
                }
                times += t.toInt()
                startsList += o.toInt()
            }
            val end = if (sentinel > 0) sentinel else bytes.size
            startsList += end

            val timesArr = IntArray(times.size) { times[it] }
            val startsArr = IntArray(startsList.size) { startsList[it] }
            if (startsArr.any { it < 0 || it > bytes.size }) return null
            for (i in 1 until startsArr.size) {
                if (startsArr[i] < startsArr[i - 1]) return null
            }
            val endsArr = IntArray(timesArr.size) {
                startsArr[it + 1].coerceAtMost(bytes.size)
            }
            return BifIndex(bytes, timesArr, startsArr, endsArr)
        }
    }
}
