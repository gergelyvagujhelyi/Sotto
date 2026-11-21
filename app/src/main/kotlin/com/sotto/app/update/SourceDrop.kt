package com.sotto.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Sotto is updated with SourceDrop, which installs Android apps straight from their source
 * repository. The update notice links there instead of downloading the APK itself.
 */
object SourceDrop {
    /** Listed under <queries> in the manifest, so Android lets us see whether it's installed. */
    const val PACKAGE = "dev.sourcedrop.app"
    const val PAGE = "https://github.com/gergelyvagujhelyi/SourceDrop"

    /** SourceDrop itself if it's installed; otherwise its page, where it can be got. */
    fun intent(context: Context): Intent =
        (context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: Intent(Intent.ACTION_VIEW, PAGE.toUri()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun open(context: Context) {
        try {
            context.startActivity(intent(context))
        } catch (_: ActivityNotFoundException) {
            // Neither SourceDrop nor a browser: nothing to open.
        }
    }
}
