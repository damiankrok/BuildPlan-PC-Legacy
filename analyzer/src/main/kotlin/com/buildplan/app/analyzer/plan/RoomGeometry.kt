package com.buildplan.app.analyzer.plan

import com.buildplan.app.analyzer.candidate.PlanCalibration
import com.buildplan.app.analyzer.candidate.Polygon
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.candidate.RingCheck
import com.buildplan.app.analyzer.candidate.RingValidity
import com.buildplan.app.analyzer.candidate.RingVerdict
import com.buildplan.app.analyzer.candidate.RoomGeometryState
import com.buildplan.app.analyzer.raster.BinaryMask
import kotlin.math.abs

/**
 * A room's plan geometry after the outline has been proved, with the two
 * areas that had to agree for it to pass: the pixels the segmentation found
 * and the ring traced around them.
 */
data class ResolvedRing(
    /** Non-null exactly when [state] is [RoomGeometryState.VALID_SIMPLE_RING]. */
    val polygon: Polygon?,
    val state: RoomGeometryState,
    /** Area of the pixels the ring was traced around, thresholds included. */
    val rasterAreaM2: Double,
    val ringAreaM2: Double,
    val maskPixels: Int,
    /**
     * Area of the enclosed regions themselves, measured to the wall faces —
     * the room's floor.
     *
     * Distinct from [rasterAreaM2] on a room the segmentation split: joining
     * the parts means spanning the doorway thresholds between them, and those
     * strips belong to the *outline* but not to the floor. A site publishes
     * room areas to the wall face, so this is the number that is comparable
     * with one, and counting the thresholds into it put a 3.7 m2 vestibule at
     * 4.6 m2 and turned a good match into a mismatch.
     */
    val regionAreaM2: Double,
    val regionPixels: Int,
    val componentCount: Int,
    val check: RingCheck,
    val note: String,
) {
    val coverage: Double get() = if (rasterAreaM2 <= 1e-9) 0.0 else ringAreaM2 / rasterAreaM2
}

/**
 * Turns the pixels of a room into a polygon, or refuses to.
 *
 * The refusal is the point. Before this existed the builder traced whatever
 * outline came back and emitted it, so a room assembled from two regions on
 * either side of a doorway got the ring of the *first* region: its floor area
 * came from the pixel count and its ceiling from the ring, and the two
 * disagreed by a factor of three with nothing in the pipeline able to notice.
 *
 * Three things happen here, in order:
 *
 * 1. **Bridge.** Regions the matcher joined are separated on the raster only
 *    by the strip the segmentation sealed across a wall-line gap — a line the
 *    source never drew. Filling exactly those strips makes the room one
 *    connected shape, which is what it is. Nothing else is ever filled, so a
 *    bridge cannot join two rooms.
 * 2. **Trace and prove.** Outlines are tried smoothest-first and each is put
 *    through [RingValidity]; the first that is a simple ring wins. Jog
 *    smoothing is therefore never able to introduce a crossing that survives.
 * 3. **Reconcile.** The ring's area must agree with the pixel area it came
 *    from. A ring that encloses far less has lost part of the room; one that
 *    encloses far more has swallowed something outside it. Either way the
 *    geometry is [RoomGeometryState.UNRESOLVED_REGION] and the quantities that
 *    need a polygon go unresolved with it, rather than being computed from a
 *    shape that is not the room.
 */
object RoomGeometry {

    /**
     * How far the traced ring may sit from the pixels it was traced around.
     *
     * Below: a ring smaller than its pixels has dropped a lobe of the room.
     * Above: rings are outer boundaries and holes are not modelled, so an
     * interior pier or duct block legitimately puts the ring a little over
     * its pixel count; more than this and the outline is not the room's.
     */
    const val MIN_COVERAGE = 0.90
    const val MAX_COVERAGE = 1.15

    fun resolve(
        mask: BinaryMask,
        calibration: PlanCalibration,
        jogPx: Int,
        /** Pixels of the enclosed regions before any threshold was bridged; defaults to [mask]. */
        regionPixels: Int = mask.count(),
    ): ResolvedRing {
        val ppm = calibration.pixelsPerMeter.requireValue()
        val pixels = mask.count()
        val rasterArea = pixels / (ppm * ppm)
        val regionArea = regionPixels / (ppm * ppm)
        if (pixels == 0) {
            return ResolvedRing(null, RoomGeometryState.UNRESOLVED_REGION, 0.0, 0.0, 0, regionArea, regionPixels, 0, RingCheck(RingVerdict.TOO_FEW_VERTICES, "no pixels"), "the region is empty")
        }

        val components = mask.components()
        // More than one component after bridging: the parts are genuinely apart on the plan.
        // Trace the largest, and let the coverage test below decide whether what is left over
        // is a rounding artefact or half the room.
        val traceMask = if (components.count <= 1) mask else {
            val biggest = (1..components.count).maxByOrNull { components.sizes[it] } ?: 1
            components.maskOf(biggest)
        }
        val traceBox = traceMask.boundingBox()
            ?: return ResolvedRing(null, RoomGeometryState.UNRESOLVED_REGION, rasterArea, 0.0, pixels, regionArea, regionPixels, components.count, RingCheck(RingVerdict.TOO_FEW_VERTICES, "no pixels"), "the region is empty")

        val raw = Regions.traceOutline(traceMask, traceBox)
        val smoothed = Regions.smoothJogs(raw, jogPx.toDouble())
        val notes = mutableListOf<String>()
        if (components.count > 1) {
            notes += "${components.count} disconnected parts; the largest was traced"
        }

        // Smoothing a jog always slides one wall onto the neighbouring one, so it can only ever
        // remove area, never add it: at a 0.15 m tolerance a room with a few shallow recesses
        // loses a real fraction of itself. So try progressively gentler smoothing and accept the
        // first ring that is *both* simple and still covers the pixels it came from. The raw
        // trace is always last and always covers them exactly, which makes it the safety net
        // rather than a fallback that is never reached.
        val attempts = buildList {
            if (jogPx > 1) add("outline, jogs under $jogPx px smoothed" to smoothed)
            if (jogPx > 3) add("outline, jogs under ${jogPx / 2} px smoothed" to Regions.smoothJogs(raw, (jogPx / 2).toDouble()))
            add("raw traced outline" to raw)
        }
        var lastCheck = RingCheck(RingVerdict.TOO_FEW_VERTICES, "no outline traced")
        var lastNote = "no outline traced"
        for ((label, outlinePx) in attempts) {
            val ring = outlinePx.map { calibration.toMeters(it) }
            var check = RingValidity.check(ring)
            var candidate: List<Pt>? = if (check.isValid) ring else null
            var extra = emptyList<String>()
            if (candidate == null) {
                // Not simple as traced: repair may still recover it, but a repair that kept only a
                // fraction of the ring removed a lobe of the room, and that is a finding, not a fix.
                val repair = RingValidity.repair(ring)
                check = repair.check
                if (repair.ring != null && repair.check.isValid && repair.areaRetained >= MIN_COVERAGE) {
                    candidate = repair.ring
                    extra = repair.steps
                } else if (repair.ring != null) {
                    extra = repair.steps + "repair kept only ${percent(repair.areaRetained)} of the ring"
                }
            }
            lastCheck = check
            if (candidate == null) {
                lastNote = "$label: ${check.verdict} (${check.detail})${if (extra.isEmpty()) "" else "; " + extra.joinToString("; ")}"
                continue
            }
            val oriented = RingValidity.normaliseWinding(candidate)
            val polygon = Polygon(oriented)
            val coverage = if (rasterArea <= 1e-9) 0.0 else polygon.area / rasterArea
            if (coverage < MIN_COVERAGE || coverage > MAX_COVERAGE) {
                lastNote = "$label: encloses ${percent(coverage)} of its pixels, outside the ${percent(MIN_COVERAGE)}–${percent(MAX_COVERAGE)} band"
                continue
            }
            return ResolvedRing(
                polygon, RoomGeometryState.VALID_SIMPLE_RING, rasterArea, polygon.area, pixels, regionArea, regionPixels, components.count, check,
                (notes + label + extra).joinToString("; "),
            )
        }
        return ResolvedRing(
            null, RoomGeometryState.UNRESOLVED_REGION, rasterArea, 0.0, pixels, regionArea, regionPixels, components.count, lastCheck,
            (notes + "no simple ring of the right size could be traced; last attempt $lastNote").joinToString("; "),
        )
    }

    private fun percent(v: Double) = "${"%.0f".format(java.util.Locale.ROOT, v * 100)} %"

    /**
     * Fills the sealed-gap strips that separate parts of one room.
     *
     * A gap qualifies only when the regions on *both* of its faces belong to
     * the same room, so the strip being filled is a wall line the segmentation
     * invented and not a wall the source drew. Every other gap is left alone.
     */
    fun bridge(
        mask: BinaryMask,
        gaps: List<PieceGap>,
        regionIndexAt: (Int, Int) -> Int?,
        roomRegionIndices: Set<Int>,
        probePx: Int,
    ): BinaryMask {
        if (roomRegionIndices.size < 2) return mask
        val out = mask.copy()
        var filled = 0
        gaps.forEach { g ->
            val mid = (g.from + g.to) / 2
            fun regionAt(offsetAcross: Int): Int? {
                val c = if (offsetAcross < 0) g.low + offsetAcross else g.high - 1 + offsetAcross
                val x = if (g.axis == Axis.HORIZONTAL) mid else c
                val y = if (g.axis == Axis.HORIZONTAL) c else mid
                return regionIndexAt(x, y)
            }
            val a = regionAt(-probePx)
            val b = regionAt(probePx)
            if (a == null || b == null || a == b) return@forEach
            if (a !in roomRegionIndices || b !in roomRegionIndices) return@forEach
            val horizontal = g.axis == Axis.HORIZONTAL
            val x0 = if (horizontal) g.from else g.low
            val y0 = if (horizontal) g.low else g.from
            val x1 = if (horizontal) g.to else g.high
            val y1 = if (horizontal) g.high else g.to
            for (y in maxOf(0, y0) until minOf(out.height, y1)) for (x in maxOf(0, x0) until minOf(out.width, x1)) {
                if (!out[x, y]) { out[x, y] = true; filled++ }
            }
        }
        return if (filled == 0) mask else out
    }

    /** Whether two areas agree within [tolerance]; used to state flat-ceiling consistency. */
    fun agrees(a: Double, b: Double, tolerance: Double): Boolean {
        val scale = maxOf(abs(a), abs(b))
        return scale <= 1e-9 || abs(a - b) / scale <= tolerance
    }
}

