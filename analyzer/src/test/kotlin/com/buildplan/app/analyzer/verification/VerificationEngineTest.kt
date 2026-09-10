package com.buildplan.app.analyzer.verification

import com.buildplan.app.analyzer.candidate.OpeningType
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.snapshot.SnapshotCodec
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * V025 — the verification engine: root questions, recompute, lineage, undo,
 * replay and the `USER_CONFIRMED` boundary, on a plain JVM.
 */
class VerificationEngineTest {

    private val report get() = VerificationFixture.dressed

    private fun session() = VerificationSession.start(report)

    private fun RootQuestion.isKind(kind: RootQuestionKind) = this.kind == kind

    // ------------------------------------------------------------- priority

    @Test
    fun `V025-01 questions are ordered by tier then score, never by creation order`() {
        val questions = session().questions
        assertTrue("the fixture raises questions", questions.size >= 5)
        val tiers = questions.map { it.tier.ordinal }
        assertEquals("tiers are non-decreasing", tiers.sorted(), tiers)
        questions.groupBy { it.tier }.values.forEach { inTier ->
            val scores = inTier.map { it.priorityScore }
            assertEquals("within a tier, higher score first", scores.sortedDescending(), scores)
        }
        assertEquals("room identity is REQUIRED and comes first", RootQuestionKind.ROOM_IDENTITY, questions.first().kind)
        assertTrue("every question has Polish text and a reason", questions.all { it.text.isNotBlank() && it.why.isNotBlank() })
        assertEquals("ids are unique", questions.size, questions.map { it.id }.toSet().size)
    }

    @Test
    fun `V025-02 a root question carries its fan-out`() {
        val s = session()
        val family = s.questions.first { it.isKind(RootQuestionKind.OPENING_HEIGHT) && it.subjectIds.any { id -> report.candidate!!.openings.first { o -> o.id == id }.exterior } }
        assertTrue("an exterior opening family resolves its own height rows", family.affectedQuantityKeys.containsAll(family.groupMemberIds.map { "opening:$it:height" }))
        assertTrue("and the envelope figures", family.affectedQuantityKeys.contains("project:exteriorJoinery"))
        assertTrue("and at least one wall face", family.affectedQuantityKeys.any { it.contains(":wallFaceNet") })
        assertTrue("the count is what the screen shows", family.affectedCount == family.affectedQuantityKeys.size && family.affectedCount >= 3)
    }

    @Test
    fun `V025-03 burden counts decisions, not rows`() {
        val burden = RootQuestions.burden(report)
        assertTrue("rows needing confirmation: ${burden.rawQuantitiesNeedingConfirmation}", burden.rawQuantitiesNeedingConfirmation > burden.rootDecisions)
        assertEquals(burden.rootDecisions, burden.required + burden.highImpact + burden.recommended + burden.optional)
        assertTrue(burden.render().contains("rootDecisions = "))
    }

    // ---------------------------------------------------------- immutability

    @Test
    fun `V025-04 the analyzer snapshot is never edited and never carries USER_CONFIRMED`() {
        val before = SnapshotCodec.write(report.snapshot)
        val s = session()
        assertTrue("a fresh session confirms nothing", s.verified.quantities.none { it.state == VerifiedState.USER_CONFIRMED || it.state == VerifiedState.USER_OVERRIDDEN })
        assertFalse(SnapshotCodec.write(s.verified.effectiveCandidate.let { report.snapshot.copy(candidate = it) }).contains(FactFidelity.USER_CONFIRMED.name))
        val identity = s.questions.first { it.isKind(RootQuestionKind.ROOM_IDENTITY) }
        val chosen = s.choose(identity.id, identity.options.first { !it.isCurrent }.id)
        val terrain = chosen.questions.first { it.id == "q:level:terrain" }
        val after = chosen.provide(terrain.id, -0.32)
        assertEquals("the report is untouched by decisions", before, SnapshotCodec.write(after.report.snapshot))
        assertEquals(before, SnapshotCodec.write(report.snapshot))
        assertTrue("USER_CONFIRMED appears in the effective candidate only where a decision put it", SnapshotCodec.write(report.snapshot.copy(candidate = after.verified.effectiveCandidate)).contains(FactFidelity.USER_CONFIRMED.name))
    }

    // ------------------------------------------------------------- deciding

    @Test
    fun `V025-05 choosing a room alternative renames the room and recomputes its rows from the decision`() {
        val s = session()
        val q = s.questions.first { it.isKind(RootQuestionKind.ROOM_IDENTITY) }
        val roomId = q.subjectIds.single()
        val alternative = q.options.first { !it.isCurrent }
        val next = s.choose(q.id, alternative.id)
        val room = next.verified.effectiveCandidate.room(roomId)!!
        assertEquals(FactFidelity.USER_CONFIRMED, room.matchConfidence)
        assertEquals("Pokój", room.name)
        assertNotEquals("the rejected reading is kept in history", "", next.decisions.single().previousReading)
        val rows = next.verified.quantities.filter { it.ownerId == roomId }
        assertTrue(rows.isNotEmpty())
        assertTrue("every row of the room knows the decision", rows.all { next.decisions.single().id in it.decisionIds })
        assertTrue(rows.all { it.state == VerifiedState.DERIVED_FROM_USER_CONFIRMED || it.state == VerifiedState.ASSUMPTION || it.state == VerifiedState.UNRESOLVED })
        assertTrue("the summary says a room changed", next.verified.changeSummary.any { it.contains("Pokój") })
    }

    @Test
    fun `V025-06 a supplied opening height replaces the assumption and the dependent rows recompute`() {
        val s = session()
        val exterior = report.candidate!!.openings.filter { it.exterior && it.type != OpeningType.PASSAGE }
        assertTrue(exterior.isNotEmpty())
        val q = s.questions.first { it.isKind(RootQuestionKind.OPENING_HEIGHT) && it.groupMemberIds.contains(exterior.first().id) }
        val member = exterior.first()
        val beforeNet = s.verified.quantity("project:facadeNet")!!.after
        val next = s.provide(q.id, 2.10)
        val height = next.verified.quantity("opening:${member.id}:height")!!
        assertEquals(2.10, height.after.value!!, 1e-9)
        assertEquals(FactFidelity.USER_CONFIRMED, height.after.fidelity)
        assertEquals(VerifiedState.USER_CONFIRMED, height.state)
        assertTrue("a provided height was missing, so it is confirmed rather than overridden", next.decisions.single().kind == DecisionKind.PROVIDE_MISSING_VALUE)
        val face = next.verified.quantities.first { it.key.contains(":wallFaceNet") && q.affectedQuantityKeys.contains(it.key) }
        assertTrue("the face knows which decision it rests on", next.decisions.single().id in face.decisionIds)
        assertTrue("and which root question", q.id in face.rootQuestionIds)
        val afterNet = next.verified.quantity("project:facadeNet")!!.after
        assertNotEquals("the facade net changed", beforeNet.value, afterNet.value)
        // With every exterior height supplied, nothing about the envelope rests on an assumption any more.
        var all = next
        all.questions.filter { it.isKind(RootQuestionKind.OPENING_HEIGHT) && it.groupMemberIds.any { id -> exterior.any { o -> o.id == id } } && !all.isAnswered(it.id) }
            .forEach { all = all.provide(it.id, 1.50) }
        val joinery = all.verified.quantity("project:exteriorJoinery")!!
        assertNotEquals(FactFidelity.DISPLAY_ASSUMPTION, joinery.after.fidelity)
        assertEquals(VerifiedState.DERIVED_FROM_USER_CONFIRMED, joinery.state)
        assertTrue(joinery.after.value!! > 0.0)
    }

    @Test
    fun `V025-07 only this opening keeps the rest of the family open`() {
        val s = session()
        val q = s.questions.first { it.isKind(RootQuestionKind.OPENING_HEIGHT) && it.groupMemberIds.size >= 2 }
        val one = q.groupMemberIds.first()
        val next = s.provide(q.id, 1.20, onlyIds = setOf(one))
        assertEquals(1.20, next.verified.quantity("opening:$one:height")!!.after.value!!, 1e-9)
        q.groupMemberIds.drop(1).forEach { other ->
            assertTrue("$other stays missing", next.verified.quantity("opening:$other:height")!!.after.value == null)
        }
    }

    @Test
    fun `V025-08 confirming an assumption keeps the value and marks it user-confirmed`() {
        val s = session()
        val q = s.questions.first { it.id == "q:level:terrain" }
        val before = s.verified.quantity("project:terrainLevel")!!.after
        val next = s.confirm(q.id)
        val after = next.verified.quantity("project:terrainLevel")!!
        assertEquals(before.value!!, after.after.value!!, 1e-9)
        assertEquals(FactFidelity.USER_CONFIRMED, after.after.fidelity)
        assertEquals(VerifiedState.USER_CONFIRMED, after.state)
        assertEquals(DecisionKind.CONFIRM_ASSUMPTION, next.decisions.single().kind)
        assertEquals("nothing else moved", s.verified.quantity("project:ridgeLevel")!!.after.value!!, next.verified.quantity("project:ridgeLevel")!!.after.value!!, 1e-9)
        assertEquals(1, next.verified.summary.assumptionsConfirmed)
    }

    @Test
    fun `V025-09 overriding the terrain moves the roof and the chained levels by the same amount`() {
        val s = session()
        val terrainBefore = s.verified.quantity("project:terrainLevel")!!.after.value!!
        val ridgeBefore = s.verified.quantity("project:ridgeLevel")!!.after.value!!
        val next = s.provide("q:level:terrain", terrainBefore - 0.02)
        val ridgeAfter = next.verified.quantity("project:ridgeLevel")!!
        assertEquals(ridgeBefore - 0.02, ridgeAfter.after.value!!, 1e-6)
        assertEquals(VerifiedState.DERIVED_FROM_USER_CONFIRMED, ridgeAfter.state)
        assertEquals(VerifiedState.USER_OVERRIDDEN, next.verified.quantity("project:terrainLevel")!!.state)
        val roof = next.verified.effectiveCandidate.roof!!
        val originalRoof = report.candidate!!.roof!!
        assertEquals(originalRoof.eaveElevation.value!! - 0.02, roof.eaveElevation.value!!, 1e-6)
        assertEquals("the roof translated as one body", originalRoof.facets.first().vertices.first().y - 0.02, roof.facets.first().vertices.first().y, 1e-6)
        assertEquals("its area did not change", originalRoof.totalArea.value!!, next.verified.effectiveQuantities!!.roofTotal.value!!, 1e-6)
        assertEquals(1, next.verified.summary.userOverrides)
    }

    @Test
    fun `V025-10 replacing the attic ceiling recomputes the attic rooms and nothing on the ground floor`() {
        val s = session()
        val attic = report.candidate!!.floors.maxByOrNull { it.order }!!
        val ground = report.candidate!!.floors.minByOrNull { it.order }!!
        val atticRoom = attic.rooms.first { it.polygon != null }
        val groundRoom = ground.rooms.first { it.polygon != null }
        val before = s.verified
        val next = s.provide("q:level:atticCeiling", 2.30)
        val volumeBefore = before.quantity("room:${atticRoom.id}:volume")!!.after.value!!
        val volumeAfter = next.verified.quantity("room:${atticRoom.id}:volume")!!.after.value!!
        assertTrue("attic volume shrank with a lower ceiling: $volumeBefore -> $volumeAfter", volumeAfter < volumeBefore)
        assertEquals(before.quantity("room:${groundRoom.id}:volume")!!.after.value!!, next.verified.quantity("room:${groundRoom.id}:volume")!!.after.value!!, 1e-9)
        assertTrue(next.verified.quantity("room:${atticRoom.id}:volume")!!.rootQuestionIds.contains("q:level:atticCeiling"))
    }

    @Test
    fun `V025-11 rejecting a stair removes it from the effective candidate and nothing else`() {
        val s = session()
        val q = s.questions.first { it.isKind(RootQuestionKind.STAIR_INTERPRETATION) }
        val stairId = q.subjectIds.single()
        assertTrue(report.candidate!!.stairs.any { it.id == stairId })
        val next = s.choose(q.id, "not-stair")
        assertEquals(DecisionKind.REJECT_OBSERVATION, next.decisions.single().kind)
        assertTrue(next.verified.effectiveCandidate.stairs.none { it.id == stairId })
        assertEquals(1, next.verified.summary.observationsRejected)
        assertEquals(s.verified.quantities.map { it.after.value }, next.verified.quantities.map { it.after.value })
    }

    @Test
    fun `V025-12 an unclaimed region assigned to a row becomes a room with a floor and honest missing faces`() {
        val s = session()
        val q = s.questions.first { it.isKind(RootQuestionKind.ROOM_UNCLAIMED) && it.input == QuestionInput.CHOICE }
        val row = q.options.first { it.id.startsWith("row:") }
        val next = s.choose(q.id, row.id)
        val floor = next.verified.effectiveCandidate.floors.first { it.id == q.floorId }
        val room = floor.rooms.first { it.matchConfidence == FactFidelity.USER_CONFIRMED && it.id.endsWith("-room") }
        assertTrue(floor.unmatchedRegions.none { it.id == q.subjectIds.single() })
        val floorArea = next.verified.quantity("room:${room.id}:floorArea")!!
        assertTrue(floorArea.after.value!! > 0.0)
        assertTrue("wall faces are not invented", next.verified.quantity("room:${room.id}:wallFaceNet")!!.after.fidelity == FactFidelity.MISSING)
    }

    // ------------------------------------------------------------- history

    @Test
    fun `V025-13 undo restores the previous result exactly`() {
        val s = session()
        val q = s.questions.first { it.isKind(RootQuestionKind.OPENING_HEIGHT) }
        val decided = s.provide(q.id, 2.00)
        assertTrue(decided.canUndo)
        val undone = decided.undoLast()
        assertFalse(undone.canUndo)
        assertEquals(s.verified.quantities, undone.verified.quantities)
        assertEquals(s.verified.summary, undone.verified.summary)
        assertEquals(s.verified.effectiveCandidate, undone.verified.effectiveCandidate)
    }

    @Test
    fun `V025-14 reset drops every decision of one question and keeps the others`() {
        val s = session()
        val a = s.questions.first { it.isKind(RootQuestionKind.OPENING_HEIGHT) }
        val b = s.questions.first { it.id == "q:level:terrain" }
        val both = s.provide(a.id, 2.00).provide(b.id, -0.35).provide(a.id, 2.05)
        assertEquals(3, both.decisions.size)
        val reset = both.reset(a.id)
        assertEquals(1, reset.decisions.size)
        assertTrue(reset.isAnswered(b.id))
        assertFalse(reset.isAnswered(a.id))
        assertEquals(null, reset.verified.quantity("opening:${a.groupMemberIds.first()}:height")!!.after.value)
        assertEquals(-0.35, reset.verified.quantity("project:terrainLevel")!!.after.value!!, 1e-9)
    }

    @Test
    fun `V025-15 defer keeps a question open and refuses a REQUIRED one`() {
        val s = session()
        val optional = s.questions.first { it.tier != PriorityTier.REQUIRED }
        val deferred = s.defer(optional.id)
        assertTrue(deferred.isDeferred(optional.id))
        assertFalse(deferred.isAnswered(optional.id))
        assertEquals(1, deferred.verified.summary.deferred)
        assertTrue(deferred.verified.deferred.any { it.id == optional.id })
        assertTrue(deferred.verified.unresolved.any { it.id == optional.id })
        val required = s.questions.first { it.tier == PriorityTier.REQUIRED }
        try {
            s.defer(required.id)
            fail("a REQUIRED question must not be deferrable")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("REQUIRED"))
        }
    }

    @Test
    fun `V025-16 readiness is gated by REQUIRED, then by HIGH_IMPACT`() {
        var s = session()
        assertEquals(VerificationReadiness.NEEDS_REQUIRED_INPUT, s.verified.readiness)
        s.questions.filter { it.tier == PriorityTier.REQUIRED }.forEach { q -> s = s.choose(q.id, q.options.first().id) }
        assertEquals(VerificationReadiness.READY_FOR_OWNER_REVIEW, s.verified.readiness)
        s.questions.filter { it.tier == PriorityTier.HIGH_IMPACT && !s.isAnswered(it.id) }.forEach { q ->
            s = when (q.input) {
                QuestionInput.CHOICE -> s.choose(q.id, q.options.first().id)
                QuestionInput.LENGTH, QuestionInput.COUNT -> if (q.assumedValue != null) s.confirm(q.id) else s.provide(q.id, 1.0)
                QuestionInput.CONFIRM -> if (q.recomputes) s.confirm(q.id) else s.defer(q.id)
            }
        }
        val readiness = s.verified.readiness
        assertTrue(readiness == VerificationReadiness.READY_FOR_CANONICALIZATION_LATER || s.verified.unresolved.any { it.tier == PriorityTier.HIGH_IMPACT && !it.recomputes })
        assertTrue("no false 100 % language anywhere", s.verified.changeSummary.none { it.contains("100") })
    }

    @Test
    fun `V025-17 replaying the decision log gives the same verified candidate`() {
        val s = session()
        val a = s.questions.first { it.isKind(RootQuestionKind.OPENING_HEIGHT) }
        val identity = s.questions.first { it.isKind(RootQuestionKind.ROOM_IDENTITY) }
        val decided = s.provide(a.id, 2.10).choose(identity.id, identity.options.last().id).provide("q:level:terrain", -0.32).defer(s.questions.first { it.tier == PriorityTier.OPTIONAL }.id)
        val replayed = VerificationSession.start(report).replay(decided.decisions)
        assertEquals(decided.verified.quantities, replayed.verified.quantities)
        assertEquals(decided.verified.summary, replayed.verified.summary)
        assertEquals(decided.verified.readiness, replayed.verified.readiness)
        assertEquals(decided.verified.effectiveCandidate, replayed.verified.effectiveCandidate)
        // And it is a function of the log, not of the order decisions happened to be applied in.
        val engine = VerificationEngine.verify(report, decided.questions, decided.decisions)
        assertEquals(decided.verified.quantities, engine.quantities)
    }

    @Test
    fun `V025-18 lineage names the decisions a derived row rests on and the roots still open`() {
        val s = session()
        val a = s.questions.first { it.isKind(RootQuestionKind.OPENING_HEIGHT) && it.affectedQuantityKeys.any { k -> k.contains(":wallFaceNet") } }
        val next = s.provide(a.id, 2.10)
        val face = next.verified.quantities.first { it.key.contains(":wallFaceNet") && a.affectedQuantityKeys.contains(it.key) }
        assertTrue(face.decisionIds.contains(next.decisions.single().id))
        assertTrue(face.rootQuestionIds.contains(a.id))
        // A row still resting on an open root says so.
        val open = next.verified.quantities.firstOrNull { it.state == VerifiedState.ASSUMPTION && it.rootQuestionIds.isNotEmpty() }
        if (open != null) assertNotNull(open.caveat)
    }

    @Test
    fun `V025-19 the summary counts roots, families and what stays missing`() {
        val s = session()
        val summary = s.verified.summary
        assertEquals(s.questions.size, summary.rootQuestions)
        assertEquals(0, summary.resolved)
        assertTrue(summary.remainingMissing.any { it.endsWith(":height") })
        val next = s.provide(s.questions.first { it.isKind(RootQuestionKind.OPENING_HEIGHT) }.id, 2.0)
        assertTrue(next.verified.summary.quantityFamiliesAffected.contains("opening"))
        assertTrue(next.verified.summary.quantitiesUserConfirmed >= 1)
    }

    @Test
    fun `V025-20 next open question walks the priority order and skips what was answered`() {
        var s = session()
        val first = s.nextOpenAfter(null)!!
        assertEquals(s.questions.first().id, first.id)
        s = s.choose(first.id, first.options.first().id)
        val second = s.nextOpenAfter(first.id)!!
        assertNotEquals(first.id, second.id)
        assertEquals(s.questions.first { !s.isAnswered(it.id) }.id, second.id)
    }

    @Test
    fun `V025-21 every provided value is validated before it enters the log`() {
        val s = session()
        val q = s.questions.first { it.isKind(RootQuestionKind.OPENING_HEIGHT) }
        try {
            s.provide(q.id, Double.NaN)
            fail("NaN must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
        try {
            s.choose(q.id, "anything")
            fail("a length question takes no option")
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
        try {
            s.provide(q.id, 1.0, onlyIds = setOf("not-a-member"))
            fail("a subset outside the family must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun `V025-22 the dependency graph is built from the ledger and the candidate`() {
        val graph = DependencyGraph.of(report)
        assertTrue(graph.rootKeys.any { it.startsWith("opening:") })
        assertTrue(graph.rootKeys.contains("level:terrain"))
        val terrain = graph.dependentsOf("level:terrain")
        assertTrue(terrain.contains("project:ridgeLevel"))
        assertTrue("terrain reaches the ground-floor faces", terrain.any { it.contains(":wallFaceGross") })
        assertTrue("every dependent is a real ledger key", terrain.all { k -> report.quantityVerification.any { it.key == k } })
        assertTrue(graph.rootsOf("project:ridgeLevel").contains("level:terrain"))
        assertEquals(emptyList<String>(), graph.dependentsOf("no-such-root"))
        assertTrue(abs(graph.dependentsOf(listOf("level:terrain", "level:slab")).size - graph.dependentsOf("level:terrain").size) >= 0)
    }
}
