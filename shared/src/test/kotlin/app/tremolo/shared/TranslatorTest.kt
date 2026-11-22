package app.tremolo.shared

import app.tremolo.shared.i18n.Translations
import app.tremolo.shared.i18n.Translator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatorTest {
    private val translator = Translator(
        mapOf(
            "Save" to "Opslaan",
            "Tremor bad" to "Tremor erg",
            "Logged: {}" to "Vastgelegd: {}",
            "Trial running until {}" to "Proef loopt tot {}",
            "{} of {} blocks done" to "{} van {} blokken klaar",
            "{} had less {}: {}" to "{2} was minder bij {1}: {3}",
            "{} days" to "{} dagen",
            "DBS appointment in {} days" to "DBS-afspraak over {} dagen",
            " (too few)" to " (te weinig)",
            "{} x" to "{} y",
        ),
    )

    @Test
    fun exactTextsAreTranslated() {
        assertEquals("Opslaan", translator.translate("Save"))
    }

    @Test
    fun unknownTextsStayEnglish() {
        assertEquals("Something new", translator.translate("Something new"))
    }

    @Test
    fun valuesAreKeptAndTranslatedWhenKnown() {
        assertEquals("Proef loopt tot 24 nov 19:31", translator.translate("Trial running until 24 nov 19:31"))
        assertEquals("Vastgelegd: Tremor erg", translator.translate("Logged: Tremor bad"))
        assertEquals("2 van 6 blokken klaar", translator.translate("2 of 6 blocks done"))
    }

    @Test
    fun numberedPlaceholdersReorder() {
        assertEquals("tremor was minder bij A: 5%", translator.translate("A had less tremor: 5%"))
    }

    @Test
    fun theMostSpecificTemplateWins() {
        assertEquals("DBS-afspraak over 5 dagen", translator.translate("DBS appointment in 5 days"))
        assertEquals("14 dagen", translator.translate("14 days"))
    }

    @Test
    fun surroundingSpaceAndLinesAreHandled() {
        assertEquals("  Opslaan ", translator.translate("  Save "))
        assertEquals(" (te weinig)", translator.translate(" (too few)"))
        assertEquals("Tremor erg\n21 nov", translator.translate("Tremor bad\n21 nov"))
    }

    @Test
    fun templatesWithAlmostNoFixedTextAreIgnored() {
        assertEquals("5 x", translator.translate("5 x"))
    }

    @Test
    fun packagedDutchDictionaryLoadsAndIsConsistent() {
        val lines = javaClass.getResourceAsStream("/i18n/nl.tsv")!!.bufferedReader(Charsets.UTF_8).readLines()
        val entries = Translations.parse(lines)
        assertTrue("entries: ${entries.size}", entries.size > 400)
        // Every value the app fills in must have a place in the translation.
        entries.forEach { (key, value) ->
            assertEquals("placeholders in: $key", key.split("{}").size, value.split("{}").size)
        }
        assertNotNull(Translations.forLanguage("nl"))
        assertEquals("Opslaan", Translations.forLanguage("nl")!!.translate("Save"))
        assertEquals("Vastgelegd: Tremor erg", Translations.forLanguage("nl")!!.translate("Logged: Tremor bad"))
    }
}
