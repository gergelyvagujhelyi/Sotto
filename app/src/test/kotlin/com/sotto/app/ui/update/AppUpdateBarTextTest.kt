package com.sotto.app.ui.update

import com.sotto.app.update.AppUpdate
import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdateBarTextTest {

    @Test
    fun `the bar names the version, in one short line`() {
        assertEquals("Sotto 0.7.24 is ready", appUpdateBarText(AppUpdate.Ready("0.7.24")))
        // Kept short: a longer line was cut off on a 1440 px wide phone.
        assertEquals("Sotto 0.7.24 is on its way", appUpdateBarText(AppUpdate.OnItsWay("0.7.24")))
        assertEquals("", appUpdateBarText(AppUpdate.None))
    }
}
