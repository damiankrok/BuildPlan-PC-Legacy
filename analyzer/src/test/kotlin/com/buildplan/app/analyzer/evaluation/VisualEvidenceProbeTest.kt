package com.buildplan.app.analyzer.evaluation

import com.buildplan.app.analyzer.candidate.VisualObservationKind
import com.buildplan.app.analyzer.pipeline.ProjectAnalyzer
import com.buildplan.app.analyzer.raster.RasterImage
import com.buildplan.app.analyzer.source.ProjectInput
import com.buildplan.app.analyzer.visual.ElevationReader
import com.buildplan.app.analyzer.visual.PixelClass
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import org.junit.Test

/**
 * EVAL-025-VISUAL — draws what the elevation reader saw over each picture of
 * both evaluation projects, into the evidence directory, so a person can judge
 * the observations against the picture rather than against a number.
 *
 * Opt-in like every evaluation test. Writes `iter/visual-<a|b>/<role>.png`
 * with the observation boxes, `<role>-classes.png` with the pixel classes,
 * and `observations.txt` with every observation in words.
 */
class VisualEvidenceProbeTest {

    @Test
    fun `project A visual probe`() = run(EvaluationProjects.A)

    @Test
    fun `project B visual probe`() = run(EvaluationProjects.B)

    private fun run(project: EvaluationProjects.Project) {
        val dir = EvidenceHarness.directoryOrSkip()
        val label = project.label.lowercase()
        val analyzer = ProjectAnalyzer(EvidenceHarness.fetcher(dir), EvidenceHarness.codec, EvidenceHarness.storage(dir, "run-$label"))
        val run = analyzer.analyze(ProjectInput(project.ownerUrl))
        val candidate = run.candidate ?: return
        val out = File(dir, "iter/visual-$label").apply { mkdirs() }
        val text = StringBuilder()
        text.appendLine("== facades")
        candidate.visual.facades.forEach { text.appendLine("  ${it.role} → ${it.sides} (${fmt(it.confidence)}): ${it.reason}") }
        text.appendLine("== conflicts ${candidate.visual.conflicts.size}")
        candidate.visual.conflicts.forEach { text.appendLine("  ${it.id} ${it.kind} ${it.severity} facade=${it.facade} conf=${fmt(it.confidence)}: ${it.candidateReading} | ${it.sourceReading}") }
        text.appendLine("== appearance ${candidate.visual.appearance.size}")
        candidate.visual.appearance.forEach { text.appendLine("  ${it.featureId} ${it.kind} conf=${fmt(it.confidence)} ${it.fidelity} region=${it.affectedRegion.facade} ${it.affectedRegion.facadeFraction?.let { b -> "[${fmt(b.left)},${fmt(b.top)}-${fmt(b.right)},${fmt(b.bottom)}]" } ?: ""} params=${it.presentationParameters.mapValues { e -> fmt(e.value) }}: ${it.note}") }
        candidate.visual.notes.forEach { text.appendLine("note: $it") }
        candidate.visual.assets.forEach { asset ->
            text.appendLine("== ${asset.role} ${asset.viewpoint} ${asset.widthPx}x${asset.heightPx} confidence ${fmt(asset.confidence)} ${asset.fidelity}")
            asset.notes.forEach { text.appendLine("  note: $it") }
            asset.observations.forEachIndexed { i, o ->
                text.appendLine("  [$i] ${o.kind} conf=${fmt(o.confidence)} [${fmt(o.bounds.left)},${fmt(o.bounds.top)}-${fmt(o.bounds.right)},${fmt(o.bounds.bottom)}] ${o.method}${if (o.note.isNotBlank()) " — ${o.note}" else ""}")
            }
            val record = run.assets?.manifest?.assets?.firstOrNull { it.url == asset.assetUrl } ?: return@forEach
            val image = run.assets?.image(record) ?: return@forEach
            val name = "${asset.role.name.lowercase()}-${asset.assetUrl.substringAfterLast('_').substringBefore('.')}"
            ImageIO.write(overlay(image, asset.observations.map { it.kind to it.bounds }), "png", File(out, "$name.png"))
            val (_, layers) = ElevationReader.readWithLayers(asset.assetUrl, asset.role, image)
            ImageIO.write(classes(image, layers), "png", File(out, "$name-classes.png"))
        }
        EvidenceHarness.write(out, "observations.txt", text.toString())
    }

    private fun overlay(image: RasterImage, boxes: List<Pair<VisualObservationKind, com.buildplan.app.analyzer.candidate.NormalizedBox>>): BufferedImage {
        val scale = 2
        val out = BufferedImage(image.width * scale, image.height * scale, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.drawImage(ImageIoRasterCodec.toBufferedImage(image), 0, 0, image.width * scale, image.height * scale, null)
        g.stroke = BasicStroke(2f)
        boxes.forEach { (kind, b) ->
            g.color = when (kind) {
                VisualObservationKind.BUILDING_SILHOUETTE -> Color.MAGENTA
                VisualObservationKind.ROOFLINE_SEGMENT -> Color.YELLOW
                VisualObservationKind.ROOF_APEX, VisualObservationKind.RIDGE_LINE -> Color.RED
                VisualObservationKind.EAVE_LINE -> Color.ORANGE
                VisualObservationKind.ROOF_REGION -> Color(128, 0, 255)
                VisualObservationKind.OPENING_RECTANGLE -> Color.CYAN
                VisualObservationKind.DOOR_RECTANGLE -> Color(0, 200, 255)
                VisualObservationKind.GARAGE_GATE_RECTANGLE -> Color.BLUE
                VisualObservationKind.DARK_MASS -> Color(0, 100, 0)
                VisualObservationKind.HORIZONTAL_BAND -> Color.PINK
                VisualObservationKind.CLADDING_PATCH -> Color(200, 120, 0)
                VisualObservationKind.FRAME_OR_PORTAL -> Color.WHITE
                VisualObservationKind.RAILING_STRIP -> Color(0, 255, 128)
                VisualObservationKind.ROOF_STACK -> Color.RED
                VisualObservationKind.ROOFLIGHT_PATCH -> Color.GREEN
                VisualObservationKind.ROOF_COVER_TEXTURE -> Color(90, 90, 90)
                VisualObservationKind.GABLE_READ, VisualObservationKind.HIP_READ -> Color(255, 255, 255, 0)
            }
            val x = (b.left * out.width).toInt()
            val y = (b.top * out.height).toInt()
            val w = ((b.right - b.left) * out.width).toInt().coerceAtLeast(1)
            val h = ((b.bottom - b.top) * out.height).toInt().coerceAtLeast(1)
            if (g.color.alpha > 0) g.drawRect(x, y, w, h)
        }
        g.dispose()
        return out
    }

    private fun classes(image: RasterImage, layers: ElevationReader.Layers): BufferedImage {
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val c = when (layers.classes[x, y]) {
                PixelClass.SKY -> 0x6FA8DC
                PixelClass.VEGETATION -> 0x2E8B57
                PixelClass.WHITE -> 0xFFFFFF
                PixelClass.GREY -> 0xA0A0A0
                PixelClass.DARK -> 0x202020
                PixelClass.WOOD -> 0xD2691E
                PixelClass.GLASS -> 0x0033AA
                PixelClass.OTHER -> 0xFF00FF
            }
            val inBuilding = layers.building[x, y]
            val inSeed = layers.seed[x, y]
            val roof = layers.roofRegion?.get(x, y) == true
            val nonWall = layers.nonWall?.get(x, y) == true
            out.setRGB(x, y, when {
                nonWall -> 0xFF3333
                roof -> (c and 0xFEFEFE) shr 1 or 0x400000
                inBuilding -> c
                inSeed -> (c shr 1) and 0x7F7F7F or 0x004000
                else -> (c shr 2) and 0x3F3F3F
            })
        }
        return out
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.2f", v)
}
