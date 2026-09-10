package com.buildplan.app.analyzer.verification

import com.buildplan.app.analyzer.asset.AssetRole
import com.buildplan.app.analyzer.candidate.AppearanceKind
import com.buildplan.app.analyzer.candidate.FloorCandidate
import com.buildplan.app.analyzer.candidate.OpeningCandidate
import com.buildplan.app.analyzer.candidate.OpeningType
import com.buildplan.app.analyzer.candidate.PolishText
import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.candidate.RoofFamily
import com.buildplan.app.analyzer.candidate.VisualConflictKind
import com.buildplan.app.analyzer.candidate.VisualConflictSeverity
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.quantity.QuantityTakeoffEngine
import com.buildplan.app.analyzer.service.AmbiguityKind
import com.buildplan.app.analyzer.service.ProjectAnalysisReport
import com.buildplan.app.analyzer.validate.Requirement
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns everything a report leaves open into the smallest set of decisions
 * a person has to make, ordered by how much each one settles.
 *
 * The rule this object exists to hold: **verify roots, not rows.** A report
 * on a real house carries a few hundred quantity rows that rest on an
 * assumption, and they rest on a few dozen facts — a room's identity, a
 * family of opening heights, a storey level. Each of those is one question
 * here, and the rows it settles are listed on it so the screen can say why
 * the question is worth a minute.
 *
 * Source-neutral: nothing here knows a project, a room name or an expected
 * value. Every question is built from what the report says it could not
 * settle, and every count is a count of rows in the report's own ledger.
 *
 * Priority accounts, in order, for: geometry that blocks the model, room
 * identity and topology, how many rows an answer resolves, whether the
 * answer changes what the model looks like, and whether a picture disagrees.
 * Creation order plays no part.
 */
object RootQuestions {

    fun of(report: ProjectAnalysisReport): List<RootQuestion> {
        val candidate = report.candidate ?: return emptyList()
        val graph = DependencyGraph.of(report)
        val out = mutableListOf<RootQuestion>()
        val b = Builder(report, candidate, graph)

        out += b.roomIdentity()
        out += b.roomGeometry()
        out += b.unclaimedRegions()
        out += b.unplacedRooms()
        out += b.openPlanBoundaries()
        out += b.openingHeights()
        out += b.levels()
        out += b.ridgeDirection()
        out += b.secondaryMasses()
        out += b.stairs()
        out += b.facadeScope()
        out += b.visualConflicts()
        out += b.appearance()
        out += b.signatureFeatures()
        out += b.dimensionCheck()

        return out
            .distinctBy { it.id }
            .sortedWith(compareBy<RootQuestion> { it.tier.ordinal }.thenByDescending { it.priorityScore }.thenBy { it.id })
    }

    /** The burden of a report: rows versus decisions, and what each decision buys. */
    fun burden(report: ProjectAnalysisReport, questions: List<RootQuestion> = of(report)): VerificationBurden = VerificationBurden(
        rawQuantities = report.quantityVerification.size,
        rawQuantitiesNeedingConfirmation = report.unsafeForCosting.size,
        rawAnalyzerQuestions = report.questions.size,
        rawAmbiguities = report.ambiguities.size,
        rootDecisions = questions.size,
        required = questions.count { it.tier == PriorityTier.REQUIRED },
        highImpact = questions.count { it.tier == PriorityTier.HIGH_IMPACT },
        recommended = questions.count { it.tier == PriorityTier.RECOMMENDED },
        optional = questions.count { it.tier == PriorityTier.OPTIONAL },
        resolvedPerRoot = questions.associate { it.id to it.affectedCount },
    )

    // ---------------------------------------------------------------- scoring

    /** Weights of the priority score, stated once so the order is explainable. */
    private object Weight {
        const val BLOCKING = 100.0
        const val IDENTITY = 60.0
        const val PER_ROW = 1.0
        const val VISIBLE_3D = 8.0
        const val CONFLICT_HIGH = 20.0
        const val CONFLICT_MEDIUM = 10.0
        const val CONFLICT_LOW = 3.0
        const val COSMETIC = -5.0
    }

    private class Builder(
        val report: ProjectAnalysisReport,
        val candidate: ProjectAnalysisCandidate,
        val graph: DependencyGraph,
    ) {
        private val floorsById = candidate.floors.associateBy { it.id }
        private val planUrlByFloor: Map<String, String?> = candidate.floors.associate { it.id to it.planAssetUrl }
        private val sectionUrl: String? = report.source?.assets?.firstWithRole(AssetRole.SECTION)?.url

        fun roomIdentity(): List<RootQuestion> = report.ambiguities.filter { it.kind == AmbiguityKind.ROOM_IDENTITY }.map { amb ->
            val room = candidate.room(amb.subjectId)
            val affected = graph.dependentsOf("room:${amb.subjectId}:identity")
            RootQuestion(
                id = "q:room-identity:${amb.subjectId}",
                kind = RootQuestionKind.ROOM_IDENTITY,
                tier = PriorityTier.REQUIRED,
                input = QuestionInput.CHOICE,
                subjectIds = listOf(amb.subjectId),
                subjectLabel = amb.subjectLabel,
                floorId = room?.floorId,
                text = amb.question,
                why = "Nazwa pomieszczenia decyduje o tym, do czego zostanie przypisanych jego ${affected.size} wielkości przedmiaru i późniejsze koszty. Powierzchnia nie rozróżnia tych wierszy.",
                currentAssumption = amb.alternatives.firstOrNull { it.id == amb.currentChoiceId }?.label,
                assumedValue = null,
                unit = null,
                options = amb.alternatives.map { QuestionOption(it.id, it.label, it.evidence, isCurrent = it.id == amb.currentChoiceId) },
                affectedQuantityKeys = affected,
                evidence = roomEvidence(room?.floorId, room?.polygon?.vertices, "obszar odrysowany z rzutu; sama powierzchnia nie rozróżnia wierszy tabeli", room?.matchConfidence),
                priorityScore = Weight.IDENTITY + Weight.VISIBLE_3D + affected.size * Weight.PER_ROW,
            )
        }

        fun roomGeometry(): List<RootQuestion> = candidate.rooms.filter { it.polygon == null }.map { room ->
            val affected = graph.dependentsOf("room:${room.id}:identity")
            RootQuestion(
                id = "q:room-geometry:${room.id}",
                kind = RootQuestionKind.ROOM_GEOMETRY,
                tier = PriorityTier.HIGH_IMPACT,
                input = QuestionInput.CONFIRM,
                subjectIds = listOf(room.id),
                subjectLabel = room.name,
                floorId = room.floorId,
                text = "Obrys pomieszczenia „${room.name}” nie został potwierdzony jako zamknięty. Sufit, kubatura i lica ścian pozostają nieustalone; narzędzie do poprawy obrysu pojawi się w późniejszym etapie.",
                why = "Bez obrysu ${affected.size} wielkości tego pomieszczenia nie da się policzyć. Tu można to tylko odłożyć — nie wolno podstawić kształtu.",
                currentAssumption = null,
                assumedValue = null,
                unit = null,
                options = emptyList(),
                affectedQuantityKeys = affected,
                evidence = roomEvidence(room.floorId, null, "obrysu nie udało się potwierdzić jako zamkniętego pierścienia", room.matchConfidence),
                priorityScore = Weight.BLOCKING + affected.size * Weight.PER_ROW,
                recomputes = false,
            )
        }

        fun unclaimedRegions(): List<RootQuestion> = candidate.floors.flatMap { floor ->
            val unmatchedRows = unmatchedPublishedRooms(floor)
            floor.unmatchedRegions.map { region ->
                val options = unmatchedRows.map { row ->
                    QuestionOption(
                        id = "row:${row.rowIndex}",
                        label = "${row.ordinal?.let { "$it. " } ?: ""}${row.name}",
                        evidence = "wiersz ${row.rowIndex + 1} tabeli, ${fmt(row.areaM2)} m² podane; obszar ma ${fmt(region.areaM2)} m²",
                    )
                } + QuestionOption("not-a-room", "To nie jest osobne pomieszczenie", "obszar zostaje bez nazwy; nie wchodzi do sum kondygnacji")
                val small = region.areaM2 < SMALL_REGION_M2
                val affected = listOf("floor:${floor.id}:roomFloorAreaSum", "project:floorsAndStairs").filter { k -> report.quantityVerification.any { it.key == k } }
                val choice = options.size >= 2
                RootQuestion(
                    id = "q:region:${region.id}",
                    kind = RootQuestionKind.ROOM_UNCLAIMED,
                    tier = if (small) PriorityTier.OPTIONAL else PriorityTier.RECOMMENDED,
                    input = if (choice) QuestionInput.CHOICE else QuestionInput.CONFIRM,
                    subjectIds = listOf(region.id),
                    subjectLabel = "Obszar ${fmt(region.areaM2)} m² na kondygnacji ${floor.name}",
                    floorId = floor.id,
                    text = if (choice) {
                        "Obszar ${fmt(region.areaM2)} m² na kondygnacji ${floor.name} nie pasuje do żadnego wiersza tabeli. Które pomieszczenie tam jest?"
                    } else {
                        "Obszar ${fmt(region.areaM2)} m² na kondygnacji ${floor.name} nie pasuje do żadnego wiersza tabeli, a wszystkie wiersze są już przypisane. Potwierdź, że to nie jest osobne pomieszczenie."
                    },
                    why = if (unmatchedRows.isEmpty()) "Nienazwany obszar nie wchodzi do sum kondygnacji; potwierdzenie zamyka pytanie bez zmiany liczb." else "Nazwany obszar staje się pomieszczeniem z własną podłogą i sufitem; ${unmatchedRows.size} wierszy tabeli czeka na miejsce.",
                    currentAssumption = "obszar bez pomieszczenia",
                    assumedValue = null,
                    unit = null,
                    options = if (choice) options else emptyList(),
                    affectedQuantityKeys = affected,
                    evidence = roomEvidence(floor.id, region.polygon.vertices, "obszar ${fmt(region.areaM2)} m2 zamknięty ścianami, bez wiersza w tabeli", FactFidelity.TRACE_UNCERTAIN),
                    priorityScore = (if (small) Weight.COSMETIC else 20.0) + affected.size * Weight.PER_ROW + region.areaM2,
                )
            }
        }

        fun unplacedRooms(): List<RootQuestion> = candidate.floors.flatMap { floor ->
            val regions = floor.unmatchedRegions
            unmatchedPublishedRooms(floor).filter { row ->
                // Rows a region could still take are asked about through that region.
                regions.none { it.areaM2 >= row.areaM2 * PLACEABLE_SHARE }
            }.map { row ->
                RootQuestion(
                    id = "q:room-unplaced:${floor.id}:${row.rowIndex}",
                    kind = RootQuestionKind.ROOM_UNPLACED,
                    tier = PriorityTier.OPTIONAL,
                    input = QuestionInput.CONFIRM,
                    subjectIds = emptyList(),
                    subjectLabel = "${row.ordinal?.let { "$it. " } ?: ""}${row.name}",
                    floorId = floor.id,
                    text = "Pomieszczenia „${row.ordinal?.let { "$it. " } ?: ""}${row.name}” (${fmt(row.areaM2)} m²) nie udało się umieścić na rzucie kondygnacji ${floor.name} i żaden wolny obszar nie jest dość duży. Wskazanie go na rzucie będzie możliwe w późniejszym etapie.",
                    why = "Pomieszczenie bez miejsca nie ma podłogi, ścian ani sufitu w przedmiarze. Tu można je tylko odnotować.",
                    currentAssumption = "brak pomieszczenia w modelu",
                    assumedValue = null,
                    unit = null,
                    options = emptyList(),
                    affectedQuantityKeys = emptyList(),
                    evidence = QuestionEvidence(planUrlByFloor[floor.id], null, null, "wiersz ${row.rowIndex + 1} tabeli kondygnacji ${floor.name} bez obszaru na rzucie", FactFidelity.MISSING, "room matching by published area"),
                    priorityScore = Weight.COSMETIC + row.areaM2,
                    recomputes = false,
                )
            }
        }

        fun openPlanBoundaries(): List<RootQuestion> = report.questions
            .filter { it.requirement == Requirement.ROOM_TOPOLOGY && it.currentAssumption != null && (it.subject?.contains(",") == true) }
            .map { q ->
                val ids = q.subject.orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }
                val rooms = ids.mapNotNull { candidate.room(it) }
                val affected = graph.dependentsOf(ids.map { "room:$it:boundary" })
                val floorId = rooms.firstOrNull()?.floorId
                RootQuestion(
                    id = "q:open-plan:${ids.joinToString("+")}",
                    kind = RootQuestionKind.OPEN_PLAN_BOUNDARY,
                    tier = PriorityTier.HIGH_IMPACT,
                    input = QuestionInput.CONFIRM,
                    subjectIds = ids,
                    subjectLabel = rooms.joinToString(" / ") { it.name },
                    floorId = floorId,
                    text = q.text,
                    why = "Granica przyjęta proporcjonalnie do podanych powierzchni rozdziela ${affected.size} wielkości między ${rooms.size} pomieszczenia. Potwierdzenie zamyka podział; inny przebieg granicy wymaga narzędzia z późniejszego etapu.",
                    currentAssumption = q.currentAssumption,
                    assumedValue = null,
                    unit = null,
                    options = emptyList(),
                    affectedQuantityKeys = affected,
                    evidence = roomEvidence(floorId, rooms.flatMap { it.polygon?.vertices.orEmpty() }.ifEmpty { null }, "rzut nie rysuje ściany między tymi pomieszczeniami; granicę przyjęto proporcjonalnie do podanych powierzchni", FactFidelity.DISPLAY_ASSUMPTION),
                    priorityScore = Weight.IDENTITY / 2 + affected.size * Weight.PER_ROW,
                )
            }

        fun openingHeights(): List<RootQuestion> {
            val families = candidate.openings
                .filter { it.type != OpeningType.PASSAGE && it.height.value == null }
                .groupBy { Triple(it.floorId, it.type, widthBucket(it.width.value)) }
            return families.map { (key, members) ->
                val (floorId, type, bucket) = key
                val exterior = members.any { it.exterior }
                val ids = members.map { it.id }
                val affected = graph.dependentsOf(ids.map { "opening:$it:height" })
                val floor = floorsById[floorId]
                val assumed = QuantityTakeoffEngine.DEFAULT_ASSUMED_OPENING_HEIGHTS[type]
                val width = bucket / 100.0
                val tier = when {
                    exterior && (members.size >= 3 || affected.size >= 6) -> PriorityTier.HIGH_IMPACT
                    exterior -> PriorityTier.RECOMMENDED
                    affected.size >= 8 -> PriorityTier.RECOMMENDED
                    else -> PriorityTier.OPTIONAL
                }
                val one = members.size == 1
                val text = if (one) {
                    "${typeNamePl(type, 1).replaceFirstChar { it.uppercase() }} o szerokości ${fmt(width)} m na kondygnacji ${floor?.name ?: floorId}: podaj wysokość w metrach albo potwierdź założenie ${fmt(assumed ?: 0.0)} m."
                } else {
                    "${members.size} ${typeNamePl(type, members.size)} o szerokości ${fmt(width)} m na kondygnacji ${floor?.name ?: floorId} wyglądają na ten sam typ. Podaj wysokość w metrach dla wszystkich albo potwierdź założenie ${fmt(assumed ?: 0.0)} m."
                }
                RootQuestion(
                    id = "q:opening-height:$floorId:${type.name.lowercase()}:$bucket",
                    kind = RootQuestionKind.OPENING_HEIGHT,
                    tier = tier,
                    input = QuestionInput.LENGTH,
                    subjectIds = ids,
                    subjectLabel = "${members.size} × ${typeNamePl(type, members.size)} ${fmt(width)} m",
                    floorId = floorId,
                    text = text,
                    why = if (exterior) {
                        "Wysokość wchodzi do powierzchni stolarki, odliczeń od ścian ${linkedRoomNames(members)} i elewacji netto — razem ${affected.size} wielkości. Wykaz stolarki jest na rzucie tekstem zbyt małym do odczytu."
                    } else {
                        "Wysokość wchodzi do odliczeń od lic ścian ${linkedRoomNames(members)} — ${affected.size} wielkości. Wykaz stolarki jest na rzucie tekstem zbyt małym do odczytu."
                    },
                    currentAssumption = assumed?.let { "${fmt(it)} m (założenie wg typu)" },
                    assumedValue = assumed,
                    unit = MeasureUnit.METER,
                    options = emptyList(),
                    affectedQuantityKeys = affected,
                    evidence = openingEvidence(members.first(), floor),
                    priorityScore = (if (exterior) 30.0 else 10.0) + affected.size * Weight.PER_ROW + members.size * 2 + Weight.VISIBLE_3D,
                    groupMemberIds = ids,
                )
            }
        }

        fun levels(): List<RootQuestion> {
            val l = candidate.levels
            val out = mutableListOf<RootQuestion>()
            fun level(name: String, m: Measured, tier: PriorityTier, subjectLabel: String, text: String, why: String, score: Double) {
                if (m.fidelity != FactFidelity.DISPLAY_ASSUMPTION || m.value == null) return
                val affected = graph.dependentsOf("level:$name")
                out += RootQuestion(
                    id = "q:level:$name",
                    kind = RootQuestionKind.LEVEL_ASSUMPTION,
                    tier = tier,
                    input = QuestionInput.LENGTH,
                    subjectIds = emptyList(),
                    subjectLabel = subjectLabel,
                    floorId = null,
                    text = text,
                    why = "$why Razem ${affected.size} wielkości.",
                    currentAssumption = "${fmt(m.value)} m (założenie)",
                    assumedValue = m.value,
                    unit = MeasureUnit.METER,
                    options = emptyList(),
                    affectedQuantityKeys = affected,
                    evidence = QuestionEvidence(sectionUrl, null, null, "rzędna nieodczytana z przekroju; przyjęta, żeby domknąć łańcuch pionowy", m.fidelity, m.provenance.method),
                    priorityScore = score + affected.size * Weight.PER_ROW,
                )
            }
            level(
                "upperFloor", l.upperFloor, PriorityTier.HIGH_IMPACT, "Rzędna stropu nad parterem",
                "Przekrój podaje rzędną stropu nad parterem, ale tekst jest zbyt mały do odczytu. Podaj ją w metrach względem poziomu parteru (±0,00) albo potwierdź założenie ${fmt(l.upperFloor.value ?: 0.0)} m.",
                "Rzędna stropu ustala wysokość parteru w świetle, a więc lica ścian, kubatury i obwiednię całego parteru.",
                40.0 + Weight.VISIBLE_3D,
            )
            level(
                "slab", l.upperSlabThickness, PriorityTier.HIGH_IMPACT, "Grubość stropu nad parterem",
                "Przekrój wymiaruje strop nad parterem, ale tekst jest zbyt mały do odczytu. Podaj grubość stropu w metrach albo potwierdź założenie ${fmt(l.upperSlabThickness.value ?: 0.0)} m.",
                "Grubość stropu razem z rzędną stropu daje wysokość parteru w świetle.",
                30.0,
            )
            level(
                "terrain", l.terrain, PriorityTier.RECOMMENDED, "Rzędna terenu",
                "Przekrój podaje rzędną terenu przy wejściu, ale tekst jest zbyt mały do odczytu. Podaj ją w metrach względem poziomu parteru (na przykład −0,30) albo potwierdź założenie ${fmt(l.terrain.value ?: 0.0)} m.",
                "Wysokość budynku jest podana od terenu, więc rzędna terenu przesuwa kalenicę, okap i strop względem parteru.",
                20.0 + Weight.VISIBLE_3D,
            )
            level(
                "atticCeiling", l.atticFlatCeilingHeight, PriorityTier.RECOMMENDED, "Wysokość sufitu poddasza",
                "Przekrój wymiaruje płaski sufit poddasza, ale tekst jest zbyt mały do odczytu. Podaj wysokość sufitu nad podłogą poddasza w metrach albo potwierdź założenie ${fmt(l.atticFlatCeilingHeight.value ?: 0.0)} m.",
                "Poziom sufitu dzieli sufit poddasza na część płaską i skosy, i ogranicza kubaturę.",
                20.0,
            )
            return out
        }

        fun ridgeDirection(): List<RootQuestion> {
            val roof = candidate.roof ?: return emptyList()
            if (roof.family != RoofFamily.GABLE || roof.fidelity != FactFidelity.DISPLAY_ASSUMPTION) return emptyList()
            val alongZ = roof.note.contains("ridge along Z")
            val affected = graph.dependentsOf("roof:ridge")
            val options = listOf(
                QuestionOption("ridge-z", "Kalenica wzdłuż osi północ–południe (pionowo na rzucie)", "szczyty na północnym i południowym końcu obrysu", isCurrent = alongZ),
                QuestionOption("ridge-x", "Kalenica wzdłuż osi wschód–zachód (poziomo na rzucie)", "szczyty na wschodnim i zachodnim końcu obrysu", isCurrent = !alongZ),
            )
            return listOf(
                RootQuestion(
                    id = "q:roof:ridge",
                    kind = RootQuestionKind.ROOF_RIDGE_DIRECTION,
                    tier = PriorityTier.HIGH_IMPACT,
                    input = QuestionInput.CHOICE,
                    subjectIds = listOf("roof"),
                    subjectLabel = "Dach",
                    floorId = null,
                    text = "Podana powierzchnia dachu nie rozstrzyga, wzdłuż której osi biegnie kalenica; przyjęto dłuższy bok. Potwierdź kierunek kalenicy.",
                    why = "Kierunek kalenicy zmienia połacie, szczyty, skosy poddasza i elewację — ${affected.size} wielkości i cały widok dachu.",
                    currentAssumption = options.first { it.isCurrent }.label,
                    assumedValue = null,
                    unit = null,
                    options = options,
                    affectedQuantityKeys = affected,
                    evidence = QuestionEvidence(planUrlByFloor[candidate.floors.maxByOrNull { it.order }?.id], null, null, "podana powierzchnia dachu pasuje do obu kierunków kalenicy; przyjęto dłuższy bok", roof.fidelity, roof.totalArea.provenance.method),
                    priorityScore = 50.0 + Weight.VISIBLE_3D * 2 + affected.size * Weight.PER_ROW,
                )
            )
        }

        fun secondaryMasses(): List<RootQuestion> = candidate.roof?.secondaryMasses.orEmpty().map { mass ->
            val affected = graph.dependentsOf("roof:mass:${mass.id}")
            val large = mass.outline.area >= 10.0
            RootQuestion(
                id = "q:roof-mass:${mass.id}",
                kind = RootQuestionKind.SECONDARY_MASS_ROOF,
                tier = if (large) PriorityTier.HIGH_IMPACT else PriorityTier.RECOMMENDED,
                input = QuestionInput.LENGTH,
                subjectIds = listOf(mass.id),
                subjectLabel = "Bryła poza dachem głównym, ${fmt(mass.outline.area, 1)} m²",
                floorId = null,
                text = "Część rzutu parteru (${fmt(mass.outline.area, 1)} m²) leży poza obrysem dachu głównego. Przyjęto dach płaski z górą na ${fmt(mass.topElevation.value ?: 0.0)} m. Podaj rzędną góry tego dachu w metrach albo potwierdź założenie.",
                why = "Rzędna góry decyduje o wysokości ścian tej bryły w zakresie elewacji i o tym, jak czyta się relacja bryły do domu. Razem ${affected.size} wielkości.",
                currentAssumption = "${fmt(mass.topElevation.value ?: 0.0)} m, dach płaski (założenie)",
                assumedValue = mass.topElevation.value,
                unit = MeasureUnit.METER,
                options = emptyList(),
                affectedQuantityKeys = affected,
                evidence = roomEvidence(candidate.floors.minByOrNull { it.order }?.id, mass.outline.vertices, "część rzutu parteru poza obrysem dachu głównego; przyjęto dach płaski", mass.fidelity),
                priorityScore = (if (large) 35.0 else 15.0) + Weight.VISIBLE_3D + affected.size * Weight.PER_ROW,
            )
        }

        fun stairs(): List<RootQuestion> {
            val out = mutableListOf<RootQuestion>()
            report.ambiguities.filter { it.kind == AmbiguityKind.STAIR_INTERPRETATION }.forEach { amb ->
                val stair = candidate.stairs.firstOrNull { it.id == amb.subjectId }
                out += RootQuestion(
                    id = "q:stair-interpretation:${amb.subjectId}",
                    kind = RootQuestionKind.STAIR_INTERPRETATION,
                    tier = PriorityTier.RECOMMENDED,
                    input = QuestionInput.CHOICE,
                    subjectIds = listOf(amb.subjectId),
                    subjectLabel = stair?.roomId?.let { candidate.room(it)?.name }?.let { "Linie w pomieszczeniu „$it”" } ?: "Strefa ${amb.subjectId}",
                    floorId = stair?.floorId,
                    text = amb.question,
                    why = "Bieg schodów zostaje w modelu jako element obiegu; półki nie. Liczby przedmiaru nie zmieniają się, zmienia się model.",
                    currentAssumption = amb.alternatives.firstOrNull { it.id == amb.currentChoiceId }?.label,
                    assumedValue = null,
                    unit = null,
                    options = amb.alternatives.map { QuestionOption(it.id, it.label, it.evidence, isCurrent = it.id == amb.currentChoiceId) },
                    affectedQuantityKeys = emptyList(),
                    evidence = roomEvidence(stair?.floorId, stair?.zone?.let { listOf(Pt(it.minX, it.minZ), Pt(it.maxX, it.maxZ)) }, "równo rozstawione linie bez potwierdzenia z sąsiedniej kondygnacji", stair?.fidelity),
                    priorityScore = 15.0 + Weight.VISIBLE_3D,
                )
            }
            candidate.stairs.filter { it.treadCount.value == null || it.treadCount.fidelity == FactFidelity.TRACE_UNCERTAIN }.forEach { stair ->
                out += RootQuestion(
                    id = "q:stair-detail:${stair.id}",
                    kind = RootQuestionKind.STAIR_DETAIL,
                    tier = PriorityTier.OPTIONAL,
                    input = QuestionInput.COUNT,
                    subjectIds = listOf(stair.id),
                    subjectLabel = stair.roomId?.let { candidate.room(it)?.name }?.let { "Schody w „$it”" } ?: "Schody ${stair.id}",
                    floorId = stair.floorId,
                    text = "Liczba stopni schodów w strefie ${fmt(stair.zone.width, 1)} × ${fmt(stair.zone.depth, 1)} m nie wynika z rzutu (jest opisana tekstem). Podaj liczbę stopni.",
                    why = "Liczba stopni nie wchodzi do żadnej wielkości tego etapu; jest zapisywana na później.",
                    currentAssumption = stair.treadCount.value?.let { "${it.roundToInt()} linii stopni odczytanych niepewnie" },
                    assumedValue = stair.treadCount.value,
                    unit = MeasureUnit.COUNT,
                    options = emptyList(),
                    affectedQuantityKeys = emptyList(),
                    evidence = roomEvidence(stair.floorId, listOf(Pt(stair.zone.minX, stair.zone.minZ), Pt(stair.zone.maxX, stair.zone.maxZ)), "strefa odrysowana z rzutu; liczba stopni nie wynika z rysunku", stair.fidelity),
                    priorityScore = Weight.COSMETIC,
                )
            }
            return out
        }

        fun facadeScope(): List<RootQuestion> = report.ambiguities.filter { it.kind == AmbiguityKind.FACADE_SCOPE }.map { amb ->
            RootQuestion(
                id = "q:facade-scope",
                kind = RootQuestionKind.FACADE_SCOPE,
                tier = PriorityTier.RECOMMENDED,
                input = QuestionInput.CHOICE,
                subjectIds = emptyList(),
                subjectLabel = amb.subjectLabel,
                floorId = null,
                text = amb.question,
                why = "Strona kosztów drukuje jedną liczbę pod elewacją i nie mówi, co obejmuje. Wybór zakresu ustala, którą powierzchnię porównać z tą liczbą.",
                currentAssumption = amb.alternatives.firstOrNull { it.id == amb.currentChoiceId }?.label,
                assumedValue = null,
                unit = null,
                options = amb.alternatives.map { QuestionOption(it.id, it.label, it.evidence, isCurrent = it.id == amb.currentChoiceId) },
                affectedQuantityKeys = graph.dependentsOf("facade:scope"),
                evidence = QuestionEvidence(report.source?.relatedPages?.firstOrNull()?.url, null, null, "podana liczba mieści się w tolerancji dla dwóch różnych zakresów obwiedni", FactFidelity.SOURCE_DERIVED, "facade scope enumeration"),
                priorityScore = 12.0,
            )
        }

        fun visualConflicts(): List<RootQuestion> = candidate.visual.conflicts.map { c ->
            val structural = c.kind in setOf(VisualConflictKind.OPENING_COUNT_CONFLICT, VisualConflictKind.MASSING_CONFLICT, VisualConflictKind.GARAGE_RELATION_CONFLICT, VisualConflictKind.ROOF_SILHOUETTE_CONFLICT, VisualConflictKind.OPENING_POSITION_CONFLICT)
            val tier = when {
                c.kind == VisualConflictKind.VISUAL_EVIDENCE_UNCORROBORATED -> PriorityTier.OPTIONAL
                structural && c.severity == VisualConflictSeverity.HIGH -> PriorityTier.HIGH_IMPACT
                structural -> PriorityTier.RECOMMENDED
                else -> PriorityTier.OPTIONAL
            }
            val conflictWeight = when (c.severity) {
                VisualConflictSeverity.HIGH -> Weight.CONFLICT_HIGH
                VisualConflictSeverity.MEDIUM -> Weight.CONFLICT_MEDIUM
                VisualConflictSeverity.LOW -> Weight.CONFLICT_LOW
            }
            val affected = graph.dependentsOf(c.subjectIds.map { "opening:$it:height" } + listOf("roof:ridge").filter { c.subjectIds.contains("roof") })
            val observation = candidate.visual.asset(c.assetUrl)?.observations?.getOrNull(c.observationIndex)
            // The conflict's own `impact`, `candidateReading` and `sourceReading` are the
            // analyzer's diagnostic English, kept for the record and the log. What a person reads
            // is written here, in their language, from the conflict's kind — the same split the
            // facade-scope question already makes between an engine's vocabulary and a question.
            val fromPlan = "odczyt z rzutu: ${PolishText.exteriorOpenings(c.subjectIds.size)} na tej elewacji"
            RootQuestion(
                id = "q:visual:${c.id}",
                kind = RootQuestionKind.VISUAL_CONFLICT,
                tier = tier,
                input = QuestionInput.CHOICE,
                subjectIds = c.subjectIds,
                subjectLabel = facadeNamePl(c.facade) ?: "Elewacja",
                floorId = null,
                text = "${facadeNamePl(c.facade)?.let { "$it: " } ?: ""}${c.recommendedAction}",
                why = conflictWhyPl(c.kind, affected.size),
                currentAssumption = "odczyt z rzutu bez zmian",
                assumedValue = null,
                unit = null,
                options = listOf(
                    QuestionOption("candidate", "Rzut jest właściwy — odrzuć obserwację z obrazu", fromPlan, isCurrent = true),
                    QuestionOption("source", "Obraz ma rację — odnotuj do późniejszego etapu", "obraz nie zmienia dziś geometrii; odpowiedź zostaje zapisana"),
                ),
                affectedQuantityKeys = affected,
                evidence = QuestionEvidence(c.assetUrl, null, observation?.bounds, conflictReadingPl(c.kind, c.subjectIds.size), observation?.fidelity ?: FactFidelity.TRACE_UNCERTAIN, observation?.method ?: "visual observation"),
                priorityScore = conflictWeight + (if (structural) Weight.VISIBLE_3D else 0.0) + affected.size * Weight.PER_ROW + c.confidence * 10,
                recomputes = false,
            )
        }

        fun appearance(): List<RootQuestion> = candidate.visual.appearance.map { a ->
            val strong = a.confidence >= STRONG_APPEARANCE && a.kind in setOf(AppearanceKind.FACADE_FRAME, AppearanceKind.HORIZONTAL_BAND, AppearanceKind.BALCONY, AppearanceKind.RAILING, AppearanceKind.GARAGE_PORTAL, AppearanceKind.ROOF_STACK)
            val cover = a.kind == AppearanceKind.ROOF_COVER_HINT
            val ref = a.sourceEvidence.firstOrNull()
            val observation = ref?.let { candidate.visual.asset(it.assetUrl)?.observations?.getOrNull(it.observationIndex) }
            RootQuestion(
                id = "q:appearance:${a.featureId}",
                kind = if (cover) RootQuestionKind.ROOF_COVER else RootQuestionKind.APPEARANCE_FEATURE,
                tier = if (strong) PriorityTier.RECOMMENDED else PriorityTier.OPTIONAL,
                input = QuestionInput.CHOICE,
                subjectIds = listOfNotNull(a.affectedRegion.floorId) + (if (cover || a.kind == AppearanceKind.ROOF_STACK || a.kind == AppearanceKind.ROOFLIGHT) listOf("roof") else emptyList()),
                subjectLabel = appearanceNamePl(a.kind),
                floorId = a.affectedRegion.floorId,
                text = if (cover) {
                    "Na obrazach pokrycie dachu wygląda jak dachówka, ale strona nie podaje jego rodzaju. Czy pokazywać dach jako kryty dachówką?"
                } else {
                    "${facadeNamePl(a.affectedRegion.facade)?.let { "$it: " } ?: "Na obrazie: "}widać ${appearanceNamePl(a.kind).lowercase()} (${a.note}). Czy dodać ${appearanceAccusativePl(a.kind)} jako element prezentacyjny modelu?"
                },
                why = "Cecha prezentacyjna nie zmienia żadnej wielkości przedmiaru; zmienia rozpoznawalność modelu. Obraz podaje jej położenie jako proporcję elewacji, nie wymiar.",
                currentAssumption = "brak w modelu",
                assumedValue = null,
                unit = null,
                options = listOf(
                    QuestionOption("accept", if (cover) "Pokaż dachówkę" else "Dodaj jako element prezentacyjny", "pewność obrazu ${fmt(a.confidence, 2)}; ${a.fidelity}"),
                    QuestionOption("skip", "Pomiń", "model bez tej cechy", isCurrent = true),
                ),
                affectedQuantityKeys = emptyList(),
                evidence = QuestionEvidence(ref?.assetUrl, null, observation?.bounds, a.note, a.fidelity, observation?.method ?: "visual observation"),
                priorityScore = (if (strong) 8.0 else Weight.COSMETIC) + a.confidence * 5 + (if (cover) 0.0 else Weight.VISIBLE_3D / 2),
                recomputes = false,
            )
        }

        fun signatureFeatures(): List<RootQuestion> {
            if (candidate.visual.appearance.isNotEmpty()) return emptyList()
            val q = report.questions.firstOrNull { it.requirement == Requirement.SIGNATURE_FEATURES } ?: return emptyList()
            return listOf(
                RootQuestion(
                    id = "q:signature-features",
                    kind = RootQuestionKind.SIGNATURE_FEATURES,
                    tier = PriorityTier.OPTIONAL,
                    input = QuestionInput.CONFIRM,
                    subjectIds = emptyList(),
                    subjectLabel = "Cechy elewacji",
                    floorId = null,
                    text = q.text,
                    why = "Obrazy nie dały żadnej obserwacji cech elewacji, więc tylko osoba może je nazwać. Zapisane na późniejszy etap.",
                    currentAssumption = null,
                    assumedValue = null,
                    unit = null,
                    options = emptyList(),
                    affectedQuantityKeys = emptyList(),
                    evidence = QuestionEvidence(report.source?.assets?.firstWithRole(AssetRole.ELEVATION_FRONT)?.url, null, null, "obrazy nie dały żadnej obserwacji cechy elewacji", FactFidelity.MISSING, null),
                    priorityScore = Weight.COSMETIC,
                    recomputes = false,
                )
            )
        }

        fun dimensionCheck(): List<RootQuestion> = report.questions
            .filter { it.requirement == Requirement.DIMENSION_CHAINS || it.requirement == Requirement.FOOTPRINT_CALIBRATION }
            .map { q ->
                val calibration = q.requirement == Requirement.FOOTPRINT_CALIBRATION
                RootQuestion(
                    id = "q:dimension:${q.requirement.name.lowercase()}",
                    kind = RootQuestionKind.DIMENSION_CHECK,
                    tier = if (calibration) PriorityTier.REQUIRED else PriorityTier.OPTIONAL,
                    input = QuestionInput.LENGTH,
                    subjectIds = emptyList(),
                    subjectLabel = "Szerokość budynku",
                    floorId = null,
                    text = q.text,
                    why = if (calibration) "Bez kalibracji żadna liczba rzutu nie jest metrem. Ten etap zapisuje wartość; przeliczenie rzutu wymaga późniejszego narzędzia." else "Podana szerokość jest sprawdzeniem odrysu, nie jego podstawą; zapisywana obok obrysu.",
                    currentAssumption = q.currentAssumption,
                    assumedValue = null,
                    unit = MeasureUnit.METER,
                    options = emptyList(),
                    affectedQuantityKeys = emptyList(),
                    evidence = QuestionEvidence(planUrlByFloor[candidate.floors.minByOrNull { it.order }?.id], null, null, q.text, FactFidelity.MISSING, null),
                    priorityScore = if (calibration) Weight.BLOCKING else Weight.COSMETIC,
                    recomputes = false,
                )
            }

        // ------------------------------------------------------------- helpers

        private class UnmatchedRow(val rowIndex: Int, val ordinal: Int?, val name: String, val areaM2: Double)

        private fun unmatchedPublishedRooms(floor: FloorCandidate): List<UnmatchedRow> {
            val index = candidate.floors.indexOf(floor)
            val published = report.source?.floors?.getOrNull(index) ?: return emptyList()
            return published.rooms.mapIndexedNotNull { i, row ->
                val placed = floor.rooms.any { it.sourceOrdinal == row.ordinal && it.name == row.name }
                if (placed) null else UnmatchedRow(i, row.ordinal, row.name, row.floorArea.value ?: row.usableArea.value ?: 0.0)
            }
        }

        private fun roomEvidence(floorId: String?, points: List<Pt>?, reading: String, fidelity: FactFidelity?): QuestionEvidence {
            val floor = floorId?.let { floorsById[it] }
            val cal = floor?.calibration
            val crop = if (cal != null && !points.isNullOrEmpty()) {
                val px = points.map { cal.toPixels(it) }
                val margin = 0.6 * cal.pixelsPerMeter.requireValue()
                val x0 = (px.minOf { it.x } - margin).roundToInt()
                val y0 = (px.minOf { it.z } - margin).roundToInt()
                val x1 = (px.maxOf { it.x } + margin).roundToInt()
                val y1 = (px.maxOf { it.z } + margin).roundToInt()
                intArrayOf(max(0, x0), max(0, y0), max(1, x1 - max(0, x0)), max(1, y1 - max(0, y0)))
            } else null
            return QuestionEvidence(planUrlByFloor[floorId], crop, null, reading, fidelity, "traced from the storey plan")
        }

        private fun openingEvidence(o: OpeningCandidate, floor: FloorCandidate?): QuestionEvidence {
            val wall = candidate.wall(o.wallId)
            val d = o.distanceAlongWall.value
            val w = o.width.value
            if (wall == null || d == null || w == null) return QuestionEvidence(floor?.planAssetUrl, null, null, "szerokość odrysowana z rzutu, wysokość nieodczytana", o.height.fidelity, o.height.provenance.method)
            val a = wall.centreline.a
            val bpt = wall.centreline.b
            val len = a.distanceTo(bpt)
            if (len < 1e-9) return QuestionEvidence(floor?.planAssetUrl, null, null, "szerokość odrysowana z rzutu, wysokość nieodczytana", o.height.fidelity, o.height.provenance.method)
            val t0 = (d / len).coerceIn(0.0, 1.0)
            val t1 = ((d + w) / len).coerceIn(0.0, 1.0)
            val p0 = Pt(a.x + (bpt.x - a.x) * t0, a.z + (bpt.z - a.z) * t0)
            val p1 = Pt(a.x + (bpt.x - a.x) * t1, a.z + (bpt.z - a.z) * t1)
            return roomEvidence(o.floorId, listOf(p0, p1), "otwór ${fmt(w)} m odrysowany jako przerwa w ścianie; wysokość jest na rzucie tekstem poniżej progu czytelności", o.height.fidelity)
        }

        private fun linkedRoomNames(members: List<OpeningCandidate>): String {
            val names = members.flatMap { it.linkedRoomIds }.distinct().mapNotNull { candidate.room(it)?.name }.distinct()
            return if (names.isEmpty()) "sąsiednich pomieszczeń" else names.joinToString(", ") { "„$it”" }
        }
    }

    private const val SMALL_REGION_M2 = 1.5
    private const val PLACEABLE_SHARE = 0.5
    private const val STRONG_APPEARANCE = 0.6

    /** Width rounded to 5 cm, so two windows drawn a pixel apart are one family. */
    fun widthBucket(width: Double?): Int = ((width ?: 0.0) * 20).roundToInt() * 5

    fun typeNamePl(type: OpeningType, count: Int): String = when (type) {
        OpeningType.DOOR -> if (count == 1) "drzwi" else "drzwi"
        OpeningType.WINDOW -> when {
            count == 1 -> "okno"
            count in 2..4 -> "okna"
            else -> "okien"
        }
        OpeningType.GARAGE_GATE -> if (count == 1) "brama garażowa" else "bramy garażowe"
        OpeningType.PASSAGE -> if (count == 1) "przejście" else "przejścia"
        OpeningType.ROOFLIGHT -> if (count == 1) "okno połaciowe" else "okna połaciowe"
        OpeningType.UNKNOWN -> if (count == 1) "otwór" else "otwory"
    }

    /** Why a picture disagreeing with the trace is worth a person's minute, in their language. */
    private fun conflictWhyPl(kind: VisualConflictKind, affected: Int): String = when (kind) {
        VisualConflictKind.OPENING_COUNT_CONFLICT ->
            "Otwory, których rzut nie odczytał, nie wchodzą do stolarki ani do odliczeń od elewacji; przerwy, które otworami nie są, zaniżają mur. Dotyczy $affected wielkości."
        VisualConflictKind.OPENING_POSITION_CONFLICT ->
            "Położenie otworu decyduje o tym, na której ścianie zostanie odliczony. Dotyczy $affected wielkości."
        VisualConflictKind.ROOF_SILHOUETTE_CONFLICT ->
            "Rodzaj dachu zmienia połacie, kalenice, skosy poddasza i całą elewację szczytową. Dotyczy $affected wielkości."
        VisualConflictKind.MASSING_CONFLICT ->
            "Brakująca bryła zmienia obrys, dach nad nią i powierzchnię elewacji."
        VisualConflictKind.GARAGE_RELATION_CONFLICT ->
            "Garaż po innej stronie zmienia bryłę i to, czy jego ściany wchodzą do ocieplanej elewacji."
        VisualConflictKind.FACADE_FEATURE_MISSING ->
            "Cecha elewacji nie zmienia przedmiaru; zmienia to, czy model wygląda jak ten dom."
        VisualConflictKind.VISUAL_EVIDENCE_UNCORROBORATED ->
            "Wizualizacja jest najsłabszym świadectwem i sama niczego nie ustala. To tylko odnotowanie różnicy."
    }

    /** What the picture and the plan each said, in the reader's language. */
    private fun conflictReadingPl(kind: VisualConflictKind, subjects: Int): String = when (kind) {
        VisualConflictKind.OPENING_COUNT_CONFLICT ->
            "rzut daje ${PolishText.exteriorOpenings(subjects)} na tej elewacji; obraz pokazuje inną liczbę prostokątów wyglądających na otwory"
        VisualConflictKind.ROOF_SILHOUETTE_CONFLICT ->
            "linia dachu na elewacji czyta się inaczej niż rodzaj dachu przyjęty z rzutu i podanego kąta"
        VisualConflictKind.MASSING_CONFLICT ->
            "na elewacji stoi obok domu niższa bryła, której obrys parteru nie ma"
        VisualConflictKind.GARAGE_RELATION_CONFLICT ->
            "na elewacji widać bramę tam, gdzie rzut nie ma bramy"
        VisualConflictKind.OPENING_POSITION_CONFLICT ->
            "otwór na elewacji leży gdzie indziej niż odczytany z rzutu"
        VisualConflictKind.FACADE_FEATURE_MISSING ->
            "obraz pokazuje cechę elewacji, której model nie ma"
        VisualConflictKind.VISUAL_EVIDENCE_UNCORROBORATED ->
            "wizualizacja sugeruje coś, czego nie potwierdza ani rzut, ani elewacja"
    }

    fun facadeNamePl(side: com.buildplan.app.analyzer.candidate.FacadeSide?): String? = when (side) {
        com.buildplan.app.analyzer.candidate.FacadeSide.NORTH -> "Elewacja północna (góra rzutu)"
        com.buildplan.app.analyzer.candidate.FacadeSide.SOUTH -> "Elewacja południowa (dół rzutu)"
        com.buildplan.app.analyzer.candidate.FacadeSide.EAST -> "Elewacja wschodnia (prawa strona rzutu)"
        com.buildplan.app.analyzer.candidate.FacadeSide.WEST -> "Elewacja zachodnia (lewa strona rzutu)"
        null -> null
    }

    fun appearanceNamePl(kind: AppearanceKind): String = when (kind) {
        AppearanceKind.FACADE_FRAME -> "Rama wokół przeszklenia szczytu"
        AppearanceKind.HORIZONTAL_BAND -> "Pozioma opaska elewacji"
        AppearanceKind.BALCONY -> "Balkon"
        AppearanceKind.RAILING -> "Balustrada"
        AppearanceKind.EAVES_FASCIA -> "Pas okapowy"
        AppearanceKind.ROOF_STACK -> "Komin ponad dachem"
        AppearanceKind.ROOFLIGHT -> "Okno połaciowe"
        AppearanceKind.ROOF_COVER_HINT -> "Pokrycie dachu"
        AppearanceKind.GARAGE_PORTAL -> "Portal bramy garażowej"
        AppearanceKind.CLADDING -> "Okładzina elewacji"
    }

    private fun appearanceAccusativePl(kind: AppearanceKind): String = when (kind) {
        AppearanceKind.FACADE_FRAME -> "ramę"
        AppearanceKind.HORIZONTAL_BAND -> "opaskę"
        AppearanceKind.BALCONY -> "balkon"
        AppearanceKind.RAILING -> "balustradę"
        AppearanceKind.EAVES_FASCIA -> "pas okapowy"
        AppearanceKind.ROOF_STACK -> "komin"
        AppearanceKind.ROOFLIGHT -> "okno połaciowe"
        AppearanceKind.ROOF_COVER_HINT -> "pokrycie"
        AppearanceKind.GARAGE_PORTAL -> "portal"
        AppearanceKind.CLADDING -> "okładzinę"
    }

    fun fmt(v: Double, digits: Int = 2): String = String.format(Locale.ROOT, "%.${digits}f", v).let { s ->
        // Polish decimal comma in text a person reads; the number itself stays Locale.ROOT for determinism.
        s.replace('.', ',')
    }
}
