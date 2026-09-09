package com.buildplan.app.analyzer.evaluation

import com.buildplan.app.analyzer.asset.AssetFetcher
import com.buildplan.app.analyzer.asset.AssetRole
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.plan.FloorPlanAnalysis
import com.buildplan.app.analyzer.plan.PlanAnalyzer
import com.buildplan.app.analyzer.plan.PlanDebugSink
import com.buildplan.app.analyzer.raster.BinaryMask
import com.buildplan.app.analyzer.site.ScalarKey
import com.buildplan.app.analyzer.site.SourcePackage
import com.buildplan.app.analyzer.site.archon.ArchonSiteAdapter
import com.buildplan.app.analyzer.source.ProjectInput
import com.buildplan.app.analyzer.source.SourceResolver
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EVAL-023-PLAN — plan analysis on both evaluation projects (cached source).
 * Writes masks and a text report per floor into the evidence directory so
 * every iteration's state can be inspected, and asserts only the coarse
 * facts that a working pipeline must produce: a calibration, regions, and
 * more matched than unmatched rooms.
 */
class PlanEvaluationTest {

    @Test
    fun `project A plans analyse`() = runFor(EvaluationProjects.A)

    @Test
    fun `project B plans analyse`() = runFor(EvaluationProjects.B)

    private fun runFor(project: EvaluationProjects.Project) {
        val dir = EvidenceHarness.directoryOrSkip()
        val fetcher = EvidenceHarness.fetcher(dir)
        val resolution = SourceResolver(fetcher).resolve(ProjectInput(project.ownerUrl))
        assertTrue(resolution.isResolved)
        val pkg = ArchonSiteAdapter().read(resolution.identity!!, resolution.page!!, fetcher)
        val assets = AssetFetcher(fetcher, EvidenceHarness.codec, EvidenceHarness.storage(dir, "run-${project.label.lowercase()}")).fetchAll(pkg.assets, "assets")
        val out = File(dir, "iter/plan-${project.label.lowercase()}")
        out.mkdirs()
        val sink = PlanDebugSink { name, mask -> writeMask(File(out, "$name.png"), mask) }
        val analyzer = PlanAnalyzer(sink)

        val report = StringBuilder()
        var shared: Measured? = null
        listOf(AssetRole.PLAN_GROUND to 0, AssetRole.PLAN_UPPER to 1).forEach { (role, index) ->
            val record = assets.manifest.firstWithRole(role) ?: return@forEach
            val image = assets.image(record) ?: return@forEach
            val floor = pkg.floors.getOrNull(index)
            val analysis = analyzer.analyse(
                assetUrl = record.url,
                image = image,
                floor = floor,
                footprintAreaM2 = if (index == 0) pkg.scalar(ScalarKey.FOOTPRINT_AREA)?.measured else null,
                sharedScale = shared,
                attic = index > 0,
                debugPrefix = "floor$index",
            )
            if (index == 0) shared = analysis.calibration?.pixelsPerMeter
            report.append(describe(index, floor?.name ?: "?", analysis, pkg))
            EvidenceHarness.write(dir, "iter/plan-${project.label.lowercase()}/report.txt", report.toString())
            assertNotNull("floor $index should calibrate", analysis.calibration)
            assertTrue("floor $index should find regions", analysis.regions.size >= 3)
        }
    }

    private fun describe(index: Int, name: String, a: FloorPlanAnalysis, pkg: SourcePackage): String = buildString {
        appendLine("==== floor $index $name  (${a.assetUrl})")
        appendLine("calibration: ${a.calibration?.pixelsPerMeter?.value} px/m ${a.calibration?.confidence} residual=${a.calibration?.residual} ${a.calibration?.method}")
        a.calibration?.anchors?.forEach { appendLine("  anchor ${it.kind}: source=${it.sourceValue.value} px=${it.measuredPixels} -> ${"%.3f".format(it.impliedPixelsPerMeter)} px/m") }
        appendLine("footprint: ${a.footprintPixelArea} px, outline ${a.footprintOutlinePx.size} corners: ${a.footprintOutlinePx.joinToString { "(${it.x.toInt()},${it.z.toInt()})" }}")
        a.calibration?.let { c -> appendLine("footprint m: ${a.footprintOutlinePx.map { c.toMeters(it) }.joinToString { "(${"%.2f".format(it.x)},${"%.2f".format(it.z)})" }}") }
        appendLine("roof band outline: ${a.roofBandOutlinePx?.size ?: 0} corners ${a.roofBandOutlinePx?.joinToString { "(${it.x.toInt()},${it.z.toInt()})" } ?: ""}")
        appendLine("pieces: ${a.pieces.size} (exterior ${a.exteriorPieceIndices.size})")
        a.pieces.forEachIndexed { i, p -> appendLine("  [$i] ${p.axis} from=${p.from} to=${p.to} low=${p.low} high=${p.high} len=${p.length} t=${p.thickness} ${if (i in a.exteriorPieceIndices) "EXT" else ""} ${a.calibration?.let { "%.2f m, t=%.2f m".format(a.lengthM(p.length.toDouble()), a.lengthM(p.thickness.toDouble())) } ?: ""}") }
        appendLine("gaps: ${a.gaps.size} (sealed ${a.sealedGaps.size}); unexplained structure px: ${a.unexplainedStructurePixels}")
        a.gaps.forEach { g -> appendLine("  ${g.axis} ${g.from}..${g.to} (w=${g.width}px ${a.calibration?.let { "%.2f m".format(a.lengthM(g.width.toDouble())) }}) between [${g.beforeIndex}] and [${g.afterIndex}]") }
        appendLine("regions: ${a.regions.size}")
        a.regions.forEachIndexed { i, r -> appendLine("  region ${i + 1}: ${r.pixelArea} px = ${a.regionAreaM2(r)?.let { "%.2f".format(it) }} m2 box=${r.box} corners=${r.outline.size}") }
        appendLine("matching:")
        a.matching?.matches?.forEach { m ->
            val rooms = m.roomIndices.joinToString { pkg.floors[index].rooms[it].let { r -> "${r.ordinal}. ${r.name} (${r.usableArea.value}/${r.floorArea.value})" } }
            appendLine("  regions ${m.regionIndices.map { it + 1 }} -> $rooms  ${m.fidelity}  ${m.note}")
        }
        appendLine("  unmatched regions: ${a.matching?.unmatchedRegions?.map { it + 1 }}")
        appendLine("  unmatched rooms: ${a.matching?.unmatchedRooms?.map { pkg.floors[index].rooms[it].name }}")
        appendLine("stairs: ${a.stairs}")
        appendLine("issues:")
        a.issues.forEach { appendLine("  ${it.severity} ${it.stage} ${it.subject ?: ""}: ${it.message}") }
        appendLine()
    }

    private fun writeMask(file: File, mask: BinaryMask) {
        val img = BufferedImage(mask.width, mask.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until mask.height) for (x in 0 until mask.width) img.setRGB(x, y, if (mask[x, y]) 0x000000 else 0xFFFFFF)
        ImageIO.write(img, "png", file)
    }
}
