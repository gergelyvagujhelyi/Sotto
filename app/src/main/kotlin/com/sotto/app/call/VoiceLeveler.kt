package com.sotto.app.call

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Evens out the other person's voice in a call, just before it plays (ARCHITECTURE.md, ADR-23).
 *
 * Levelling (off by default) lifts a quiet voice towards a normal level and leaves a normal or
 * loud one exactly as it came. Louder aims higher, and lifts a normal voice too. With both off,
 * the voice plays exactly as it came. The gain follows the voice
 * slowly and only while someone speaks, so pauses don't pump up background noise, and a peak
 * limiter keeps a lifted voice from clipping.
 *
 * Works on blocks of 10 ms in WebRTC's float samples (full scale is ±32768), on WebRTC's
 * playback thread: no allocation and no locks there. The switches are set from other threads.
 */
internal class VoiceLeveler {

    /** Lift quiet voices: Settings > Calls, "Level quiet voices" (off by default). */
    @Volatile var levelling: Boolean = false

    /** Louder, from the call screen: a higher level, and normal voices lifted too. */
    @Volatile var louder: Boolean = false

    /** The voice's level so far in this call, in dBFS; NaN until it has spoken a little. For the log. */
    @Volatile var speechLevelDb: Float = Float.NaN
        private set

    /** The gain applied now, in dB. For the log. */
    @Volatile var gainDb: Float = 0f
        private set

    /** Set by [startCall]; the playback thread clears its state before the next block. */
    @Volatile private var restart = false

    // The playback thread's state.
    private var noiseFloorDb = Float.NaN
    private var speechPower = 0.0
    private var speechBlocks = 0
    private var speechDb = Float.NaN
    private var gain = 0f
    private var appliedGain = 1f
    private var envelope = 0f
    private var limiterGain = 1f
    private val subPeaks = FloatArray(SUBFRAMES)
    private val subGains = FloatArray(SUBFRAMES)

    /** A new call: forget the last voice, and use these switches. */
    fun startCall(levelling: Boolean, louder: Boolean) {
        this.levelling = levelling
        this.louder = louder
        speechLevelDb = Float.NaN
        gainDb = 0f
        restart = true
    }

    /** Processes one block of [count] samples (10 ms) in place. */
    fun process(samples: FloatArray, count: Int) {
        if (count < SUBFRAMES || count > samples.size) return
        if (restart) {
            restart = false
            noiseFloorDb = Float.NaN
            speechPower = 0.0
            speechBlocks = 0
            speechDb = Float.NaN
            gain = 0f
            appliedGain = 1f
            envelope = 0f
            limiterGain = 1f
        }
        analyse(samples, count)
        gain = stepTowards(gain, wantedGain())
        gainDb = gain
        val target = dbToGain(gain)
        if (target == 1f && appliedGain == 1f && limiterGain == 1f) {
            // Nothing to lift: the voice plays exactly as it came.
            envelope = 0f
            return
        }
        applyGainAndLimit(samples, count, target)
    }

    /** Follows the noise between words and, over blocks that hold speech, the voice's level. */
    private fun analyse(x: FloatArray, n: Int) {
        var sum = 0.0
        for (i in 0 until n) {
            val s = x[i].toDouble()
            sum += s * s
        }
        val power = sum / n
        val level = powerToDb(power)
        val floor = noiseFloorDb
        noiseFloorDb = when {
            floor.isNaN() -> level
            level < floor -> floor + (level - floor) * FLOOR_FALL
            else -> floor + min(level - floor, FLOOR_RISE_DB_PER_BLOCK)
        }
        if (level <= SPEECH_GATE_DB || level <= noiseFloorDb + SPEECH_ABOVE_FLOOR_DB) return
        speechBlocks++
        // An average over the first seconds of speech, then a moving one over the last 1.5 s.
        val weight = max(1.0 / speechBlocks, 1.0 / SPEECH_AVERAGE_BLOCKS)
        speechPower += (power - speechPower) * weight
        if (speechBlocks >= MIN_SPEECH_BLOCKS) {
            speechDb = powerToDb(speechPower)
            speechLevelDb = speechDb
        }
    }

    private fun wantedGain(): Float {
        val loud = louder
        if (!levelling && !loud) return 0f
        val speech = speechDb
        if (speech.isNaN()) return gain
        return wantedGain(speech, loud)
    }

    private fun stepTowards(current: Float, wanted: Float): Float {
        val step = if (wanted > current) UP_DB_PER_BLOCK else DOWN_DB_PER_BLOCK
        return when {
            abs(wanted - current) <= step -> wanted
            wanted > current -> current + step
            else -> current - step
        }
    }

    /**
     * Applies the gain, moving smoothly from the last block's, and a peak limiter like WebRTC's
     * own (AGC2): the peak of each millisecond, an instant attack that starts a millisecond
     * early, a 60 ms release, and the limiter's gain interpolated sample by sample.
     */
    private fun applyGainAndLimit(x: FloatArray, n: Int, target: Float) {
        val sub = n / SUBFRAMES
        val start = appliedGain
        for (k in 0 until SUBFRAMES) {
            var peak = 0f
            for (i in k * sub until (k + 1) * sub) peak = max(peak, abs(x[i]))
            subPeaks[k] = peak * max(ramp(start, target, k * sub, n), ramp(start, target, (k + 1) * sub, n))
        }
        for (k in 0 until SUBFRAMES - 1) {
            if (subPeaks[k] < subPeaks[k + 1]) subPeaks[k] = subPeaks[k + 1]
        }
        var env = envelope
        for (k in 0 until SUBFRAMES) {
            val peak = subPeaks[k]
            env = if (peak >= env) peak else max(peak, env * RELEASE_PER_SUBFRAME)
            subGains[k] = if (env > LIMIT) LIMIT / env else 1f
        }
        envelope = env

        var from = limiterGain
        for (k in 0 until SUBFRAMES) {
            val to = subGains[k]
            // A peak at the very start of a block can't be seen early: go down fast there.
            val attackAtStart = k == 0 && to < from
            for (j in 0 until sub) {
                val i = k * sub + j
                val t = j.toFloat() / sub
                val limit = if (attackAtStart) {
                    val u = 1f - t
                    val u2 = u * u
                    val u4 = u2 * u2
                    u4 * u4 * (from - to) + to
                } else {
                    from + (to - from) * t
                }
                x[i] = (x[i] * ramp(start, target, i, n) * limit).coerceIn(-FULL_SCALE, FULL_SCALE)
            }
            from = to
        }
        // Samples beyond whole milliseconds (none at WebRTC's rates) get the block's last gains.
        for (i in sub * SUBFRAMES until n) x[i] = (x[i] * target * from).coerceIn(-FULL_SCALE, FULL_SCALE)
        limiterGain = from
        appliedGain = target
    }

    private fun ramp(from: Float, to: Float, i: Int, n: Int): Float = from + (to - from) * i / n

    internal companion object {
        /** The level levelling lifts a quiet voice to (speech RMS), and the most it adds. */
        const val NORMAL_LEVEL_DB = -23f
        const val NORMAL_MAX_GAIN_DB = 15f

        /** Louder: this level, at least this much gain, never above the ceiling, and at most this much. */
        const val LOUDER_LEVEL_DB = -16f
        const val LOUDER_MIN_GAIN_DB = 6f
        const val LOUDER_CEILING_DB = -12f
        const val LOUDER_MAX_GAIN_DB = 21f

        /** The gain for a voice at [speechDb]: never below 0 dB, so only ever louder. */
        fun wantedGain(speechDb: Float, louder: Boolean): Float = if (louder) {
            min(max(LOUDER_LEVEL_DB - speechDb, LOUDER_MIN_GAIN_DB), LOUDER_CEILING_DB - speechDb)
                .coerceIn(0f, LOUDER_MAX_GAIN_DB)
        } else {
            (NORMAL_LEVEL_DB - speechDb).coerceIn(0f, NORMAL_MAX_GAIN_DB)
        }

        private const val SUBFRAMES = 10
        private const val FULL_SCALE = 32767f
        /** The limiter keeps peaks at −1 dBFS. */
        private const val LIMIT = 0.891f * 32768f
        private val RELEASE_PER_SUBFRAME = exp(-1.0 / 60.0).toFloat()

        /** Blocks quieter than this, or than the noise floor plus the margin, aren't speech. */
        private const val SPEECH_GATE_DB = -55f
        private const val SPEECH_ABOVE_FLOOR_DB = 10f
        private const val FLOOR_FALL = 0.2f
        private const val FLOOR_RISE_DB_PER_BLOCK = 0.01f
        private const val SPEECH_AVERAGE_BLOCKS = 150
        private const val MIN_SPEECH_BLOCKS = 30

        /** The gain rises at most 6 dB a second and falls at most 20. */
        private const val UP_DB_PER_BLOCK = 0.06f
        private const val DOWN_DB_PER_BLOCK = 0.2f

        private const val FULL_SCALE_POWER = 32768.0 * 32768.0

        fun powerToDb(power: Double): Float =
            if (power <= 1e-6) -120f else (10.0 * log10(power / FULL_SCALE_POWER)).toFloat()

        fun dbToGain(db: Float): Float = if (db == 0f) 1f else 10.0.pow(db / 20.0).toFloat()
    }
}
