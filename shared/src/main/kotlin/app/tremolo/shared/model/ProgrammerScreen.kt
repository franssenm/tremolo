package app.tremolo.shared.model

/** A piece of text recognised in a photo, with the box it was found in (pixels, y grows downwards). */
data class TextBox(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val height: Float get() = bottom - top
    val width: Float get() = right - left
}

/**
 * Finds the stimulation values in the text recognised on a photo of a clinician's programmer
 * screen. The screen is expected to show the two leads side by side, each under a heading
 * that says "Left" or "Right". Those are sides of the brain. When the sides cannot be told
 * apart nothing is returned, because a guess could swap them.
 */
object ProgrammerScreen {
    data class Side(
        val amplitude: String = "",
        val pulseWidth: String = "",
        val frequency: String = "",
        val contacts: String = "",
    ) {
        val isEmpty: Boolean get() = amplitude.isEmpty() && pulseWidth.isEmpty() && frequency.isEmpty() && contacts.isEmpty()
    }

    data class Reading(val leftBrain: Side, val rightBrain: Side)

    private val LEFT = Regex("""\bleft\b""", RegexOption.IGNORE_CASE)
    private val RIGHT = Regex("""\bright\b""", RegexOption.IGNORE_CASE)
    private val AMPLITUDE = Regex("""(\d+(?:[.,]\d+)?)\s*mA\b""")
    // Text recognition often reads the micro sign as a u or a p.
    private val PULSE_WIDTH = Regex("""(\d+)\s*[µμup]s\b""")
    private val FREQUENCY = Regex("""(\d+)\s*Hz\b""", RegexOption.IGNORE_CASE)
    private val STIMULATION = Regex("stimulation", RegexOption.IGNORE_CASE)
    private val PERCENT = Regex("""^([+\-−–])\s*(\d{1,3})\s*%$""")
    private val CONTACT = Regex("""^(\d{1,2}[A-C]?|IPG|CASE)$""", RegexOption.IGNORE_CASE)

    /** [lines] are whole lines of text, [words] the single words they consist of. */
    fun read(lines: List<TextBox>, words: List<TextBox>): Reading? {
        val leftHeading = lines.filter { LEFT.containsMatchIn(it.text) && !RIGHT.containsMatchIn(it.text) }
            .minByOrNull { it.top } ?: return null
        val rightHeading = lines.filter { RIGHT.containsMatchIn(it.text) && !LEFT.containsMatchIn(it.text) }
            .minByOrNull { it.top } ?: return null
        val first = if (leftHeading.left <= rightHeading.left) leftHeading else rightHeading
        val second = if (first === leftHeading) rightHeading else leftHeading
        // The headings must stand next to each other, not one above the other.
        if (second.left < first.right) return null

        val start = first.left - 2 * first.height
        val border = second.left - second.height / 2
        fun inFirst(box: TextBox) = box.centerX >= start && box.centerX < border
        fun inSecond(box: TextBox) = box.centerX >= border

        val firstSide = side(lines.filter(::inFirst), words.filter(::inFirst), first)
        val secondSide = side(lines.filter(::inSecond), words.filter(::inSecond), second)
        if (firstSide.isEmpty && secondSide.isEmpty) return null
        return if (first === leftHeading) Reading(firstSide, secondSide) else Reading(secondSide, firstSide)
    }

    private fun side(lines: List<TextBox>, words: List<TextBox>, heading: TextBox): Side {
        val below = lines.filter { it.top >= heading.top }
        fun first(pattern: Regex, from: List<TextBox> = below): String =
            from.filter { pattern.containsMatchIn(it.text) }.minByOrNull { it.top }
                ?.let { pattern.find(it.text)!!.groupValues[1].replace(',', '.') }.orEmpty()

        // "Patient Min" and "Patient Max" are amplitudes too; the one that counts follows "Stimulation".
        val label = below.filter { STIMULATION.containsMatchIn(it.text) }.minByOrNull { it.top }
        val amplitude = first(AMPLITUDE, if (label == null) below else below.filter { it.top >= label.top - label.height })

        return Side(
            amplitude = amplitude,
            pulseWidth = first(PULSE_WIDTH),
            frequency = first(FREQUENCY),
            contacts = contacts(words.filter { it.top >= heading.top }),
        )
    }

    /**
     * Pairs each percentage with the contact name printed just above it. The negative and the
     * positive shares must each add up to 100; otherwise a contact was missed or misread, and
     * an incomplete list would be worse than none.
     */
    private fun contacts(words: List<TextBox>): String {
        val names = words.filter { CONTACT.matches(it.text.trim()) }
        val pairs = words.mapNotNull { word ->
            val percent = PERCENT.find(word.text.trim()) ?: return@mapNotNull null
            val name = names.filter { name ->
                name.centerY < word.centerY &&
                    word.top - name.bottom < 3 * word.height &&
                    kotlin.math.abs(name.centerX - word.centerX) < maxOf(name.width, word.width)
            }.maxByOrNull { it.centerY } ?: return@mapNotNull null
            val sign = if (percent.groupValues[1] == "+") "+" else "-"
            name.text.trim().uppercase() to "$sign${percent.groupValues[2]}%"
        }.let { found ->
            // Next to segments such as 2A and 2C, a "28" is a 2B that was misread.
            val lettered = found.any { it.first.last() in 'A'..'C' }
            found.map { (name, share) ->
                val asB = name.dropLast(1) + "B"
                if (lettered && name.length == 2 && name.endsWith("8") && found.none { it.first == asB }) asB to share
                else name to share
            }
        }.distinctBy { it.first }.sortedBy { it.first }
        fun total(sign: Char) = pairs.filter { it.second[0] == sign }.sumOf { it.second.drop(1).dropLast(1).toInt() }
        if (total('-') != 100 || total('+') != 100) return ""
        return pairs.joinToString(", ") { "${it.first} ${it.second}" }
    }
}
