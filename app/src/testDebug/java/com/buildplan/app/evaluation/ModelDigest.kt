package com.buildplan.app.evaluation

import com.buildplan.app.analyzer.candidate.OpeningType
import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.candidate.RoofFamily
import com.buildplan.app.analyzer.fidelity.FactFidelity
import java.util.Locale

/**
 * One measurement of a house, reduced to a number a second house can be
 * compared against, with how far the *model it came from* may be trusted for
 * it.
 *
 * [fidelity] is the point. The hand-built reference is an oracle for the
 * numbers its source stated — the printed 12.05 m width, the section's +3.06 —
 * and it is not an oracle for the ones it assumed to close a shape. Scoring
 * an analyzer against a guess and calling the difference an error is how a
 * comparison becomes fiction, so a measurement whose reference side is a
 * display assumption is reported and not scored.
 */
data class DigestValue(
    val value: Double?,
    val fidelity: FactFidelity,
    val note: String = "",
) {
    val known: Boolean get() = value != null
}

/** One house as a flat set of named measurements. */
data class ModelDigest(
    val label: String,
    val values: Map<String, DigestValue>,
    /** Per-facade exterior opening counts, north/east/south/west. */
    val openingsByFacade: Map<String, Int>,
    /** Room name → plan area, for the rooms the model names. */
    val roomAreas: Map<String, Double>,
    /** Room name → plan centroid (x, z). */
    val roomCentroids: Map<String, Pair<Double, Double>>,
    val roofFamily: String,
) {
    operator fun get(key: String): DigestValue? = values[key]
}

/** How a measurement compares between the two models. */
enum class DiffVerdict {
    /** Within 2 % or 5 cm. */
    MATCH,

    /** Within 10 %. */
    CLOSE,

    /** Beyond 10 %: a difference a person would see. */
    DIFFERS,

    /** One side does not have the measurement. */
    ONE_SIDED,

    /** The reference's own value is an assumption, so the difference means nothing. */
    NOT_SCORED,
}

data class Difference(
    val key: String,
    val candidate: Double?,
    val reference: Double?,
    val unit: String,
    val verdict: DiffVerdict,
    val referenceFidelity: FactFidelity,
    val note: String,
) {
    val absolute: Double? get() = if (candidate != null && reference != null) candidate - reference else null
    val relative: Double?
        get() = if (candidate != null && reference != null && reference != 0.0) (candidate - reference) / reference else null

    fun render(): String {
        val c = candidate?.let { fmt(it) } ?: "—"
        val r = reference?.let { fmt(it) } ?: "—"
        val rel = relative?.let { " (${fmt(it * 100, 1)} %)" } ?: ""
        return "${key.padEnd(34)} cand $c $unit vs ref $r $unit$rel  ${verdict.name} [${referenceFidelity.name}]${if (note.isBlank()) "" else " — $note"}"
    }
}

private fun fmt(v: Double, digits: Int = 2) = String.format(Locale.ROOT, "%.${digits}f", v)

/**
 * Compares an analyzer candidate against the hand-built reference model,
 * measurement by measurement.
 *
 * Debug and test only, and deliberately one-directional: the reference is
 * evidence about what a careful person got from the same source, not truth
 * above the source. Where the two disagree and the *source* backs the
 * analyzer, the finding is reference debt; where the source backs the
 * reference, it is an analyzer defect worth fixing — and only the second kind
 * may change the analyzer.
 */
object CandidateReferenceComparator {

    /** Tolerances: a match is within this, close is within ten times looser. */
    private const val MATCH_RELATIVE = 0.02
    private const val MATCH_ABSOLUTE = 0.05
    private const val CLOSE_RELATIVE = 0.10

    fun compare(candidate: ModelDigest, reference: ModelDigest): List<Difference> {
        val keys = (candidate.values.keys + reference.values.keys).sorted()
        return keys.map { key ->
            val c = candidate[key]
            val r = reference[key]
            val unit = unitOf(key)
            val verdict = when {
                r == null || c == null || !r.known || !c.known -> DiffVerdict.ONE_SIDED
                r.fidelity == FactFidelity.DISPLAY_ASSUMPTION || r.fidelity == FactFidelity.MISSING -> DiffVerdict.NOT_SCORED
                else -> {
                    val diff = kotlin.math.abs(c.value!! - r.value!!)
                    val rel = if (r.value != 0.0) diff / kotlin.math.abs(r.value) else diff
                    when {
                        diff <= MATCH_ABSOLUTE || rel <= MATCH_RELATIVE -> DiffVerdict.MATCH
                        rel <= CLOSE_RELATIVE -> DiffVerdict.CLOSE
                        else -> DiffVerdict.DIFFERS
                    }
                }
            }
            Difference(key, c?.value, r?.value, unit, verdict, r?.fidelity ?: FactFidelity.MISSING, listOfNotNull(c?.note?.takeIf { it.isNotBlank() }, r?.note?.takeIf { it.isNotBlank() }).joinToString("; "))
        }
    }

    /** The whole comparison as a table a person reads beside the screenshots. */
    fun render(candidate: ModelDigest, reference: ModelDigest): String = buildString {
        val diffs = compare(candidate, reference)
        appendLine("== ${candidate.label} vs ${reference.label}")
        appendLine("roof family: candidate ${candidate.roofFamily}, reference ${reference.roofFamily}")
        appendLine()
        appendLine("-- measurements")
        diffs.forEach { appendLine("  ${it.render()}") }
        appendLine()
        appendLine("-- openings by facade (exterior)")
        (candidate.openingsByFacade.keys + reference.openingsByFacade.keys).sorted().forEach { side ->
            appendLine("  ${side.padEnd(6)} candidate ${candidate.openingsByFacade[side] ?: 0}, reference ${reference.openingsByFacade[side] ?: 0}")
        }
        appendLine()
        appendLine("-- rooms named by both")
        val shared = candidate.roomAreas.keys.intersect(reference.roomAreas.keys).sorted()
        shared.forEach { name ->
            val c = candidate.roomAreas.getValue(name)
            val r = reference.roomAreas.getValue(name)
            val cc = candidate.roomCentroids[name]
            val rc = reference.roomCentroids[name]
            val distance = if (cc != null && rc != null) kotlin.math.hypot(cc.first - rc.first, cc.second - rc.second) else null
            appendLine("  ${name.padEnd(20)} ${fmt(c)} m2 vs ${fmt(r)} m2 (${fmt((c - r) / r * 100, 1)} %)${distance?.let { ", centroid ${fmt(it)} m apart" } ?: ""}")
        }
        appendLine("  candidate-only: ${(candidate.roomAreas.keys - reference.roomAreas.keys).sorted()}")
        appendLine("  reference-only: ${(reference.roomAreas.keys - candidate.roomAreas.keys).sorted()}")
        appendLine()
        appendLine("-- summary")
        DiffVerdict.entries.forEach { v -> appendLine("  ${v.name} = ${diffs.count { d -> d.verdict == v }}") }
    }

    private fun unitOf(key: String): String = when {
        key.endsWith("Area") || key.contains("area") -> "m2"
        key.endsWith("Count") || key.contains("count") -> "szt"
        key.contains("pitch") -> "deg"
        else -> "m"
    }
}

/** The analyzer's candidate reduced to a digest. */
object CandidateDigest {

    fun of(candidate: ProjectAnalysisCandidate, label: String): ModelDigest {
        val ground = candidate.floors.minByOrNull { it.order }
        val top = candidate.floors.maxByOrNull { it.order }
        val footprint = ground?.footprint
        val levels = candidate.levels
        fun d(m: com.buildplan.app.analyzer.fidelity.Measured) = DigestValue(m.value, m.fidelity, m.note.orEmpty())
        val values = buildMap {
            put("plan.extentX", DigestValue(footprint?.bounds?.width, FactFidelity.SOURCE_TRACED))
            put("plan.extentZ", DigestValue(footprint?.bounds?.depth, FactFidelity.SOURCE_TRACED))
            put("plan.footprintArea", DigestValue(footprint?.area, FactFidelity.SOURCE_TRACED))
            put("level.terrain", d(levels.terrain))
            put("level.groundFloor", d(levels.groundFloor))
            put("level.upperFloor", d(levels.upperFloor))
            put("level.groundClearHeight", d(levels.groundClearHeight))
            put("level.kneeWall", d(levels.kneeWall))
            put("level.eave", d(levels.eave))
            put("level.ridge", d(levels.ridge))
            put("height.total", d(levels.buildingHeight))
            put("roof.pitchDeg", candidate.roof?.let { d(it.pitchDegrees) } ?: DigestValue(null, FactFidelity.MISSING))
            put("roof.area", candidate.roof?.let { d(it.totalArea) } ?: DigestValue(null, FactFidelity.MISSING))
            put("roof.facetCount", DigestValue(candidate.roof?.facets?.size?.toDouble(), FactFidelity.SOURCE_DERIVED))
            put("roof.ridgeLength", DigestValue(candidate.roof?.ridgeLines?.sumOf { it.length }, FactFidelity.SOURCE_DERIVED))
            put("roof.outlineExtentX", DigestValue(candidate.roof?.outline?.bounds?.width, FactFidelity.SOURCE_TRACED))
            put("roof.outlineExtentZ", DigestValue(candidate.roof?.outline?.bounds?.depth, FactFidelity.SOURCE_TRACED))
            put("walls.exteriorLengthGround", DigestValue(candidate.walls.filter { it.floorId == ground?.id && it.wallClass == com.buildplan.app.analyzer.candidate.WallClass.EXTERIOR }.sumOf { it.length.value ?: 0.0 }, FactFidelity.SOURCE_TRACED))
            put("openings.exteriorCount", DigestValue(candidate.openings.count { it.exterior && it.type != OpeningType.ROOFLIGHT }.toDouble(), FactFidelity.SOURCE_TRACED))
            put("openings.garageGateCount", DigestValue(candidate.openings.count { it.type == OpeningType.GARAGE_GATE }.toDouble(), FactFidelity.SOURCE_TRACED))
            put("rooms.count", DigestValue(candidate.rooms.size.toDouble(), FactFidelity.SOURCE_TRACED))
            put("rooms.planAreaSum", DigestValue(candidate.rooms.sumOf { it.plannedArea.value ?: 0.0 }, FactFidelity.SOURCE_TRACED))
            put("stairs.count", DigestValue(candidate.stairs.size.toDouble(), FactFidelity.SOURCE_TRACED))
            val mass = candidate.roof?.secondaryMasses?.maxByOrNull { it.outline.area }
            put("mass.secondaryArea", DigestValue(mass?.outline?.area, FactFidelity.SOURCE_TRACED))
            put("mass.secondaryTop", mass?.let { d(it.topElevation) } ?: DigestValue(null, FactFidelity.MISSING))
            put("floors.count", DigestValue(candidate.floors.size.toDouble(), FactFidelity.SOURCE_EXACT))
            put("level.atticClearHeight", top?.clearHeight?.let { d(it) } ?: DigestValue(null, FactFidelity.MISSING))
        }
        val sides = com.buildplan.app.analyzer.visual.FacadeMapper.of(candidate)
        val byFacade = candidate.openings.filter { it.exterior && it.type != OpeningType.ROOFLIGHT }
            .mapNotNull { sides.sideOf(it)?.name }
            .groupingBy { it }.eachCount()
        return ModelDigest(
            label = label,
            values = values,
            openingsByFacade = byFacade,
            roomAreas = candidate.rooms.groupBy { it.name }.mapValues { (_, list) -> list.sumOf { it.plannedArea.value ?: 0.0 } },
            roomCentroids = candidate.rooms.mapNotNull { r -> r.polygon?.let { r.name to (it.centroid.x to it.centroid.z) } }.toMap(),
            roofFamily = candidate.roof?.family?.name ?: RoofFamily.UNKNOWN.name,
        )
    }
}
