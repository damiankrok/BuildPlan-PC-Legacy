package com.buildplan.app.analyzer.verification

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * V025-LANG — everything the verification workspace puts in front of a person
 * is in that person's language.
 *
 * STAGE-024 caught this once, on a device, when the takeoff engine's own scope
 * names — "net of the openings, less the garage" — were interpolated into a
 * Polish question. The same seam reopens every time a new question type takes
 * a diagnostic string and shows it: `matchNote`, `roof.note`, `stair.note` and
 * `VisualConflict.impact` are all the analyzer's English by design, and all of
 * them are one field away from a screen.
 *
 * So this test walks every root question the fixture raises and every string
 * on it that the workspace renders, and refuses anything that reads as
 * English. It is deliberately a check on the *output*, not on the call sites:
 * a new question type is covered the day it is written.
 */
class QuestionLanguageTest {

    /**
     * Words that only appear in the analyzer's own diagnostic prose. Chosen to
     * be unambiguous in a Polish sentence — none is a loan word a Polish
     * question would use — and matched on word boundaries so "regionu" or
     * "planie" do not trip them.
     */
    private val english = listOf(
        "the", "and", "with", "from", "matched", "joined", "traced", "published",
        "openings", "opening", "wall", "walls", "roof", "region", "regions", "row",
        "gaps", "gap", "scope", "less", "net", "assumed", "assumption", "elevation",
        "facade", "ridge", "eave", "storey", "stair", "height", "width", "area",
    )

    private fun englishWordsIn(text: String): List<String> {
        val words = Regex("""[A-Za-z]+""").findAll(text.lowercase()).map { it.value }.toSet()
        return english.filter { it in words }
    }

    /** Every string of a question the workspace actually renders to the user. */
    private fun rendered(q: RootQuestion): List<Pair<String, String>> = buildList {
        add("text" to q.text)
        add("why" to q.why)
        add("subjectLabel" to q.subjectLabel)
        q.currentAssumption?.let { add("currentAssumption" to it) }
        add("evidence.analyzerReading" to q.evidence.analyzerReading)
        q.options.forEach {
            add("option.label" to it.label)
            add("option.evidence" to it.evidence)
        }
    }

    @Test
    fun `no root question shows the analyzer's English to the reader`() {
        val questions = VerificationSession.start(VerificationFixture.dressed).questions
        assertTrue("the fixture must raise questions to check", questions.size >= 10)
        val offenders = questions.flatMap { q ->
            rendered(q).mapNotNull { (field, text) ->
                englishWordsIn(text).takeIf { it.isNotEmpty() }?.let { "${q.id} $field: $it in \"$text\"" }
            }
        }
        if (offenders.isNotEmpty()) fail("English reached the reader:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `every rendered string is non-empty and every question explains itself`() {
        val questions = VerificationSession.start(VerificationFixture.dressed).questions
        val offenders = questions.flatMap { q ->
            buildList {
                if (q.text.isBlank()) add("${q.id}: no text")
                if (q.why.isBlank()) add("${q.id}: no reason")
                if (q.subjectLabel.isBlank()) add("${q.id}: no subject")
                if (q.evidence.analyzerReading.isBlank()) add("${q.id}: no analyzer reading")
                q.options.filter { it.label.isBlank() || it.evidence.isBlank() }.forEach { add("${q.id}: an option says nothing") }
            }
        }
        if (offenders.isNotEmpty()) fail(offenders.joinToString("\n"))
    }

    @Test
    fun `the diagnostic English is still kept, just not shown`() {
        // The point is not to delete the analyzer's own words — a bug report needs them — but to
        // keep them out of the question. They stay reachable on the report.
        val report = VerificationFixture.dressed
        val notes = report.candidate!!.rooms.map { it.matchNote } + listOfNotNull(report.candidate!!.roof?.note)
        assertTrue("the analyzer still records its own reasoning", notes.any { englishWordsIn(it).isNotEmpty() })
        assertTrue("and the ambiguities still carry a diagnostic reason", report.ambiguities.all { it.reason.isNotBlank() })
    }
}
