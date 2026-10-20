package dev.owndemand.app.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val ARTWORK_SCHEME = "owndemand"
private const val ARTWORK_HOST = "artwork"

/** Longest side of the notification / lock-screen artwork, in pixels. */
private const val ARTWORK_MAX_PX = 720

/**
 * App-private artwork URI for a Plex thumb path (server-relative, no
 * address or token). Only [ArtworkBitmapLoader] understands it.
 */
fun artworkUri(thumbPath: String): Uri = Uri.Builder()
    .scheme(ARTWORK_SCHEME)
    .authority(ARTWORK_HOST)
    .appendQueryParameter("path", thumbPath)
    .build()

/**
 * Loads notification and lock-screen artwork (Chunk 4) through the app's
 * own authenticated client, so the media session never carries a URL with
 * the server address or the Plex token in it.
 */
@OptIn(UnstableApi::class)
class ArtworkBitmapLoader(
    /** Fetches a thumb path from the current server; null when unavailable. */
    private val fetch: suspend (thumbPath: String) -> ByteArray?,
    private val scope: CoroutineScope,
) : BitmapLoader {

    override fun supportsMimeType(mimeType: String): Boolean =
        Util.isBitmapFactorySupportedMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        decode(data)?.let { Futures.immediateFuture(it) }
            ?: Futures.immediateFailedFuture(IOException("artwork could not be decoded"))

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val path = uri.takeIf { it.scheme == ARTWORK_SCHEME && it.host == ARTWORK_HOST }
            ?.getQueryParameter("path")
            ?: return Futures.immediateFailedFuture(IOException("not app artwork"))
        val future = SettableFuture.create<Bitmap>()
        scope.launch(Dispatchers.IO) {
            val bitmap = runCatching { fetch(path) }.getOrNull()?.let(::decode)
            if (bitmap != null) future.set(bitmap) else future.setException(IOException("artwork unavailable"))
        }
        return future
    }

    /** Decodes downsampled to about [ARTWORK_MAX_PX]: thumbs can be full-size frames. */
    private fun decode(data: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ARTWORK_MAX_PX) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
