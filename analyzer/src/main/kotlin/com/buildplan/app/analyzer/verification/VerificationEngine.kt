package com.buildplan.app.analyzer.verification

import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.candidate.Pt3
import com.buildplan.app.analyzer.candidate.RingValidity
import com.buildplan.app.analyzer.candidate.RoofCandidate
import com.buildplan.app.analyzer.candidate.RoofFacetCandidate
import com.buildplan.app.analyzer.candidate.RoomCandidate
import com.buildplan.app.analyzer.candidate.RoomGeometryState
import com.buildplan.app.analyzer.candidate.Segment3
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.quantity.QuantityTakeoffEngine
import com.buildplan.app.analyzer.roof.RoofHeightField
import com.buildplan.app.analyzer.roof.RoofSolver
import com.buildplan.app.analyzer.service.ProjectAnalysisReport
import com.buildplan.app.analyzer.service.QuantityVerificationLedger
import com.buildplan.app.analyzer.service.VerificationState
import com.buildplan.app.analyzer.site.PublishedRoofFamily
import com.buildplan.app.analyzer.site.ScalarKey
import com.buildplan.app.analyzer.validate.CrossSourceValidator
import java.util.Locale

/**
 * `AnalysisCandidate + decisions → VerifiedCandidate`, as one pure function.
 *
 * Nothing here mutates the report. The decisions are folded into a copy of
 * the candidate — a renamed room, a supplied height, a moved level, a removed
 * stair — and the takeoff and the cross-source comparison are run again over
 * that copy, exactly as the analyzer ran them the first time. So a quantity a
 * person did not touch is recomputed from roots they did, not hand-edited,
 * and replaying the same log over the same report gives the same result.
 *
 * `USER_CONFIRMED` fidelity appears in the effective candidate exactly where
 * a decision put a value, and nowhere else.
 */
object VerificationEngine {

    fun verify(
        report: ProjectAnalysisReport,
        questions: List<RootQuestion>,
        decisions: List<VerificationDecision>,
    ): VerifiedCandidate {
        val original = report.candidate ?: throw IllegalArgumentException("a report without a candidate cannot be verified")
        val questionsById = questions.associateBy { it.id }
        val effectiveDecisions = effectiveDecisions(decisions)
        val applied = effectiveDecisions.values.filter { it.kind != DecisionKind.DEFER }

        // 1. The effective candidate: every applied decision folded in, in a fixed order.
        var candidate = original
        val changes = mutableListOf<String>()
        applied.sortedBy { order(questionsById[it.questionId]?.kind) }.forEach { decision ->
            val question = questionsById[decision.questionId] ?: return@forEach
            val (next, change) = apply(report, candidate, question, decision)
            candidate = next
            if (change != null) changes += change
        }

        // 2. Recompute what hangs off it.
        val roofField = candidate.roof?.let { RoofHeightField(it) }
        val quantities = QuantityTakeoffEngine(candidate, roofField).compute()
        val source = report.source
        val validations = if (source != null) CrossSourceValidator.validate(source, candidate, quantities) else report.validations

        // 3. The ledger, before and after, with lineage.
        val before = report.quantityVerification.associateBy { it.key }
        val afterEntries = QuantityVerificationLedger.of(candidate, quantities, validations)
        val graph = DependencyGraph.build(candidate, afterEntries.map { it.key }.toSet() + before.keys)
        val rootsByKey: Map<String, List<String>> = graph.flatMap { (root, keys) -> keys.map { it to root } }.groupBy({ it.first }, { it.second })
        val questionByRoot: Map<String, List<RootQuestion>> = questions.flatMap { q -> rootKeysOf(q).map { it to q } }.groupBy({ it.first }, { it.second })
        val directTargets: Map<String, VerificationDecision> = applied.flatMap { d -> d.targetKeys.map { it to d } }.toMap()
        val facadeScopeChoice = applied.firstOrNull { questionsById[it.questionId]?.kind == RootQuestionKind.FACADE_SCOPE }

        val verified = afterEntries.map { after ->
            val prior = before[after.key]
            val roots = rootsByKey[after.key].orEmpty()
            val rootQuestions = roots.flatMap { questionByRoot[it].orEmpty() }.distinctBy { it.id }
            val direct = directTargets[after.key]
            val touched = rootQuestions.filter { q -> effectiveDecisions[q.id]?.kind?.let { it != DecisionKind.DEFER } == true }
            val deferredRoots = rootQuestions.filter { q -> effectiveDecisions[q.id]?.kind == DecisionKind.DEFER }
            val openRoots = rootQuestions.filter { q -> effectiveDecisions[q.id] == null && q.recomputes }
            var measured = after.measured
            if (after.key == "project:facadeInsulation" && facadeScopeChoice != null) {
                val chosen = quantities.facadeScope.scopes.firstOrNull { it.first == facadeScopeChoice.optionId }
                if (chosen != null) {
                    measured = Measured(chosen.second, MeasureUnit.SQUARE_METER, FactFidelity.USER_CONFIRMED, Provenance(null, "decision:${facadeScopeChoice.id}", "facade scope chosen by the user: ${chosen.first}"))
                }
            }
            val state = when {
                direct != null && direct.kind == DecisionKind.REPLACE_VALUE -> VerifiedState.USER_OVERRIDDEN
                direct != null -> VerifiedState.USER_CONFIRMED
                after.key == "project:facadeInsulation" && facadeScopeChoice != null -> VerifiedState.USER_CONFIRMED
                touched.isNotEmpty() && measured.fidelity.isSettled() -> VerifiedState.DERIVED_FROM_USER_CONFIRMED
                measured.fidelity == FactFidelity.DISPLAY_ASSUMPTION && deferredRoots.isNotEmpty() && openRoots.isEmpty() -> VerifiedState.DEFERRED
                else -> stateOf(measured.fidelity)
            }
            val caveat = when (state) {
                VerifiedState.DERIVED_FROM_USER_CONFIRMED -> "przeliczone z potwierdzonych decyzji: ${touched.joinToString { it.id }}"
                VerifiedState.ASSUMPTION -> (openRoots + deferredRoots).takeIf { it.isNotEmpty() }?.let { "czeka na: ${it.joinToString { q -> q.id }}" } ?: after.caveat
                VerifiedState.DEFERRED -> "odłożone: ${deferredRoots.joinToString { it.id }}"
                else -> after.caveat
            }
            VerifiedQuantity(
                key = after.key,
                ownerId = after.ownerId,
                before = prior?.measured ?: after.measured,
                after = measured,
                state = state,
                rootQuestionIds = rootQuestions.map { it.id },
                decisionIds = (listOfNotNull(direct?.id) + touched.mapNotNull { effectiveDecisions[it.id]?.id }).distinct(),
                caveat = caveat,
            )
        }

        // 4. Summary and readiness.
        val resolved = questions.filter { effectiveDecisions[it.id]?.kind?.let { k -> k != DecisionKind.DEFER } == true }
        val deferred = questions.filter { effectiveDecisions[it.id]?.kind == DecisionKind.DEFER }
        val unresolved = questions.filter { it !in resolved }
        fun count(tier: PriorityTier) = questions.count { it.tier == tier }
        fun countResolved(tier: PriorityTier) = resolved.count { it.tier == tier }
        val summary = VerificationSummary(
            rootQuestions = questions.size,
            required = count(PriorityTier.REQUIRED),
            requiredResolved = countResolved(PriorityTier.REQUIRED),
            highImpact = count(PriorityTier.HIGH_IMPACT),
            highImpactResolved = countResolved(PriorityTier.HIGH_IMPACT),
            recommended = count(PriorityTier.RECOMMENDED),
            recommendedResolved = countResolved(PriorityTier.RECOMMENDED),
            optional = count(PriorityTier.OPTIONAL),
            optionalResolved = countResolved(PriorityTier.OPTIONAL),
            deferred = deferred.size,
            assumptionsConfirmed = applied.count { it.kind == DecisionKind.CONFIRM_ASSUMPTION || it.kind == DecisionKind.CONFIRM_VALUE },
            assumptionsReplaced = applied.count { it.kind == DecisionKind.REPLACE_VALUE && questionsById[it.questionId]?.assumedValue != null },
            userOverrides = applied.count { it.kind == DecisionKind.REPLACE_VALUE },
            observationsRejected = applied.count { it.kind == DecisionKind.REJECT_OBSERVATION },
            quantitiesTotal = verified.size,
            quantitiesUserConfirmed = verified.count { it.state == VerifiedState.USER_CONFIRMED || it.state == VerifiedState.USER_OVERRIDDEN },
            quantitiesDerivedFromUser = verified.count { it.state == VerifiedState.DERIVED_FROM_USER_CONFIRMED },
            quantitiesStillUnsafe = verified.count { !it.state.safeForCosting },
            quantityFamiliesAffected = verified.filter { it.decisionIds.isNotEmpty() }.map { it.key.substringBefore(':') }.distinct().sorted(),
            remainingMissing = verified.filter { it.after.fidelity == FactFidelity.MISSING }.map { it.key }.distinct() +
                applied.filter { it.kind == DecisionKind.CHOOSE_ALTERNATIVE && it.optionId == "source" && questionsById[it.questionId]?.kind == RootQuestionKind.VISUAL_CONFLICT }
                    .map { "visual:${it.questionId}" },
        )
        val readiness = when {
            unresolved.any { it.tier == PriorityTier.REQUIRED } -> VerificationReadiness.NEEDS_REQUIRED_INPUT
            unresolved.any { it.tier == PriorityTier.HIGH_IMPACT } -> VerificationReadiness.READY_FOR_OWNER_REVIEW
            else -> VerificationReadiness.READY_FOR_CANONICALIZATION_LATER
        }
        return VerifiedCandidate(
            source = report.identity,
            decisions = decisions,
            questions = questions,
            effectiveCandidate = candidate,
            effectiveQuantities = quantities,
            validations = validations,
            quantities = verified,
            summary = summary,
            unresolved = unresolved,
            deferred = deferred,
            readiness = readiness,
            changeSummary = changes.reversed(),
        )
    }

    /** The last decision per question wins; an earlier one is history. */
    fun effectiveDecisions(decisions: List<VerificationDecision>): Map<String, VerificationDecision> =
        decisions.groupBy { it.questionId }.mapValues { (_, list) -> list.last() }

    /** The root keys a question settles, for the lineage. */
    fun rootKeysOf(q: RootQuestion): List<String> = when (q.kind) {
        RootQuestionKind.ROOM_IDENTITY, RootQuestionKind.ROOM_GEOMETRY -> q.subjectIds.map { "room:$it:identity" }
        RootQuestionKind.OPEN_PLAN_BOUNDARY -> q.subjectIds.map { "room:$it:boundary" }
        RootQuestionKind.OPENING_HEIGHT -> q.groupMemberIds.map { "opening:$it:height" }
        RootQuestionKind.LEVEL_ASSUMPTION -> listOf("level:${q.id.substringAfterLast(':')}")
        RootQuestionKind.ROOF_RIDGE_DIRECTION -> listOf("roof:ridge")
        RootQuestionKind.SECONDARY_MASS_ROOF -> q.subjectIds.map { "roof:mass:$it" }
        RootQuestionKind.STAIR_INTERPRETATION, RootQuestionKind.STAIR_DETAIL -> q.subjectIds.map { "stair:$it" }
        RootQuestionKind.FACADE_SCOPE -> listOf("facade:scope")
        RootQuestionKind.VISUAL_CONFLICT -> q.subjectIds.map { "opening:$it:height" }
        else -> emptyList()
    }

    private fun FactFidelity.isSettled(): Boolean =
        this == FactFidelity.SOURCE_EXACT || this == FactFidelity.SOURCE_TRACED || this == FactFidelity.SOURCE_DERIVED || this == FactFidelity.USER_CONFIRMED

    private fun stateOf(fidelity: FactFidelity): VerifiedState = when (VerificationState.of(fidelity)) {
        VerificationState.SOURCE_VERIFIED -> VerifiedState.SOURCE_FACT
        VerificationState.DERIVED_VERIFIED -> VerifiedState.SOURCE_DERIVED
        VerificationState.ASSUMED -> VerifiedState.ASSUMPTION
        VerificationState.UNRESOLVED -> VerifiedState.UNRESOLVED
        VerificationState.USER_CONFIRMED -> VerifiedState.USER_CONFIRMED
    }

    /** Identity and topology first, then heights, then levels (which move the roof), then the roof itself. */
    private fun order(kind: RootQuestionKind?): Int = when (kind) {
        RootQuestionKind.ROOM_IDENTITY -> 0
        RootQuestionKind.ROOM_UNCLAIMED -> 1
        RootQuestionKind.STAIR_INTERPRETATION -> 2
        RootQuestionKind.OPENING_HEIGHT -> 3
        RootQuestionKind.LEVEL_ASSUMPTION -> 4
        RootQuestionKind.SECONDARY_MASS_ROOF -> 5
        RootQuestionKind.ROOF_RIDGE_DIRECTION -> 6
        RootQuestionKind.STAIR_DETAIL -> 7
        else -> 9
    }

    // ------------------------------------------------------------- applying

    private fun apply(
        report: ProjectAnalysisReport,
        candidate: ProjectAnalysisCandidate,
        question: RootQuestion,
        decision: VerificationDecision,
    ): Pair<ProjectAnalysisCandidate, String?> = when (question.kind) {
        RootQuestionKind.ROOM_IDENTITY -> roomIdentity(report, candidate, question, decision)
        RootQuestionKind.ROOM_UNCLAIMED -> unclaimedRegion(report, candidate, question, decision)
        RootQuestionKind.OPEN_PLAN_BOUNDARY -> candidate to "Potwierdzono podział otwartej przestrzeni: ${question.subjectLabel}"
        RootQuestionKind.OPENING_HEIGHT -> openingHeights(candidate, question, decision)
        RootQuestionKind.LEVEL_ASSUMPTION -> level(candidate, question, decision)
        RootQuestionKind.ROOF_RIDGE_DIRECTION -> ridge(report, candidate, question, decision)
        RootQuestionKind.SECONDARY_MASS_ROOF -> secondaryMass(candidate, question, decision)
        RootQuestionKind.STAIR_INTERPRETATION -> stairInterpretation(candidate, question, decision)
        RootQuestionKind.STAIR_DETAIL -> stairDetail(candidate, question, decision)
        RootQuestionKind.FACADE_SCOPE -> candidate to "Wybrano zakres elewacji: ${question.options.firstOrNull { it.id == decision.optionId }?.label ?: decision.optionId}"
        RootQuestionKind.VISUAL_CONFLICT -> candidate to (if (decision.optionId == "source") "Odnotowano: obraz ma rację (${question.subjectLabel}) — do uzupełnienia później" else "Odrzucono obserwację z obrazu: ${question.subjectLabel}")
        RootQuestionKind.APPEARANCE_FEATURE, RootQuestionKind.ROOF_COVER -> candidate to (if (decision.optionId == "accept") "Przyjęto cechę prezentacyjną: ${question.subjectLabel}" else "Pominięto cechę prezentacyjną: ${question.subjectLabel}")
        RootQuestionKind.DIMENSION_CHECK -> candidate to decision.value?.let { "Zapisano wymiar podany przez użytkownika: ${fmt(it)} m (${question.subjectLabel})" }
        RootQuestionKind.ROOM_GEOMETRY, RootQuestionKind.ROOM_UNPLACED, RootQuestionKind.SIGNATURE_FEATURES -> candidate to "Odnotowano: ${question.subjectLabel}"
    }

    private fun userValue(decision: VerificationDecision, unit: MeasureUnit, what: String): Measured? {
        val v = decision.value ?: return null
        return Measured(v, unit, FactFidelity.USER_CONFIRMED, Provenance(null, "decision:${decision.id}", "$what supplied by the user in verification"))
    }

    private fun confirmed(original: Measured, decision: VerificationDecision, what: String): Measured? {
        val v = original.value ?: return null
        return Measured(v, original.unit, FactFidelity.USER_CONFIRMED, Provenance(null, "decision:${decision.id}", "$what confirmed by the user in verification (was: ${original.provenance.method})"), original.uncertainty)
    }

    private fun roomIdentity(report: ProjectAnalysisReport, candidate: ProjectAnalysisCandidate, q: RootQuestion, d: VerificationDecision): Pair<ProjectAnalysisCandidate, String?> {
        val roomId = q.subjectIds.firstOrNull() ?: return candidate to null
        val room = candidate.room(roomId) ?: return candidate to null
        val option = d.optionId ?: return candidate to null
        if (option.startsWith("current:")) {
            return candidate.replaceRoom(room.copy(matchConfidence = FactFidelity.USER_CONFIRMED, matchNote = "identity confirmed by the user: ${room.name}")) to
                "Potwierdzono pomieszczenie „${room.name}”"
        }
        val rowIndex = option.removePrefix("row:").toIntOrNull() ?: return candidate to null
        val floorIndex = candidate.floors.indexOfFirst { it.id == room.floorId }
        val row = report.source?.floors?.getOrNull(floorIndex)?.rooms?.getOrNull(rowIndex) ?: return candidate to null
        val renamed = room.copy(
            name = row.name,
            sourceOrdinal = row.ordinal,
            kind = row.kind,
            sourceUsableArea = row.usableArea,
            sourceFloorArea = row.floorArea,
            matchConfidence = FactFidelity.USER_CONFIRMED,
            matchNote = "identity chosen by the user: row ${rowIndex + 1} „${row.name}” (was „${room.name}”)",
            matchAlternatives = room.matchAlternatives.filter { it.sourceRowIndex != rowIndex },
        )
        return candidate.replaceRoom(renamed) to "Pomieszczenie „${room.name}” → „${row.name}”"
    }

    private fun unclaimedRegion(report: ProjectAnalysisReport, candidate: ProjectAnalysisCandidate, q: RootQuestion, d: VerificationDecision): Pair<ProjectAnalysisCandidate, String?> {
        val regionId = q.subjectIds.firstOrNull() ?: return candidate to null
        val floor = candidate.floors.firstOrNull { f -> f.unmatchedRegions.any { it.id == regionId } } ?: return candidate to null
        val region = floor.unmatchedRegions.first { it.id == regionId }
        if (d.kind != DecisionKind.CHOOSE_ALTERNATIVE || d.optionId == "not-a-room") {
            return candidate to "Potwierdzono: obszar ${fmt(region.areaM2)} m² na kondygnacji ${floor.name} nie jest pomieszczeniem"
        }
        val rowIndex = d.optionId?.removePrefix("row:")?.toIntOrNull() ?: return candidate to null
        val floorIndex = candidate.floors.indexOf(floor)
        val row = report.source?.floors?.getOrNull(floorIndex)?.rooms?.getOrNull(rowIndex) ?: return candidate to null
        val check = RingValidity.check(region.polygon.vertices)
        val newRoom = RoomCandidate(
            id = "$regionId-room",
            floorId = floor.id,
            name = row.name,
            sourceOrdinal = row.ordinal,
            kind = row.kind,
            polygon = if (check.isValid) region.polygon else null,
            geometryState = if (check.isValid) RoomGeometryState.VALID_SIMPLE_RING else RoomGeometryState.UNRESOLVED_REGION,
            geometryNote = "region assigned by the user to row ${rowIndex + 1}; ${check.detail}; wall faces not derived in this stage",
            perimeter = Measured.derived(region.polygon.perimeter, MeasureUnit.METER, "region outline perimeter", emptyList()),
            plannedArea = Measured(region.areaM2, MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_TRACED, Provenance(floor.planAssetUrl, "region $regionId", "enclosed region pixel area")),
            sourceUsableArea = row.usableArea,
            sourceFloorArea = row.floorArea,
            boundary = emptyList(),
            matchConfidence = FactFidelity.USER_CONFIRMED,
            matchNote = "region assigned by the user (decision ${d.id})",
        )
        val updatedFloor = floor.copy(rooms = floor.rooms + newRoom, unmatchedRegions = floor.unmatchedRegions.filter { it.id != regionId })
        return candidate.copy(floors = candidate.floors.map { if (it.id == floor.id) updatedFloor else it }) to
            "Obszar ${fmt(region.areaM2)} m² na kondygnacji ${floor.name} → „${row.name}”"
    }

    private fun openingHeights(candidate: ProjectAnalysisCandidate, q: RootQuestion, d: VerificationDecision): Pair<ProjectAnalysisCandidate, String?> {
        val targets = d.targetKeys.map { it.removePrefix("opening:").removeSuffix(":height") }.toSet()
        val ids = if (targets.isEmpty()) q.groupMemberIds.toSet() else targets
        val updated = candidate.openings.map { o ->
            if (o.id !in ids) return@map o
            val height: Measured? = when (d.kind) {
                DecisionKind.CONFIRM_ASSUMPTION, DecisionKind.CONFIRM_VALUE -> q.assumedValue?.let { Measured(it, MeasureUnit.METER, FactFidelity.USER_CONFIRMED, Provenance(null, "decision:${d.id}", "assumed height ${fmt(it)} m confirmed by the user")) }
                DecisionKind.REPLACE_VALUE, DecisionKind.PROVIDE_MISSING_VALUE -> userValue(d, MeasureUnit.METER, "opening height")
                else -> null
            }
            if (height == null) o else o.copy(height = height)
        }
        val value = updated.firstOrNull { it.id in ids }?.height?.value
        return candidate.copy(openings = updated) to value?.let { "Wysokość ${ids.size} × ${q.subjectLabel.substringAfter("× ")}: ${fmt(it)} m" }
    }

    private fun level(candidate: ProjectAnalysisCandidate, q: RootQuestion, d: VerificationDecision): Pair<ProjectAnalysisCandidate, String?> {
        val which = q.id.substringAfterLast(':')
        val l = candidate.levels
        val original = when (which) {
            "terrain" -> l.terrain
            "upperFloor" -> l.upperFloor
            "slab" -> l.upperSlabThickness
            "atticCeiling" -> l.atticFlatCeilingHeight
            else -> return candidate to null
        }
        val value: Measured = when (d.kind) {
            DecisionKind.CONFIRM_ASSUMPTION, DecisionKind.CONFIRM_VALUE -> confirmed(original, d, q.subjectLabel)
            DecisionKind.REPLACE_VALUE, DecisionKind.PROVIDE_MISSING_VALUE -> userValue(d, MeasureUnit.METER, q.subjectLabel)
            else -> null
        } ?: return candidate to null
        val delta = value.requireValue() - (original.value ?: value.requireValue())
        val next = when (which) {
            "terrain" -> shiftForTerrain(candidate, value, delta)
            "upperFloor" -> withUpperFloor(candidate, value)
            "slab" -> withSlab(candidate, value)
            "atticCeiling" -> withAtticCeiling(candidate, value)
            else -> candidate
        }
        return next to "${q.subjectLabel}: ${fmt(value.requireValue())} m${if (kotlin.math.abs(delta) > 1e-9) " (było ${fmt(original.value ?: 0.0)} m)" else " (potwierdzono)"}"
    }

    /**
     * The terrain moved by [delta]: the published height is measured from
     * terrain to ridge, so the ridge, the eave and every level chained from
     * them move with it, and the roof translates as one body. Floor levels
     * stay where the datum put them; the storey heights absorb the move.
     */
    private fun shiftForTerrain(candidate: ProjectAnalysisCandidate, terrain: Measured, delta: Double): ProjectAnalysisCandidate {
        val l = candidate.levels
        // Re-derived from their own inputs with the assumed terrain replaced, so the fidelity the
        // assumption had lent them is gone with it: a ridge that is the published height above a
        // confirmed terrain is derived, not assumed.
        val ridge = l.ridge.value?.let { Measured.derived(it + delta, MeasureUnit.METER, "building height above terrain + terrain level", listOf(l.buildingHeight, terrain), l.ridge.uncertainty) } ?: l.ridge
        val eave = l.eave.value?.let { Measured.derived(it + delta, MeasureUnit.METER, "ridge − skeleton rise × tan(pitch)", listOf(ridge), l.eave.uncertainty) } ?: l.eave
        // An upper floor the chain placed moves with the eave it hangs from; one the chain gave
        // up on (a fallback storey height) is a statement about the storey, and stays.
        val upperFloor = when {
            l.upperFloor.fidelity == FactFidelity.USER_CONFIRMED || l.upperFloor.fidelity == FactFidelity.DISPLAY_ASSUMPTION -> l.upperFloor
            else -> l.upperFloor.value?.let { Measured.derived(it + delta, MeasureUnit.METER, "eave-chained upper floor moved with the terrain", listOf(eave, l.kneeWall), l.upperFloor.uncertainty) } ?: l.upperFloor
        }
        val levels = l.copy(
            terrain = terrain,
            ridge = ridge,
            eave = eave,
            upperFloor = upperFloor,
            groundClearHeight = upperFloor.value?.let { u -> l.upperSlabThickness.value?.let { s -> Measured.derived(u - s, MeasureUnit.METER, "upper floor − slab thickness", listOf(upperFloor, l.upperSlabThickness)) } } ?: l.groundClearHeight,
            upperClearHeight = l.upperClearHeight,
            notes = l.notes + "terrain level ${fmt(terrain.requireValue())} m supplied by the user; ridge, eave and chained levels moved by ${fmt(delta)} m",
        )
        val roof = candidate.roof?.let { translateRoof(it, delta, terrain) }
        return withLevels(candidate.copy(levels = levels, roof = roof), upperFloor, levels.groundClearHeight, candidate.levels.atticFlatCeilingHeight)
    }

    private fun withUpperFloor(candidate: ProjectAnalysisCandidate, upperFloor: Measured): ProjectAnalysisCandidate {
        val l = candidate.levels
        val groundClear = l.upperSlabThickness.value?.let { s -> Measured.derived(upperFloor.requireValue() - s, MeasureUnit.METER, "upper floor − slab thickness", listOf(upperFloor, l.upperSlabThickness)) } ?: l.groundClearHeight
        val upperClear = l.ridge.value?.let { r -> Measured.derived(r - upperFloor.requireValue(), MeasureUnit.METER, "ridge − upper floor (to the roof, not to a ceiling)", listOf(l.ridge, upperFloor)) } ?: l.upperClearHeight
        val levels = l.copy(upperFloor = upperFloor, groundClearHeight = groundClear, upperClearHeight = upperClear, notes = l.notes + "upper floor level ${fmt(upperFloor.requireValue())} m supplied by the user; the roof stays where the published height put it")
        val roof = candidate.roof?.let { r -> r.copy(secondaryMasses = r.secondaryMasses.map { m -> if (m.topElevation.fidelity == FactFidelity.USER_CONFIRMED) m else retop(m, Measured.derived(upperFloor.requireValue(), MeasureUnit.METER, "top of the storey below the attic floor", listOf(upperFloor))) }) }
        return withLevels(candidate.copy(levels = levels, roof = roof), upperFloor, groundClear, l.atticFlatCeilingHeight)
    }

    private fun withSlab(candidate: ProjectAnalysisCandidate, slab: Measured): ProjectAnalysisCandidate {
        val l = candidate.levels
        val groundClear = l.upperFloor.value?.let { u -> Measured.derived(u - slab.requireValue(), MeasureUnit.METER, "upper floor − slab thickness", listOf(l.upperFloor, slab)) } ?: l.groundClearHeight
        val levels = l.copy(upperSlabThickness = slab, groundClearHeight = groundClear, notes = l.notes + "slab thickness ${fmt(slab.requireValue())} m supplied by the user")
        return withLevels(candidate.copy(levels = levels), l.upperFloor, groundClear, l.atticFlatCeilingHeight)
    }

    private fun withAtticCeiling(candidate: ProjectAnalysisCandidate, ceiling: Measured): ProjectAnalysisCandidate {
        val levels = candidate.levels.copy(atticFlatCeilingHeight = ceiling, notes = candidate.levels.notes + "attic flat ceiling ${fmt(ceiling.requireValue())} m supplied by the user")
        return withLevels(candidate.copy(levels = levels), candidate.levels.upperFloor, candidate.levels.groundClearHeight, ceiling)
    }

    /** Puts the storeys and their walls on the given levels, the way the floor builder first placed them. */
    private fun withLevels(candidate: ProjectAnalysisCandidate, upperFloor: Measured, groundClear: Measured, atticCeiling: Measured): ProjectAnalysisCandidate {
        val ground = candidate.floors.minByOrNull { it.order }
        val top = candidate.floors.maxByOrNull { it.order }
        val floors = candidate.floors.map { f ->
            when {
                f.id == ground?.id && candidate.floors.size > 1 -> f.copy(clearHeight = groundClear)
                f.id == ground?.id -> f
                f.id == top?.id -> f.copy(floorElevation = upperFloor, clearHeight = if (atticCeiling.value != null) atticCeiling else f.clearHeight)
                else -> f
            }
        }
        val byId = floors.associateBy { it.id }
        val walls = candidate.walls.map { w ->
            val f = byId[w.floorId] ?: return@map w
            w.copy(baseElevation = f.floorElevation, height = f.clearHeight)
        }
        return candidate.copy(floors = floors, walls = walls)
    }

    private fun translateRoof(roof: RoofCandidate, delta: Double, cause: Measured): RoofCandidate {
        fun p(pt: Pt3) = Pt3(pt.x, pt.y + delta, pt.z)
        fun s(seg: Segment3) = Segment3(p(seg.a), p(seg.b))
        fun f(facet: RoofFacetCandidate) = facet.copy(vertices = facet.vertices.map(::p))
        fun m(v: Measured, what: String) = v.value?.let { Measured.derived(it + delta, v.unit, "$what moved with the user's terrain level", listOf(cause, roof.pitchDegrees), v.uncertainty) } ?: v
        return roof.copy(
            eaveElevation = m(roof.eaveElevation, "eave"),
            ridgeElevation = m(roof.ridgeElevation, "ridge"),
            facets = roof.facets.map(::f),
            ridgeLines = roof.ridgeLines.map(::s),
            hipLines = roof.hipLines.map(::s),
            secondaryMasses = roof.secondaryMasses.map { mass -> if (mass.topElevation.fidelity == FactFidelity.USER_CONFIRMED) mass else retop(mass, m(mass.topElevation, "secondary mass top")) },
        )
    }

    private fun retop(mass: com.buildplan.app.analyzer.candidate.SecondaryRoofMass, top: Measured): com.buildplan.app.analyzer.candidate.SecondaryRoofMass {
        val y = top.value ?: return mass
        return mass.copy(topElevation = top, facets = mass.facets.map { f -> f.copy(vertices = f.vertices.map { Pt3(it.x, y, it.z) }) })
    }

    private fun secondaryMass(candidate: ProjectAnalysisCandidate, q: RootQuestion, d: VerificationDecision): Pair<ProjectAnalysisCandidate, String?> {
        val roof = candidate.roof ?: return candidate to null
        val id = q.subjectIds.firstOrNull() ?: return candidate to null
        val mass = roof.secondaryMasses.firstOrNull { it.id == id } ?: return candidate to null
        val top: Measured = when (d.kind) {
            DecisionKind.CONFIRM_ASSUMPTION, DecisionKind.CONFIRM_VALUE -> confirmed(mass.topElevation, d, "secondary mass top")
            DecisionKind.REPLACE_VALUE, DecisionKind.PROVIDE_MISSING_VALUE -> userValue(d, MeasureUnit.METER, "secondary mass top")
            else -> null
        } ?: return candidate to null
        val updated = retop(mass, top).copy(fidelity = FactFidelity.USER_CONFIRMED, note = mass.note + "; top level ${fmt(top.requireValue())} m ${if (d.kind == DecisionKind.REPLACE_VALUE) "supplied" else "confirmed"} by the user")
        return candidate.copy(roof = roof.copy(secondaryMasses = roof.secondaryMasses.map { if (it.id == id) updated else it })) to
            "Góra dachu nad bryłą ${fmt(mass.outline.area, 1)} m²: ${fmt(top.requireValue())} m"
    }

    private fun ridge(report: ProjectAnalysisReport, candidate: ProjectAnalysisCandidate, q: RootQuestion, d: VerificationDecision): Pair<ProjectAnalysisCandidate, String?> {
        val roof = candidate.roof ?: return candidate to null
        val axis = when (d.optionId) {
            "ridge-z" -> RoofSolver.RidgeAxis.Z
            "ridge-x" -> RoofSolver.RidgeAxis.X
            else -> return candidate to null
        }
        val ground = candidate.floors.minByOrNull { it.order }
        val solved = RoofSolver.solve(
            RoofSolver.Input(
                outline = roof.outline,
                family = PublishedRoofFamily.GABLE,
                pitchDegrees = roof.pitchDegrees,
                eaveElevation = roof.eaveElevation,
                publishedRoofArea = report.source?.scalar(ScalarKey.ROOF_AREA)?.measured,
                uncoveredMasses = roof.secondaryMasses.map { it.outline },
                secondaryTopElevation = roof.secondaryMasses.firstOrNull()?.topElevation ?: candidate.levels.upperFloor,
                ridgeAxisHint = axis,
            ),
        ) ?: return candidate to null
        // The masses keep whatever a person already settled about them.
        val masses = solved.secondaryMasses.map { m -> roof.secondaryMasses.firstOrNull { it.id == m.id }?.takeIf { it.topElevation.fidelity == FactFidelity.USER_CONFIRMED } ?: m }
        val ridgeLevel = solved.ridgeElevation
        val levels = candidate.levels.copy(ridge = ridgeLevel, notes = candidate.levels.notes + "ridge direction chosen by the user: ${q.options.firstOrNull { it.id == d.optionId }?.label}")
        return candidate.copy(roof = solved.copy(secondaryMasses = masses), levels = levels) to
            "Kierunek kalenicy: ${q.options.firstOrNull { it.id == d.optionId }?.label ?: d.optionId}"
    }

    private fun stairInterpretation(candidate: ProjectAnalysisCandidate, q: RootQuestion, d: VerificationDecision): Pair<ProjectAnalysisCandidate, String?> {
        val id = q.subjectIds.firstOrNull() ?: return candidate to null
        val stair = candidate.stairs.firstOrNull { it.id == id } ?: return candidate to null
        return if (d.kind == DecisionKind.REJECT_OBSERVATION || d.optionId == "not-stair") {
            candidate.copy(stairs = candidate.stairs.filter { it.id != id }) to "Usunięto bieg ${id}: równo rozstawione linie to nie schody"
        } else {
            candidate.copy(stairs = candidate.stairs.map { if (it.id == id) it.copy(fidelity = FactFidelity.USER_CONFIRMED, note = it.note + "; confirmed as a stair by the user") else it }) to
                "Potwierdzono bieg schodów $id"
        }
    }

    private fun stairDetail(candidate: ProjectAnalysisCandidate, q: RootQuestion, d: VerificationDecision): Pair<ProjectAnalysisCandidate, String?> {
        val id = q.subjectIds.firstOrNull() ?: return candidate to null
        val count = d.value ?: return candidate to null
        val treads = Measured(count, MeasureUnit.COUNT, FactFidelity.USER_CONFIRMED, Provenance(null, "decision:${d.id}", "tread count supplied by the user"))
        return candidate.copy(stairs = candidate.stairs.map { if (it.id == id) it.copy(treadCount = treads) else it }) to
            "Liczba stopni $id: ${count.toInt()}"
    }

    private fun ProjectAnalysisCandidate.replaceRoom(room: RoomCandidate): ProjectAnalysisCandidate =
        copy(floors = floors.map { f -> if (f.id == room.floorId) f.copy(rooms = f.rooms.map { if (it.id == room.id) room else it }) else f })

    private fun fmt(v: Double, digits: Int = 2): String = String.format(Locale.ROOT, "%.${digits}f", v).replace('.', ',')
}
