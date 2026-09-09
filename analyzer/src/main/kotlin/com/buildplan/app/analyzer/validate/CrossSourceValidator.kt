package com.buildplan.app.analyzer.validate

import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.quantity.ProjectQuantities
import com.buildplan.app.analyzer.site.PublishedScalar
import com.buildplan.app.analyzer.site.ScalarKey
import com.buildplan.app.analyzer.site.SourcePackage
import kotlin.math.abs

enum class ValidationStatus { MATCH_STRONG, MATCH_ACCEPTABLE, MISMATCH, NOT_COMPARABLE, INSUFFICIENT_SOURCE }

/**
 * One comparison of a candidate-derived value against a source-published
 * value, with the semantics of what is being compared spelled out.
 */
data class ValidationFinding(
    val subject: String,
    val candidateValue: Double?,
    val sourceValue: Double?,
    val unit: String,
    val absoluteDifference: Double?,
    val relativeDifference: Double?,
    val status: ValidationStatus,
    val semantics: String,
    val candidateFidelity: FactFidelity?,
)

/**
 * Compares candidate quantities with what the page and the cost page
 * publish. Thresholds are fixed before any result is seen and stated here:
 *
 * - `MATCH_STRONG`      relative difference ≤ 5 %
 * - `MATCH_ACCEPTABLE`  ≤ 15 %
 * - `MISMATCH`          above 15 %
 * - `NOT_COMPARABLE`    the two numbers measure different things (a two-sided
 *                        finish area against a structural wall area)
 * - `INSUFFICIENT_SOURCE` one side is missing, or the candidate side rests on
 *                        an assumption the source does not confirm (opening heights)
 *
 * Nothing here adjusts a candidate to fit; a mismatch is reported as one.
 */
object CrossSourceValidator {

    const val STRONG = 0.05
    const val ACCEPTABLE = 0.15

    fun validate(pkg: SourcePackage, candidate: ProjectAnalysisCandidate, quantities: ProjectQuantities): List<ValidationFinding> {
        val out = mutableListOf<ValidationFinding>()

        // Rooms: polygon area vs published floor area (or usable area when only that exists).
        candidate.floors.forEach { floor ->
            val isTop = floor.order == candidate.floors.maxOf { it.order } && candidate.roof != null
            floor.rooms.forEach { room ->
                val q = quantities.rooms.firstOrNull { it.roomId == room.id }
                val planned = room.plannedArea.value
                when {
                    room.sourceFloorArea.value != null -> out += compare("Pomieszczenie ${room.name} (${room.id}): powierzchnia podłogi", planned, room.sourceFloorArea.value, "m2",
                        "candidate room polygon vs published floor area (in parentheses on the page)", room.matchConfidence)
                    isTop && room.sourceUsableArea.value != null -> out += compare("Pomieszczenie ${room.name} (${room.id}): powierzchnia użytkowa wg reguły wysokości", q?.usableAreaByHeightRule?.value, room.sourceUsableArea.value, "m2",
                        "candidate usable area by the page's own height rule (100 % above 2.2 m, 50 % from 1.4 m) vs published usable area", q?.usableAreaByHeightRule?.fidelity)
                    room.sourceUsableArea.value != null -> out += compare("Pomieszczenie ${room.name} (${room.id}): powierzchnia", planned, room.sourceUsableArea.value, "m2",
                        "candidate room polygon vs published usable area (no floor area printed)", room.matchConfidence)
                }
            }
        }

        // Floor totals.
        pkg.floors.forEachIndexed { i, published ->
            val floor = candidate.floors.getOrNull(i) ?: return@forEachIndexed
            val q = quantities.floors.firstOrNull { it.floorId == floor.id } ?: return@forEachIndexed
            val target = published.floorAreaTotal.value ?: published.usableAreaTotal.value
            val semantics = if (published.floorAreaTotal.value != null) "sum of matched room polygons vs published storey floor-area total" else "sum of matched room polygons vs published storey usable total (attic usable excludes low strips)"
            out += compare("Kondygnacja ${published.name}: suma powierzchni pomieszczeń", q.roomFloorAreaSum.value, target, "m2", semantics, q.roomFloorAreaSum.fidelity)
        }

        // Footprint: the calibration anchor, so not an independent check.
        pkg.scalar(ScalarKey.FOOTPRINT_AREA)?.let { s ->
            val footprint = candidate.floors.minByOrNull { it.order }?.footprint?.area
            out += ValidationFinding("Powierzchnia zabudowy", footprint, s.measured.value, "m2", footprint?.let { abs(it - (s.measured.value ?: 0.0)) }, null, ValidationStatus.NOT_COMPARABLE,
                "the published footprint area is the calibration anchor of the ground plan; agreement is by construction, not evidence", FactFidelity.SOURCE_DERIVED)
        }

        // Roof.
        firstScalar(pkg, ScalarKey.ROOF_AREA)?.let { s ->
            out += compare("Powierzchnia dachu", quantities.roofTotal.value, s.measured.value, "m2", "sum of skeleton facets / cos(pitch) over the roof outline vs published roof area", quantities.roofTotal.fidelity)
        }

        // Aggregate walls.
        val floorsByOrder = candidate.floors.sortedBy { it.order }
        val ground = floorsByOrder.firstOrNull()?.let { f -> quantities.floors.firstOrNull { it.floorId == f.id } }
        val upper = floorsByOrder.getOrNull(1)?.let { f -> quantities.floors.firstOrNull { it.floorId == f.id } }
        pkg.scalar(ScalarKey.EXTERNAL_WALL_AREA)?.let { s ->
            val gross = quantities.floors.sumOf { it.exteriorWallsStructural.value ?: 0.0 }
            val net = quantities.floors.sumOf { it.exteriorWallsNet.value ?: 0.0 }
            out += compare("Powierzchnia ścian zewnętrznych (brutto)", gross, s.measured.value, "m2", "exterior wall centreline length × storey height, once per wall, all storeys, before openings; the cost page does not say whether its figure is net", FactFidelity.SOURCE_DERIVED)
            out += compare("Powierzchnia ścian zewnętrznych (netto, wysokości otworów założone)", net, s.measured.value, "m2", "as above minus exterior openings with assumed heights", FactFidelity.DISPLAY_ASSUMPTION, assumptionBacked = true)
        }
        pkg.scalar(ScalarKey.INTERNAL_LOAD_BEARING_WALL_AREA)?.let { s ->
            out += compare("Powierzchnia ścian wewnętrznych nośnych", quantities.floors.sumOf { it.loadBearingWallsStructural.value ?: 0.0 }, s.measured.value, "m2", "internal walls ≥ 0.20 m thick, once each, all storeys", FactFidelity.SOURCE_DERIVED)
        }
        pkg.scalar(ScalarKey.PARTITION_WALL_AREA_GROUND)?.let { s ->
            out += compare("Powierzchnia ścian działowych parter", ground?.partitionsStructural?.value, s.measured.value, "m2", "internal walls < 0.20 m thick on the ground storey, once each (structural, not two-sided finish)", FactFidelity.SOURCE_DERIVED)
        }
        pkg.scalar(ScalarKey.PARTITION_WALL_AREA_UPPER)?.let { s ->
            out += compare("Powierzchnia ścian działowych poddasze", upper?.partitionsStructural?.value, s.measured.value, "m2", "internal walls < 0.20 m thick on the upper storey, once each, to the roof underside", FactFidelity.SOURCE_DERIVED)
        }
        pkg.scalar(ScalarKey.FLOORS_AND_STAIRS_AREA)?.let { s ->
            out += compare("Powierzchnia podłóg i schodów", quantities.floorsAndStairsArea.value, s.measured.value, "m2", "sum of all room polygons on all storeys vs published floors-and-stairs area", quantities.floorsAndStairsArea.fidelity)
        }
        pkg.scalar(ScalarKey.EXTERIOR_JOINERY_AREA)?.let { s ->
            out += compare("Powierzchnia stolarki zewnętrznej", quantities.exteriorJoinery.value, s.measured.value, "m2", "exterior openings: traced widths × assumed heights", FactFidelity.DISPLAY_ASSUMPTION, assumptionBacked = true)
        }
        pkg.scalar(ScalarKey.FACADE_INSULATION_AREA)?.let { s ->
            out += compare("Powierzchnia elewacji do ocieplenia (brutto)", quantities.facadeGross.value, s.measured.value, "m2", "exterior walls + gables before openings", quantities.facadeGross.fidelity)
            out += compare("Powierzchnia elewacji do ocieplenia (netto, wysokości otworów założone)", quantities.facadeNet.value, s.measured.value, "m2", "as above minus exterior openings with assumed heights", FactFidelity.DISPLAY_ASSUMPTION, assumptionBacked = true)
        }
        pkg.scalar(ScalarKey.BUILDING_HEIGHT)?.let { s ->
            val ridge = candidate.levels.ridge.value
            val terrain = candidate.levels.terrain.value
            out += ValidationFinding("Wysokość budynku", if (ridge != null && terrain != null) ridge - terrain else null, s.measured.value, "m", 0.0, 0.0, ValidationStatus.NOT_COMPARABLE,
                "the published height is an input of the vertical chain (ridge = height + terrain); not an independent check", candidate.levels.ridge.fidelity)
        }
        return out
    }

    private fun firstScalar(pkg: SourcePackage, key: ScalarKey): PublishedScalar? = pkg.scalars.firstOrNull { it.key == key && it.measured.value != null }

    private fun compare(
        subject: String,
        candidateValue: Double?,
        sourceValue: Double?,
        unit: String,
        semantics: String,
        fidelity: FactFidelity?,
        assumptionBacked: Boolean = false,
    ): ValidationFinding {
        if (candidateValue == null || sourceValue == null || sourceValue == 0.0) {
            return ValidationFinding(subject, candidateValue, sourceValue, unit, null, null, ValidationStatus.INSUFFICIENT_SOURCE, semantics, fidelity)
        }
        val abs = abs(candidateValue - sourceValue)
        val rel = abs / sourceValue
        val status = when {
            assumptionBacked -> ValidationStatus.INSUFFICIENT_SOURCE
            rel <= STRONG -> ValidationStatus.MATCH_STRONG
            rel <= ACCEPTABLE -> ValidationStatus.MATCH_ACCEPTABLE
            else -> ValidationStatus.MISMATCH
        }
        return ValidationFinding(subject, candidateValue, sourceValue, unit, abs, rel, status, semantics, fidelity)
    }
}
