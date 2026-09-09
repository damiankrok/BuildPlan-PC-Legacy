package com.buildplan.app.analyzer.validate

import com.buildplan.app.analyzer.fidelity.FactFidelity

/**
 * What a useful building candidate needs, as a machine-readable checklist.
 *
 * The analyzer scores itself against this list rather than against "did it
 * run": every requirement is either satisfied, satisfied only by assumption,
 * or missing, and the missing ones that matter turn into questions for the
 * user. The categories follow the stage's contract for the analyzer.
 */
enum class RequirementCategory { IDENTITY, PLAN_GEOMETRY, VERTICAL_GEOMETRY, OPENINGS, RECOGNIZABILITY }

enum class Requirement(val category: RequirementCategory, val description: String, val weight: Int) {
    SUPPORTED_PROVIDER(RequirementCategory.IDENTITY, "Rozpoznany, obsługiwany dostawca projektu", 1),
    PROJECT_IDENTITY(RequirementCategory.IDENTITY, "Tożsamość projektu i kanoniczny adres strony", 1),
    ROOM_TABLES(RequirementCategory.IDENTITY, "Opublikowane tabele pomieszczeń dla wszystkich kondygnacji", 1),

    FLOOR_PLAN_RASTER(RequirementCategory.PLAN_GEOMETRY, "Co najmniej jeden rzut kondygnacji zdekodowany", 2),
    FOOTPRINT_CALIBRATION(RequirementCategory.PLAN_GEOMETRY, "Kalibracja skali rzutu z kotwicy źródłowej", 3),
    ROOM_TOPOLOGY(RequirementCategory.PLAN_GEOMETRY, "Regiony pomieszczeń wyprowadzone z układu ścian", 3),
    ROOM_LABELS_MATCHED(RequirementCategory.PLAN_GEOMETRY, "Regiony jednoznacznie przypisane do tabeli pomieszczeń", 2),
    FLOOR_ORDERING(RequirementCategory.PLAN_GEOMETRY, "Kolejność kondygnacji ustalona", 1),

    STOREY_LEVELS(RequirementCategory.VERTICAL_GEOMETRY, "Rzędne kondygnacji", 2),
    STOREY_HEIGHTS(RequirementCategory.VERTICAL_GEOMETRY, "Wysokości kondygnacji w świetle", 2),
    KNEE_WALL(RequirementCategory.VERTICAL_GEOMETRY, "Wysokość ścianki kolankowej (dla poddasza)", 1),
    ROOF_PITCH_AND_FAMILY(RequirementCategory.VERTICAL_GEOMETRY, "Kąt i rodzaj dachu", 2),
    RIDGE_AND_EAVES(RequirementCategory.VERTICAL_GEOMETRY, "Rzędne kalenicy i okapu", 2),

    EXTERIOR_OPENINGS(RequirementCategory.OPENINGS, "Położenia i szerokości otworów zewnętrznych", 2),
    OPENING_HEIGHTS(RequirementCategory.OPENINGS, "Wysokości i parapety otworów", 2),
    STAIR_ZONE(RequirementCategory.OPENINGS, "Strefa i kierunek schodów", 1),
    DIMENSION_CHAINS(RequirementCategory.PLAN_GEOMETRY, "Łańcuchy wymiarowe odczytane z rysunku i potwierdzone geometrią", 1),
    DOOR_TOPOLOGY(RequirementCategory.OPENINGS, "Drzwi wewnętrzne łączące pomieszczenia", 1),

    GARAGE_RELATION(RequirementCategory.RECOGNIZABILITY, "Relacja garażu do bryły głównej", 1),
    ROOF_OUTLINE(RequirementCategory.RECOGNIZABILITY, "Obrys dachu z wysięgami", 2),
    MASSING_STEPS(RequirementCategory.RECOGNIZABILITY, "Uskoki i wnęki bryły", 1),
    SIGNATURE_FEATURES(RequirementCategory.RECOGNIZABILITY, "Cechy rozpoznawcze elewacji", 1),
}

enum class RequirementState { SATISFIED, ASSUMED, PARTIAL, MISSING, NOT_APPLICABLE }

data class RequirementStatus(
    val requirement: Requirement,
    val state: RequirementState,
    val fidelity: FactFidelity?,
    val evidence: String,
)

/** The completeness picture: every requirement with its state, and one score. */
data class GapAnalysis(val statuses: List<RequirementStatus>) {

    /** Weighted share of applicable requirements that are satisfied; assumed and partial count half. */
    val completenessScore: Double
        get() {
            val applicable = statuses.filter { it.state != RequirementState.NOT_APPLICABLE }
            val total = applicable.sumOf { it.requirement.weight }.toDouble()
            if (total == 0.0) return 0.0
            val earned = applicable.sumOf {
                when (it.state) {
                    RequirementState.SATISFIED -> it.requirement.weight.toDouble()
                    RequirementState.ASSUMED, RequirementState.PARTIAL -> it.requirement.weight / 2.0
                    else -> 0.0
                }
            }
            return earned / total
        }

    val missing: List<RequirementStatus> get() = statuses.filter { it.state == RequirementState.MISSING }
    val assumed: List<RequirementStatus> get() = statuses.filter { it.state == RequirementState.ASSUMED }
}

/**
 * A question the analyzer needs answered before a value stops being an
 * assumption. Generated only when the missing value changes topology,
 * dimensions, quantities, decomposition or recognisability — never for
 * decoration. Text is Polish because the user is.
 */
data class ClarificationQuestion(
    val id: String,
    val requirement: Requirement,
    val text: String,
    /** What the analyzer assumed meanwhile, if anything, so the user sees what a non-answer means. */
    val currentAssumption: String?,
    /** The element, room or asset the question is about, when it has one. */
    val subject: String?,
)
