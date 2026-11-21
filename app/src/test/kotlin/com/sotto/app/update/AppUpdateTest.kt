package com.sotto.app.update

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {

    @Test
    fun `versions compare number by number`() {
        assertTrue(compareVersions("0.7.10", "0.7.9") > 0)
        assertTrue(compareVersions("0.8.0", "0.7.99") > 0)
        assertTrue(compareVersions("1.0", "0.9.9") > 0)
        assertEquals(0, compareVersions("0.7.3", "0.7.3"))
        assertEquals(0, compareVersions("0.7", "0.7.0"))
        assertTrue(compareVersions("0.7.2", "0.7.3") < 0)
        assertEquals(0, compareVersions("0.7.3-debug", "0.7.3")) // a build suffix doesn't count
    }

    @Test
    fun `a relay that isn't newer means nothing to do`() {
        assertEquals(AppUpdate.None, AppUpdate.of("0.7.3", "0.7.3", true))
        assertEquals(AppUpdate.None, AppUpdate.of("0.7.3", "0.7.2", false))
        assertEquals(AppUpdate.None, AppUpdate.of("0.7.3", "", false))
    }

    @Test
    fun `a newer relay whose app isn't out means the update is on its way`() {
        assertEquals(AppUpdate.OnItsWay("0.7.3"), AppUpdate.of("0.7.2", "0.7.3", false))
    }

    @Test
    fun `once its app is out the update is ready`() {
        assertEquals(AppUpdate.Ready("0.7.3"), AppUpdate.of("0.7.2", "0.7.3", true))
    }

    @Test
    fun `a relay version that isn't a plain version is ignored`() {
        assertEquals(AppUpdate.None, AppUpdate.of("0.7.2", "9.9.9/../../evil", true))
    }

    // ── What the relay's answers say ───────────────────────────────────────

    private var announced: String? = null
    private val notifier = mockk<AppUpdateNotifier>(relaxed = true) {
        every { lastAnnouncedVersion() } answers { announced }
        every { announce(any()) } answers { announced = firstArg<AppUpdate.Ready>().version; true }
    }
    private val updates = AppUpdates(notifier).apply { appVersion = "0.7.2" }

    @Test
    fun `on its way - shown in the app, no notification`() {
        updates.report("0.7.3", appReady = false)
        assertEquals(AppUpdate.OnItsWay("0.7.3"), updates.state.value)
        verify(exactly = 0) { notifier.announce(any()) }
    }

    @Test
    fun `ready - one notification per version, however many answers say so`() {
        updates.report("0.7.3", appReady = false)
        repeat(5) { updates.report("0.7.3", appReady = true) }

        assertEquals(AppUpdate.Ready("0.7.3"), updates.state.value)
        verify(exactly = 1) { notifier.announce(AppUpdate.Ready("0.7.3")) }

        updates.report("0.7.4", appReady = true)
        verify(exactly = 1) { notifier.announce(match { it.version == "0.7.4" }) }
    }

    @Test
    fun `announced before the app restarted - not announced again`() {
        announced = "0.7.3"
        updates.report("0.7.3", appReady = true)
        verify(exactly = 0) { notifier.announce(any()) }
        assertEquals(AppUpdate.Ready("0.7.3"), updates.state.value)
    }

    @Test
    fun `once updated, the notification goes away - on the first answer`() {
        updates.report("0.7.2", appReady = true)
        assertEquals(AppUpdate.None, updates.state.value)
        verify(exactly = 1) { notifier.withdraw() }
        updates.report("0.7.2", appReady = true)
        verify(exactly = 1) { notifier.withdraw() }
    }

    @Test
    fun `a notification that couldn't be posted is tried again on the next answer`() {
        // Notifications not allowed yet: announce posts nothing and records nothing.
        every { notifier.announce(any()) } returns false
        updates.report("0.7.3", appReady = true)
        updates.report("0.7.3", appReady = true)
        verify(exactly = 2) { notifier.announce(any()) }

        // Allowed now: the next answer announces it, and that's the last time.
        every { notifier.announce(any()) } answers { announced = firstArg<AppUpdate.Ready>().version; true }
        updates.report("0.7.3", appReady = true)
        updates.report("0.7.3", appReady = true)
        verify(exactly = 3) { notifier.announce(any()) }
    }
}
