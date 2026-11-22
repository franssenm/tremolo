package app.tremolo.shared.i18n

import java.util.Locale

/**
 * Translates the app's English texts with a dictionary keyed by the English text itself.
 *
 * English is what the code is written in and what is stored (event names, test names,
 * recording labels), so stored data never needs converting: translation happens only at
 * the moment a text is shown. A key may contain `{}` where the app fills in a value, e.g.
 * "Trial running until {}"; the same number of `{}` in the translation says where each
 * value goes (or `{1}`, `{2}` to reorder). Filled-in values are translated too when they
 * are themselves in the dictionary, which is how a stored "Tremor bad" inside
 * "Logged: Tremor bad" comes out translated.
 *
 * Anything without a translation is shown in English, so a missing entry is never a crash.
 */
class Translator(entries: Map<String, String>) {
    private class Template(val regex: Regex, val translation: String, val literalLetters: Int)

    private val exact = HashMap<String, String>()
    private val templates: List<Template>
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 2000
    }

    /** Called with each text that had no translation; used to find gaps while testing. */
    var onMiss: ((String) -> Unit)? = null

    init {
        val list = ArrayList<Template>()
        for ((rawKey, rawValue) in entries) {
            // Surrounding space is put back around the result, so it is not part of the match.
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (!key.contains(PLACEHOLDER)) {
                exact[key] = value
                continue
            }
            val letters = key.replace(PLACEHOLDER, "").count { it.isLetter() }
            // With too little fixed text a template would match almost anything.
            if (letters < MIN_LITERAL_LETTERS) continue
            val pattern = key.split(PLACEHOLDER).joinToString("(.*?)") { Regex.escape(it) }
            list += Template(Regex(pattern, RegexOption.DOT_MATCHES_ALL), value, letters)
        }
        // The most specific template wins.
        templates = list.sortedByDescending { it.literalLetters }
    }

    @Synchronized
    fun translate(text: String): String {
        if (text.isBlank()) return text
        cache[text]?.let { return it }
        val result = translateUncached(text)
        cache[text] = result
        return result
    }

    private fun translateUncached(text: String): String {
        val core = text.trim()
        val lead = text.substring(0, text.indexOf(core))
        val trail = text.substring(lead.length + core.length)
        lookup(core)?.let { return lead + it + trail }
        // Texts put together from several lines are translated line by line.
        if (core.contains('\n')) {
            val lines = core.split('\n')
            val translated = lines.map { line -> if (line.isBlank()) line else lookupTrimmed(line) ?: line }
            if (translated != lines) return lead + translated.joinToString("\n") + trail
        }
        onMiss?.invoke(text)
        return text
    }

    private fun lookupTrimmed(line: String): String? {
        val core = line.trim()
        return lookup(core)?.let { line.replace(core, it) }
    }

    private fun lookup(core: String): String? {
        exact[core]?.let { return it }
        for (template in templates) {
            val match = template.regex.matchEntire(core) ?: continue
            // A filled-in value can be a known text or template itself ("7 days", "Tremor bad").
            val values = match.groupValues.drop(1).map { value ->
                val inner = value.trim()
                if (inner.isEmpty() || inner.length >= core.length) value
                else lookup(inner)?.let { value.replace(inner, it) } ?: value
            }
            return fill(template.translation, values)
        }
        return null
    }

    private fun fill(translation: String, values: List<String>): String {
        var next = 0
        return FILL.replace(translation) { found ->
            val index = found.groupValues[1].toIntOrNull()?.minus(1) ?: next++
            Regex.escapeReplacement(values.getOrElse(index) { "" })
        }
    }

    companion object {
        private const val PLACEHOLDER = "{}"
        private const val MIN_LITERAL_LETTERS = 4
        private val FILL = Regex("""\{(\d*)\}""")
    }
}

/** Loads the dictionaries packaged with the app and picks the one for the current language. */
object Translations {
    /** Languages the app can show, English first. */
    val languages = listOf("en", "nl")

    /** Set once at start-up in debug builds to log texts that still lack a translation. */
    @Volatile
    var onMiss: ((String) -> Unit)? = null

    @Volatile
    private var current: Pair<String, Translator?>? = null

    /** The translator for [language], or null for English and for languages without a dictionary. */
    fun forLanguage(language: String): Translator? {
        current?.takeIf { it.first == language }?.let { return it.second }
        val translator = if (language == "en" || language !in languages) {
            null
        } else {
            Translations::class.java.getResourceAsStream("/i18n/$language.tsv")?.use { stream ->
                Translator(parse(stream.bufferedReader(Charsets.UTF_8).readLines())).also { it.onMiss = onMiss }
            }
        }
        current = language to translator
        return translator
    }

    /** Lines of "English<TAB>translation"; `\n` stands for a line break, `#` starts a comment. */
    fun parse(lines: List<String>): Map<String, String> = buildMap {
        for (line in lines) {
            if (line.startsWith("#") || !line.contains('\t')) continue
            val (key, value) = line.split('\t', limit = 2)
            put(key.replace("\\n", "\n"), value.replace("\\n", "\n"))
        }
    }
}

/** [text] in the language the app is currently shown in; English when there is no translation. */
fun tr(text: String): String =
    Translations.forLanguage(Locale.getDefault().language)?.translate(text) ?: text
