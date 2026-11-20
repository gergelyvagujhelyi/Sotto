package com.sotto.app.call

// How good a call's sound is. Agreed like a direct call (ADR-17), inside the encrypted call
// setup: the caller's offer says the level its user wants, the callee's answer the lower of
// that and its own user's, and both phones use what the answer says (ADR-21). Pure, so it can
// be unit tested on the JVM.

/** Sound levels, lowest first. An older app says nothing, which reads as [STANDARD]. */
internal object CallQuality {
    /** WebRTC's own Opus settings: about 32 kbit/s for a voice. */
    const val STANDARD = 0
    /** Opus at [HIGH_BITRATE_BPS]: fuller sound, for about a quarter more data. */
    const val HIGH = 1

    const val HIGH_BITRATE_BPS = 48_000
}

/** The level this phone's user wants ("High-quality calls" in Settings). */
internal fun qualityLevel(highQualityOn: Boolean): Int = if (highQualityOn) CallQuality.HIGH else CallQuality.STANDARD

/** The call's level: the lower of the two, so never more than this phone's user wants. */
internal fun agreedQuality(mine: Int, theirs: Int): Int = minOf(mine, theirs).coerceAtLeast(CallQuality.STANDARD)

/** Opus's bitrate for a level; null leaves WebRTC's own. */
internal fun opusBitrate(level: Int): Int? = if (level >= CallQuality.HIGH) CallQuality.HIGH_BITRATE_BPS else null

/**
 * [sdp] with every Opus payload type's maxaveragebitrate set to [bitrate].
 *
 * For the other phone's description, before it's applied: a description's fmtp says what its
 * author wants to receive, and WebRTC sets this phone's Opus encoder from the peer's. So each
 * phone raises only its own sending, and only once both agreed; a phone that didn't agree
 * (or an older app) keeps getting WebRTC's usual rate.
 */
internal fun withOpusBitrate(sdp: String, bitrate: Int): String {
    val eol = if (sdp.contains("\r\n")) "\r\n" else "\n"
    val lines = sdp.split(eol).toMutableList()
    val opus = lines.mapNotNull { OPUS_RTPMAP.find(it)?.groupValues?.get(1) }.distinct()
    for (pt in opus) {
        val prefix = "a=fmtp:$pt "
        val index = lines.indexOfFirst { it.startsWith(prefix) }
        if (index >= 0) {
            val params = lines[index].removePrefix(prefix).split(';').map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("maxaveragebitrate=", ignoreCase = true) }
            lines[index] = prefix + (params + "maxaveragebitrate=$bitrate").joinToString(";")
        } else {
            val rtpmap = lines.indexOfFirst { it.startsWith("a=rtpmap:$pt ") }
            lines.add(rtpmap + 1, "${prefix}maxaveragebitrate=$bitrate")
        }
    }
    return lines.joinToString(eol)
}

private val OPUS_RTPMAP = Regex("^a=rtpmap:(\\d+) opus/48000", RegexOption.IGNORE_CASE)
