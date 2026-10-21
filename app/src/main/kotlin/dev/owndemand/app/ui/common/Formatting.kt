package dev.owndemand.app.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Youtarr titles follow the "<Channel> - <Title>" convention. Once the
 * channel is displayed separately this prefix is redundant (user request
 * 2026-10-16): strip a leading channel name when one follows it with a
 * separator ("-", "–", ":"). A title that is only the channel name, or
 * where the name only coincides without a separator, is kept whole.
 */
fun displayTitle(title: String, channelName: String?): String {
    val channel = channelName?.trim()?.takeIf { it.isNotEmpty() } ?: return title
    if (!title.startsWith(channel, ignoreCase = true)) return title
    val rest = title.substring(channel.length).trimStart()
    return rest
        .takeIf { it.isNotEmpty() && (it.startsWith("-") || it.startsWith("–") ||
            it.startsWith("—") || it.startsWith(":")) }
        ?.drop(1)
        ?.trimStart()
        ?.takeIf { it.isNotEmpty() }
        ?: title
}

/** "1:05:09" / "4:09" — reference video-app duration. */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "Mar 4, 2026" from a unix-seconds timestamp; "" when absent. */
/**
 * The date shown for a video: when it was uploaded (user choice
 * 2026-10-20), else when it was added to the library.
 */
fun formatUploadDate(item: dev.owndemand.app.data.plex.dto.ItemDto): String =
    item.uploadDate
        ?.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
        ?: formatAddedAt(item.addedAt)

fun formatAddedAt(unixSeconds: Long): String =
    if (unixSeconds <= 0) "" else
        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
            .format(java.util.Date(unixSeconds * 1000))

fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
