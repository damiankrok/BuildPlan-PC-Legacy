package com.buildplan.app.analyzer.evaluation

import com.buildplan.app.analyzer.asset.AssetFetcher
import com.buildplan.app.analyzer.asset.AssetRole
import com.buildplan.app.analyzer.raster.BinaryMask
import com.buildplan.app.analyzer.site.archon.ArchonSiteAdapter
import com.buildplan.app.analyzer.source.ProjectInput
import com.buildplan.app.analyzer.source.SourceResolver
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * A developer probe, not an assertion: prints colour statistics of the
 * cached plan rasters and writes threshold masks so pixel rules can be
 * chosen from evidence rather than guessed. Skipped without the evidence dir.
 */
class RasterProbeTest {

    @Test
    fun `probe plan raster colours`() {
        val dir = EvidenceHarness.directoryOrSkip()
        val fetcher = EvidenceHarness.fetcher(dir)
        EvaluationProjects.all.forEach { project ->
            val resolution = SourceResolver(fetcher).resolve(ProjectInput(project.ownerUrl))
            val pkg = ArchonSiteAdapter().read(resolution.identity!!, resolution.page!!, fetcher)
            val assets = AssetFetcher(fetcher, EvidenceHarness.codec, EvidenceHarness.storage(dir, "run-${project.label.lowercase()}")).fetchAll(pkg.assets, "assets")
            listOf(AssetRole.PLAN_GROUND, AssetRole.PLAN_UPPER).forEach { role ->
                val image = assets.image(role) ?: return@forEach
                val out = File(dir, "iter/probe-${project.label.lowercase()}-${role.name.lowercase()}")
                out.mkdirs()
                val report = StringBuilder()
                // Luma histogram in 16 buckets, and chroma buckets.
                val luma = IntArray(16)
                val chroma = IntArray(16)
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    luma[image.luma(x, y) / 16]++
                    chroma[image.chroma(x, y) / 16]++
                }
                report.appendLine("$role ${image.width}x${image.height}")
                report.appendLine("luma buckets: " + luma.withIndex().joinToString { "${it.index * 16}:${it.value}" })
                report.appendLine("chroma buckets: " + chroma.withIndex().joinToString { "${it.index * 16}:${it.value}" })
                // Distinct colours with counts (top 40).
                val counts = HashMap<Int, Int>()
                image.argb.forEach { counts[it and 0xFFFFFF] = (counts[it and 0xFFFFFF] ?: 0) + 1 }
                report.appendLine("distinct colours: ${counts.size}")
                counts.entries.sortedByDescending { it.value }.take(40).forEach { (c, n) ->
                    report.appendLine("  #%06x  r=%d g=%d b=%d  n=%d".format(c, (c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, n))
                }
                listOf(60, 90, 120, 150, 170, 190, 210).forEach { t ->
                    val mask = BinaryMask.of(image) { x, y -> image.luma(x, y) < t }
                    writeMask(File(out, "luma-lt-$t.png"), mask)
                }
                EvidenceHarness.write(dir, "iter/probe-${project.label.lowercase()}-${role.name.lowercase()}/report.txt", report.toString())
            }
        }
    }

    private fun writeMask(file: File, mask: BinaryMask) {
        val img = BufferedImage(mask.width, mask.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until mask.height) for (x in 0 until mask.width) img.setRGB(x, y, if (mask[x, y]) 0x000000 else 0xFFFFFF)
        ImageIO.write(img, "png", file)
    }
}
