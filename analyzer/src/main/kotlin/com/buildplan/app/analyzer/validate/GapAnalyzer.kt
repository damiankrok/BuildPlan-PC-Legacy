package com.buildplan.app.analyzer.validate

import com.buildplan.app.analyzer.asset.RetrievalState
import com.buildplan.app.analyzer.candidate.OpeningType
import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.candidate.RoofFamily
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.site.SourcePackage
import com.buildplan.app.analyzer.source.SourceResolution

/**
 * Scores the candidate against the requirement checklist and turns the
 * gaps that matter into questions for the user.
 */
object GapAnalyzer {

    /** Ledger scope for lengths read off a drawing rather than out of the page text. */
    const val SOURCE_TEXT_SCOPE = "source-text"


    data class Output(val gaps: GapAnalysis, val questions: List<ClarificationQuestion>)

    fun analyse(resolution: SourceResolution, pkg: SourcePackage?, candidate: ProjectAnalysisCandidate?): Output {
        val statuses = mutableListOf<RequirementStatus>()
        val questions = mutableListOf<ClarificationQuestion>()
        var q = 0
        fun ask(req: Requirement, text: String, assumption: String?, subject: String? = null) {
            questions += ClarificationQuestion("q${++q}", req, text, assumption, subject)
        }
        fun status(req: Requirement, state: RequirementState, fidelity: FactFidelity?, evidence: String) {
            statuses += RequirementStatus(req, state, fidelity, evidence)
        }
        fun ofMeasured(req: Requirement, m: Measured, evidence: String) {
            val state = when (m.fidelity) {
                FactFidelity.MISSING -> RequirementState.MISSING
                FactFidelity.DISPLAY_ASSUMPTION -> RequirementState.ASSUMED
                FactFidelity.TRACE_UNCERTAIN, FactFidelity.CONFLICTING -> RequirementState.PARTIAL
                else -> RequirementState.SATISFIED
            }
            status(req, state, m.fidelity, evidence)
        }

        // Identity.
        status(Requirement.SUPPORTED_PROVIDER, if (resolution.identity != null) RequirementState.SATISFIED else RequirementState.MISSING, null, resolution.identity?.site?.displayName ?: resolution.failure ?: "unresolved")
        status(Requirement.PROJECT_IDENTITY, if (resolution.identity != null) RequirementState.SATISFIED else RequirementState.MISSING, null, resolution.identity?.canonicalUrl ?: "none")
        status(Requirement.ROOM_TABLES, when { pkg == null -> RequirementState.MISSING; pkg.floors.isEmpty() -> RequirementState.MISSING; else -> RequirementState.SATISFIED }, FactFidelity.SOURCE_EXACT, "${pkg?.floors?.size ?: 0} storey tables, ${pkg?.floors?.sumOf { it.rooms.size } ?: 0} rooms")

        if (pkg == null || candidate == null) {
            Requirement.entries.filter { r -> statuses.none { it.requirement == r } }.forEach { status(it, RequirementState.MISSING, null, "no analysis") }
            return Output(GapAnalysis(statuses), questions)
        }

        // Plan geometry.
        // Counted from the storeys that came out of the analysis, not from the manifest the page
        // reader built: that manifest is the list of assets to fetch and every record in it is
        // still PENDING at this point, so asking it how many plans decoded always answered zero
        // — on runs whose plans had decoded and been analysed into rooms.
        val analysedPlans = candidate.floors.count { it.planAssetUrl != null && it.calibration != null }
        status(
            Requirement.FLOOR_PLAN_RASTER,
            if (analysedPlans > 0) RequirementState.SATISFIED else RequirementState.MISSING,
            null,
            "$analysedPlans plan rasters decoded and analysed of ${pkg.assets.plans.size} the page links",
        )
        val calibrated = candidate.floors.filter { it.calibration != null }
        val worstCalibration = calibrated.mapNotNull { it.calibration?.confidence }.maxByOrNull { FactFidelity.weakest(listOf(it)).ordinal }
        status(
            Requirement.FOOTPRINT_CALIBRATION,
            when {
                calibrated.isEmpty() -> RequirementState.MISSING
                worstCalibration == FactFidelity.CONFLICTING -> RequirementState.PARTIAL
                else -> RequirementState.SATISFIED
            },
            worstCalibration,
            calibrated.joinToString { "${it.name}: ${"%.2f".format(java.util.Locale.ROOT, it.calibration!!.pixelsPerMeter.requireValue())} px/m (${it.calibration.confidence}${it.calibration.residual?.let { r -> ", residual ${"%.1f".format(java.util.Locale.ROOT, r * 100)} %" } ?: ""})" },
        )
        if (calibrated.isEmpty()) ask(Requirement.FOOTPRINT_CALIBRATION, "Brakuje wymiaru potrzebnego do skalibrowania rzutu: strona nie podaje powierzchni zabudowy ani sum pomieszczeń. Podaj szerokość budynku w metrach.", null)

        val matchedRooms = candidate.rooms.size
        val publishedRooms = pkg.floors.sumOf { it.rooms.size }
        val uncertainRooms = candidate.rooms.count { it.matchConfidence == FactFidelity.TRACE_UNCERTAIN || it.matchConfidence == FactFidelity.DISPLAY_ASSUMPTION }
        status(Requirement.ROOM_TOPOLOGY, when { matchedRooms == 0 -> RequirementState.MISSING; matchedRooms < publishedRooms / 2 -> RequirementState.PARTIAL; else -> RequirementState.SATISFIED }, FactFidelity.SOURCE_TRACED, "$matchedRooms of $publishedRooms published rooms have a polygon")
        status(Requirement.ROOM_LABELS_MATCHED, when { matchedRooms == 0 -> RequirementState.MISSING; uncertainRooms > matchedRooms / 2 -> RequirementState.PARTIAL; else -> RequirementState.SATISFIED }, null, "$uncertainRooms of $matchedRooms room assignments are uncertain or assumed")
        candidate.floors.forEach { floor ->
            val missing = pkg.floors.getOrNull(candidate.floors.indexOf(floor))?.rooms?.filter { pr -> floor.rooms.none { it.name == pr.name && it.sourceOrdinal == pr.ordinal } }.orEmpty()
            missing.forEach { pr ->
                ask(Requirement.ROOM_TOPOLOGY, "Nie udało się jednoznacznie przypisać pomieszczenia „${pr.ordinal}. ${pr.name}” (${pr.usableArea.value ?: pr.floorArea.value} m²) do żadnego regionu rzutu kondygnacji ${floor.name}. Wskaż je na rzucie.", null, "${floor.id}:${pr.ordinal}")
            }
            // One question per open-plan union, naming the rooms it splits; the builder records
            // each union as a "rooms" issue whose subject lists the room ids.
            val floorRoomIds = floor.rooms.map { it.id }.toSet()
            candidate.issues.filter { it.stage == "rooms" }
                .map { issue -> issue.subject.orEmpty().split(", ").filter { id -> id in floorRoomIds } }
                .filter { it.size >= 2 }
                .distinct()
                .forEach { ids ->
                    val names = ids.map { id -> candidate.room(id)?.name ?: id }
                    ask(
                        Requirement.ROOM_TOPOLOGY,
                        "Rzut kondygnacji ${floor.name} nie rysuje ściany między pomieszczeniami ${names.joinToString(" i ")}; granica została przyjęta proporcjonalnie do podanych powierzchni. Potwierdź lub wskaż przebieg granicy.",
                        "podział proporcjonalny do powierzchni",
                        ids.joinToString(","),
                    )
                }
        }
        status(Requirement.FLOOR_ORDERING, RequirementState.SATISFIED, FactFidelity.SOURCE_EXACT, "storeys in the page's table order, first table lowest")

        // Vertical.
        val l = candidate.levels
        ofMeasured(Requirement.STOREY_LEVELS, l.upperFloor, "upper floor ${l.upperFloor.value?.let { "%.2f m".format(java.util.Locale.ROOT, it) } ?: "missing"}: ${l.upperFloor.provenance.method}")
        ofMeasured(Requirement.STOREY_HEIGHTS, l.groundClearHeight, "ground clear height ${l.groundClearHeight.value?.let { "%.2f m".format(java.util.Locale.ROOT, it) } ?: "missing"}: ${l.groundClearHeight.provenance.method}")
        ofMeasured(Requirement.KNEE_WALL, l.kneeWall, l.kneeWall.provenance.method)
        val roof = candidate.roof
        status(Requirement.ROOF_PITCH_AND_FAMILY, if (roof != null && roof.family != RoofFamily.UNKNOWN && roof.pitchDegrees.value != null) RequirementState.SATISFIED else RequirementState.MISSING, roof?.pitchDegrees?.fidelity, "${roof?.family} ${roof?.pitchDegrees?.value}°")
        ofMeasured(Requirement.RIDGE_AND_EAVES, l.eave, "eave ${l.eave.value?.let { "%.2f m".format(java.util.Locale.ROOT, it) } ?: "missing"}, ridge ${l.ridge.value?.let { "%.2f m".format(java.util.Locale.ROOT, it) } ?: "missing"}")
        if (l.terrain.fidelity == FactFidelity.DISPLAY_ASSUMPTION) ask(Requirement.STOREY_LEVELS, "Nie udało się odczytać rzędnej terenu z przekroju. Przyjęto teren ${"%.2f".format(java.util.Locale.ROOT, l.terrain.value)} m względem poziomu parteru; podaj rzędną z przekroju.", "${"%.2f".format(java.util.Locale.ROOT, l.terrain.value)} m", "levels.terrain")
        if (l.upperFloor.fidelity == FactFidelity.DISPLAY_ASSUMPTION) ask(Requirement.STOREY_LEVELS, "Rzędnej stropu nad parterem nie dało się wyprowadzić z wysokości budynku, kąta dachu i obrysu (łańcuch dał nieprawdopodobną wysokość parteru). Przyjęto ${"%.2f".format(java.util.Locale.ROOT, l.upperFloor.value)} m; podaj rzędną z przekroju.", "${"%.2f".format(java.util.Locale.ROOT, l.upperFloor.value)} m", "levels.upperFloor")
        if (l.upperSlabThickness.fidelity == FactFidelity.DISPLAY_ASSUMPTION && l.upperFloor.value != null) ask(Requirement.STOREY_HEIGHTS, "Nie udało się odczytać wysokości parteru w świetle z przekroju. Przyjęto grubość stropu ${"%.2f".format(java.util.Locale.ROOT, l.upperSlabThickness.value)} m; podaj wysokość w świetle albo rzędną stropu.", "${"%.2f".format(java.util.Locale.ROOT, l.upperSlabThickness.value)} m stropu", "levels.slab")
        if (l.atticFlatCeilingHeight.fidelity == FactFidelity.DISPLAY_ASSUMPTION) ask(Requirement.STOREY_HEIGHTS, "Nie udało się odczytać wysokości poddasza (poziomu sufitu płaskiego). Przyjęto ${"%.2f".format(java.util.Locale.ROOT, l.atticFlatCeilingHeight.value)} m nad podłogą poddasza.", "${"%.2f".format(java.util.Locale.ROOT, l.atticFlatCeilingHeight.value)} m", "levels.atticCeiling")

        // Openings.
        val exteriorOpenings = candidate.openings.filter { it.exterior }
        status(Requirement.EXTERIOR_OPENINGS, if (exteriorOpenings.isNotEmpty()) RequirementState.SATISFIED else RequirementState.MISSING, FactFidelity.SOURCE_TRACED, "${exteriorOpenings.size} exterior openings with traced widths")
        // Dimensions read off the drawing and corroborated by geometry that was traced without
        // reading a character. Counted from the ledger, where each one carries what let it through.
        val readDimensions = candidate.dimensions.filter { it.scope == SOURCE_TEXT_SCOPE }
        status(
            Requirement.DIMENSION_CHAINS,
            if (readDimensions.isEmpty()) RequirementState.MISSING else RequirementState.PARTIAL,
            if (readDimensions.isEmpty()) FactFidelity.MISSING else FactFidelity.SOURCE_EXACT,
            if (readDimensions.isEmpty()) {
                "no dimension chain could be read and corroborated; the plans print them but nothing survived both the reading gate and the geometry check"
            } else {
                "${readDimensions.size} corroborated dimension labels: ${readDimensions.joinToString { "${"%.2f".format(java.util.Locale.ROOT, it.measured.value ?: 0.0)} m" }}"
            },
        )
        if (readDimensions.isEmpty()) {
            ask(Requirement.DIMENSION_CHAINS, "Nie udało się odczytać i potwierdzić żadnego łańcucha wymiarowego z rzutów. Podaj wymiary zewnętrzne budynku.", null, "dimensions")
        }

        val withHeight = candidate.openings.count { it.height.value != null }
        status(
            Requirement.OPENING_HEIGHTS,
            if (withHeight > 0) RequirementState.PARTIAL else RequirementState.MISSING,
            if (withHeight > 0) FactFidelity.SOURCE_EXACT else FactFidelity.MISSING,
            if (withHeight > 0) {
                "$withHeight of ${candidate.openings.size} opening heights read from drawing labels"
            } else {
                "the plans dimension their openings, but the published raster prints those labels below the " +
                    "${com.buildplan.app.analyzer.text.DrawingTextExtractor.MIN_LEGIBLE_GLYPH_HEIGHT_PX} px legibility floor, so none was read"
            },
        )
        if (exteriorOpenings.isNotEmpty()) ask(Requirement.OPENING_HEIGHTS, "Nie udało się ustalić wysokości ${exteriorOpenings.size} otworów zewnętrznych (wykaz stolarki jest tekstem na rzucie). Podaj wysokości i parapety albo potwierdź założenia użyte do odliczeń.", "drzwi 2,05 m, okna 1,50 m, brama 2,20 m", "openings")
        val stairs = candidate.stairs
        val withTreads = stairs.count { it.treadCount.value != null }
        val linked = stairs.count { it.toFloorId != null }
        status(
            Requirement.STAIR_ZONE,
            when {
                stairs.isEmpty() -> RequirementState.MISSING
                // A zone plus a floor transition is as far as a drawing without readable step
                // text can take this; the step count stays a question either way.
                linked > 0 && withTreads > 0 -> RequirementState.PARTIAL
                else -> RequirementState.PARTIAL
            },
            if (stairs.isEmpty()) null else FactFidelity.weakest(stairs.map { it.fidelity }),
            if (stairs.isEmpty()) "0 stair zones" else "${stairs.size} stair zones, $withTreads with counted tread lines, $linked linked to the storey above; step count and direction not read",
        )
        if (stairs.isEmpty()) {
            ask(Requirement.STAIR_ZONE, "Nie udało się wykryć biegu schodów na rzutach. Wskaż strefę schodów i kierunek wejścia.", null, "stairs")
        } else {
            ask(Requirement.STAIR_ZONE, "Liczba stopni i kierunek wejścia schodów nie wynikają z rzutu (są opisane tekstem). Podaj liczbę stopni i kierunek dla ${stairs.size} wykrytych stref schodów.", null, "stairs")
        }
        val doors = candidate.openings.count { it.type == OpeningType.DOOR && !it.exterior }
        status(Requirement.DOOR_TOPOLOGY, if (doors > 0) RequirementState.PARTIAL else RequirementState.MISSING, FactFidelity.TRACE_UNCERTAIN, "$doors interior door-width gaps in wall lines")

        // Recognisability.
        val garage = candidate.rooms.any { it.name.lowercase().startsWith("gara") }
        status(Requirement.GARAGE_RELATION, if (garage) RequirementState.PARTIAL else RequirementState.NOT_APPLICABLE, null, if (garage) "garage room placed; its roof is a flat assumption where outside the roof outline" else "no garage")
        if (roof?.secondaryMasses?.isNotEmpty() == true) ask(Requirement.GARAGE_RELATION, "Część rzutu parteru (${roof.secondaryMasses.joinToString { "%.1f m²".format(java.util.Locale.ROOT, it.outline.area) }}) leży poza obrysem dachu głównego. Przyjęto dach płaski na poziomie stropu; potwierdź rodzaj i wysokość dachu nad tą częścią.", "dach płaski", "roof.secondary")
        status(Requirement.ROOF_OUTLINE, if (roof != null) (if (roof.fidelity == FactFidelity.SOURCE_DERIVED) RequirementState.SATISFIED else RequirementState.PARTIAL) else RequirementState.MISSING, roof?.fidelity, roof?.note ?: "no roof")
        if (roof != null && roof.family == RoofFamily.GABLE && roof.fidelity == FactFidelity.DISPLAY_ASSUMPTION) ask(Requirement.ROOF_OUTLINE, "Kierunek kalenicy nie wynika z żadnej podanej liczby. Potwierdź, wzdłuż której osi biegnie kalenica.", roof.note, "roof.ridge")
        status(Requirement.MASSING_STEPS, if (candidate.floors.any { (it.footprint?.vertices?.size ?: 0) > 4 }) RequirementState.SATISFIED else RequirementState.PARTIAL, FactFidelity.SOURCE_TRACED, "footprint corners: ${candidate.floors.joinToString { "${it.name} ${it.footprint?.vertices?.size ?: 0}" }}")
        status(Requirement.SIGNATURE_FEATURES, RequirementState.MISSING, FactFidelity.MISSING, "facade bands, frames and recesses are read from elevations; this stage does not read elevations")
        ask(Requirement.SIGNATURE_FEATURES, "Cechy rozpoznawcze elewacji (pasy, ramy, wnęki, balkony) nie są odczytywane z elewacji przez ten etap. Wskaż, które z nich mają być modelowane.", null, "elevations")

        return Output(GapAnalysis(statuses), questions)
    }
}
