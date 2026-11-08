package dev.owndemand.app.playback

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import dev.owndemand.app.data.plex.DecoderSupport
import java.util.concurrent.ConcurrentHashMap

/**
 * Asks the device's decoder list (the one the player itself uses) whether a
 * codec can be decoded. Software decoders count: AV1 plays fine on devices
 * without AV1 hardware when they ship a software decoder. Answers are
 * cached; the list does not change while the app runs.
 */
@OptIn(UnstableApi::class)
class MediaCodecDecoderSupport : DecoderSupport {
    private val cache = ConcurrentHashMap<Triple<String, Int, Int>, Boolean>()

    override fun canDecode(codec: String, width: Int, height: Int): Boolean =
        cache.getOrPut(Triple(codec, width, height)) { query(codec, width, height) }

    private fun query(codec: String, width: Int, height: Int): Boolean {
        val mime = when (codec) {
            "av1" -> MimeTypes.VIDEO_AV1
            "h264" -> MimeTypes.VIDEO_H264
            else -> return false
        }
        val decoders = runCatching {
            MediaCodecUtil.getDecoderInfos(mime, /* secure = */ false, /* tunneling = */ false)
        }.getOrElse { emptyList() }
        val ok = decoders.any { info ->
            width <= 0 || height <= 0 ||
                info.isVideoSizeAndRateSupportedV21(width, height, /* frameRate = */ 30.0)
        }
        Log.i("OwnDemand", "decoder for $codec ${width}x$height: ${if (ok) "yes" else "no"} (${decoders.size} listed)")
        return ok
    }
}
