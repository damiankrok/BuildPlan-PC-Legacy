package com.buildplan.app.analyzer.visual

import com.buildplan.app.analyzer.asset.AssetRole
import com.buildplan.app.analyzer.asset.FetchedAssets
import com.buildplan.app.analyzer.asset.RetrievalState
import com.buildplan.app.analyzer.candidate.AffectedRegion
import com.buildplan.app.analyzer.candidate.AppearanceCandidate
import com.buildplan.app.analyzer.candidate.AppearanceKind
import com.buildplan.app.analyzer.candidate.EvidenceRef
import com.buildplan.app.analyzer.candidate.FacadeAssignment
import com.buildplan.app.analyzer.candidate.FacadeSide
import com.buildplan.app.analyzer.candidate.OpeningCandidate
import com.buildplan.app.analyzer.candidate.OpeningType
import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.candidate.RoofFamily
import com.buildplan.app.analyzer.candidate.VisualAssetEvidence
import com.buildplan.app.analyzer.candidate.VisualConflict
import com.buildplan.app.analyzer.candidate.VisualConflictKind
import com.buildplan.app.analyzer.candidate.VisualConflictSeverity
import com.buildplan.app.analyzer.candidate.VisualEvidence
import com.buildplan.app.analyzer.candidate.VisualObservation
import com.buildplan.app.analyzer.candidate.VisualObservationKind
import com.buildplan.app.analyzer.candidate.VisualViewpoint
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.pipeline.AnalysisStage
import com.buildplan.app.analyzer.pipeline.CancellationSignal
import com.buildplan.app.analyzer.pipeline.checkpoint
import com.buildplan.app.analyzer.site.RoomKind
import com.buildplan.app.analyzer.candidate.PolishText
import java.util.Locale
import kotlin.math.abs

/**
 * Everything the pictures say about the candidate, put beside it.
 *
 * Three steps, each source-neutral:
 *
 * 1. **Read** every decoded elevation and render with [ElevationReader].
 * 2. **Orient**: decide which facade of the candidate each elevation shows.
 *    The front is the side with the entrance door — the exterior door of a
 *    vestibule or hall — failing that the side with the garage gate; the rear
 *    is opposite; the two side elevations could each be either remaining
 *    facade and are listed as both, unsettled. Nothing is matched by trying
 *    assignments until counts agree.
 * 3. **Compare and propose**: count openings per settled facade against the
 *    picture, check the roof family against the roofline's read, check a
 *    lower dark mass against the candidate's secondary masses, and turn
 *    frames, bands, railings, stacks, rooflights and roof courses into
 *    appearance candidates.
 *
 * A render outranks nothing. It corroborates an elevation's reading (a frame
 * seen twice is a stronger proposal) and on its own produces a proposal at
 * [FactFidelity.TRACE_UNCERTAIN] and nothing structural.
 */
object VisualEvidenceBuilder {

    fun build(
        candidate: ProjectAnalysisCandidate,
        assets: FetchedAssets,
        cancellation: CancellationSignal = CancellationSignal.NONE,
        log: (String) -> Unit = {},
    ): VisualEvidence {
        val readings = assets.manifest.assets
            .filter { it.retrieval == RetrievalState.DECODED && ElevationReader.viewpointOf(it.role) != null }
            .mapNotNull { record ->
                cancellation.checkpoint(AnalysisStage.VISUAL)
                val image = assets.image(record) ?: return@mapNotNull null
                ElevationReader.read(record.url, record.role, image).also { e ->
                    log("visual ${record.role.name.lowercase()}: ${e.observations.size} observations, confidence ${fmt(e.confidence)} — ${summary(e)}")
                }
            }
        return of(candidate, readings, log)
    }

    /** The comparison alone, over readings already made. */
    fun of(candidate: ProjectAnalysisCandidate, readings: List<VisualAssetEvidence>, log: (String) -> Unit = {}): VisualEvidence {
        val notes = mutableListOf<String>()
        val sides = FacadeMapper.of(candidate)
        val facades = assign(readings, sides, notes)
        facades.forEach { log("facade ${it.role.name.lowercase()} → ${it.sides.joinToString("|")} (${fmt(it.confidence)}): ${it.reason}") }
        val conflicts = conflicts(candidate, readings, facades, sides, notes)
        val appearance = appearance(candidate, readings, facades)
        conflicts.forEach { log("visual conflict ${it.kind} ${it.severity}: ${it.candidateReading} | ${it.sourceReading}") }
        appearance.forEach { log("appearance ${it.kind} ${fmt(it.confidence)}: ${it.note}") }
        return VisualEvidence(readings, facades, conflicts, appearance, notes)
    }

    // ----------------------------------------------------------- orientation

    private fun assign(readings: List<VisualAssetEvidence>, sides: FacadeMapper.Sides, notes: MutableList<String>): List<FacadeAssignment> {
        val front = sides.front
        val elevations = readings.filter { it.viewpoint == VisualViewpoint.ORTHOGRAPHIC_ELEVATION }
        if (front == null) {
            notes += "front facade undecided: no exterior door of a vestibule or hall and no garage gate; elevations left unassigned"
            return elevations.map { FacadeAssignment(it.role, it.assetUrl, FacadeSide.entries, 0.0, "candidate does not say which side is the entrance") }
        }
        val rear = opposite(front)
        val flanks = FacadeSide.entries.filter { it != front && it != rear }
        return elevations.map { e ->
            when (e.role) {
                AssetRole.ELEVATION_FRONT -> FacadeAssignment(e.role, e.assetUrl, listOf(front), sides.frontConfidence, sides.frontReason)
                AssetRole.ELEVATION_REAR -> FacadeAssignment(e.role, e.assetUrl, listOf(rear), sides.frontConfidence, "opposite the front: ${sides.frontReason}")
                else -> FacadeAssignment(e.role, e.assetUrl, flanks, sides.frontConfidence * 0.5, "a side elevation shows one of the two flanks; the page does not say which")
            }
        }
    }

    private fun opposite(side: FacadeSide): FacadeSide = when (side) {
        FacadeSide.NORTH -> FacadeSide.SOUTH
        FacadeSide.SOUTH -> FacadeSide.NORTH
        FacadeSide.EAST -> FacadeSide.WEST
        FacadeSide.WEST -> FacadeSide.EAST
    }

    // ------------------------------------------------------------- conflicts

    private fun conflicts(
        candidate: ProjectAnalysisCandidate,
        readings: List<VisualAssetEvidence>,
        facades: List<FacadeAssignment>,
        sides: FacadeMapper.Sides,
        notes: MutableList<String>,
    ): List<VisualConflict> {
        val out = mutableListOf<VisualConflict>()
        var n = 0
        fun id() = "vc${++n}"
        val byUrl = readings.associateBy { it.assetUrl }

        facades.filter { it.isSettled }.forEach { f ->
            val e = byUrl[f.assetUrl] ?: return@forEach
            val side = f.sides.single()
            val onSide = candidate.openings.filter { it.exterior && it.type != OpeningType.ROOFLIGHT && sides.sideOf(it) == side }
            val seen = e.observations.filter { it.kind == VisualObservationKind.OPENING_RECTANGLE || it.kind == VisualObservationKind.DOOR_RECTANGLE || it.kind == VisualObservationKind.GARAGE_GATE_RECTANGLE }
                .filter { it.confidence >= COUNT_MIN_CONFIDENCE }
            val diff = seen.size - onSide.size
            // The reader misses openings far more often than it invents them — a door in a dark
            // wall is invisible to it — so a picture showing *more* is a conflict worth a person's
            // minute, and a picture showing fewer is a low note, never a question about the plan.
            if (e.confidence >= 0.5 && (diff >= COUNT_MIN_DIFF || diff <= -COUNT_MIN_MISSING)) {
                val severity = when {
                    diff < 0 -> VisualConflictSeverity.LOW
                    diff >= 3 && e.confidence >= 0.7 -> VisualConflictSeverity.HIGH
                    else -> VisualConflictSeverity.MEDIUM
                }
                out += VisualConflict(
                    id = id(),
                    kind = VisualConflictKind.OPENING_COUNT_CONFLICT,
                    severity = severity,
                    subjectIds = onSide.map { it.id },
                    facade = side,
                    assetUrl = e.assetUrl,
                    observationIndex = -1,
                    candidateReading = "${onSide.size} exterior openings traced on this facade",
                    sourceReading = "${seen.size} opening-like rectangles seen on the elevation",
                    impact = if (diff > 0) "openings the plan trace missed would be missing from joinery and facade deductions" else "openings the elevation does not show may be gaps in the wall line that are not openings",
                    recommendedAction = if (diff > 0) "Na elewacji widać ${PolishText.openings(seen.size)}, a z rzutu odczytano ${onSide.size}. Sprawdź, czy rzut nie pominął otworów." else "Z rzutu odczytano ${PolishText.openings(onSide.size)}, a na elewacji widać ${seen.size}. Sprawdź, czy przerwy w ścianie są otworami.",
                    confidence = e.confidence * (seen.map { it.confidence }.average().takeIf { !it.isNaN() } ?: 0.5),
                )
            }
            // A gate seen where the candidate has none.
            val gateSeen = e.observations.withIndex().firstOrNull { it.value.kind == VisualObservationKind.GARAGE_GATE_RECTANGLE && it.value.confidence >= COUNT_MIN_CONFIDENCE }
            if (gateSeen != null && onSide.none { it.type == OpeningType.GARAGE_GATE }) {
                out += VisualConflict(
                    id(), VisualConflictKind.GARAGE_RELATION_CONFLICT, VisualConflictSeverity.MEDIUM, onSide.map { it.id }, side, e.assetUrl, gateSeen.index,
                    "no garage gate traced on this facade",
                    "a wide, low rectangle standing on the ground: gate-like",
                    "a garage on the wrong facade changes the massing and the facade scope",
                    "Na elewacji widać coś w rodzaju bramy garażowej, a rzut nie ma tu bramy. Sprawdź położenie garażu.",
                    e.confidence * gateSeen.value.confidence,
                )
            }
            // A lower dark mass beside the main body where the candidate has no secondary mass and no garage.
            val mass = e.observations.withIndex().firstOrNull { it.value.kind == VisualObservationKind.DARK_MASS && it.value.confidence >= COUNT_MIN_CONFIDENCE }
            val hasSecondary = candidate.roof?.secondaryMasses?.isNotEmpty() == true || candidate.rooms.any { it.kind == RoomKind.GARAGE }
            if (mass != null && !hasSecondary) {
                out += VisualConflict(
                    id(), VisualConflictKind.MASSING_CONFLICT, VisualConflictSeverity.MEDIUM, listOfNotNull(candidate.floors.minByOrNull { it.order }?.id), side, e.assetUrl, mass.index,
                    "one body under one roof; no secondary mass and no garage room",
                    "a wide dark mass standing on the ground beside the main wall",
                    "a missing secondary body changes the footprint, the roof and the facade",
                    "Elewacja pokazuje niższą bryłę obok domu, której kandydat nie ma. Sprawdź obrys parteru.",
                    e.confidence * mass.value.confidence,
                )
            }
        }

        // Roof family against the roofline's read on the two gable-facing elevations.
        val roof = candidate.roof
        if (roof != null) {
            val ends = facades.filter { it.role == AssetRole.ELEVATION_FRONT || it.role == AssetRole.ELEVATION_REAR }.mapNotNull { byUrl[it.assetUrl] }
            val gable = ends.mapNotNull { e -> e.observations.withIndex().firstOrNull { it.value.kind == VisualObservationKind.GABLE_READ }?.let { e to it } }
            val hip = ends.mapNotNull { e -> e.observations.withIndex().firstOrNull { it.value.kind == VisualObservationKind.HIP_READ }?.let { e to it } }
            // A hipped house may well carry one gable feature on its front; both ends reading as a
            // plain gable is what contradicts the family.
            if (roof.family == RoofFamily.HIP && gable.size >= 2 && hip.isEmpty()) {
                val (e, obs) = gable.maxBy { it.second.value.confidence }
                if (obs.value.confidence >= 0.7) {
                    out += VisualConflict(
                        id(), VisualConflictKind.ROOF_SILHOUETTE_CONFLICT, VisualConflictSeverity.MEDIUM, listOf("roof"), facades.first { it.assetUrl == e.assetUrl }.sides.firstOrNull(), e.assetUrl, obs.index,
                        "roof solved as hipped", "the elevation's roofline reads as a gable", "the roof family changes every roof quantity and the attic",
                        "Kalenica i połacie: elewacja czyta się jak dach dwuspadowy, a kandydat ma dach kopertowy. Sprawdź rodzaj dachu.",
                        e.confidence * obs.value.confidence,
                    )
                }
            }
            if (roof.family == RoofFamily.GABLE && hip.size >= 2 && gable.isEmpty()) {
                val (e, obs) = hip.maxBy { it.second.value.confidence }
                out += VisualConflict(
                    id(), VisualConflictKind.ROOF_SILHOUETTE_CONFLICT, VisualConflictSeverity.MEDIUM, listOf("roof"), facades.first { it.assetUrl == e.assetUrl }.sides.firstOrNull(), e.assetUrl, obs.index,
                    "roof solved as a gable", "both end elevations show a flat top between slopes", "the roof family changes every roof quantity and the attic",
                    "Oba końce domu na elewacjach mają płaski szczyt między spadkami, a kandydat ma dach dwuspadowy. Sprawdź rodzaj dachu.",
                    e.confidence * obs.value.confidence,
                )
            }
        }

        // Render-only structural cues are named, never acted on.
        readings.filter { it.viewpoint == VisualViewpoint.PERSPECTIVE_RENDER }.forEach { r ->
            val gate = r.observations.withIndex().firstOrNull { it.value.kind == VisualObservationKind.GARAGE_GATE_RECTANGLE }
            val elevationsShowGate = readings.any { it.viewpoint == VisualViewpoint.ORTHOGRAPHIC_ELEVATION && it.of(VisualObservationKind.GARAGE_GATE_RECTANGLE).isNotEmpty() }
            if (gate != null && !elevationsShowGate && candidate.openings.none { it.type == OpeningType.GARAGE_GATE }) {
                out += VisualConflict(
                    id(), VisualConflictKind.VISUAL_EVIDENCE_UNCORROBORATED, VisualConflictSeverity.LOW, emptyList(), null, r.assetUrl, gate.index,
                    "no garage gate traced", "a gate-like rectangle in a perspective render", "a render alone cannot place a gate",
                    "Wizualizacja sugeruje bramę garażową, której nie potwierdza ani rzut, ani elewacja.",
                    r.confidence * gate.value.confidence,
                )
            }
        }
        if (out.isEmpty()) notes += "no visual conflict raised"
        return out
    }

    // ------------------------------------------------------------ appearance

    private fun appearance(candidate: ProjectAnalysisCandidate, readings: List<VisualAssetEvidence>, facades: List<FacadeAssignment>): List<AppearanceCandidate> {
        val out = mutableListOf<AppearanceCandidate>()
        val facadeByUrl = facades.associateBy { it.assetUrl }
        val renders = readings.filter { it.viewpoint == VisualViewpoint.PERSPECTIVE_RENDER }
        val renderCladding = renders.any { it.of(VisualObservationKind.CLADDING_PATCH).isNotEmpty() }
        val topFloor = candidate.floors.maxByOrNull { it.order }?.id
        var n = 0
        fun id(kind: AppearanceKind) = "ap${++n}-${kind.name.lowercase().replace('_', '-')}"

        readings.filter { it.viewpoint == VisualViewpoint.ORTHOGRAPHIC_ELEVATION }.forEach { e ->
            val side = facadeByUrl[e.assetUrl]?.takeIf { it.isSettled }?.sides?.single()
            val silhouette = e.silhouette ?: return@forEach
            fun region(o: VisualObservation) = AffectedRegion(side, null, o.bounds.relativeTo(silhouette))
            fun ref(o: VisualObservation) = EvidenceRef(e.assetUrl, e.observations.indexOf(o), e.viewpoint)

            e.of(VisualObservationKind.FRAME_OR_PORTAL).forEach { o ->
                val corroborated = renderCladding
                out += AppearanceCandidate(
                    id(AppearanceKind.FACADE_FRAME), AppearanceKind.FACADE_FRAME, listOf(ref(o)) + renders.mapNotNull { r -> r.of(VisualObservationKind.CLADDING_PATCH).firstOrNull()?.let { EvidenceRef(r.assetUrl, r.observations.indexOf(it), r.viewpoint) } },
                    region(o).copy(floorId = topFloor), (o.confidence + if (corroborated) 0.15 else 0.0).coerceAtMost(0.95), e.fidelity,
                    mapOf("thicknessFraction" to (o.note.substringAfter("thickness ").toDoubleOrNull() ?: 0.05), "widthFraction" to o.bounds.relativeTo(silhouette).width, "topFraction" to o.bounds.relativeTo(silhouette).top),
                    "jasna rama wokół cofniętego, okładzinowanego szczytu${if (corroborated) "; wizualizacja pokazuje tę samą okładzinę" else ""}",
                )
            }
            val bands = e.of(VisualObservationKind.HORIZONTAL_BAND).filter { o ->
                val rel = o.bounds.relativeTo(silhouette)
                rel.top in 0.25..0.75 && o.confidence >= 0.3
            }
            val band = bands.maxByOrNull { it.confidence }
            if (band != null) {
                val rel = band.bounds.relativeTo(silhouette)
                out += AppearanceCandidate(
                    id(AppearanceKind.HORIZONTAL_BAND), AppearanceKind.HORIZONTAL_BAND, listOf(ref(band)), region(band),
                    band.confidence.coerceAtMost(0.85), e.fidelity,
                    mapOf("topFraction" to rel.top, "widthFraction" to rel.width, "leftFraction" to rel.left),
                    "pozioma krawędź przez ${fmt(band.confidence * 100, 0)} % szerokości elewacji, na ${fmt(rel.top * 100, 0)} % wysokości",
                )
            }
            e.of(VisualObservationKind.RAILING_STRIP).forEach { o ->
                out += AppearanceCandidate(id(AppearanceKind.RAILING), AppearanceKind.RAILING, listOf(ref(o)), region(o).copy(floorId = topFloor), o.confidence, e.fidelity, mapOf("topFraction" to o.bounds.relativeTo(silhouette).top, "heightFraction" to o.bounds.relativeTo(silhouette).height, "widthFraction" to o.bounds.relativeTo(silhouette).width), "półprzezroczysty pas na poziomej krawędzi w połowie wysokości")
                if (band != null) {
                    out += AppearanceCandidate(id(AppearanceKind.BALCONY), AppearanceKind.BALCONY, listOf(ref(o), ref(band)), region(band).copy(floorId = topFloor), (o.confidence + band.confidence) / 2, e.fidelity, mapOf("topFraction" to band.bounds.relativeTo(silhouette).top, "widthFraction" to o.bounds.relativeTo(silhouette).width), "balustrada nad krawędzią płyty: balkon")
                }
            }
            e.of(VisualObservationKind.ROOF_STACK).forEach { o ->
                out += AppearanceCandidate(id(AppearanceKind.ROOF_STACK), AppearanceKind.ROOF_STACK, listOf(ref(o)), region(o), o.confidence, e.fidelity, mapOf("leftFraction" to o.bounds.relativeTo(silhouette).left, "widthFraction" to o.bounds.relativeTo(silhouette).width, "heightFraction" to o.bounds.relativeTo(silhouette).height), "wąski występ ponad linią dachu")
            }
            e.of(VisualObservationKind.ROOFLIGHT_PATCH).forEach { o ->
                out += AppearanceCandidate(id(AppearanceKind.ROOFLIGHT), AppearanceKind.ROOFLIGHT, listOf(ref(o)), region(o), o.confidence, e.fidelity, mapOf("leftFraction" to o.bounds.relativeTo(silhouette).left, "topFraction" to o.bounds.relativeTo(silhouette).top), "jasny prostokąt w połaci")
            }
            e.of(VisualObservationKind.GARAGE_GATE_RECTANGLE).forEach { o ->
                if (candidate.openings.any { it.type == OpeningType.GARAGE_GATE }) {
                    out += AppearanceCandidate(id(AppearanceKind.GARAGE_PORTAL), AppearanceKind.GARAGE_PORTAL, listOf(ref(o)), region(o), o.confidence * 0.8, e.fidelity, mapOf("widthFraction" to o.bounds.relativeTo(silhouette).width, "leftFraction" to o.bounds.relativeTo(silhouette).left), "brama widoczna na elewacji tam, gdzie rzut ma bramę")
                }
            }
        }

        // One covering hint for the whole roof: the strongest course reading anywhere.
        val cover = readings.flatMap { e -> e.of(VisualObservationKind.ROOF_COVER_TEXTURE).map { e to it } }.maxByOrNull { it.second.confidence }
        if (cover != null) {
            val (e, o) = cover
            out += AppearanceCandidate(
                id(AppearanceKind.ROOF_COVER_HINT), AppearanceKind.ROOF_COVER_HINT, listOf(EvidenceRef(e.assetUrl, e.observations.indexOf(o), e.viewpoint)),
                AffectedRegion(null, null, null), o.confidence, e.fidelity, mapOf("periodPx" to (o.note.substringAfter("period ").substringBefore(" px").toDoubleOrNull() ?: 0.0)),
                "regularne poziome rzędy na połaci: pokrycie dachówkowe (${o.note})",
            )
        }

        // Cladding seen only on renders: a cue, at the weakest fidelity.
        if (out.none { it.kind == AppearanceKind.FACADE_FRAME } && renderCladding) {
            val r = renders.first { it.of(VisualObservationKind.CLADDING_PATCH).isNotEmpty() }
            val o = r.of(VisualObservationKind.CLADDING_PATCH).maxBy { it.confidence }
            out += AppearanceCandidate(id(AppearanceKind.CLADDING), AppearanceKind.CLADDING, listOf(EvidenceRef(r.assetUrl, r.observations.indexOf(o), r.viewpoint)), AffectedRegion(null, null, null), (o.confidence * 0.6).coerceAtMost(0.5), FactFidelity.TRACE_UNCERTAIN, emptyMap(), "ciepła okładzina widoczna tylko na wizualizacji")
        }
        return out
    }

    private fun summary(e: VisualAssetEvidence): String =
        e.observations.groupingBy { it.kind }.eachCount().entries.joinToString { "${it.key.name.lowercase()} ×${it.value}" }

    private fun fmt(v: Double, digits: Int = 2) = String.format(Locale.ROOT, "%.${digits}f", v)

    private const val COUNT_MIN_CONFIDENCE = 0.55
    private const val COUNT_MIN_DIFF = 2
    private const val COUNT_MIN_MISSING = 3
}

/**
 * Which way each exterior opening of the candidate faces, and which facade
 * is the front.
 *
 * The outward normal of an opening is the direction from the centroid of the
 * room it opens from to the opening itself, snapped to the plan's four
 * directions; an opening with no room falls back to the storey's footprint
 * centroid. The front is where the entrance door is — an exterior door of a
 * vestibule or hall — and failing that where the garage gate is. When
 * neither exists the front is unknown and every count comparison is
 * withheld; nothing is guessed from the page's picture order.
 */
object FacadeMapper {

    class Sides(private val sideByOpening: Map<String, FacadeSide>, val front: FacadeSide?, val frontConfidence: Double, val frontReason: String) {
        fun sideOf(opening: OpeningCandidate): FacadeSide? = sideByOpening[opening.id]
        fun sideOf(id: String): FacadeSide? = sideByOpening[id]
    }

    fun of(candidate: ProjectAnalysisCandidate): Sides {
        val walls = candidate.walls.associateBy { it.id }
        val rooms = candidate.rooms.associateBy { it.id }
        val footprintCentroid = candidate.floors.associate { f -> f.id to (f.footprint?.centroid ?: Pt(0.0, 0.0)) }
        val sideByOpening = candidate.openings.filter { it.exterior }.mapNotNull { o ->
            val wall = walls[o.wallId] ?: return@mapNotNull null
            val d = o.distanceAlongWall.value ?: return@mapNotNull null
            val w = o.width.value ?: 0.0
            val a = wall.centreline.a
            val b = wall.centreline.b
            val len = a.distanceTo(b)
            if (len < 1e-9) return@mapNotNull null
            val t = (d + w / 2) / len
            val at = Pt(a.x + (b.x - a.x) * t, a.z + (b.z - a.z) * t)
            val from = o.linkedRoomIds.mapNotNull { rooms[it]?.polygon?.centroid }.firstOrNull() ?: footprintCentroid[o.floorId] ?: return@mapNotNull null
            val dx = at.x - from.x
            val dz = at.z - from.z
            val horizontalWall = abs(a.z - b.z) <= abs(a.x - b.x)
            // A horizontal wall faces north or south; which one, the room says.
            val side = if (horizontalWall) (if (dz < 0) FacadeSide.NORTH else FacadeSide.SOUTH) else (if (dx < 0) FacadeSide.WEST else FacadeSide.EAST)
            o.id to side
        }.toMap()

        val entrance = candidate.openings.filter { o ->
            o.exterior && o.type == OpeningType.DOOR && o.linkedRoomIds.any { rooms[it]?.kind == RoomKind.VESTIBULE || rooms[it]?.kind == RoomKind.HALL }
        }.mapNotNull { sideByOpening[it.id] }
        val gate = candidate.openings.filter { it.exterior && it.type == OpeningType.GARAGE_GATE }.mapNotNull { sideByOpening[it.id] }
        val (front, confidence, reason) = when {
            entrance.isNotEmpty() -> Triple(entrance.groupingBy { it }.eachCount().maxBy { it.value }.key, 0.8, "the exterior door of the vestibule or hall faces this way")
            gate.isNotEmpty() -> Triple(gate.first(), 0.6, "no entrance door found; the garage gate faces this way")
            else -> Triple(null, 0.0, "no entrance door and no garage gate")
        }
        return Sides(sideByOpening, front, confidence, reason)
    }
}
