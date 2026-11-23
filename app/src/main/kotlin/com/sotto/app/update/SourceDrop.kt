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

    /**
     * Ask SourceDrop to open Sotto's page and check for [version] right away, instead of
     * showing what it last saw. Older SourceDrops ignore these and just open.
     */
    private const val EXTRA_PACKAGE_NAME = "dev.sourcedrop.app.extra.PACKAGE_NAME"
    private const val EXTRA_VERSION = "dev.sourcedrop.app.extra.VERSION"

    /**
     * SourceDrop itself if it's installed, pointed at Sotto and the [version] that's out;
     * otherwise its page, where it can be got.
     */
    fun intent(context: Context, version: String): Intent {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: return Intent(Intent.ACTION_VIEW, PAGE.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch
            .putExtra(EXTRA_PACKAGE_NAME, context.packageName)
            .putExtra(EXTRA_VERSION, version)
            // If SourceDrop is already running, hand it this intent (extras included)
            // rather than just bringing its old screen back.
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
    }

    fun open(context: Context, version: String) {
        try {
            context.startActivity(intent(context, version))
        } catch (_: ActivityNotFoundException) {
            // Neither SourceDrop nor a browser: nothing to open.
        }
    }
}
