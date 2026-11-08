package dev.owndemand.app.ui.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Android TV (Chunk 6): the system runs in television mode, or the device
 * declares the leanback (TV) feature. Decided once per launch; the TV
 * interface replaces the phone/tablet shell.
 */
fun Context.isTelevision(): Boolean {
    val uiMode = (getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
    return uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}
