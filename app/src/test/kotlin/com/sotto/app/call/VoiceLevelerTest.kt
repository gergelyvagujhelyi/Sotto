package com.sotto.app.call

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class VoiceLevelerTest {

    /** A leveler with "Level quiet voices" on (it's off by default). */
    private fun levelling() = VoiceLeveler().apply { levelling = true }

    @Test
    fun `by default a voice plays as it came, until Louder`() {
        val leveler = VoiceLeveler()
        val voice = TestVoice(speechDb = -35f)
        repeat(600) {
            val block = voice.next()
            val original = block.copyOf()
            leveler.process(block, BLOCK)
            assertArrayEquals(original, block, 0f)
        }
        leveler.louder = true
        val out = run(leveler, voice, seconds = 8)
        assertEquals(-16.0, out.speechDbOverLast(2), 1.0)
    }

    @Test
    fun `a quiet voice is lifted to a normal level`() {
        val out = run(levelling(), TestVoice(speechDb = -35f), seconds = 8)
        assertEquals(-23.0, out.speechDbOverLast(2), 1.0)
    }

    @Test
    fun `a normal voice plays exactly as it came`() {
        val leveler = levelling()
        val voice = TestVoice(speechDb = -20f)
        repeat(800) {
            val block = voice.next()
            val original = block.copyOf()
            leveler.process(block, BLOCK)
            assertArrayEquals(original, block, 0f)
        }
        assertEquals(0f, leveler.gainDb)
        assertEquals(-20f, leveler.speechLevelDb, 1f)
    }

    @Test
    fun `Louder lifts a normal voice, and a quiet one further than levelling`() {
        val normal = run(VoiceLeveler().apply { louder = true }, TestVoice(speechDb = -20f), seconds = 8)
        assertEquals(-14.0, normal.speechDbOverLast(2), 1.0)

        val quietLevelled = run(levelling(), TestVoice(speechDb = -35f), seconds = 8)
        val quietLouder = run(VoiceLeveler().apply { louder = true }, TestVoice(speechDb = -35f), seconds = 8)
        assertEquals(-16.0, quietLouder.speechDbOverLast(2), 1.0)
        assertTrue(quietLouder.speechDbOverLast(2) - quietLevelled.speechDbOverLast(2) >= 6.0)
    }

    @Test
    fun `a lifted voice doesn't clip`() {
        // A quiet voice with loud clicks, 26 dB above it, lifted 14 dB by Louder.
        val out = run(VoiceLeveler().apply { louder = true }, TestVoice(speechDb = -30f, clicksDb = -4f), seconds = 8)
        assertTrue("peak ${out.peak}", out.peak < 0.95f * 32768f)
        assertEquals(0, out.clamped)
        assertEquals(-16.0, out.speechDbOverLast(2), 2.0)
    }

    @Test
    fun `noise and pauses don't raise the gain`() {
        for (noise in listOf(-60f, -50f)) {
            val leveler = levelling()
            val voice = TestVoice(speechDb = null, noiseDb = noise)
            repeat(500) {
                val block = voice.next()
                val original = block.copyOf()
                leveler.process(block, BLOCK)
                assertArrayEquals(original, block, 0f)
            }
            assertEquals(0f, leveler.gainDb)
        }

        val leveler = levelling()
        run(leveler, TestVoice(speechDb = -35f), seconds = 8)
        val lifted = leveler.gainDb
        assertEquals(12f, lifted, 1f)
        run(leveler, TestVoice(speechDb = null), seconds = 10)
        assertEquals(lifted, leveler.gainDb)
    }

    @Test
    fun `with levelling and Louder off nothing changes`() {
        val leveler = VoiceLeveler().apply { levelling = false }
        val voice = TestVoice(speechDb = -35f)
        repeat(800) {
            val block = voice.next()
            val original = block.copyOf()
            leveler.process(block, BLOCK)
            assertArrayEquals(original, block, 0f)
        }
    }

    @Test
    fun `the gain moves smoothly`() {
        val leveler = levelling().apply { louder = true }
        val voice = TestVoice(speechDb = -40f)
        var last = 0f
        var rose = false
        repeat(1_000) { i ->
            if (i == 600) leveler.louder = false
            leveler.process(voice.next(), BLOCK)
            val step = leveler.gainDb - last
            assertTrue("step $step", step <= 0.0601f && step >= -0.2001f)
            if (step > 0) rose = true
            last = leveler.gainDb
        }
        assertTrue(rose)
        assertEquals(15f, last, 0.5f) // back down from Louder's 21 to levelling's most
    }

    @Test
    fun `a new call starts from scratch`() {
        val leveler = levelling()
        run(leveler, TestVoice(speechDb = -35f), seconds = 6)
        assertTrue(leveler.gainDb > 5f)
        leveler.startCall(levelling = true, louder = false)
        assertEquals(0f, leveler.gainDb)
        assertTrue(leveler.speechLevelDb.isNaN())
        val block = TestVoice(speechDb = -35f).next()
        val original = block.copyOf()
        leveler.process(block, BLOCK)
        assertArrayEquals(original, block, 0f)
    }

    @Test
    fun `how much each voice is lifted`() {
        assertEquals(12f, VoiceLeveler.wantedGain(-35f, louder = false))
        assertEquals(0f, VoiceLeveler.wantedGain(-20f, louder = false))
        assertEquals(15f, VoiceLeveler.wantedGain(-45f, louder = false))
        assertEquals(19f, VoiceLeveler.wantedGain(-35f, louder = true))
        assertEquals(6f, VoiceLeveler.wantedGain(-20f, louder = true))
        assertEquals(3f, VoiceLeveler.wantedGain(-15f, louder = true)) // not above −12 dBFS
        assertEquals(0f, VoiceLeveler.wantedGain(-10f, louder = true))
        assertEquals(21f, VoiceLeveler.wantedGain(-50f, louder = true))
    }

    /** Runs [voice] through [leveler] and measures what comes out. */
    private fun run(leveler: VoiceLeveler, voice: TestVoice, seconds: Int): Output {
        val out = Output()
        repeat(seconds * 100) { i ->
            val block = voice.next()
            leveler.process(block, BLOCK)
            out.add(block, voice.speaking, i, seconds * 100)
        }
        return out
    }

    private class Output {
        var peak = 0f
        var clamped = 0
        var samples = 0
        private val speechPowers = mutableListOf<Pair<Int, Double>>()
        private var total = 0

        fun add(block: FloatArray, speaking: Boolean, index: Int, blocks: Int) {
            total = blocks
            for (s in block) {
                peak = max(peak, abs(s))
                if (abs(s) >= 32767f) clamped++
            }
            samples += block.size
            if (speaking) speechPowers += index to block.sumOf { it.toDouble() * it } / block.size
        }

        /** The voice's level (power average over speech) in the last [seconds]. */
        fun speechDbOverLast(seconds: Int): Double {
            val from = total - seconds * 100
            val powers = speechPowers.filter { it.first >= from }.map { it.second }
            return 10 * log10(powers.average() / (32768.0 * 32768.0))
        }
    }

    /**
     * A voice-like signal at 48 kHz, 10 ms at a time: 200 ms "syllables" of a 150 Hz voice with
     * 20 harmonics, 100 ms gaps, and 600 ms pauses every 2 s, over faint noise. [speechDb] is its
     * level while speaking (null: no voice, only noise); [clicksDb] adds a loud 2 ms click, as
     * abrupt as a click gets, every quarter second of speech, peaking there.
     */
    private class TestVoice(
        private val speechDb: Float?,
        private val noiseDb: Float = -75f,
        private val clicksDb: Float? = null,
    ) {
        private val random = Random(7)
        private val phases = DoubleArray(HARMONICS) { random.nextDouble() * 2 * PI }
        private var block = 0
        private var sample = 0L
        var speaking = false
            private set

        fun next(): FloatArray {
            val position = block % 200
            speaking = speechDb != null && position < 140 && position % 30 < 20
            val voiceScale = speechDb?.let { 32768.0 * 10.0.pow(it / 20.0) / VOICE_RMS } ?: 0.0
            val noiseScale = 32768.0 * 10.0.pow(noiseDb / 20.0)
            val click = clicksDb != null && speaking && position % 25 == 3
            val out = FloatArray(BLOCK)
            for (i in 0 until BLOCK) {
                val t = (sample + i).toDouble() / RATE
                var v = 0.0
                if (speaking) {
                    for (k in 1..HARMONICS) v += sin(2 * PI * 150.0 * k * t + phases[k - 1]) / k
                    v *= voiceScale
                }
                if (click && i in CLICK_AT until CLICK_AT + 96) {
                    val peak = 32768.0 * 10.0.pow(clicksDb!! / 20.0)
                    val c = i - CLICK_AT
                    v += peak * exp(-c / 30.0) * sin(2 * PI * 1000.0 * c / RATE + PI / 2)
                }
                v += random.nextGaussian() * noiseScale
                out[i] = v.toFloat()
            }
            block++
            sample += BLOCK
            return out
        }

        companion object {
            const val HARMONICS = 20
            /** Where in its block a click starts: an abrupt peak, 4 ms in. */
            const val CLICK_AT = 200
            /** The RMS of the sum of harmonics 1/k: sqrt(sum(1/k²)/2). */
            val VOICE_RMS = sqrt((1..HARMONICS).sumOf { 1.0 / (it * it) } / 2)
        }
    }

    private companion object {
        const val RATE = 48_000
        const val BLOCK = RATE / 100
    }
}
