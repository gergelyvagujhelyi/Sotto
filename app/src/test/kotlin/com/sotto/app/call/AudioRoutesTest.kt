package com.sotto.app.call

import android.media.AudioDeviceInfo
import com.sotto.app.R
import com.sotto.app.domain.model.AudioRoute
import com.sotto.app.domain.model.AudioRouteKind
import com.sotto.app.ui.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRoutesTest {
    private fun route(id: Int, kind: AudioRouteKind, name: String) = AudioRoute(id, kind, UiText.Plain(name), routeKey(kind, name))

    private val phone = route(1, AudioRouteKind.EARPIECE, "Phone")
    private val speaker = route(2, AudioRouteKind.SPEAKER, "Speaker")
    private val buds = route(7, AudioRouteKind.BLUETOOTH, "Galaxy Buds3 Pro")
    private val car = route(8, AudioRouteKind.BLUETOOTH, "VW BT 4521")
    private val wired = route(9, AudioRouteKind.WIRED, "Headset")

    @Test
    fun `device types map to routes, the rest is left out`() {
        assertEquals(AudioRouteKind.EARPIECE, routeKindFor(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertEquals(AudioRouteKind.SPEAKER, routeKindFor(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(AudioRouteKind.WIRED, routeKindFor(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(AudioRouteKind.WIRED, routeKindFor(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(AudioRouteKind.USB, routeKindFor(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(AudioRouteKind.BLUETOOTH, routeKindFor(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(AudioRouteKind.BLUETOOTH, routeKindFor(26)) // BLE headset
        assertEquals(AudioRouteKind.HEARING_AID, routeKindFor(23))
        assertNull(routeKindFor(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)) // music only, no microphone
        assertNull(routeKindFor(AudioDeviceInfo.TYPE_HDMI))
    }

    @Test
    fun `earbuds listed twice (classic and LE Audio) become one route, on the LE Audio link`() {
        val candidates = listOf(
            RouteCandidate(1, AudioRouteKind.EARPIECE, "SM-N960F"),
            RouteCandidate(2, AudioRouteKind.SPEAKER, "SM-N960F"),
            RouteCandidate(30, AudioRouteKind.BLUETOOTH, "Galaxy Buds3 Pro", AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
            RouteCandidate(41, AudioRouteKind.BLUETOOTH, "Galaxy Buds3 Pro ", AudioDeviceInfo.TYPE_BLE_HEADSET),
        )
        val merged = mergeRoutes(candidates, currentId = 41)
        assertEquals(
            listOf(UiText.res(R.string.call_route_phone), UiText.res(R.string.call_route_speaker), UiText.Plain("Galaxy Buds3 Pro")),
            merged.routes.map { it.name },
        )
        val budsRoute = merged.routes.last()
        assertEquals(41, budsRoute.id)
        assertEquals(listOf(41, 30), merged.members[budsRoute.key])

        // Not in use, or on the classic link: LE Audio still leads, classic is the fallback.
        for (current in listOf(1, 30, null)) {
            val other = mergeRoutes(candidates, currentId = current)
            assertEquals(41, other.routes.last().id)
            assertEquals(listOf(41, 30), other.members[other.routes.last().key])
        }
    }

    @Test
    fun `equal links keep the one in use first, then Android's order`() {
        // Some phones list each bud of a pair as its own LE Audio device.
        val buds = listOf(
            RouteCandidate(51, AudioRouteKind.BLUETOOTH, "Galaxy Buds3 Pro", AudioDeviceInfo.TYPE_BLE_HEADSET),
            RouteCandidate(52, AudioRouteKind.BLUETOOTH, "Galaxy Buds3 Pro", AudioDeviceInfo.TYPE_BLE_HEADSET),
        )
        assertEquals(listOf(52, 51), mergeRoutes(buds, currentId = 52).members.values.single())
        assertEquals(listOf(51, 52), mergeRoutes(buds, currentId = null).members.values.single())
    }

    @Test
    fun `different devices stay apart, and a jack listed as headset and headphones is one`() {
        val merged = mergeRoutes(
            listOf(
                RouteCandidate(7, AudioRouteKind.BLUETOOTH, "Galaxy Buds3 Pro"),
                RouteCandidate(8, AudioRouteKind.BLUETOOTH, "VW BT 4521"),
                RouteCandidate(10, AudioRouteKind.WIRED, "Headphones", AudioDeviceInfo.TYPE_WIRED_HEADPHONES),
                RouteCandidate(9, AudioRouteKind.WIRED, "Headset", AudioDeviceInfo.TYPE_WIRED_HEADSET),
            ),
            currentId = null,
        )
        assertEquals(3, merged.routes.size)
        assertEquals(listOf(9, 10), merged.members["wired"]) // the headset, with its microphone, first
    }

    @Test
    fun `a car connecting never takes the call off the earbuds`() {
        assertEquals(RouteAction.Stay, onRoutesAdded(buds, buds, chosenByUser = false, listOf(phone, speaker, buds, car), setOf(car.key)))
        assertEquals(RouteAction.Stay, onRoutesAdded(buds, buds, chosenByUser = true, listOf(phone, speaker, buds, car), setOf(car.key)))
    }

    @Test
    fun `the call goes back to its device when that device returns`() {
        // The car grabbed the audio and the earbuds dropped out; now they're back.
        assertEquals(
            RouteAction.Restore(buds),
            onRoutesAdded(car, buds, chosenByUser = false, listOf(phone, speaker, buds, car), setOf(buds.key)),
        )
        // Also when the earbuds were the user's pick.
        assertEquals(
            RouteAction.Restore(buds),
            onRoutesAdded(phone, buds, chosenByUser = true, listOf(phone, speaker, buds), setOf(buds.key)),
        )
    }

    @Test
    fun `only a call left on the earpiece moves to a headset that connects`() {
        assertEquals(
            RouteAction.TakeOverLater(buds),
            onRoutesAdded(phone, phone, chosenByUser = false, listOf(phone, speaker, buds), setOf(buds.key)),
        )
        // The user picked the phone or the speaker: it stays there.
        assertEquals(RouteAction.Stay, onRoutesAdded(phone, phone, chosenByUser = true, listOf(phone, speaker, buds), setOf(buds.key)))
        assertEquals(RouteAction.Stay, onRoutesAdded(speaker, speaker, chosenByUser = true, listOf(phone, speaker, buds), setOf(buds.key)))
    }

    @Test
    fun `audio taken by another Bluetooth device goes back to the call's headset`() {
        val available = listOf(phone, speaker, buds, car)
        assertEquals(buds, reassertTarget(current = car, chosen = buds, available = available))
        // Moved to the phone or speaker (a button on the earbuds or the car): left alone.
        assertNull(reassertTarget(current = phone, chosen = buds, available = available))
        assertNull(reassertTarget(current = speaker, chosen = buds, available = available))
        // Already there, or the earbuds are gone.
        assertNull(reassertTarget(current = buds, chosen = buds, available = available))
        assertNull(reassertTarget(current = car, chosen = buds, available = listOf(phone, speaker, car)))
    }

    @Test
    fun `a call starts on a connected headset, Bluetooth first, else the phone`() {
        assertEquals(buds, preferredRoute(listOf(phone, speaker, wired, buds)))
        assertEquals(wired, preferredRoute(listOf(phone, speaker, wired)))
        assertEquals(phone, preferredRoute(listOf(speaker, phone)))
        assertNull(preferredRoute(emptyList()))
    }

    @Test
    fun `routes are listed phone, speaker, then attached and wireless`() {
        assertEquals(listOf(phone, speaker, wired, buds), sortRoutes(listOf(buds, wired, speaker, phone)))
    }

    @Test
    fun `the chooser appears only with more than the phone's own earpiece and speaker`() {
        assertFalse(hasRouteChoice(listOf(phone, speaker)))
        assertTrue(hasRouteChoice(listOf(phone, speaker, buds)))
    }

    @Test
    fun `labels use the device's own name where it has one`() {
        assertEquals(UiText.res(R.string.call_route_phone), routeLabel(AudioRouteKind.EARPIECE, "SM-N960F"))
        assertEquals(UiText.res(R.string.call_route_speaker), routeLabel(AudioRouteKind.SPEAKER, "SM-N960F"))
        assertEquals(UiText.Plain("Galaxy Buds3 Pro"), routeLabel(AudioRouteKind.BLUETOOTH, " Galaxy Buds3 Pro "))
        assertEquals(UiText.res(R.string.call_route_bluetooth), routeLabel(AudioRouteKind.BLUETOOTH, ""))
        assertEquals(UiText.res(R.string.call_route_headset), routeLabel(AudioRouteKind.WIRED, null))
    }
}
