package app.tremolo.shared.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechAnalyzerTest {
    private val fs = 16_000

    /** A 200 Hz "voice" whose loudness follows [loudness] (0..1), plus faint room noise. */
    private fun voice(seconds: Double, loudness: (Double) -> Double): ShortArray {
        val random = Random(7)
        return ShortArray((seconds * fs).toInt()) { i ->
            val t = i.toDouble() / fs
            (8000 * loudness(t) * sin(2 * PI * 200 * t) + random.nextDouble(-30.0, 30.0)).toInt().toShort()
        }
    }

    @Test
    fun countsSyllablesAndTheirRate() {
        // Six bursts a second, each 80 ms long, for five seconds.
        val audio = voice(5.0) { t -> if (t % (1.0 / 6) < 0.08) 1.0 else 0.0 }
        val result = SpeechAnalyzer.syllables(audio, fs)
        assertNotNull(result)
        assertTrue("count ${result!!.syllables}", abs(result.syllables - 30) <= 1)
        assertEquals(6.0, result.ratePerSecond, 0.2)
        assertTrue("variation ${result.rhythmVariationPct}", result.rhythmVariationPct < 10)
    }

    @Test
    fun unevenSyllablesShowAsRhythmVariation() {
        // Gaps alternate between 120 and 280 ms.
        val audio = voice(5.0) { t ->
            val phase = t % 0.4
            if (phase < 0.06 || (phase >= 0.12 && phase < 0.18)) 1.0 else 0.0
        }
        val result = SpeechAnalyzer.syllables(audio, fs)
        assertNotNull(result)
        assertTrue("variation ${result!!.rhythmVariationPct}", result.rhythmVariationPct > 25)
    }

    @Test
    fun silenceGivesNoResult() {
        val audio = voice(5.0) { 0.0 }
        assertNull(SpeechAnalyzer.syllables(audio, fs))
        assertNull(SpeechAnalyzer.sustained(audio, fs))
    }

    @Test
    fun steadyVoiceHasLittleLoudnessTremor() {
        val audio = voice(8.0) { t -> if (t in 1.0..7.0) 1.0 else 0.0 }
        val result = SpeechAnalyzer.sustained(audio, fs)
        assertNotNull(result)
        assertEquals(6.0, result!!.seconds, 0.3)
        assertTrue("tremor ${result.loudnessTremorPct}", result.loudnessTremorPct < 2)
    }

    @Test
    fun waveringVoiceIsMeasured() {
        // Loudness swings 20% either way, five times a second.
        val audio = voice(8.0) { t -> if (t in 1.0..7.0) 0.8 * (1 + 0.2 * sin(2 * PI * 5 * t)) else 0.0 }
        val result = SpeechAnalyzer.sustained(audio, fs)
        assertNotNull(result)
        assertEquals(5.0, result!!.tremorHz, 0.3)
        // A 20% swing is about 14% RMS; the simple band filter passes most of it.
        assertTrue("tremor ${result.loudnessTremorPct}", result.loudnessTremorPct in 8.0..16.0)
    }
}
