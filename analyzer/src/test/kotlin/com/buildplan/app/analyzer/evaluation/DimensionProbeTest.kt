package com.buildplan.app.analyzer.evaluation

import com.buildplan.app.analyzer.asset.AssetFetcher
import com.buildplan.app.analyzer.asset.AssetRole
import com.buildplan.app.analyzer.raster.PixelBox
import com.buildplan.app.analyzer.raster.RasterImage
import com.buildplan.app.analyzer.site.archon.ArchonSiteAdapter
import com.buildplan.app.analyzer.source.ProjectInput
import com.buildplan.app.analyzer.source.SourceResolver
import com.buildplan.app.analyzer.text.DimensionChains
import com.buildplan.app.analyzer.text.DrawingTextExtractor
import com.buildplan.app.analyzer.text.GlyphRunLocator
import com.buildplan.app.analyzer.text.TemplateDigitRecogniser
import com.buildplan.app.analyzer.text.TextLegibility
import com.buildplan.app.analyzer.text.TextOrientation
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Developer probe: what the plan rasters actually print, and how big.
 *
 * Opt-in through `BUILDPLAN_ANALYZER_DIMPROBE=1`. Writes every located text
 * run with its bounds and glyph height, then an ASCII rendering of each glyph
 * in the runs that clear the legibility floor — the raw material for deciding
 * whether a recogniser can be built at all, and for authoring its templates
 * against real strokes rather than against an assumption about the font.
 */
class DimensionProbeTest {

    @Test
    fun `dimension run probe`() {
        assumeTrue("BUILDPLAN_ANALYZER_DIMPROBE not set", System.getenv("BUILDPLAN_ANALYZER_DIMPROBE") == "1")
        val dir = EvidenceHarness.directoryOrSkip()
        val report = StringBuilder()

        EvaluationProjects.all.forEach { project ->
            val fetcher = EvidenceHarness.fetcher(dir)
            val resolution = SourceResolver(fetcher).resolve(ProjectInput(project.ownerUrl))
            val pkg = ArchonSiteAdapter().read(resolution.identity!!, resolution.page!!, fetcher)
            val assets = AssetFetcher(fetcher, EvidenceHarness.codec, EvidenceHarness.storage(dir, "run-${project.label.lowercase()}")).fetchAll(pkg.assets, "assets")
            listOf(AssetRole.PLAN_GROUND, AssetRole.PLAN_UPPER, AssetRole.SECTION).forEach { role ->
                val record = assets.manifest.firstWithRole(role) ?: return@forEach
                val image = assets.image(record) ?: return@forEach
                report.appendLine("======== ${project.label} $role ${image.width}x${image.height}")
                val runs = GlyphRunLocator(TemplateDigitRecogniser()).extract(record.url, image)
                val big = runs.filter { it.glyphHeightPx >= DrawingTextExtractor.MIN_LEGIBLE_GLYPH_HEIGHT_PX }
                report.appendLine("runs=${runs.size} legibleHeight=${big.size} read=${big.count { it.legibility == TextLegibility.READ }} heights=${runs.groupingBy { it.glyphHeightPx }.eachCount().toSortedMap()}")
                big.sortedWith(compareBy({ it.bounds.minY }, { it.bounds.minX })).forEach { r ->
                    report.appendLine(
                        "-- run at (${r.bounds.minX},${r.bounds.minY})-(${r.bounds.maxX},${r.bounds.maxY}) glyphs=${r.glyphCount} h=${r.glyphHeightPx} " +
                            "${r.orientation} ${r.legibility} text=${r.text ?: "-"} conf=${String.format(java.util.Locale.ROOT, "%.3f", r.confidence)}",
                    )
                    report.appendLine("   ${r.method}")
                    ascii(report, image, r.bounds)
                }
                // The plan's own calibration, from the published built-up area, so a chain can be
                // checked against a scale derived without reading a single character.
                val planPpm = if (role == AssetRole.PLAN_GROUND) planScale(project, dir) else null
                report.appendLine("planPixelsPerMeter=${planPpm?.let { String.format(java.util.Locale.ROOT, "%.2f", it) } ?: "-"}")
                DimensionChains.assemble(runs, planPpm).forEach { chain ->
                    report.appendLine(
                        "== chain ${chain.orientation} ${chain.verdict} parts=${chain.parts.joinToString("+") { it.centimetres.toString() }} " +
                            "total=${chain.totalCentimetres} ppm=${chain.pixelsPerMeter?.let { String.format(java.util.Locale.ROOT, "%.2f", it) } ?: "-"} :: ${chain.note}",
                    )
                    chain.parts.forEach { p -> report.appendLine("     ${p.centimetres} at along=${p.alongPx} across=${p.acrossPx} conf=${String.format(java.util.Locale.ROOT, "%.3f", p.observation.confidence)}") }
                }
                // Per-glyph candidate scores in the frame each orientation was scanned in, so a
                // template can be checked against the strokes that actually defeated it.
                listOf(TextOrientation.HORIZONTAL, TextOrientation.VERTICAL).forEach { orientation ->
                    val (ink, runs2) = GlyphRunLocator(TemplateDigitRecogniser()).debugRuns(image, orientation)
                    val recogniser = TemplateDigitRecogniser()
                    runs2.filter { run -> run.map { it.height }.sorted()[run.size / 2] >= DrawingTextExtractor.MIN_LEGIBLE_GLYPH_HEIGHT_PX }
                        .forEach { run ->
                            report.appendLine("~~ $orientation run of ${run.size} at (${run.first().minX},${run.first().minY})")
                            val shear = recogniser.estimateShear(ink, run)
                            report.appendLine("   runShear=$shear perGlyph=" + run.map { recogniser.estimateShear(ink, listOf(it)) })
                            run.forEach { g ->
                                val ranked = recogniser.rank(ink, g, shear)?.take(3)?.joinToString(" ") { "${it.first}=${String.format(java.util.Locale.ROOT, "%.3f", it.second)}" }
                                report.appendLine("   glyph ${g.width}x${g.height} at (${g.minX},${g.minY}) holes=${recogniser.holesOf(ink, g)}: $ranked")
                                val raw = (g.minY..g.maxY).map { y -> (g.minX..g.maxX).map { x -> if (ink[x, y]) '#' else '.' }.joinToString("") }
                                val norm = recogniser.normalisedRows(ink, g, shear).orEmpty()
                                val height = maxOf(raw.size, norm.size)
                                for (i in 0 until height) {
                                    report.appendLine("      ${raw.getOrElse(i) { "" }.padEnd(10)}  |  ${norm.getOrElse(i) { "" }}")
                                }
                            }
                        }
                }
            }
        }
        EvidenceHarness.write(dir, "iter/dimension-probe.txt", report.toString())
    }

    /** Ground-plan scale taken from the finished evaluation metrics, so the probe needs no pipeline run. */
    private fun planScale(project: EvaluationProjects.Project, dir: java.io.File): Double? =
        java.io.File(dir, "iter/e2e-${project.label.lowercase()}/metrics.txt").takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.startsWith("floor.f0.calibrationPpm") }
            ?.substringAfter("= ")?.trim()?.toDoubleOrNull()

    private fun ascii(out: StringBuilder, image: RasterImage, box: PixelBox) {
        val pad = 1
        for (y in (box.minY - pad)..(box.maxY + pad)) {
            val sb = StringBuilder("   ")
            for (x in (box.minX - pad)..(box.maxX + pad)) {
                sb.append(if (x in 0 until image.width && y in 0 until image.height && image.luma(x, y) < 150 && image.alpha(x, y) >= 128) '#' else '.')
            }
            out.appendLine(sb)
        }
    }
}

