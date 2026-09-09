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
            // The cost page lists this among the things to *build*, so it is masonry: the wall
            // material between the openings, which is what the traced wall pieces already are.
            // There is deliberately no "net" line beside it any more — the previous one subtracted
            // the openings from a figure they were never in, and reported Project A's exterior
            // walls at 73 m2 when the traced masonry alone was 123 m2.
            out += compare(
                "Powierzchnia ścian zewnętrznych (mur między otworami)",
                quantities.facadeWallMaterial.value, s.measured.value, "m2",
                "traced exterior wall pieces × storey height, once per piece, all storeys; openings are gaps between pieces and are already absent, so this is not reduced by them again",
                quantities.facadeWallMaterial.fidelity,
            )
            out += ValidationFinding(
                "Powierzchnia zewnętrzna obwiedni (nad otworami)", quantities.floors.sumOf { it.exteriorEnvelopeGross.value ?: 0.0 }, s.measured.value, "m2", null, null,
                ValidationStatus.NOT_COMPARABLE,
                "the envelope run over the openings — a facade surface, not a masonry quantity, and not what a cost page's external-wall line prices; reported so the two readings of the envelope are both visible",
                FactFidelity.SOURCE_DERIVED,
            )
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
            out += bracketed(
                subject = "Powierzchnia elewacji do ocieplenia",
                gross = quantities.facadeGross.value,
                net = quantities.facadeNet.value,
                sourceValue = s.measured.value,
                grossSemantics = "storey footprint perimeter × storey height + gables, measured over the openings",
                netSemantics = "the same envelope minus exterior openings at assumed heights",
                whyAmbiguous = "the cost page does not say whether its facade figure is measured over the openings or net of them, " +
                    "nor whether it includes the plinth below the ground-floor level and the garage",
                fidelity = quantities.facadeGross.fidelity,
            )
            out += ValidationFinding(
                "Powierzchnia zewnętrznych ścian murowanych (bez otworów)", quantities.facadeWallMaterial.value, s.measured.value, "m2", null, null,
                ValidationStatus.NOT_COMPARABLE,
                "traced exterior wall pieces only: openings are absent because an opening is not a wall piece, and so is any envelope the raster left oblique or unresolved. " +
                    "It is the lower bracket on the envelope, never the facade area, and must not be compared with a facade figure",
                quantities.facadeWallMaterial.fidelity,
            )
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

    /**
     * Compares a published figure against a candidate quantity that the source
     * leaves ambiguous between two readings.
     *
     * A facade figure measured over the openings and one measured net of them
     * are different numbers for the same wall, and the cost page does not say
     * which it prints. When the published value falls *between* the two, the
     * geometry is not in dispute — the definition is — and saying so is worth
     * more than picking whichever reading is nearer and calling the remainder
     * an error. Only a value outside both readings is a real disagreement, and
     * it is then reported against the nearer of them so the size of the gap is
     * still visible.
     */
    private fun bracketed(
        subject: String,
        gross: Double?,
        net: Double?,
        sourceValue: Double?,
        grossSemantics: String,
        netSemantics: String,
        whyAmbiguous: String,
        fidelity: FactFidelity?,
    ): ValidationFinding {
        if (gross == null || net == null || sourceValue == null || sourceValue == 0.0) {
            return ValidationFinding(subject, gross, sourceValue, "m2", null, null, ValidationStatus.INSUFFICIENT_SOURCE, "$grossSemantics; $whyAmbiguous", fidelity)
        }
        val low = minOf(gross, net)
        val high = maxOf(gross, net)
        if (sourceValue in low..high) {
            return ValidationFinding(
                subject, gross, sourceValue, "m2", null, null, ValidationStatus.NOT_COMPARABLE,
                "the published figure falls between the two readings of this envelope — " +
                    "${fmt(net)} m2 net ($netSemantics) and ${fmt(gross)} m2 gross ($grossSemantics) — so the difference is a definition, not geometry: $whyAmbiguous",
                fidelity,
            )
        }
        val nearer = if (abs(sourceValue - low) <= abs(sourceValue - high)) low else high
        val rel = abs(nearer - sourceValue) / sourceValue
        val status = when {
            rel <= STRONG -> ValidationStatus.MATCH_STRONG
            rel <= ACCEPTABLE -> ValidationStatus.MATCH_ACCEPTABLE
            else -> ValidationStatus.MISMATCH
        }
        return ValidationFinding(
            subject, nearer, sourceValue, "m2", abs(nearer - sourceValue), rel, status,
            "outside both readings of this envelope (${fmt(net)}–${fmt(gross)} m2), compared against the nearer; $whyAmbiguous",
            fidelity,
        )
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v)

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
