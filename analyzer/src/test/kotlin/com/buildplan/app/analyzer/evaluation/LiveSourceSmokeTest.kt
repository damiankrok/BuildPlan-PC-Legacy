package com.buildplan.app.analyzer.evaluation

import com.buildplan.app.analyzer.asset.AssetFetcher
import com.buildplan.app.analyzer.asset.RetrievalState
import com.buildplan.app.analyzer.site.ScalarKey
import com.buildplan.app.analyzer.site.archon.ArchonSiteAdapter
import com.buildplan.app.analyzer.source.ProjectInput
import com.buildplan.app.analyzer.source.SourceResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LIVE-023-01/02 — the source layer against the real site (or its cached
 * copy). Opt-in through [EvidenceHarness]; never the only proof of anything.
 *
 * The two URLs are the owner's inputs for this stage. Their expected
 * identities are evaluation truth held here, in a debug test, and nowhere in
 * the analyzer.
 */
class LiveSourceSmokeTest {

    @Test
    fun `project A resolves directly and yields facts, floors and assets`() {
        val dir = EvidenceHarness.directoryOrSkip()
        val fetcher = EvidenceHarness.fetcher(dir)
        val resolution = SourceResolver(fetcher).resolve(ProjectInput(EvaluationProjects.A.ownerUrl))
        assertTrue(resolution.steps.joinToString("\n") { "${it.kind}: ${it.detail}" }, resolution.isResolved)
        assertEquals(EvaluationProjects.A.projectKey, resolution.identity!!.projectKey)

        val pkg = ArchonSiteAdapter().read(resolution.identity!!, resolution.page!!, fetcher)
        val assets = AssetFetcher(fetcher, EvidenceHarness.codec, EvidenceHarness.storage(dir, "run-a")).fetchAll(pkg.assets, "assets")
        EvidenceHarness.write(dir, "iter/iter1-a-source.txt", describe(resolution.steps.joinToString("\n") { "${it.kind}: ${it.detail}" }, pkg, assets.manifest.assets))

        assertEquals(2, pkg.floors.size)
        assertEquals(9, pkg.floors[0].rooms.size)
        assertEquals(9, pkg.floors[1].rooms.size)
        assertTrue(pkg.scalar(ScalarKey.FOOTPRINT_AREA) != null)
        assertTrue(pkg.scalar(ScalarKey.ROOF_PITCH) != null)
        assertTrue(pkg.scalar(ScalarKey.KNEE_WALL_HEIGHT) != null)
        assertTrue(pkg.scalar(ScalarKey.EXTERNAL_WALL_AREA) != null)
        assertTrue(assets.manifest.plans.count { it.retrieval == RetrievalState.DECODED } >= 2)
    }

    @Test
    fun `project B owner URL resolves through the slug stem and yields facts, floors and assets`() {
        val dir = EvidenceHarness.directoryOrSkip()
        val fetcher = EvidenceHarness.fetcher(dir)
        val resolution = SourceResolver(fetcher).resolve(ProjectInput(EvaluationProjects.B.ownerUrl))
        assertTrue(resolution.steps.joinToString("\n") { "${it.kind}: ${it.detail}" }, resolution.isResolved)
        assertEquals(EvaluationProjects.B.projectKey, resolution.identity!!.projectKey)
        assertTrue(resolution.steps.any { it.kind == com.buildplan.app.analyzer.source.ResolutionStep.Kind.DERIVED_CANDIDATE })

        val pkg = ArchonSiteAdapter().read(resolution.identity!!, resolution.page!!, fetcher)
        val assets = AssetFetcher(fetcher, EvidenceHarness.codec, EvidenceHarness.storage(dir, "run-b")).fetchAll(pkg.assets, "assets")
        EvidenceHarness.write(dir, "iter/iter1-b-source.txt", describe(resolution.steps.joinToString("\n") { "${it.kind}: ${it.detail}" }, pkg, assets.manifest.assets))

        assertEquals(2, pkg.floors.size)
        assertEquals(10, pkg.floors[0].rooms.size)
        assertEquals(10, pkg.floors[1].rooms.size)
        assertTrue(pkg.scalar(ScalarKey.ROOF_PITCH) != null)
        assertTrue(pkg.scalar(ScalarKey.KNEE_WALL_HEIGHT) != null)
        assertTrue(assets.manifest.plans.count { it.retrieval == RetrievalState.DECODED } >= 2)
    }

    private fun describe(steps: String, pkg: com.buildplan.app.analyzer.site.SourcePackage, assets: List<com.buildplan.app.analyzer.asset.AssetRecord>): String = buildString {
        appendLine("== resolution")
        appendLine(steps)
        appendLine("== package: ${pkg.title} (${pkg.identity.canonicalUrl})")
        appendLine("tags: ${pkg.siteTags}")
        appendLine("== scalars")
        pkg.scalars.forEach { appendLine("${it.key} | ${it.rawLabel} | ${it.rawValue} | ${it.measured.value} ${it.measured.unit} ${it.measured.fidelity} | ${it.measured.provenance.locator}") }
        appendLine("== construction")
        pkg.construction.forEach { appendLine("${it.label}: ${it.text}") }
        appendLine("== floors")
        pkg.floors.forEach { floor ->
            appendLine("${floor.name} usable=${floor.usableAreaTotal.value} floor=${floor.floorAreaTotal.value}")
            floor.rooms.forEach { appendLine("  ${it.ordinal}. ${it.name} usable=${it.usableArea.value} floor=${it.floorArea.value}") }
        }
        appendLine("== assets")
        assets.forEach { appendLine("${it.role} ${it.retrieval} ${it.widthPx}x${it.heightPx} ${it.byteCount}B sha=${it.sha256?.take(12)} ${it.url} ${it.failure ?: ""}") }
    }
}
