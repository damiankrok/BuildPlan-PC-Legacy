package com.buildplan.app.analyzer.service

import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.candidate.RoomCandidate
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.quantity.ProjectQuantities
import com.buildplan.app.analyzer.validate.CrossSourceValidator

/** What kind of thing the source left open. */
enum class AmbiguityKind {

    /** Two published room rows fit the same region, and nothing in the drawing separates them. */
    ROOM_IDENTITY,

    /** A region was traced but no published row claims it. */
    ROOM_UNCLAIMED,

    /** A published figure fits two scopes of the envelope; which one the page priced is not stated. */
    FACADE_SCOPE,

    /** Evenly spaced parallel lines that could be a flight of stairs or could be shelving. */
    STAIR_INTERPRETATION,

    /** A room whose outline could not be proved a simple ring, so its surfaces stay unresolved. */
    ROOM_GEOMETRY,
}

/** One reading the source permits. */
data class AmbiguityAlternative(
    /** Stable id of this reading within its ambiguity: a source row index, a scope name. */
    val id: String,
    val label: String,
    /** What is measurable about this reading — an area, a residual — so a person can judge it. */
    val evidence: String,
)

/**
 * Something the source does not settle, stated with every reading it permits.
 *
 * The rule this type exists to hold: **name the ambiguity, do not resolve it.**
 * Two rooms of the same published area on one storey are not a matching bug,
 * they are a plan that prints two identical numbers and no names on the
 * drawing; taking the nearer is a definition the source never gave. So the
 * candidate keeps one assignment — it must, or there is no geometry — and says
 * here, beside it, that the assignment is a choice and what the other readings
 * were.
 *
 * [alternatives] is never collapsed. A verification screen shows the
 * highlighted subject and offers exactly these.
 */
data class CandidateAmbiguity(
    val id: String,
    val kind: AmbiguityKind,
    /** The room, region, stair or quantity the ambiguity is about. */
    val subjectId: String,
    val subjectLabel: String,
    /** The reading the candidate currently carries, so the user sees what leaving it alone means. */
    val currentChoiceId: String?,
    val alternatives: List<AmbiguityAlternative>,
    /** Why the source cannot decide. Diagnostic prose, in the analyzer's own English. */
    val reason: String,
    /** What the user is being asked. Polish, because the user is. */
    val question: String,
) {
    init {
        val needsChoices = kind == AmbiguityKind.ROOM_IDENTITY ||
            kind == AmbiguityKind.FACADE_SCOPE ||
            kind == AmbiguityKind.STAIR_INTERPRETATION
        require(!needsChoices || alternatives.size >= 2) {
            "A $kind ambiguity with fewer than two readings is not an ambiguity"
        }
        require(question.isNotBlank()) { "An ambiguity that asks nothing is not actionable" }
    }
}

/**
 * Restates the ambiguities a candidate already carries in a shape a product
 * screen can offer as a choice.
 *
 * Every input was decided upstream — the matcher's alternatives, the ring
 * validator's verdict, the stair detector's competing reading, the facade
 * scope's attribution — and nothing is re-judged here. There is deliberately
 * no threshold in this file: a number here would be a second opinion about
 * what counts as uncertain, and the pipeline already holds the only one.
 */
object CandidateAmbiguities {

    fun of(
        candidate: ProjectAnalysisCandidate?,
        quantities: ProjectQuantities?,
        publishedFacadeArea: Double?,
    ): List<CandidateAmbiguity> {
        if (candidate == null) return emptyList()
        val out = mutableListOf<CandidateAmbiguity>()

        candidate.floors.forEach { floor ->
            floor.rooms.forEach { room ->
                roomIdentity(room)?.let { out += it }
                roomGeometry(room)?.let { out += it }
            }
            floor.unmatchedRegions.forEach { region ->
                out += CandidateAmbiguity(
                    id = "unclaimed:${region.id}",
                    kind = AmbiguityKind.ROOM_UNCLAIMED,
                    subjectId = region.id,
                    subjectLabel = region.id,
                    currentChoiceId = null,
                    alternatives = emptyList(),
                    reason = "region of ${fmt(region.areaM2)} m2 on ${floor.name} matched no published room row",
                    question = "Do którego pomieszczenia należy obszar ${fmt(region.areaM2)} m² na kondygnacji „${floor.name}”?",
                )
            }
        }

        candidate.stairs.filter { it.fidelity == FactFidelity.TRACE_UNCERTAIN }.forEach { stair ->
            val competing = stair.unresolved.joinToString("; ")
                .ifBlank { "brak potwierdzenia połączenia z sąsiednią kondygnacją" }
            out += CandidateAmbiguity(
                id = "stair:${stair.id}",
                kind = AmbiguityKind.STAIR_INTERPRETATION,
                subjectId = stair.id,
                subjectLabel = stair.id,
                currentChoiceId = "stair",
                alternatives = listOf(
                    AmbiguityAlternative("stair", "bieg schodów", stair.note.ifBlank { "równo rozstawione linie w strefie schodów" }),
                    AmbiguityAlternative("not-stair", "równo rozstawione linie, które nie są stopniami", competing),
                ),
                reason = "evenly spaced parallel lines with no cross-floor confirmation",
                question = "Czy zaznaczony układ równoległych linii to bieg schodów, czy element wyposażenia (na przykład półki)?",
            )
        }

        val scope = quantities?.facadeScope
        if (scope != null && publishedFacadeArea != null && publishedFacadeArea > 0.0) {
            val attribution = scope.attribute(publishedFacadeArea, CrossSourceValidator.ACCEPTABLE)
            val rival = attribution?.alternative
            val rivalRelative = attribution?.alternativeRelative
            if (attribution != null && rival != null && rivalRelative != null) {
                out += CandidateAmbiguity(
                    id = "facade:scope",
                    kind = AmbiguityKind.FACADE_SCOPE,
                    subjectId = "project:facadeInsulation",
                    subjectLabel = "Powierzchnia elewacji do ocieplenia",
                    currentChoiceId = attribution.label,
                    alternatives = listOf(
                        AmbiguityAlternative(attribution.label, scopeNamePl(attribution.label), "${fmt(attribution.value)} m², ${pct(attribution.relative)} od podanej"),
                        AmbiguityAlternative(rival, scopeNamePl(rival), "${pct(rivalRelative)} od podanej"),
                    ),
                    reason = "the published figure fits two scopes of the envelope within tolerance",
                    question = "Który zakres elewacji wyceniła strona: ${scopeNamePl(attribution.label)} czy ${scopeNamePl(rival)}?",
                )
            }
        }
        return out
    }

    private fun roomIdentity(room: RoomCandidate): CandidateAmbiguity? {
        val uncertain = room.matchConfidence == FactFidelity.TRACE_UNCERTAIN || room.matchConfidence == FactFidelity.CONFLICTING
        if (!uncertain || room.matchAlternatives.isEmpty()) return null
        val current = AmbiguityAlternative(
            id = "current:${room.id}",
            label = room.name,
            evidence = room.matchNote.ifBlank { "bieżące przypisanie" },
        )
        val others = room.matchAlternatives.map { alt ->
            AmbiguityAlternative(
                id = "row:${alt.sourceRowIndex}",
                label = alt.roomName,
                evidence = "wiersz ${alt.sourceRowIndex + 1} tabeli, ${fmt(alt.publishedAreaM2)} m², różnica ${pct(alt.relativeError)}",
            )
        }
        val names = room.matchAlternatives.joinToString(" albo ") { "„${it.roomName}”" }
        return CandidateAmbiguity(
            id = "room:${room.id}",
            kind = AmbiguityKind.ROOM_IDENTITY,
            subjectId = room.id,
            subjectLabel = room.name,
            currentChoiceId = current.id,
            alternatives = listOf(current) + others,
            reason = room.matchNote,
            question = "Które pomieszczenie z tabeli leży w zaznaczonym obszarze: „${room.name}”, czy $names?",
        )
    }

    private fun roomGeometry(room: RoomCandidate): CandidateAmbiguity? {
        if (room.polygon != null) return null
        return CandidateAmbiguity(
            id = "geometry:${room.id}",
            kind = AmbiguityKind.ROOM_GEOMETRY,
            subjectId = room.id,
            subjectLabel = room.name,
            currentChoiceId = null,
            alternatives = emptyList(),
            reason = room.geometryNote,
            question = "Obrys pomieszczenia „${room.name}” nie został potwierdzony jako zamknięty, " +
                "więc sufit, kubatura i lica ścian pozostają nieustalone. Czy podać obrys ręcznie?",
        )
    }

    /**
     * The Polish name of an envelope scope.
     *
     * The takeoff engine names its scopes in English, like the rest of the
     * analyzer's own vocabulary, and that is right for the semantics text a
     * reviewer reads beside a comparison. It is wrong inside a question put to
     * a person, which is why the translation happens here — at the layer that
     * produces user-facing text — rather than by making the engine speak
     * Polish.
     *
     * An unrecognised scope falls back to its own name. A new scope should
     * arrive here as a missing translation, not as a crash or a blank.
     */
    private fun scopeNamePl(label: String): String = when (label) {
        "over the openings" -> "obwiednia nad otworami"
        "net of the openings" -> "obwiednia bez otworów"
        "over the openings, less the garage" -> "obwiednia nad otworami, bez garażu"
        "net of the openings, less the garage" -> "obwiednia bez otworów i bez garażu"
        "net of the openings, less a secondary mass" -> "obwiednia bez otworów i bez bryły dodatkowej"
        "net of the openings, less the garage and a secondary mass" ->
            "obwiednia bez otworów, bez garażu i bez bryły dodatkowej"
        else -> label
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.2f", v)

    private fun pct(v: Double) = String.format(java.util.Locale.ROOT, "%.1f %%", v * 100)
}
