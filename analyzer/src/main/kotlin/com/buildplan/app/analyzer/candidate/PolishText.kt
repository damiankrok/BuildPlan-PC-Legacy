package com.buildplan.app.analyzer.candidate

/**
 * The few Polish forms the analyzer's user-facing sentences need.
 *
 * It sits in `candidate/` because both layers that write those sentences —
 * the visual reader, which phrases a conflict, and the verification layer,
 * which phrases a question — already depend on this package, and neither
 * should depend on the other.
 *
 * Deliberately tiny. This is not a localisation framework and the app's own
 * labels stay in `strings.xml`; what lives here is the wording the analyzer
 * itself composes around a number it computed, which no resource file can
 * hold because the sentence is built from the data.
 */
object PolishText {

    /**
     * "1 otwór", "4 otwory", "5 otworów".
     *
     * Polish counts in three forms, not two, and the rule is on the last two
     * digits: 2–4 take the plural, 12–14 do not. A sentence that says
     * "4 otworów" reads as broken to the person being asked a question about
     * their own house, which is the wrong moment to look careless.
     */
    fun openings(n: Int): String = "$n ${form(n, "otwór", "otwory", "otworów")}"

    /** "1 otwór zewnętrzny", "4 otwory zewnętrzne", "5 otworów zewnętrznych" — noun and adjective agree. */
    fun exteriorOpenings(n: Int): String =
        "$n ${form(n, "otwór", "otwory", "otworów")} ${form(n, "zewnętrzny", "zewnętrzne", "zewnętrznych")}"

    /** The three-way form for a count: one, few (2–4 except the teens), many. */
    fun form(n: Int, one: String, few: String, many: String): String {
        val last = n % 10
        val lastTwo = n % 100
        return when {
            n == 1 -> one
            last in 2..4 && lastTwo !in 12..14 -> few
            else -> many
        }
    }
}
