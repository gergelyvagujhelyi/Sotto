package com.sotto.app.call

import android.app.NotificationManager
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class RingPlanTest {
    private val all = NotificationManager.INTERRUPTION_FILTER_ALL

    @Test
    fun `normal mode rings and vibrates`() {
        assertEquals(RingPlan(sound = true, vibrate = true), ringPlan(AudioManager.RINGER_MODE_NORMAL, all))
    }

    @Test
    fun `vibrate mode only vibrates, silent mode does neither`() {
        assertEquals(RingPlan(sound = false, vibrate = true), ringPlan(AudioManager.RINGER_MODE_VIBRATE, all))
        assertEquals(RingPlan(sound = false, vibrate = false), ringPlan(AudioManager.RINGER_MODE_SILENT, all))
    }

    @Test
    fun `do not disturb keeps it quiet whatever the ringer mode`() {
        for (filter in listOf(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            NotificationManager.INTERRUPTION_FILTER_NONE,
        )) {
            assertEquals(RingPlan(sound = false, vibrate = false), ringPlan(AudioManager.RINGER_MODE_NORMAL, filter))
        }
    }

    @Test
    fun `an unknown filter does not silence the call`() {
        assertEquals(
            RingPlan(sound = true, vibrate = true),
            ringPlan(AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_UNKNOWN),
        )
    }
}
