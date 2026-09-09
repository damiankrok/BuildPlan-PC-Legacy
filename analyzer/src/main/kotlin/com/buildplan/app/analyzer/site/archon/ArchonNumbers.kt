package com.buildplan.app.analyzer.site.archon

/**
 * Number parsing for Polish-formatted ARCHON text: `131,16`, `19,05 x 20,6 m`,
 * `nachylenie 40 st.`, `130 cm`, `2,83 m2`. Deterministic and forgiving of
 * whitespace and entity residue, strict about ambiguity: a string with two
 * candidate numbers where one is expected is returned as null, never guessed.
 */
object ArchonNumbers {

    private val decimal = Regex("""(?<![\d,.\p{L}])(-?\d{1,4}(?:[.,]\d{1,3})?)(?![\d,.])""")

    /** Parses one Polish decimal (comma or dot) from [text]; null when zero or more than one is present. */
    fun single(text: String): Double? {
        val all = decimal.findAll(clean(text)).map { it.groupValues[1] }.toList()
        return all.singleOrNull()?.toPolishDouble()
    }

    /** The first Polish decimal in [text], or null. For strings known to lead with their number. */
    fun first(text: String): Double? =
        decimal.find(clean(text))?.groupValues?.get(1)?.toPolishDouble()

    /** Every Polish decimal in [text], in order. */
    fun all(text: String): List<Double> =
        decimal.findAll(clean(text)).mapNotNull { it.groupValues[1].toPolishDouble() }.toList()

    /** `19,05 x 20,6 m` -> 19.05 to 20.6. Accepts `x`, `×` and `X`. */
    fun pair(text: String): Pair<Double, Double>? {
        val parts = clean(text).split(Regex("""\s*[x×X]\s*"""))
        if (parts.size != 2) return null
        val a = first(parts[0]) ?: return null
        val b = first(parts[1]) ?: return null
        return a to b
    }

    /** `40 st.` / `38°` -> 40.0 / 38.0, from a phrase containing the word for pitch. */
    fun pitchDegrees(text: String): Double? {
        val match = Regex("""nachylenie\s*(?:dachu\s*)?(\d{1,2}(?:[.,]\d)?)\s*(?:st\.?|°|stopni)""", RegexOption.IGNORE_CASE)
            .find(clean(text)) ?: return null
        return match.groupValues[1].toPolishDouble()
    }

    /** `130 cm` -> 1.30 m; `1,3 m` -> 1.3 m. Null when no unit is attached. */
    fun lengthMeters(text: String): Double? {
        val match = Regex("""(\d{1,4}(?:[.,]\d{1,2})?)\s*(cm|mm|m)\b""").find(clean(text)) ?: return null
        val value = match.groupValues[1].toPolishDouble() ?: return null
        return when (match.groupValues[2]) {
            "cm" -> value / 100.0
            "mm" -> value / 1000.0
            else -> value
        }
    }

    /** Strips entity residue and collapses whitespace so regexes see plain text. */
    fun clean(text: String): String = text
        .replace("&nbsp;", " ")
        .replace(' ', ' ')
        .replace("&sup2;", "2")
        .replace("&sup3;", "3")
        .replace("²", "2")
        .replace("³", "3")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.toPolishDouble(): Double? = replace(',', '.').toDoubleOrNull()
}
