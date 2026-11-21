package com.sotto.app.update

/**
 * How this app stands against the relay it uses. A merge deploys the relay within minutes,
 * but the matching app (its APK) is only built once the release is tagged; every answer
 * from the relay says whether it is out yet (see server/app/services/app_release.py).
 */
sealed interface AppUpdate {
    /** The relay isn't newer than this app: nothing to say. */
    data object None : AppUpdate

    /** The relay runs [version], newer than this app, but its APK isn't out yet. */
    data class OnItsWay(val version: String) : AppUpdate

    /** The app of [version], the relay's, is out: it can be installed with [SourceDrop]. */
    data class Ready(val version: String) : AppUpdate

    companion object {
        /**
         * The relay only says *whether* the app is out, never where to get it: the notice
         * always points to SourceDrop, so a hostile relay can't send people to some other APK.
         */
        fun of(appVersion: String, relayVersion: String, appReady: Boolean): AppUpdate = when {
            !PLAIN_VERSION.matches(relayVersion) -> None
            compareVersions(relayVersion, appVersion) <= 0 -> None
            !appReady -> OnItsWay(relayVersion)
            else -> Ready(relayVersion)
        }

        private val PLAIN_VERSION = Regex("[0-9]+(\\.[0-9]+){1,3}")
    }
}

/**
 * Compares dotted versions number by number: "0.7.10" is newer than "0.7.9", and a missing
 * part counts as 0. Anything after the numbers (a "-debug" suffix) doesn't count.
 */
internal fun compareVersions(a: String, b: String): Int {
    fun parts(v: String) = v.takeWhile { it.isDigit() || it == '.' }.split('.').map { it.toIntOrNull() ?: 0 }
    val pa = parts(a)
    val pb = parts(b)
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val diff = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}
