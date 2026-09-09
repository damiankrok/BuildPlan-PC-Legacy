package com.buildplan.app.analyzer.evaluation

import com.buildplan.app.analyzer.asset.AssetFetcher
import com.buildplan.app.analyzer.asset.AssetRole
import com.buildplan.app.analyzer.plan.PlanAnalyzer
import com.buildplan.app.analyzer.plan.PlanDebugSink
import com.buildplan.app.analyzer.plan.PlanRaster
import com.buildplan.app.analyzer.raster.BinaryMask
import com.buildplan.app.analyzer.site.ScalarKey
import com.buildplan.app.analyzer.site.archon.ArchonSiteAdapter
import com.buildplan.app.analyzer.source.ProjectInput
import com.buildplan.app.analyzer.source.SourceResolver
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Developer probe: ASCII crops of the ink and mid-tone masks plus luma and
 * chroma values in regions of one plan raster, and — when asked — the same
 * crops of the analyzer's intermediate masks. Opt-in twice over: needs the
 * evidence directory and `BUILDPLAN_ANALYZER_PROBE`, formatted
 * `<a|b>:<AssetRole>:<x,y,w,h[,step]>[;...]`. `BUILDPLAN_ANALYZER_PROBE_MASKS`
 * names debug masks (comma-separated, e.g. `floor1-4a-outside2`) to crop too.
 * Writes `iter/probe-crop.txt`.
 */
class RasterCropProbeTest {

    @Test
    fun `crop probe`() {
        val dir = EvidenceHarness.directoryOrSkip()
        val spec = System.getenv("BUILDPLAN_ANALYZER_PROBE")
        assumeTrue("BUILDPLAN_ANALYZER_PROBE not set", !spec.isNullOrBlank())
        val (which, roleName, cropSpec) = spec!!.split(":", limit = 3)
        val project = if (which == "b") EvaluationProjects.B else EvaluationProjects.A
        val fetcher = EvidenceHarness.fetcher(dir)
        val resolution = SourceResolver(fetcher).resolve(ProjectInput(project.ownerUrl))
        val pkg = ArchonSiteAdapter().read(resolution.identity!!, resolution.page!!, fetcher)
        val assets = AssetFetcher(fetcher, EvidenceHarness.codec, EvidenceHarness.storage(dir, "run-$which")).fetchAll(pkg.assets, "assets")
        val role = AssetRole.valueOf(roleName)
        val record = assets.manifest.firstWithRole(role)!!
        val image = assets.image(record)!!
        val raster = PlanRaster(image)
        val crops = cropSpec.split(";").map { c -> c.split(",").map { it.trim().toInt() } }
        val report = StringBuilder()

        fun ascii(title: String, x0: Int, y0: Int, w: Int, h: Int, step: Int, cell: (Int, Int) -> Char) {
            report.appendLine("== $title x=$x0..${x0 + w} y=$y0..${y0 + h} step=$step")
            var y = y0
            while (y < y0 + h) {
                val sb = StringBuilder()
                var x = x0
                while (x < x0 + w) { sb.append(cell(x, y)); x += step }
                report.appendLine(sb)
                y += step
            }
        }
        crops.forEach { c ->
            val (x, y, w, h) = c
            val step = c.getOrElse(4) { 1 }
            ascii("raster (# ink, m midtone, . other)", x, y, w, h, step) { px, py -> if (raster.ink[px, py]) '#' else if (raster.midTone[px, py]) 'm' else '.' }
            if (step == 1) {
                report.appendLine("== luma")
                for (py in y until y + h) report.appendLine((x until x + w).joinToString(" ") { px -> "%3d".format(java.util.Locale.ROOT, image.luma(px, py)) })
                report.appendLine("== chroma")
                for (py in y until y + h) report.appendLine((x until x + w).joinToString(" ") { px -> "%3d".format(java.util.Locale.ROOT, image.chroma(px, py)) })
            }
        }

        val wanted = System.getenv("BUILDPLAN_ANALYZER_PROBE_MASKS")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (wanted.isNotEmpty()) {
            val captured = LinkedHashMap<String, BinaryMask>()
            val sink = PlanDebugSink { name, mask -> if (name in wanted) captured[name] = mask }
            val index = if (role == AssetRole.PLAN_GROUND) 0 else 1
            val ground = if (index == 1) {
                val g = assets.manifest.firstWithRole(AssetRole.PLAN_GROUND)?.let { assets.image(it) }
                g?.let { PlanAnalyzer().analyse(it.hashCode().toString(), it, pkg.floors.getOrNull(0), pkg.scalar(ScalarKey.FOOTPRINT_AREA)?.measured, null, attic = false) }
            } else null
            PlanAnalyzer(sink).analyse(
                assetUrl = record.url,
                image = image,
                floor = pkg.floors.getOrNull(index),
                footprintAreaM2 = if (index == 0) pkg.scalar(ScalarKey.FOOTPRINT_AREA)?.measured else null,
                sharedScale = ground?.calibration?.pixelsPerMeter,
                attic = index > 0,
                debugPrefix = "floor$index",
            )
            captured.forEach { (name, mask) ->
                crops.forEach { c ->
                    val (x, y, w, h) = c
                    val step = c.getOrElse(4) { 1 }
                    ascii("mask $name (# set, . clear)", x, y, w, h, step) { px, py -> if (mask[px, py]) '#' else '.' }
                }
            }
            report.appendLine("captured masks: ${captured.keys}")
        }
        EvidenceHarness.write(dir, "iter/probe-crop.txt", report.toString())
    }
}
