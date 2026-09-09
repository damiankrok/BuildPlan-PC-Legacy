package com.buildplan.app.analyzer.service

import com.buildplan.app.analyzer.ArchonFixture
import com.buildplan.app.analyzer.candidate.FloorCandidate
import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.candidate.RoomCandidate
import com.buildplan.app.analyzer.candidate.RoomGeometryState
import com.buildplan.app.analyzer.candidate.RoomMatchAlternative
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.snapshot.SnapshotCodec
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AN024-CONTRACT — what a later stage may rely on the report to still be
 * carrying.
 *
 * STAGE-025 has to be able to show a person the things this analyzer could not
 * decide and let them decide. That is only possible if the alternatives, the
 * absences and the questions survive as data rather than as prose, and if no
 * run can mark anything as confirmed on its own.
 */
class ReportContractTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("analyzer-contract", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun report(): ProjectAnalysisReport = runBlocking {
        val service = AnalyzerServices.create(
            platform = ServiceFixture.platform(ServiceFixture.fetcher()),
            cacheRoot = root,
            dispatcher = Dispatchers.Default,
        )
        val outcome = withTimeout(120_000) { service.analyzeOnce(AnalyzeProjectRequest(ArchonFixture.PAGE_URL)) }
        requireNotNull(outcome.reportOrNull) { "expected a report, got $outcome" }
    }

    @Test
    fun `the durable snapshot round-trips through its codec unchanged`() {
        val report = report()
        val written = SnapshotCodec.write(report.snapshot)
        val reparsed = SnapshotCodec.read(written)
        assertEquals(written, SnapshotCodec.write(reparsed))

        // And the whole app-facing report rebuilds from the snapshot alone, which is what makes
        // a cached run and a fresh run indistinguishable to a caller.
        val rebuilt = ProjectAnalysisReport.of(reparsed, report.identity.requestedUrl, servedFromCache = true, generatedAtEpochMillis = 1L)
        assertNotNull(rebuilt)
        assertEquals(report.identity.copy(), rebuilt!!.identity)
        assertEquals(report.quantityVerification.size, rebuilt.quantityVerification.size)
        assertEquals(report.ambiguities, rebuilt.ambiguities)
        assertEquals(report.questions, rebuilt.questions)
    }

    @Test
    fun `two runs over the same bytes differ only in run metadata`() {
        val first = report()
        root.deleteRecursively()
        root.mkdirs()
        val second = report()
        assertEquals(first.deterministicJson(), second.deterministicJson())
        // The metadata that legitimately differs is outside that view, and is still reported.
        assertTrue(first.generation.generatedAtEpochMillis > 0)
    }

    @Test
    fun `the deterministic view carries no device path`() {
        val report = report()
        val json = report.deterministicJson()
        assertTrue("a storage path must not travel in a report", !json.contains(root.absolutePath.replace('\\', '/')))
        assertTrue(!json.contains("storagePath\": \"" + root.name))
        // The drawings are still identified — by URL and checksum, which are facts about the
        // source rather than about this device.
        assertTrue(json.contains("sha256"))
    }

    @Test
    fun `the analyzer never marks anything as user-confirmed`() {
        val report = report()
        assertTrue(
            "no quantity may claim a person confirmed it",
            report.quantityVerification.none { it.state == VerificationState.USER_CONFIRMED },
        )
        assertTrue(
            "and no measurement anywhere in the snapshot may carry that fidelity",
            !SnapshotCodec.write(report.snapshot).contains(FactFidelity.USER_CONFIRMED.name),
        )
        assertTrue(
            "quantities that rest on an assumption are not safe to cost",
            report.quantityVerification.filter { it.isAssumption }.none { it.state.safeForCosting },
        )
    }

    @Test
    fun `opening heights stay missing beside widths that were traced`() {
        val report = report()
        val openings = report.candidate?.openings.orEmpty()
        assertTrue("the drawn plan has openings to find", openings.isNotEmpty())

        val heights = report.quantityVerification.filter { it.key.endsWith(":height") }
        val widths = report.quantityVerification.filter { it.key.endsWith(":width") }
        assertEquals(openings.size, heights.size)
        assertTrue("a height the source never printed is absent, not small", heights.all { it.isMissing })
        assertTrue("every absence says why", heights.all { !it.caveat.isNullOrBlank() })
        assertTrue("every absence is unresolved rather than trusted", heights.all { it.state == VerificationState.UNRESOLVED })
        assertTrue("widths were measured off the drawing", widths.any { !it.isMissing })
        assertTrue(
            "and the gap is asked about",
            report.questions.any { it.requirement.name.contains("OPENING") },
        )
    }

    @Test
    fun `questions survive onto the report`() {
        val report = report()
        assertTrue(report.questions.isNotEmpty())
        assertTrue("a question with no text is not a question", report.questions.all { it.text.isNotBlank() })
        assertTrue("every question names what it is about", report.questions.all { it.requirement.description.isNotBlank() })
    }

    @Test
    fun `a quantity carries fidelity, provenance and the source's verdict where there is one`() {
        val report = report()
        val roof = report.verification("project:roofArea")
        assertNotNull("the roof area is a quantity a later stage will cost", roof)
        requireNotNull(roof)
        assertEquals(MeasureUnit.SQUARE_METER, roof.unit)
        assertTrue(roof.provenance.method.isNotBlank())
        // The fixture's cost page publishes a roof area, so this one has been compared.
        assertNotNull("a published figure means a verdict", roof.comparison)
        assertTrue(
            "every comparison points at a quantity that exists",
            report.validations.all { finding -> report.verification(finding.key) != null || finding.key.startsWith("project:") },
        )
    }

    @Test
    fun `everything put to the user is in the user's language`() {
        // Caught on a device, not here: the facade-scope question interpolated the takeoff
        // engine's own English scope names into a Polish sentence, and a person reading it was
        // asked to choose between "net of the openings, less the garage" and "net of the
        // openings". The engine is right to name its scopes in English; the question is the
        // wrong place to show that name.
        val report = report()
        val texts = report.ambiguities.flatMap { listOf(it.question) + it.alternatives.map { a -> a.label } } +
            report.questions.map { it.text }
        assertTrue("this check must have something to check", texts.isNotEmpty())
        assertTrue("untranslated internal labels reached the reader: ${leaks(texts)}", leaks(texts).isEmpty())
    }

    @Test
    fun `the facade scope question names its scopes in Polish`() {
        // The path the device found. Built directly, because whether a given fixture happens to
        // produce a facade ambiguity is not something this guard should depend on.
        val scope = facadeScope(gross = 300.0, net = 260.0, garage = 40.0)
        val published = 258.0 // fits "net of the openings" and "over the openings, less the garage"
        val quantities = quantitiesWith(scope)

        val ambiguity = CandidateAmbiguities.of(candidateWithNoRooms(), quantities, published)
            .single { it.kind == AmbiguityKind.FACADE_SCOPE }
        assertEquals(2, ambiguity.alternatives.size)
        assertTrue("got: ${ambiguity.question}", leaks(listOf(ambiguity.question)).isEmpty())
        assertTrue("got: ${ambiguity.question}", ambiguity.question.contains("obwiednia"))
        ambiguity.alternatives.forEach { assertTrue("got: ${it.label}", it.label.startsWith("obwiednia")) }
        // The id stays the engine's own name, so a later stage can act on the answer.
        assertTrue(ambiguity.alternatives.any { it.id == "net of the openings" })
    }

    private fun leaks(texts: List<String>): List<String> {
        val english = listOf("over the openings", "net of the openings", "less the garage", "secondary mass")
        return texts.filter { text -> english.any { text.contains(it) } }
    }

    private fun m(v: Double) = Measured.traced(v, MeasureUnit.SQUARE_METER, Provenance("u", "l", "traced"))

    private fun facadeScope(gross: Double, net: Double, garage: Double) = com.buildplan.app.analyzer.quantity.FacadeScope(
        exteriorStructuralWall = m(200.0),
        finishGross = m(gross),
        finishNet = m(net),
        openingDeduction = m(gross - net),
        gableFace = m(20.0),
        garageExterior = m(garage),
        secondaryMassExterior = m(0.0),
        plinth = Measured.missing(MeasureUnit.SQUARE_METER, "terrain assumed"),
    )

    private fun quantitiesWith(scope: com.buildplan.app.analyzer.quantity.FacadeScope) =
        com.buildplan.app.analyzer.quantity.ProjectQuantities(
            surfaces = emptyList(), rooms = emptyList(), floors = emptyList(),
            roofFacetAreas = emptyList(), roofTotal = m(0.0), ridgeLength = m(0.0), hipLength = m(0.0),
            eaveLength = m(0.0), exteriorJoinery = m(0.0),
            facadeGross = scope.finishGross, facadeNet = scope.finishNet,
            facadeWallMaterial = scope.exteriorStructuralWall, floorsAndStairsArea = m(0.0),
            facadeScope = scope, notes = emptyList(),
        )

    private fun candidateWithNoRooms() = ProjectAnalysisCandidate(
        floors = emptyList(), walls = emptyList(), openings = emptyList(), stairs = emptyList(),
        roof = null, levels = levels(), dimensions = emptyList(), issues = emptyList(),
    )

    // ---------------------------------------------------------------- ambiguity, as a unit

    @Test
    fun `two rooms that fit one region are reported as both, never as one`() {
        val room = RoomCandidate(
            id = "f0-r1",
            floorId = "f0",
            name = "Hol",
            sourceOrdinal = 2,
            polygon = null,
            geometryState = RoomGeometryState.UNRESOLVED_REGION,
            geometryNote = "no ring",
            perimeter = Measured.missing(MeasureUnit.METER, "no ring"),
            plannedArea = Measured.traced(9.18, MeasureUnit.SQUARE_METER, Provenance("u", "region", "pixels")),
            sourceUsableArea = Measured.exact(9.18, MeasureUnit.SQUARE_METER, Provenance("u", "td", "table")),
            sourceFloorArea = Measured.missing(MeasureUnit.SQUARE_METER, "not printed"),
            boundary = emptyList(),
            matchConfidence = FactFidelity.TRACE_UNCERTAIN,
            matchNote = "region 3: 9.18 m2 fits Hol and Pokój (~9.18 m2); assigned by table order",
            matchAlternatives = listOf(
                RoomMatchAlternative(6, "Pokój", 9.18, 0.0, "area fits within tolerance"),
            ),
        )
        val candidate = ProjectAnalysisCandidate(
            floors = listOf(
                FloorCandidate("f0", "Parter", 0, null, null, null, Measured.assumed(0.0, MeasureUnit.METER, "x"), Measured.assumed(2.7, MeasureUnit.METER, "x"), listOf(room), emptyList(), null),
            ),
            walls = emptyList(),
            openings = emptyList(),
            stairs = emptyList(),
            roof = null,
            levels = levels(),
            dimensions = emptyList(),
            issues = emptyList(),
        )

        val ambiguities = CandidateAmbiguities.of(candidate, null, null)
        val identity = ambiguities.single { it.kind == AmbiguityKind.ROOM_IDENTITY }
        assertEquals("f0-r1", identity.subjectId)
        assertEquals("both readings are offered", 2, identity.alternatives.size)
        assertTrue("the current assignment is one of them", identity.alternatives.any { it.id == identity.currentChoiceId })
        assertTrue("the rival names its source row", identity.alternatives.any { it.id == "row:6" })
        assertTrue("and it is asked, not decided", identity.question.contains("Hol") && identity.question.contains("Pokój"))

        // A room with no proved ring is a separate statement: its surfaces are unresolved, and
        // that is not the same problem as not knowing which room it is.
        val geometry = ambiguities.single { it.kind == AmbiguityKind.ROOM_GEOMETRY }
        assertEquals("f0-r1", geometry.subjectId)
        assertTrue(geometry.alternatives.isEmpty())
    }

    private fun levels() = com.buildplan.app.analyzer.candidate.LevelsCandidate(
        terrain = Measured.assumed(-0.3, MeasureUnit.METER, "x"),
        groundFloor = Measured.assumed(0.0, MeasureUnit.METER, "x"),
        upperFloor = Measured.assumed(3.0, MeasureUnit.METER, "x"),
        groundClearHeight = Measured.assumed(2.7, MeasureUnit.METER, "x"),
        upperClearHeight = Measured.missing(MeasureUnit.METER, "x"),
        upperSlabThickness = Measured.assumed(0.3, MeasureUnit.METER, "x"),
        kneeWall = Measured.assumed(1.0, MeasureUnit.METER, "x"),
        eave = Measured.assumed(4.0, MeasureUnit.METER, "x"),
        ridge = Measured.assumed(7.5, MeasureUnit.METER, "x"),
        buildingHeight = Measured.assumed(7.8, MeasureUnit.METER, "x"),
        atticFlatCeilingHeight = Measured.assumed(2.6, MeasureUnit.METER, "x"),
        notes = emptyList(),
    )
}
