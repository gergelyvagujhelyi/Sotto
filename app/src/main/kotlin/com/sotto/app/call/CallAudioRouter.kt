package com.sotto.app.call

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.sotto.app.R
import com.sotto.app.domain.model.AudioRoute
import com.sotto.app.domain.model.AudioRouteKind
import com.sotto.app.ui.text.UiText

/**
 * A call's audio route: the earpiece, the speaker, a wired or USB headset, a hearing aid or a
 * Bluetooth device (a car connected over Bluetooth is one too). Output and microphone move
 * together. Lives from the moment the call takes audio focus until it lets it go; [onChange]
 * gets the routes on offer and the one in use whenever either changes.
 *
 * The call stays on the route it's on until the user picks another one:
 * - A device that connects mid-call never takes the call off a headset. A car whose Android
 *   Auto kept failing to connect used to pull calls off earbuds this way, again and again.
 * - When the route's device drops out and comes back, or another Bluetooth device grabs the
 *   audio while it's still there, the call goes back to it.
 * - Only a call on the phone's own earpiece, where nobody chose to have it, moves to a
 *   headset that connects, once it has stayed connected for a moment.
 */
class CallAudioRouter(
    context: Context,
    private val onChange: (routes: List<AudioRoute>, current: AudioRoute?) -> Unit,
) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    /** Where the call belongs: the route it started on, the headset it moved to, or the user's pick. */
    private var chosen: AudioRoute? = null
    private var chosenByUser = false
    /** Every device of each route (by key), the one in use first: earbuds can be listed twice. */
    private var members: Map<String, List<Int>> = emptyMap()
    /** Android 8–11 only: the route picked by hand (the system has no notion of it). */
    private var legacyRoute: AudioRouteKind? = null
    private var pendingTakeOver: Runnable? = null
    private val reasserts = ArrayDeque<Long>()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            if (!started) return
            val routes = routes()
            val addedKeys = added.mapNotNull { device ->
                routeKindFor(device.type)?.let { routeKey(it, device.productName?.toString()) }
            }.toSet()
            when (val action = onRoutesAdded(currentRoute(routes), chosen, chosenByUser, routes, addedKeys)) {
                is RouteAction.Restore -> moveTo(action.route)
                is RouteAction.TakeOverLater -> takeOverLater(action.route)
                RouteAction.Stay -> publish()
            }
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (!started) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                removed.any { routeKindFor(it.type) == legacyRoute }
            ) {
                legacyRoute = null // back to the earpiece (or a wired headset, by itself)
            }
            // [chosen] stays: when its device comes back, so does the call.
            publish()
        }
    }

    private val communicationListener: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AudioManager.OnCommunicationDeviceChangedListener { onCommunicationDeviceChanged() }
    } else {
        null
    }

    fun start() {
        if (started || audio == null) return
        started = true
        audio.registerAudioDeviceCallback(deviceCallback, handler)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.addOnCommunicationDeviceChangedListener(
                appContext.mainExecutor,
                communicationListener as AudioManager.OnCommunicationDeviceChangedListener,
            )
        }
        val preferred = preferredRoute(routes())
        chosen = preferred
        chosenByUser = false
        if (preferred != null) apply(preferred)
        publish()
    }

    fun stop() {
        if (!started || audio == null) return
        started = false
        cancelTakeOver()
        audio.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.removeOnCommunicationDeviceChangedListener(
                communicationListener as AudioManager.OnCommunicationDeviceChangedListener,
            )
            audio.clearCommunicationDevice()
        } else {
            resetLegacy()
        }
        legacyRoute = null
        chosen = null
        chosenByUser = false
        reasserts.clear()
        onChange(emptyList(), null)
    }

    /** The user picked [route]: the call stays there until they pick another. */
    fun select(route: AudioRoute) {
        if (!started || audio == null) return
        chosen = route
        chosenByUser = true
        cancelTakeOver()
        apply(route)
        publish()
    }

    /** Back to (or on to) [route] by itself, keeping who chose it. */
    private fun moveTo(route: AudioRoute) {
        cancelTakeOver()
        if (chosen?.key != route.key) chosen = route
        apply(route)
        publish()
    }

    private fun takeOverLater(route: AudioRoute) {
        cancelTakeOver()
        val task = Runnable {
            pendingTakeOver = null
            if (!started) return@Runnable
            val routes = routes()
            val current = currentRoute(routes)
            val stillThere = routes.firstOrNull { it.key == route.key }
            if (stillThere != null && !chosenByUser && (current == null || current.kind == AudioRouteKind.EARPIECE)) {
                moveTo(stillThere)
            }
        }
        pendingTakeOver = task
        handler.postDelayed(task, TAKE_OVER_DELAY_MS)
        publish()
    }

    private fun cancelTakeOver() {
        pendingTakeOver?.let { handler.removeCallbacks(it) }
        pendingTakeOver = null
    }

    /** Android 12+: another Bluetooth device took the call's audio while its own device is still here. */
    private fun onCommunicationDeviceChanged() {
        if (!started) return
        val routes = routes()
        val back = reassertTarget(currentRoute(routes), chosen, routes)
        if (back != null && allowReassert()) {
            // A moment for the switch to settle, then check again before taking it back.
            handler.postDelayed({
                if (!started) return@postDelayed
                val now = routes()
                val target = reassertTarget(currentRoute(now), chosen, now)
                if (target != null) {
                    Log.i(TAG, "Another device took the call's audio; moving it back to ${target.kind}")
                    apply(target)
                }
                publish()
            }, REASSERT_DELAY_MS)
        }
        onChange(routes, currentRoute(routes))
    }

    private fun allowReassert(): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (reasserts.isNotEmpty() && now - reasserts.first() > REASSERT_WINDOW_MS) reasserts.removeFirst()
        if (reasserts.size >= MAX_REASSERTS) return false
        reasserts.addLast(now)
        return true
    }

    /** Points the call's audio at [route], trying each of its devices in turn. */
    private fun apply(route: AudioRoute): Boolean {
        val audio = audio ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audio.availableCommunicationDevices
                val ids = members[route.key] ?: listOf(route.id)
                val ok = ids.any { id ->
                    val device = devices.firstOrNull { it.id == id }
                    device != null && audio.setCommunicationDevice(device)
                }
                if (!ok) Log.w(TAG, "Couldn't switch the call to ${route.kind}")
                ok
            } else {
                selectLegacy(route.kind)
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't switch the call to ${route.kind}: ${e.message}")
            false
        }
    }

    private fun routes(): List<AudioRoute> {
        val audio = audio ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val candidates = audio.availableCommunicationDevices.mapNotNull { device ->
                routeKindFor(device.type)?.let { RouteCandidate(device.id, it, device.productName?.toString(), device.type) }
            }
            val merged = mergeRoutes(candidates, audio.communicationDevice?.id)
            members = merged.members
            merged.routes
        } else {
            legacyRoutes(audio)
        }
    }

    private fun currentRoute(routes: List<AudioRoute>): AudioRoute? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio?.communicationDevice?.let { device ->
                routes.firstOrNull { route -> device.id in (members[route.key] ?: listOf(route.id)) }
            }
        } else {
            routes.firstOrNull { it.kind == legacyRoute } ?: routes.firstOrNull()
        }

    private fun publish() {
        if (!started) return
        val routes = routes()
        onChange(routes, currentRoute(routes))
    }

    // --- Android 8–11: the speaker and Bluetooth SCO by hand; a wired headset routes itself.

    private fun legacyRoutes(audio: AudioManager): List<AudioRoute> {
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val headset = outputs.firstOrNull { routeKindFor(it.type).let { k -> k == AudioRouteKind.WIRED || k == AudioRouteKind.USB } }
        val bluetooth = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        return buildList {
            add(
                headset?.let { device ->
                    val kind = routeKindFor(device.type)!!
                    AudioRoute(device.id, kind, routeLabel(kind, device.productName?.toString()), routeKey(kind, device.productName?.toString()))
                } ?: AudioRoute(LEGACY_EARPIECE_ID, AudioRouteKind.EARPIECE, routeLabel(AudioRouteKind.EARPIECE, null), routeKey(AudioRouteKind.EARPIECE, null)),
            )
            add(AudioRoute(LEGACY_SPEAKER_ID, AudioRouteKind.SPEAKER, routeLabel(AudioRouteKind.SPEAKER, null), routeKey(AudioRouteKind.SPEAKER, null)))
            bluetooth?.let {
                val name = it.productName?.toString()
                add(AudioRoute(it.id, AudioRouteKind.BLUETOOTH, routeLabel(AudioRouteKind.BLUETOOTH, name), routeKey(AudioRouteKind.BLUETOOTH, name)))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun selectLegacy(kind: AudioRouteKind) {
        val audio = audio ?: return
        when (kind) {
            AudioRouteKind.SPEAKER -> {
                stopLegacySco(audio)
                audio.isSpeakerphoneOn = true
            }
            AudioRouteKind.BLUETOOTH -> {
                audio.isSpeakerphoneOn = false
                audio.startBluetoothSco()
                audio.isBluetoothScoOn = true
            }
            else -> {
                stopLegacySco(audio)
                audio.isSpeakerphoneOn = false
            }
        }
        legacyRoute = kind
    }

    @Suppress("DEPRECATION")
    private fun resetLegacy() {
        val audio = audio ?: return
        audio.isSpeakerphoneOn = false
        stopLegacySco(audio)
    }

    @Suppress("DEPRECATION")
    private fun stopLegacySco(audio: AudioManager) {
        if (audio.isBluetoothScoOn) {
            audio.isBluetoothScoOn = false
            audio.stopBluetoothSco()
        }
    }

    private companion object {
        const val TAG = "CallAudioRouter"
        const val LEGACY_EARPIECE_ID = -1
        const val LEGACY_SPEAKER_ID = -2
        /** A headset must stay connected this long before a call on the earpiece moves to it. */
        const val TAKE_OVER_DELAY_MS = 2_500L
        const val REASSERT_DELAY_MS = 400L
        const val REASSERT_WINDOW_MS = 30_000L
        /** Taking the audio back at most this often per window, so the app never fights a device for long. */
        const val MAX_REASSERTS = 3
    }
}

/** The kind of route an audio device is, null for devices a call can't use. */
internal fun routeKindFor(type: Int): AudioRouteKind? = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRouteKind.EARPIECE
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRouteKind.SPEAKER
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioRouteKind.WIRED
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> AudioRouteKind.USB
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER -> AudioRouteKind.BLUETOOTH
    TYPE_HEARING_AID -> AudioRouteKind.HEARING_AID
    else -> null
}

// Device types newer than the app's minimum Android version.
private const val TYPE_HEARING_AID = 23
private const val TYPE_BLE_HEADSET = 26
private const val TYPE_BLE_SPEAKER = 27

/** Kinds that are something worn or connected, as opposed to the phone's own earpiece and speaker. */
private val HEADSETS = setOf(AudioRouteKind.BLUETOOTH, AudioRouteKind.WIRED, AudioRouteKind.USB, AudioRouteKind.HEARING_AID)

/**
 * Names the physical device behind a route. Bluetooth earbuds that support LE Audio are
 * listed twice by Android (classic Bluetooth and LE Audio, sometimes each bud), all under
 * the earbuds' name; a wired jack can be listed as headset and as headphones.
 */
internal fun routeKey(kind: AudioRouteKind, productName: String?): String {
    val name = productName?.trim()?.lowercase().orEmpty()
    return when (kind) {
        AudioRouteKind.EARPIECE -> "earpiece"
        AudioRouteKind.SPEAKER -> "speaker"
        AudioRouteKind.WIRED -> "wired"
        AudioRouteKind.USB -> "usb"
        AudioRouteKind.BLUETOOTH -> "bluetooth:$name"
        AudioRouteKind.HEARING_AID -> "hearing-aid:$name"
    }
}

/** A device Android offers for calls, before devices listed twice are merged; [type] is its AudioDeviceInfo type. */
internal data class RouteCandidate(val id: Int, val kind: AudioRouteKind, val productName: String?, val type: Int = 0)

/**
 * Which of one device's links a call should use, higher first: LE Audio before classic
 * Bluetooth (LE Audio's LC3 carries wider-band speech than the classic call link), and a
 * headset before headphones (the headset has the microphone).
 */
internal fun linkRank(type: Int): Int = when (type) {
    TYPE_BLE_HEADSET -> 2
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADSET -> 1
    else -> 0
}

internal class MergedRoutes(val routes: List<AudioRoute>, val members: Map<String, List<Int>>)

/**
 * One route per physical device ([routeKey]). A route stands for its best link ([linkRank]);
 * [MergedRoutes.members] keeps all of them, best first, to try in turn: a call on the route
 * uses LE Audio when the earbuds offer it, and classic Bluetooth only if that fails. Among
 * equal links, the one in use comes first, then Android's order.
 */
internal fun mergeRoutes(candidates: List<RouteCandidate>, currentId: Int?): MergedRoutes {
    val groups = LinkedHashMap<String, MutableList<RouteCandidate>>()
    for (candidate in candidates) {
        groups.getOrPut(routeKey(candidate.kind, candidate.productName)) { mutableListOf() }.add(candidate)
    }
    val ordered = groups.mapValues { (_, devices) ->
        devices.sortedWith(compareByDescending<RouteCandidate> { linkRank(it.type) }.thenByDescending { it.id == currentId })
    }
    val routes = ordered.map { (key, devices) ->
        val lead = devices.first()
        AudioRoute(lead.id, lead.kind, routeLabel(lead.kind, lead.productName), key)
    }
    return MergedRoutes(sortRoutes(routes), ordered.mapValues { (_, devices) -> devices.map { it.id } })
}

/** Phone first, then speaker, then the attached and wireless ones. */
internal fun sortRoutes(routes: List<AudioRoute>): List<AudioRoute> = routes.sortedBy { it.kind.ordinal }

/** A call starts on a headset (Bluetooth first) when one is connected, otherwise on the earpiece. */
internal fun preferredRoute(routes: List<AudioRoute>): AudioRoute? =
    routes.firstOrNull { it.kind == AudioRouteKind.BLUETOOTH }
        ?: routes.firstOrNull { it.kind == AudioRouteKind.WIRED }
        ?: routes.firstOrNull { it.kind == AudioRouteKind.USB }
        ?: routes.firstOrNull { it.kind == AudioRouteKind.HEARING_AID }
        ?: routes.firstOrNull { it.kind == AudioRouteKind.EARPIECE }
        ?: routes.firstOrNull()

internal sealed interface RouteAction {
    /** Leave the call where it is. */
    data object Stay : RouteAction
    /** The call's own device is back: return to it. */
    data class Restore(val route: AudioRoute) : RouteAction
    /** A headset connected while the call is on the earpiece: move to it if it stays. */
    data class TakeOverLater(val route: AudioRoute) : RouteAction
}

/** What to do when devices ([addedKeys]) connect during a call. */
internal fun onRoutesAdded(
    current: AudioRoute?,
    chosen: AudioRoute?,
    chosenByUser: Boolean,
    available: List<AudioRoute>,
    addedKeys: Set<String>,
): RouteAction {
    if (chosen != null && chosen.key in addedKeys && current?.key != chosen.key) {
        available.firstOrNull { it.key == chosen.key }?.let { return RouteAction.Restore(it) }
    }
    if (!chosenByUser && (current == null || current.kind == AudioRouteKind.EARPIECE)) {
        available.firstOrNull { it.key in addedKeys && it.kind in HEADSETS }?.let { return RouteAction.TakeOverLater(it) }
    }
    return RouteAction.Stay
}

/**
 * The route to take the audio back to, when another Bluetooth device took it from the
 * call's headset while that headset is still connected; null otherwise. Moves to the
 * earpiece or speaker are left alone: earbuds and cars have buttons for that.
 */
internal fun reassertTarget(current: AudioRoute?, chosen: AudioRoute?, available: List<AudioRoute>): AudioRoute? {
    if (chosen == null || current == null || current.key == chosen.key) return null
    if (chosen.kind !in HEADSETS || current.kind != AudioRouteKind.BLUETOOTH) return null
    return available.firstOrNull { it.key == chosen.key }
}

/** "Phone", "Speaker", or the device's own name for headsets and Bluetooth (a car's name). */
internal fun routeLabel(kind: AudioRouteKind, productName: String?): UiText {
    val name = productName?.trim()?.takeIf { it.isNotEmpty() }?.let { UiText.Plain(it) }
    return when (kind) {
        AudioRouteKind.EARPIECE -> UiText.res(R.string.call_route_phone)
        AudioRouteKind.SPEAKER -> UiText.res(R.string.call_route_speaker)
        AudioRouteKind.WIRED -> name ?: UiText.res(R.string.call_route_headset)
        AudioRouteKind.USB -> name ?: UiText.res(R.string.call_route_usb_headset)
        AudioRouteKind.BLUETOOTH -> name ?: UiText.res(R.string.call_route_bluetooth)
        AudioRouteKind.HEARING_AID -> name ?: UiText.res(R.string.call_route_hearing_aid)
    }
}

/** The routes worth a chooser: more than the phone's own earpiece and speaker. */
internal fun hasRouteChoice(routes: List<AudioRoute>): Boolean =
    routes.any { it.kind != AudioRouteKind.EARPIECE && it.kind != AudioRouteKind.SPEAKER }
