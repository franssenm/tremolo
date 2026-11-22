package app.tremolo.shared

import app.tremolo.shared.Synthetic.tones
import app.tremolo.shared.dsp.AnalyzerParams
import app.tremolo.shared.dsp.MovementContext
import app.tremolo.shared.dsp.TremorAnalyzer
import app.tremolo.shared.dsp.WindowFeatures
import app.tremolo.shared.model.RawWindow
import app.tremolo.shared.model.RawWindowCodec
import app.tremolo.shared.model.SensorSeries
import app.tremolo.shared.protocol.ProtocolJson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TremorAnalyzerTest {

    private fun analyze(window: RawWindow): WindowFeatures = TremorAnalyzer.analyze(window)!!

    // --- Tremor that must be found -------------------------------------------------------

    @Test
    fun `steady 5 Hz tremor is detected with the right frequency and amplitude`() {
        val f = analyze(Synthetic.window(gyro = tones(Tone(5.0, 30.0))))
        assertEquals(5.0, f.dominantFreqHz, 0.25)
        assertEquals(30.0 / sqrt(2.0), f.tremorRmsDegPerSec, 1.0)
        assertTrue(f.tremorDetected)
        assertEquals(1.0, f.tremorFraction, 0.05)
        assertEquals(30.0 / sqrt(2.0), f.tremorRmsWhenPresent, 1.5)
        assertTrue("rhythmic signal should have a high peak ratio", f.peakRatio > 0.9)
        assertEquals(MovementContext.REST, f.context)
        assertTrue(f.segmentClasses.all { it == 'T' })
    }

    @Test
    fun `tremor on top of ordinary arm use is still detected`() {
        val f = analyze(Synthetic.window(gyro = tones(Tone(0.8, 20.0), Tone(6.0, 25.0))))
        assertEquals(MovementContext.ACTIVE, f.context)
        assertTrue(f.tremorDetected)
        assertEquals(6.0, f.dominantFreqHz, 0.25)
    }

    @Test
    fun `tremor is not mistaken for a harmonic of a slow movement it happens to align with`() {
        // 5 Hz is exactly 5 x 1 Hz, but there is nothing at 4 Hz, so it is its own peak.
        val f = analyze(Synthetic.window(gyro = tones(Tone(1.0, 60.0), Tone(5.0, 25.0))))
        assertTrue(f.tremorDetected)
        assertEquals(5.0, f.dominantFreqHz, 0.25)
    }

    @Test
    fun `tremor present for half the window gives about half tremor time`() {
        val f = analyze(Synthetic.window(gyro = tones(Tone(5.0, 30.0), toSec = 10.0)))
        assertEquals(0.5, f.tremorFraction, 0.1)
        assertTrue(f.tremorDetected)
    }

    @Test
    fun `tremor with a second harmonic is still one tremor`() {
        val f = analyze(Synthetic.window(gyro = tones(Tone(4.5, 30.0), Tone(9.0, 12.0))))
        assertTrue(f.tremorDetected)
        assertEquals(4.5, f.dominantFreqHz, 0.25)
    }

    // --- Things that must not be called tremor -------------------------------------------

    @Test
    fun `sensor noise alone is not tremor`() {
        val f = analyze(Synthetic.window())
        assertFalse(f.tremorDetected)
        assertEquals(0.0, f.tremorFraction, 1e-9)
        assertTrue(f.tremorRmsDegPerSec < 1.0)
        assertEquals(MovementContext.REST, f.context)
        assertTrue(f.segmentClasses.all { it == 'Q' })
    }

    @Test
    fun `tremor below the amplitude threshold is ignored`() {
        assertFalse(analyze(Synthetic.window(gyro = tones(Tone(5.0, 1.0)))).tremorDetected)
    }

    @Test
    fun `walking is recognised from the step rhythm and not called tremor`() {
        val f = analyze(
            Synthetic.window(
                // Arm swing at half the step rate, with harmonics reaching into the tremor band.
                gyro = tones(Tone(0.9, 120.0), Tone(1.8, 40.0), Tone(3.6, 20.0), Tone(5.4, 8.0)),
                accel = tones(Tone(1.8, 2.5), Tone(3.6, 1.0)),
            ),
        )
        assertEquals(MovementContext.WALKING, f.context)
        assertFalse(f.tremorDetected)
        assertEquals(1.0, f.walkingFraction, 1e-9)
    }

    @Test
    fun `walking with the arm held still is still walking`() {
        val f = analyze(Synthetic.window(accel = tones(Tone(1.9, 2.0), Tone(3.8, 0.8))))
        assertEquals(MovementContext.WALKING, f.context)
        assertFalse(f.tremorDetected)
    }

    @Test
    fun `rhythmic voluntary movement with harmonics is not tremor`() {
        // Waving or stirring: strong 1.8 Hz movement whose harmonics fall in the tremor band.
        val f = analyze(Synthetic.window(gyro = tones(Tone(1.8, 150.0), Tone(3.6, 20.0), Tone(5.4, 8.0))))
        assertEquals(MovementContext.GROSS, f.context)
        assertFalse(f.tremorDetected)
        assertTrue(f.segmentClasses.all { it == 'H' })
    }

    @Test
    fun `the fading tail of slow movement reaching into the band is not tremor`() {
        // Irregular voluntary movement: strongest when slow, fading towards 3 Hz, no peak above it.
        val f = analyze(
            Synthetic.window(
                gyro = tones(
                    Tone(0.7, 40.0), Tone(1.3, 35.0), Tone(2.1, 30.0),
                    Tone(2.6, 22.0), Tone(2.9, 16.0), Tone(3.2, 10.0),
                ),
            ),
        )
        assertFalse(f.segmentClasses, f.tremorDetected)
    }

    @Test
    fun `a slow 3,3 Hz tremor is still found`() {
        val f = analyze(Synthetic.window(gyro = tones(Tone(3.3, 30.0))))
        assertTrue(f.segmentClasses, f.tremorDetected)
        assertEquals(3.3, f.dominantFreqHz, 0.25)
    }

    @Test
    fun `a short handshake burst is not tremor`() {
        val f = analyze(Synthetic.window(gyro = tones(Tone(4.0, 150.0), fromSec = 8.0, toSec = 9.5)))
        assertFalse(f.tremorDetected)
        assertEquals(0.0, f.tremorFraction, 1e-9)
    }

    @Test
    fun `broadband jitter like typing is not tremor`() {
        for (seed in 1..5) {
            val f = analyze(Synthetic.window(gyroNoiseDegPerSec = 5.0, seed = seed))
            assertTrue("jitter should be strong enough to matter", f.tremorRmsDegPerSec > 2.0)
            assertFalse("seed $seed: ${f.segmentClasses}", f.tremorDetected)
        }
    }

    @Test
    fun `isolated taps and bumps are not tremor`() {
        // A sharp 60 ms jolt every 1.7 seconds.
        val f = analyze(Synthetic.window(gyro = { t -> if (t % 1.7 < 0.06) 400.0 else 0.0 }))
        assertFalse(f.segmentClasses, f.tremorDetected)
    }

    @Test
    fun `a rhythm outside the personal tremor range is not counted once calibrated`() {
        val window = Synthetic.window(gyro = tones(Tone(8.0, 20.0)))
        assertTrue(analyze(window).tremorDetected)
        val calibrated = AnalyzerParams(personalLoHz = 4.0, personalHiHz = 7.0)
        val f = TremorAnalyzer.analyze(window, calibrated)!!
        assertFalse(f.tremorDetected)
        assertTrue(f.segmentClasses.all { it == 'O' })
        val own = TremorAnalyzer.analyze(Synthetic.window(gyro = tones(Tone(5.5, 20.0))), calibrated)!!
        assertTrue(own.tremorDetected)
    }

    @Test
    fun `walking reports cadence and arm swing`() {
        val f = analyze(
            Synthetic.window(
                gyro = tones(Tone(0.9, 120.0)),
                accel = tones(Tone(1.8, 2.5), Tone(3.6, 1.0)),
            ),
        )
        assertEquals(108.0, f.cadenceSpm, 12.0)
        assertEquals(120.0 / sqrt(2.0), f.armSwingDegPerSec, 8.0)
    }

    @Test
    fun `gravity direction describes how the wrist is held`() {
        val f = analyze(Synthetic.window())
        assertEquals(0.0, f.gravityX, 0.01)
        assertEquals(0.0, f.gravityY, 0.01)
        assertEquals(1.0, f.gravityZ, 0.01)
    }

    // --- Plumbing ------------------------------------------------------------------------

    @Test
    fun `too short a window cannot be analysed`() {
        assertNull(TremorAnalyzer.analyze(Synthetic.window(gyro = tones(Tone(5.0, 30.0)), seconds = 2.0)))
        val empty = SensorSeries(LongArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
        assertNull(TremorAnalyzer.analyze(RawWindow(empty, empty)))
    }

    @Test
    fun `spectrum peaks at the tremor frequency`() {
        val psd = TremorAnalyzer.spectrum(Synthetic.window(gyro = tones(Tone(7.0, 30.0))))
        assertNotNull(psd)
        assertEquals(7.0, psd!!.frequencyOf(psd.peakBin(1.0, 20.0)), 0.25)
    }

    @Test
    fun `features round trip through json`() {
        val f = analyze(Synthetic.window(gyro = tones(Tone(5.0, 30.0))))
        val json = ProtocolJson.encodeToString(WindowFeatures.serializer(), f)
        assertEquals(f, ProtocolJson.decodeFromString(WindowFeatures.serializer(), json))
    }

    @Test
    fun `raw window survives encoding`() {
        val window = Synthetic.window(gyro = tones(Tone(5.0, 30.0)), seconds = 5.0)
        val bytes = ByteArrayOutputStream().also { RawWindowCodec.write(window, it) }.toByteArray()
        val decoded = RawWindowCodec.read(ByteArrayInputStream(bytes))
        assertEquals(window.gyro.size, decoded.gyro.size)
        assertEquals(window.accel.size, decoded.accel.size)
        assertEquals(window.gyro.timestampsNanos[17], decoded.gyro.timestampsNanos[17])
        assertEquals(window.gyro.x[123], decoded.gyro.x[123], 0f)
        assertEquals(window.accel.z[200], decoded.accel.z[200], 0f)
    }
}
