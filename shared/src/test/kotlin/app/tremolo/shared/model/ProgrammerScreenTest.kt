package app.tremolo.shared.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgrammerScreenTest {
    private fun box(text: String, left: Int, top: Int, width: Int = text.length * 9, height: Int = 16) =
        TextBox(text, left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat())

    /** One lead's column as a programmer shows it; [x] is where its heading starts. */
    private fun column(x: Int, side: String, amplitude: String = "2.4mA"): List<TextBox> = listOf(
        box("Area 1", x + 30, 710),
        box("Port: ${side.first()}, $side Gpi", x, 740),
        box("IPG", x + 50, 840), box("+100%", x + 40, 876),
        box("Stimulation", x + 25, 990),
        box(amplitude, x + 45, 1012), box("90µs", x + 60, 1040), box("185Hz", x + 50, 1066),
        box("2C", x + 140, 1124, 14, 10), box("-33%", x + 125, 1136, 26, 10),
        box("2B", x + 180, 1124, 14, 10), box("-33%", x + 172, 1136, 26, 10),
        box("Patient Min", x + 25, 1150), box("0.0mA", x + 50, 1172),
        box("2A", x + 160, 1184, 14, 10), box("-34%", x + 152, 1196, 26, 10),
        box("Patient Max", x + 25, 1230), box("4.5mA", x + 50, 1252),
    )

    /** The panel left of the leads, whose numbers must not be mistaken for values. */
    private val panel = listOf(box("Program", 95, 700), box("3", 130, 750), box("P3: 100%", 185, 1242), box("P1: 0%", 185, 1206))

    private fun read(boxes: List<TextBox>) = ProgrammerScreen.read(boxes, boxes)

    @Test
    fun readsBothSidesOfAProgrammerScreen() {
        val reading = read(panel + column(288, "Left") + column(528, "Right", amplitude = "2.1mA"))!!
        assertEquals(ProgrammerScreen.Side("2.4", "90", "185", "2A -34%, 2B -33%, 2C -33%, IPG +100%"), reading.leftBrain)
        assertEquals("2.1", reading.rightBrain.amplitude)
        assertEquals("2A -34%, 2B -33%, 2C -33%, IPG +100%", reading.rightBrain.contacts)
    }

    @Test
    fun theRightLeadMayStandOnTheLeftOfTheScreen() {
        val reading = read(column(288, "Right", amplitude = "1.5mA") + column(528, "Left"))!!
        assertEquals("1.5", reading.rightBrain.amplitude)
        assertEquals("2.4", reading.leftBrain.amplitude)
    }

    @Test
    fun patientLimitsAreNotTheAmplitude() {
        // The limits are recognised higher up than the stimulation value itself.
        val boxes = listOf(
            box("Left Gpi", 288, 740), box("Right Gpi", 528, 740),
            box("Patient Max", 300, 800), box("4.5mA", 320, 822),
            box("Stimulation", 300, 990), box("2,4 mA", 320, 1012),
        )
        assertEquals("2.4", read(boxes)!!.leftBrain.amplitude)
    }

    @Test
    fun contactsThatDoNotAddUpAreLeftOut() {
        // One of the three segments was not recognised.
        val boxes = (column(288, "Left") + column(528, "Right")).filterNot { it.text == "2B" }
        val reading = read(boxes)!!
        assertEquals("", reading.leftBrain.contacts)
        assertEquals("2.4", reading.leftBrain.amplitude)
    }

    @Test
    fun aSegmentReadAsADigitIsPutRight() {
        val boxes = (column(288, "Left") + column(528, "Right")).map { if (it.text == "2B") it.copy(text = "28") else it }
        assertEquals("2A -34%, 2B -33%, 2C -33%, IPG +100%", read(boxes)!!.leftBrain.contacts)
    }

    @Test
    fun misreadMicroSignStillGivesThePulseWidth() {
        val boxes = listOf(box("Left Gpi", 288, 740), box("Right Gpi", 528, 740), box("90us", 320, 1040), box("60ps", 560, 1040))
        val reading = read(boxes)!!
        assertEquals("90", reading.leftBrain.pulseWidth)
        assertEquals("60", reading.rightBrain.pulseWidth)
    }

    @Test
    fun withoutSideHeadingsNothingIsGuessed() {
        // A patient remote: only L and R, which there mean sides of the body.
        assertNull(read(listOf(box("Amplitude", 100, 100), box("L", 60, 200), box("2.1", 60, 260), box("R", 200, 200), box("2.3", 200, 260))))
        assertNull(read(column(288, "Left")))
    }
}
