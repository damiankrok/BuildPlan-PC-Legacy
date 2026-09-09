package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.asset.AssetManifest
import com.buildplan.app.analyzer.asset.AssetRecord
import com.buildplan.app.analyzer.asset.AssetRole
import com.buildplan.app.analyzer.asset.RetrievalState
import com.buildplan.app.analyzer.candidate.FloorCandidate
import com.buildplan.app.analyzer.candidate.LevelsCandidate
import com.buildplan.app.analyzer.candidate.Polygon
import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.roof.RoofSolver
import com.buildplan.app.analyzer.site.PublishedRoofFamily
import com.buildplan.app.analyzer.site.SourcePackage
import com.buildplan.app.analyzer.snapshot.Json
import com.buildplan.app.analyzer.snapshot.JsonValue
import com.buildplan.app.analyzer.snapshot.ProjectAnalysisSnapshot
import com.buildplan.app.analyzer.snapshot.SnapshotCodec
import com.buildplan.app.analyzer.source.ResolutionStep
import com.buildplan.app.analyzer.source.SourceIdentity
import com.buildplan.app.analyzer.source.SupportedSite
import com.buildplan.app.analyzer.validate.ClarificationQuestion
import com.buildplan.app.analyzer.validate.GapAnalysis
import com.buildplan.app.analyzer.validate.Requirement
import com.buildplan.app.analyzer.validate.RequirementState
import com.buildplan.app.analyzer.validate.RequirementStatus
import com.buildplan.app.analyzer.validate.ValidationFinding
import com.buildplan.app.analyzer.validate.ValidationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AN023-SNAP — the snapshot is deterministic, complete for a synthetic candidate, and round-trips exactly. */
class SnapshotCodecTest {

    private fun m(v: Double?, unit: MeasureUnit = MeasureUnit.METER, f: FactFidelity = FactFidelity.SOURCE_TRACED) =
        if (v == null) Measured.missing(unit, "none") else Measured(v, unit, f, Provenance("https://www.archon.pl/x", "loc", "method"), 0.01, "note")

    @Test
    fun `json writer is deterministic and parser inverts it`() {
        val obj = JsonValue.Obj(linkedMapOf("b" to JsonValue.Num(1.5), "a" to JsonValue.Arr(listOf(JsonValue.Str("x\"y\n"), JsonValue.Null, JsonValue.Bool(true))), "n" to JsonValue.Num(3.0)))
        val text = Json.write(obj)
        assertTrue(text.indexOf("\"b\"") < text.indexOf("\"a\""))
        assertTrue(text.contains("\"n\": 3\n") || text.contains("\"n\": 3"))
        assertEquals(text, Json.write(Json.parse(text)))
    }

    @Test
    fun `snapshot round trip is byte identical and keeps fidelity and provenance`() {
        val identity = SourceIdentity(SupportedSite.ARCHON, "mtest0000000000", "https://www.archon.pl/projekty-domow/x-mtest0000000000")
        val outline = Polygon(listOf(Pt(0.0, 0.0), Pt(8.0, 0.0), Pt(8.0, 10.0), Pt(0.0, 10.0)))
        val roof = RoofSolver.solve(RoofSolver.Input(outline, PublishedRoofFamily.HIP, m(38.0, MeasureUnit.DEGREE), m(4.0), m(120.0, MeasureUnit.SQUARE_METER), emptyList(), m(3.0)))!!
        val levels = LevelsCandidate(m(-0.3, f = FactFidelity.DISPLAY_ASSUMPTION), m(0.0), m(3.0), m(2.7), m(null), m(0.3), m(1.0), m(4.0), m(7.5), m(7.8), m(2.6), listOf("n"))
        val floor = FloorCandidate("f0", "Parter", 0, null, outline, null, m(0.0), m(2.7), emptyList(), emptyList(), "https://assets.archon.pl/x.gif")
        val candidate = ProjectAnalysisCandidate(listOf(floor), emptyList(), emptyList(), emptyList(), roof, levels, emptyList(), emptyList())
        val source = SourcePackage(identity, "Dom testowy", 1L, emptyList(), emptyList(), emptyList(),
            AssetManifest(listOf(AssetRecord(AssetRole.PLAN_GROUND, "https://assets.archon.pl/x.gif", identity.canonicalUrl, "img", "alt", "image/gif", RetrievalState.DECODED, 10L, "ab", 853, 853, "/p", null, 0))),
            emptyList(), mapOf("projectRoof" to "czterospadowy"))
        val snapshot = ProjectAnalysisSnapshot(
            ProjectAnalysisSnapshot.SCHEMA_VERSION, ProjectAnalysisSnapshot.ANALYZER_VERSION, 0L, identity.canonicalUrl,
            listOf(ResolutionStep(ResolutionStep.Kind.ACCEPTED, "ok")), source, candidate, null,
            listOf(ValidationFinding("s", 1.0, 1.1, "m2", 0.1, 0.09, ValidationStatus.MATCH_ACCEPTABLE, "sem", FactFidelity.SOURCE_DERIVED)),
            GapAnalysis(listOf(RequirementStatus(Requirement.ROOF_OUTLINE, RequirementState.SATISFIED, FactFidelity.SOURCE_DERIVED, "e"))),
            listOf(ClarificationQuestion("q1", Requirement.STOREY_LEVELS, "Pytanie?", "0,30 m", "levels")),
            listOf("log line"), linkedMapOf("RESOLVE" to 12L),
        )
        val text = SnapshotCodec.write(snapshot)
        val parsed = SnapshotCodec.read(text)
        assertEquals(text, SnapshotCodec.write(parsed))
        assertEquals(text, SnapshotCodec.write(snapshot))
        assertEquals(FactFidelity.DISPLAY_ASSUMPTION, parsed.candidate!!.levels.terrain.fidelity)
        assertEquals("method", parsed.candidate!!.levels.terrain.provenance.method)
        assertEquals(4, parsed.candidate!!.roof!!.facets.size)
        assertEquals(roof.totalArea.value!!, parsed.candidate!!.roof!!.totalArea.value!!, 1e-9)
        assertEquals(1, parsed.questions.size)
        assertEquals("czterospadowy", parsed.source!!.siteTags["projectRoof"])
        assertTrue(text.contains("\"schemaVersion\": 1"))
        assertTrue(!text.contains("NaN") && !text.contains("Infinity"))
    }
}
