package com.sotto.app.ui.update

import com.sotto.app.R
import com.sotto.app.ui.text.UiText
import com.sotto.app.update.AppUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateBarTextTest {

    @Test
    fun `the bar names the version, in one short line`() {
        // Kept short (the strings): a longer line was cut off on a 1440 px wide phone.
        assertEquals(UiText.res(R.string.update_ready, "0.7.24"), appUpdateBarText(AppUpdate.Ready("0.7.24")))
        assertEquals(UiText.res(R.string.update_on_its_way, "0.7.24"), appUpdateBarText(AppUpdate.OnItsWay("0.7.24")))
        assertNull(appUpdateBarText(AppUpdate.None))
    }
}
