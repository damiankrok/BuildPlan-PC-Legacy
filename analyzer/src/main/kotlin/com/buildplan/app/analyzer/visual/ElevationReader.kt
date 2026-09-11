package com.buildplan.app.analyzer.visual

import com.buildplan.app.analyzer.asset.AssetRole
import com.buildplan.app.analyzer.candidate.NormalizedBox
import com.buildplan.app.analyzer.candidate.VisualAssetEvidence
import com.buildplan.app.analyzer.candidate.VisualObservation
import com.buildplan.app.analyzer.candidate.VisualObservationKind
import com.buildplan.app.analyzer.candidate.VisualViewpoint
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.raster.BinaryMask
import com.buildplan.app.analyzer.raster.PixelBox
import com.buildplan.app.analyzer.raster.RasterImage
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads what a deterministic pass can read off one elevation drawing or one
 * render: the building's silhouette, its roofline, where the roof meets the
 * wall, the dark and glazed rectangles in the wall, the warm cladding, the
 * long horizontal edges, the spikes above the roof and the small patches in
 * it, and whether the roof shows regular courses.
 *
 * Every rule here is a colour or a shape rule, stated in the code, with the
 * limits it is known to have (a cloud is white; a door in a dark wall is
 * invisible). Nothing is learned and nothing leaves the process. The output
 * is *observations* — where in the picture, how sure, by which rule — and
 * never a metre: an elevation gives ratios of the building's own box, a
 * render gives presence.
 *
 * Reads elevations as [VisualViewpoint.ORTHOGRAPHIC_ELEVATION] and hero or
 * unnamed renders as [VisualViewpoint.PERSPECTIVE_RENDER]; the second get
 * only the coarse observations and the weaker fidelity.
 */
object ElevationReader {

    /** The silhouette and the layers a debug harness may want to draw. */
    class Layers(
        val classes: PictureClasses,
        /** The fabric seed after margins, clouds and the vertical opening: what the building was chosen from. */
        val seed: BinaryMask,
        val building: BinaryMask,
        val silhouette: PixelBox?,
        val roofRegion: BinaryMask?,
        val nonWall: BinaryMask?,
        val marginLeft: Int,
        val marginRight: Int,
    )

    fun viewpointOf(role: AssetRole): VisualViewpoint? = when {
        role.isElevation -> VisualViewpoint.ORTHOGRAPHIC_ELEVATION
        role == AssetRole.HERO_RENDER || role == AssetRole.UNKNOWN_RELEVANT_IMAGE -> VisualViewpoint.PERSPECTIVE_RENDER
        else -> null
    }

    fun read(url: String, role: AssetRole, image: RasterImage): VisualAssetEvidence = readWithLayers(url, role, image).first

    fun readWithLayers(url: String, role: AssetRole, image: RasterImage): Pair<VisualAssetEvidence, Layers> {
        val viewpoint = viewpointOf(role) ?: VisualViewpoint.PERSPECTIVE_RENDER
        val elevation = viewpoint == VisualViewpoint.ORTHOGRAPHIC_ELEVATION
        val fidelity = if (elevation) FactFidelity.SOURCE_DERIVED else FactFidelity.TRACE_UNCERTAIN
        val classes = PictureClasses.of(image)
        val w = image.width
        val h = image.height
        val notes = mutableListOf<String>()
        val out = mutableListOf<VisualObservation>()

        // 1. Margins: a column with no sky and no garden in it is a frame or a logo strip, not the scene.
        val marginRight = marginColumns(classes, fromRight = true)
        val marginLeft = marginColumns(classes, fromRight = false)
        if (marginRight > 0 || marginLeft > 0) notes += "margin columns: left $marginLeft, right $marginRight"

        // 2. The building: the fabric classes — white, grey, dark, timber — with thin strips (the
        //    path, a wisp of cloud) opened away, as the largest piece that does not touch the top
        //    edge, then with its holes filled so the glazing inside it belongs to it. Glass is left
        //    out of the seed on purpose: a watermark over the sky darkens the blue into the glass
        //    class, and a silhouette grown from it grows the watermark's letters.
        val fabric = classes.mask(PixelClass.WHITE, PixelClass.GREY, PixelClass.DARK, PixelClass.WOOD)
        for (y in 0 until h) {
            for (x in 0 until marginLeft) fabric[x, y] = false
            for (x in w - marginRight until w) fabric[x, y] = false
        }
        // A cloud is building-white. What tells it from a wall is its company: a cloud borders
        // sky on nearly every side, a wall borders a roof, a window, a door or the ground.
        removeClouds(classes, fabric)
        val opened = fabric.open(0, OPEN_RADIUS_PX)
        val components = opened.components()
        val candidates = (1..components.count).mapNotNull { label ->
            val box = components.boundingBox(label) ?: return@mapNotNull null
            Triple(label, components.sizes[label], box)
        }
        val chosen = candidates.filter { it.third.minY > 0 }.maxByOrNull { it.second } ?: candidates.maxByOrNull { it.second }
        if (chosen == null) {
            notes += "no building-coloured region found"
            return VisualAssetEvidence(url, role, viewpoint, w, h, emptyList(), 0.0, fidelity, notes) to Layers(classes, opened, opened, null, null, null, marginLeft, marginRight)
        }
        val building = fillHoles(components.maskOf(chosen.first))
        val groundTop = groundRow(building, marginLeft, marginRight, chosen.third)
        // Everything below the ground row is the path the building stands on, not the building.
        for (y in groundTop + 1 until h) for (x in 0 until w) building[x, y] = false
        val box = building.boundingBox() ?: chosen.third
        val bw = box.width
        val bh = box.height
        val touchesSide = box.minX <= marginLeft + 1 || box.maxX >= w - marginRight - 2
        val assetConfidence = when {
            !elevation -> 0.4
            touchesSide -> 0.5
            else -> 0.75
        }
        if (touchesSide) notes += "silhouette touches a picture edge; the building may be cropped"
        notes += "building: ${chosen.second} px of ${candidates.size} fabric pieces; runner-up ${candidates.filter { it.first != chosen.first }.maxOfOrNull { it.second } ?: 0} px"
        out += VisualObservation(VisualObservationKind.BUILDING_SILHOUETTE, norm(box, w, h), if (touchesSide) 0.6 else 0.9, "largest region that is neither sky-blue nor garden-green, opened by $OPEN_RADIUS_PX px", fidelity)

        // 3. The roofline: the top edge of the silhouette per column.
        val top = IntArray(bw) { i ->
            val x = box.minX + i
            var y = box.minY
            while (y <= box.maxY && !building[x, y]) y++
            y
        }
        val smooth = median3(top)
        val stacks = if (elevation) stacks(smooth) else emptyList()
        stacks.forEach { (x0, x1, base, peak) ->
            out += VisualObservation(VisualObservationKind.ROOF_STACK, norm(PixelBox(box.minX + x0, peak, box.minX + x1, base), w, h), 0.7, "narrow flat-topped spike above the roofline (${x1 - x0 + 1} px wide, ${base - peak} px tall)", fidelity)
        }
        val profile = withoutStacks(smooth, stacks)
        val apexY = profile.min()
        val apexColumns = profile.indices.filter { profile[it] <= apexY + 2 }
        val apexWidth = (apexColumns.max() - apexColumns.min() + 1).toDouble() / bw
        val segments = if (elevation) simplify(profile, ROOFLINE_TOLERANCE_PX) else emptyList()
        segments.forEach { (i0, i1) ->
            val y0 = profile[i0]
            val y1 = profile[i1]
            val slope = if (i1 > i0) (y1 - y0).toDouble() / (i1 - i0) else 0.0
            out += VisualObservation(
                VisualObservationKind.ROOFLINE_SEGMENT,
                norm(PixelBox(box.minX + i0, min(y0, y1), box.minX + i1, max(y0, y1)), w, h),
                0.8, "roofline piece, slope ${fmt(slope)} (picture rows per column)", fidelity,
                note = if (abs(slope) < FLAT_SLOPE) "flat" else if (slope < 0) "rising to the right" else "falling to the right",
            )
        }
        if (elevation) {
            val gable = gableRead(profile, segments, apexWidth)
            val hip = hipRead(profile, segments, apexWidth, bw)
            if (apexWidth < APEX_POINT_SHARE) {
                out += VisualObservation(VisualObservationKind.ROOF_APEX, norm(PixelBox(box.minX + apexColumns.min(), apexY, box.minX + apexColumns.max(), apexY), w, h), 0.8, "highest point of the roofline", fidelity)
            } else {
                out += VisualObservation(VisualObservationKind.RIDGE_LINE, norm(PixelBox(box.minX + apexColumns.min(), apexY, box.minX + apexColumns.max(), apexY), w, h), 0.7, "flat top of the roofline, ${fmt(apexWidth * 100, 0)} % of the building wide", fidelity)
            }
            if (gable > 0.0) out += VisualObservation(VisualObservationKind.GABLE_READ, norm(box, w, h), gable, "two opposing slopes meet at a point", fidelity, rejectedAlternatives = if (hip > 0.0) listOf("hip read ${fmt(hip)}") else emptyList())
            if (hip > 0.0) out += VisualObservation(VisualObservationKind.HIP_READ, norm(box, w, h), hip, "a flat top between two slopes", fidelity, rejectedAlternatives = if (gable > 0.0) listOf("gable read ${fmt(gable)}") else emptyList())
        }

        // 4. The roof region and the eave: the dark body hanging from the roofline.
        val roofRegion = if (elevation) roofRegion(classes, building, box, image, profile) else null
        var eaveY: Int? = null
        if (roofRegion != null) {
            val rb = roofRegion.boundingBox()
            if (rb != null) {
                out += VisualObservation(VisualObservationKind.ROOF_REGION, norm(rb, w, h), 0.75, "largest dark region hanging from the roofline", fidelity)
                // The eave is where the roof body stops being wide: leaks down a dark frame or a
                // shadow are narrow and do not move it.
                val roofWidth = rb.width
                val eave = (rb.minY..rb.maxY).lastOrNull { y -> (rb.minX..rb.maxX).count { x -> roofRegion[x, y] } >= roofWidth * EAVE_COVERAGE }
                if (eave != null) {
                    eaveY = eave
                    out += VisualObservation(VisualObservationKind.EAVE_LINE, norm(PixelBox(rb.minX, eave, rb.maxX, eave), w, h), 0.7, "lowest row where the roof body still spans ${fmt(EAVE_COVERAGE * 100, 0)} % of its width", fidelity)
                }
                roofCover(image, roofRegion, rb)?.let { (period, strength) ->
                    out += VisualObservation(VisualObservationKind.ROOF_COVER_TEXTURE, norm(rb, w, h), strength, "row-luma autocorrelation in the roof region peaks at $period px", fidelity, note = "period $period px, ${fmt((rb.height).toDouble() / period, 0)} courses")
                }
                rooflights(image, roofRegion, rb).forEach { pb ->
                    out += VisualObservation(VisualObservationKind.ROOFLIGHT_PATCH, norm(pb, w, h), 0.6, "small light rectangle inside the roof region", fidelity)
                }
            }
        }
        // A gable seen end-on shows no roof surface and gets no eave line: its wall is the whole
        // silhouette, and a corner height read off columns that may belong to a lower body beside
        // it would be a number without a meaning.

        // 5. The wall and what interrupts it.
        val groundY = box.maxY
        val nonWall: BinaryMask? = if (elevation) {
            val wallTop = eaveY ?: box.minY
            val (nw, wallLuma) = nonWallMask(image, classes, building, roofRegion, box, wallTop)
            notes += "wall luma reference $wallLuma"
            val comps = nw.components()
            for (label in 1..comps.count) {
                val pb = comps.boundingBox(label) ?: continue
                val pixels = comps.sizes[label]
                val fill = pixels.toDouble() / pb.area
                val widthFrac = pb.width.toDouble() / bw
                val heightFrac = pb.height.toDouble() / bh
                if (pb.width < 4 || pb.height < 4) continue
                val touchesGround = pb.maxY >= groundY - max(3, (bh * GROUND_TOLERANCE).toInt())
                val glassShare = classes.count(pb.minX, pb.minY, pb.maxX + 1, pb.maxY + 1, PixelClass.GLASS).toDouble() / pixels
                val aspect = pb.width.toDouble() / pb.height
                // An opening is a hole *in* a wall: it has wall on both sides of it. A dark gable
                // end, a shadowed recess or a lower body has picture behind it on at least one
                // side, and that is what stops the whole gable of an end elevation — which shows
                // no roof surface, so nothing else marks it as roof — from reading as glazing.
                val framed = framedByWall(classes, building, roofRegion, pb)
                val kind = when {
                    fill < RECT_FILL -> null
                    // A lower body beside the main wall: wide, tall, standing on the ground, not glazed.
                    touchesGround && widthFrac >= DARK_MASS_MIN_SHARE && heightFrac >= DARK_MASS_MIN_HEIGHT && glassShare < 0.3 -> VisualObservationKind.DARK_MASS
                    !framed -> null
                    touchesGround && widthFrac >= GATE_MIN_SHARE && widthFrac <= GATE_MAX_SHARE && heightFrac >= GATE_MIN_HEIGHT && aspect >= 1.3 && glassShare < 0.5 -> VisualObservationKind.GARAGE_GATE_RECTANGLE
                    touchesGround && heightFrac >= 0.2 && widthFrac in 0.02..DOOR_MAX_SHARE -> VisualObservationKind.DOOR_RECTANGLE
                    touchesGround && heightFrac >= 0.2 && glassShare >= 0.5 && widthFrac <= 0.5 -> VisualObservationKind.OPENING_RECTANGLE
                    !touchesGround && widthFrac in 0.02..0.5 && heightFrac in 0.04..0.6 -> VisualObservationKind.OPENING_RECTANGLE
                    else -> null
                } ?: continue
                val confidence = (fill * (0.55 + 0.45 * min(1.0, glassShare + 0.5))).coerceIn(0.2, 0.95)
                out += VisualObservation(
                    kind, norm(pb, w, h), confidence,
                    "rectangle of pixels contrasting with the wall (fill ${fmt(fill)}, glass share ${fmt(glassShare)})", fidelity,
                    note = "${fmt(widthFrac * 100, 0)} % of the building wide, ${fmt(heightFrac * 100, 0)} % tall${if (touchesGround) ", on the ground" else ""}",
                )
            }
            nw
        } else null

        // Blue reflections are often as bright as sky. Read them inside the closed
        // building mask independently of dark-wall contrast; retain narrow mullion gaps.
        if (elevation) {
            val glass = classes.mask(PixelClass.GLASS, PixelClass.SKY).and(building)
            val joined = glass.close(max(1, bw / 140), max(1, bh / 100)).components()
            for (label in 1..joined.count) {
                val pb = joined.boundingBox(label) ?: continue
                val fill = joined.sizes[label].toDouble() / pb.area
                val widthShare = pb.width.toDouble() / bw
                val heightShare = pb.height.toDouble() / bh
                if (widthShare !in 0.035..0.60 || heightShare !in 0.075..0.75 || fill < 0.42) continue
                if (pb.minX <= box.minX + 1 || pb.maxX >= box.maxX - 1) continue
                val roofShare = if (roofRegion == null) 0.0 else (pb.minY..pb.maxY).sumOf { y -> (pb.minX..pb.maxX).count { x -> roofRegion[x, y] } }.toDouble() / pb.area
                if (roofShare > 0.25) continue
                val bounds = norm(pb, w, h)
                out.removeAll { it.kind == VisualObservationKind.OPENING_RECTANGLE && it.bounds.left >= bounds.left && it.bounds.right <= bounds.right && it.bounds.top >= bounds.top && it.bounds.bottom <= bounds.bottom }
                if (out.any { it.kind == VisualObservationKind.OPENING_RECTANGLE && abs(it.bounds.centreX - bounds.centreX) < bounds.width / 4 && abs(it.bounds.centreY - bounds.centreY) < bounds.height / 4 && it.bounds.width >= bounds.width * 0.8 && it.bounds.height >= bounds.height * 0.8 }) continue
                out += VisualObservation(VisualObservationKind.OPENING_RECTANGLE, bounds, min(0.85, 0.55 + fill * 0.3),
                    "blue reflection enclosed by building fabric; mullion gaps closed at image-relative scale", fidelity,
                    note = "glazing group bounding box; sloped head and railing occlusion remain uncertain")
            }
        }

        // 6. Cladding, and a frame round it; a wide, low timber panel on the ground is a gate.
        val wood = classes.mask(PixelClass.WOOD).and(building)
        val woodComps = wood.components()
        for (label in 1..woodComps.count) {
            val pb = woodComps.boundingBox(label) ?: continue
            val share = woodComps.sizes[label].toDouble() / (bw * bh)
            if (share < CLADDING_MIN_SHARE) continue
            val widthFrac = pb.width.toDouble() / bw
            val heightFrac = pb.height.toDouble() / bh
            val fill = woodComps.sizes[label].toDouble() / pb.area
            val onGround = pb.maxY >= groundY - max(3, (bh * GROUND_TOLERANCE).toInt())
            if (elevation && onGround && fill >= RECT_FILL && widthFrac in GATE_MIN_SHARE..GATE_MAX_SHARE && heightFrac >= GATE_MIN_HEIGHT && pb.width.toDouble() / pb.height >= 1.3) {
                out += VisualObservation(VisualObservationKind.GARAGE_GATE_RECTANGLE, norm(pb, w, h), min(0.9, 0.5 + fill * 0.4), "wide, low timber panel standing on the ground", fidelity, note = "${fmt(widthFrac * 100, 0)} % of the building wide, ${fmt(heightFrac * 100, 0)} % tall, on the ground")
                continue
            }
            out += VisualObservation(VisualObservationKind.CLADDING_PATCH, norm(pb, w, h), min(0.9, 0.5 + share * 5), "warm saturated region inside the silhouette (${fmt(share * 100, 1)} % of it)", fidelity)
            if (elevation) frameAround(classes, building, box, pb, profile)?.let { (frameBox, thickness) ->
                out += VisualObservation(VisualObservationKind.FRAME_OR_PORTAL, norm(frameBox, w, h), 0.7, "light border between the roofline and a clad recess under the gable, ${fmt(thickness * 100, 0)} % of the building wide", fidelity, note = "thickness ${fmt(thickness)}")
            }
        }

        // 7. Long horizontal edges across the wall, and a pale strip riding on one.
        if (elevation) {
            val wallTop = eaveY ?: box.minY
            val bands = horizontalBands(image, building, box, wallTop, groundY).filter { band ->
                // The eave itself and the plinth line are edges too, and they are not bands.
                abs(band.y - wallTop) > bh * BAND_EDGE_EXCLUSION && abs(band.y - groundY) > bh * BAND_EDGE_EXCLUSION
            }
            bands.forEach { (y, x0, x1, coverage) ->
                out += VisualObservation(VisualObservationKind.HORIZONTAL_BAND, norm(PixelBox(x0, y, x1, y + 1), w, h), coverage, "horizontal luma edge across ${fmt(coverage * 100, 0)} % of the building", fidelity, note = "at ${fmt((y - box.minY).toDouble() / bh)} of the building height")
            }
            railings(classes, building, box, bands).forEach { pb ->
                out += VisualObservation(VisualObservationKind.RAILING_STRIP, norm(pb, w, h), 0.55, "pale translucent strip resting on a horizontal edge at mid height", fidelity)
            }
        }

        return VisualAssetEvidence(url, role, viewpoint, w, h, out, assetConfidence, fidelity, notes) to
            Layers(classes, opened, building, box, roofRegion, nonWall, marginLeft, marginRight)
    }

    // ------------------------------------------------------------------ steps

    private fun marginColumns(classes: PictureClasses, fromRight: Boolean): Int {
        val w = classes.width
        val h = classes.height
        var n = 0
        val range = if (fromRight) (w - 1 downTo 0) else (0 until w)
        for (x in range) {
            val scene = classes.count(x, 0, x + 1, h, PixelClass.SKY) + classes.count(x, 0, x + 1, h, PixelClass.VEGETATION)
            if (scene.toDouble() / h < MARGIN_SCENE_SHARE) n++ else break
            if (n > w / 4) break
        }
        return if (n > w / 4) 0 else n
    }

    private fun median3(a: IntArray): IntArray = IntArray(a.size) { i ->
        val v = listOf(a[max(0, i - 1)], a[i], a[min(a.size - 1, i + 1)]).sorted()
        v[1]
    }

    /**
     * Columns rising above their neighbourhood as a narrow, flat-topped spike
     * with the roofline returning to its level on both sides: (x0, x1, base,
     * peak). A hip's step, a dormer's cheek and a tree at the picture's edge
     * fail one of those and are not stacks.
     */
    private fun stacks(profile: IntArray): List<IntArray> {
        val n = profile.size
        if (n < 20) return emptyList()
        val out = mutableListOf<IntArray>()
        val minWidth = max(3, (n * STACK_MIN_SHARE).toInt())
        val maxWidth = (n * STACK_MAX_SHARE).toInt()
        var i = 1
        while (i < n - 1) {
            // A sudden rise: the roofline jumps up by a stack's height in one column.
            val jump = profile[i - 1] - profile[i]
            if (jump < STACK_MIN_RISE_PX) { i++; continue }
            val top = profile[i]
            var j = i
            while (j + 1 < n && abs(profile[j + 1] - top) <= STACK_TOP_TOLERANCE_PX) j++
            val width = j - i + 1
            val drop = if (j + 1 < n) profile[j + 1] - profile[j] else 0
            // ... stays flat for a stack's width, then drops back by about as much, onto a
            // roofline at roughly the height it left — a stack straddles one slope, a dormer's
            // cheek steps between two.
            val similarBases = j + 1 < n && abs(profile[i - 1] - profile[j + 1]) <= max(6, width)
            if (width in minWidth..maxWidth && drop >= STACK_MIN_RISE_PX && similarBases) {
                // The base is the higher neighbour: the conservative height.
                val base = min(profile[i - 1], profile[j + 1])
                out += intArrayOf(i, j, base, (i..j).minOf { profile[it] })
            }
            i = j + 1
        }
        return out
    }

    /** The mask with every enclosed hole filled: the glazing inside a wall is part of the building. */
    private fun fillHoles(mask: BinaryMask): BinaryMask = mask.outsideRegion().not()

    /**
     * Whether [pb] has light wall on both sides of it at its own mid-height:
     * the test of "a hole in a wall" as opposed to "the dark thing this
     * elevation mostly is".
     *
     * Roof pixels count as wall for this purpose — a rooflight is framed by
     * roof — and anything outside the building counts as neither, so a patch
     * running off the silhouette fails.
     */
    private fun framedByWall(classes: PictureClasses, building: BinaryMask, roof: BinaryMask?, pb: PixelBox): Boolean {
        val rows = listOf(pb.minY + pb.height / 4, (pb.minY + pb.maxY) / 2, pb.maxY - pb.height / 4).distinct()
        fun wallAt(x: Int, y: Int): Boolean {
            if (!building[x, y]) return false
            if (roof?.get(x, y) == true) return true
            return classes[x, y] == PixelClass.WHITE || classes[x, y] == PixelClass.GREY || classes[x, y] == PixelClass.WOOD
        }
        fun sideIsWall(fromX: Int, step: Int): Boolean = rows.count { y ->
            (1..FRAMED_REACH_PX).any { d ->
                val x = fromX + step * d
                x in 0 until building.width && wallAt(x, y)
            }
        } >= (rows.size + 1) / 2
        return sideIsWall(pb.minX, -1) && sideIsWall(pb.maxX, 1)
    }

    /**
     * Clears white components whose boundary is almost all sky from [fabric].
     * A wall, a fascia or a cheek always borders something that is not sky —
     * a roof, a pane, a door, the ground — along a good part of its edge.
     */
    private fun removeClouds(classes: PictureClasses, fabric: BinaryMask) {
        val white = classes.mask(PixelClass.WHITE).and(fabric)
        val comps = white.components()
        val w = classes.width
        val h = classes.height
        for (label in 1..comps.count) {
            val pb = comps.boundingBox(label) ?: continue
            var sky = 0
            var other = 0
            for (y in pb.minY..pb.maxY) for (x in pb.minX..pb.maxX) {
                if (comps.label(x, y) != label) continue
                for ((dx, dy) in NEIGHBOURS) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    if (comps.label(nx, ny) == label) continue
                    when (classes[nx, ny]) {
                        PixelClass.SKY, PixelClass.VEGETATION -> sky++
                        PixelClass.WHITE -> Unit
                        else -> other++
                    }
                }
            }
            val boundary = sky + other
            if (boundary > 0 && sky.toDouble() / boundary >= CLOUD_SKY_SHARE) {
                for (y in pb.minY..pb.maxY) for (x in pb.minX..pb.maxX) if (comps.label(x, y) == label) fabric[x, y] = false
            }
        }
    }

    private val NEIGHBOURS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

    /**
     * The first row, from the bottom of [box] upwards, that is not the ground:
     * the path a building stands on is drawn across most of the picture, the
     * building is not.
     */
    private fun groundRow(building: BinaryMask, marginLeft: Int, marginRight: Int, box: PixelBox): Int {
        val sceneWidth = (building.width - marginLeft - marginRight).coerceAtLeast(1)
        var y = box.maxY
        while (y > box.minY) {
            val covered = (marginLeft until building.width - marginRight).count { x -> building[x, y] }
            if (covered.toDouble() / sceneWidth < GROUND_COVERAGE) break
            y--
        }
        return y
    }

    private fun withoutStacks(profile: IntArray, stacks: List<IntArray>): IntArray {
        val out = profile.copyOf()
        stacks.forEach { (x0, x1, base, _) -> for (i in x0..x1) out[i] = base }
        return out
    }

    /** Douglas–Peucker over (index, value): the breakpoints as index pairs. */
    private fun simplify(profile: IntArray, tolerance: Double): List<Pair<Int, Int>> {
        val keep = sortedSetOf(0, profile.size - 1)
        fun split(a: Int, b: Int) {
            if (b - a < 2) return
            var worst = -1
            var worstD = 0.0
            for (i in a + 1 until b) {
                val t = (i - a).toDouble() / (b - a)
                val y = profile[a] + t * (profile[b] - profile[a])
                val d = abs(profile[i] - y)
                if (d > worstD) { worstD = d; worst = i }
            }
            if (worstD > tolerance && worst > 0) {
                keep += worst
                split(a, worst)
                split(worst, b)
            }
        }
        split(0, profile.size - 1)
        return keep.zipWithNext()
    }

    private fun gableRead(profile: IntArray, segments: List<Pair<Int, Int>>, apexWidth: Double): Double {
        if (apexWidth >= APEX_POINT_SHARE || segments.size < 2) return 0.0
        val apexIndex = profile.indices.minBy { profile[it] }
        val before = segments.lastOrNull { it.second <= apexIndex && it.second - it.first >= 4 } ?: return 0.0
        val after = segments.firstOrNull { it.first >= apexIndex && it.second - it.first >= 4 } ?: return 0.0
        val s1 = (profile[before.second] - profile[before.first]).toDouble() / (before.second - before.first)
        val s2 = (profile[after.second] - profile[after.first]).toDouble() / (after.second - after.first)
        if (!(s1 < -GABLE_MIN_SLOPE && s2 > GABLE_MIN_SLOPE)) return 0.0
        val symmetry = 1.0 - min(1.0, abs(abs(s1) - abs(s2)) / max(abs(s1), abs(s2)))
        return (0.55 + 0.4 * symmetry).coerceIn(0.0, 0.95)
    }

    private fun hipRead(profile: IntArray, segments: List<Pair<Int, Int>>, apexWidth: Double, bw: Int): Double {
        // A ridge as wide as the building is a gable roof seen from its side, not a hip.
        if (apexWidth < HIP_FLAT_SHARE || apexWidth > HIP_MAX_FLAT_SHARE) return 0.0
        val apexY = profile.min()
        val flat = segments.filter { (a, b) -> abs(profile[a] - apexY) <= 3 && abs(profile[b] - apexY) <= 3 && b - a >= bw * HIP_FLAT_SHARE }
        if (flat.isEmpty()) return 0.0
        val slopes = segments.filter { (a, b) -> b - a >= 4 && abs((profile[b] - profile[a]).toDouble() / (b - a)) > GABLE_MIN_SLOPE }
        return if (slopes.size >= 2) 0.7 else 0.4
    }

    /**
     * The roof as a surface: the largest dark region that is wide and hangs
     * straight from the roofline. A dark gable wall or a clad recess under a
     * gable is also dark and wide, but it starts a frame's width below the
     * roofline, and that gap is what tells them apart.
     */
    private fun roofRegion(classes: PictureClasses, building: BinaryMask, box: PixelBox, image: RasterImage, profile: IntArray): BinaryMask? {
        val dark = BinaryMask(classes.width, classes.height)
        for (y in box.minY..box.maxY) for (x in box.minX..box.maxX) {
            if (!building[x, y]) continue
            val c = classes[x, y]
            if (c == PixelClass.DARK || (c == PixelClass.GREY && image.luma(x, y) < ROOF_GREY_LUMA)) dark[x, y] = true
        }
        // Closed before it is opened: the light course lines of a tiled roof would otherwise cut
        // the roof into strips, and the strip nearest the roofline would pass for the roof.
        val opened = dark.close(2, 2).open(1, 1)
        val comps = opened.components()
        val best = (1..comps.count).mapNotNull { label ->
            val pb = comps.boundingBox(label) ?: return@mapNotNull null
            if (pb.width.toDouble() / box.width < ROOF_MIN_WIDTH_SHARE) return@mapNotNull null
            // How many of the region's columns start within a few rows of the roofline.
            val mask = comps.maskOf(label)
            var hanging = 0
            var columns = 0
            for (x in pb.minX..pb.maxX) {
                val topOfRegion = (pb.minY..pb.maxY).firstOrNull { y -> mask[x, y] } ?: continue
                columns++
                if (topOfRegion - profile[(x - box.minX).coerceIn(0, profile.size - 1)] <= ROOF_HANG_TOLERANCE_PX) hanging++
            }
            if (columns == 0 || hanging.toDouble() / columns < ROOF_HANG_SHARE) return@mapNotNull null
            label to comps.sizes[label]
        }.maxByOrNull { it.second } ?: return null
        return comps.maskOf(best.first)
    }

    private fun roofCover(image: RasterImage, roof: BinaryMask, rb: PixelBox): Pair<Int, Double>? {
        val rows = (rb.minY..rb.maxY).map { y ->
            var sum = 0.0
            var n = 0
            for (x in rb.minX..rb.maxX) if (roof[x, y]) { sum += image.luma(x, y); n++ }
            if (n < 10) Double.NaN else sum / n
        }.filter { !it.isNaN() }
        if (rows.size < 30) return null
        val window = 9
        val detrended = rows.indices.map { i ->
            val lo = max(0, i - window)
            val hi = min(rows.size - 1, i + window)
            rows[i] - rows.subList(lo, hi + 1).average()
        }
        val variance = detrended.sumOf { it * it }
        if (variance < 1e-6) return null
        var bestLag = 0
        var best = 0.0
        for (lag in 3..14) {
            var s = 0.0
            for (i in 0 until detrended.size - lag) s += detrended[i] * detrended[i + lag]
            val r = s / variance
            if (r > best) { best = r; bestLag = lag }
        }
        return if (best >= COVER_MIN_AUTOCORR) bestLag to min(0.9, best + 0.3) else null
    }

    private fun rooflights(image: RasterImage, roof: BinaryMask, rb: PixelBox): List<PixelBox> {
        val light = BinaryMask(roof.width, roof.height)
        val eroded = roof.erode(3, 3)
        for (y in rb.minY..rb.maxY) for (x in rb.minX..rb.maxX) {
            if (eroded[x, y] && image.luma(x, y) > 140) light[x, y] = true
        }
        val comps = light.components()
        val roofArea = roof.count().toDouble()
        return (1..comps.count).mapNotNull { label ->
            val pb = comps.boundingBox(label) ?: return@mapNotNull null
            val share = comps.sizes[label] / roofArea
            val fill = comps.sizes[label].toDouble() / pb.area
            if (share in 0.002..0.04 && fill >= 0.6 && pb.width >= 4 && pb.height >= 4) pb else null
        }
    }

    private fun nonWallMask(image: RasterImage, classes: PictureClasses, building: BinaryMask, roof: BinaryMask?, box: PixelBox, wallTop: Int): Pair<BinaryMask, Int> {
        val lumas = mutableListOf<Int>()
        for (y in wallTop..box.maxY) for (x in box.minX..box.maxX) {
            if (!building[x, y] || (roof != null && roof[x, y])) continue
            val c = classes[x, y]
            if (c == PixelClass.WHITE || c == PixelClass.GREY) lumas += image.luma(x, y)
        }
        // The wall is the light fabric. A dark secondary body can outnumber it in pixels, and a
        // reference taken over both would put the wall halfway between the two and see nothing.
        val whites = mutableListOf<Int>()
        for (y in wallTop..box.maxY) for (x in box.minX..box.maxX) {
            if (building[x, y] && (roof == null || !roof[x, y]) && classes[x, y] == PixelClass.WHITE) whites += image.luma(x, y)
        }
        val wallLuma = when {
            whites.size >= lumas.size * WHITE_WALL_SHARE && whites.isNotEmpty() -> whites.sorted()[whites.size / 2]
            lumas.isEmpty() -> 220
            else -> lumas.sorted()[lumas.size / 2]
        }
        val nw = BinaryMask(building.width, building.height)
        for (y in wallTop..box.maxY) for (x in box.minX..box.maxX) {
            if (!building[x, y] || (roof != null && roof[x, y])) continue
            val c = classes[x, y]
            if (c == PixelClass.WOOD) continue
            val contrast = abs(image.luma(x, y) - wallLuma)
            if (c == PixelClass.GLASS || c == PixelClass.DARK || contrast > WALL_CONTRAST) nw[x, y] = true
        }
        // Opened by more than a line: a band across the wall must not weld a window to a door.
        return nw.open(2, 3) to wallLuma
    }

    private fun frameAround(classes: PictureClasses, building: BinaryMask, box: PixelBox, patch: PixelBox, profile: IntArray): Pair<PixelBox, Double>? {
        // The patch has to sit up in the gable — starting in the upper half of the silhouette,
        // under a roofline that slopes across it — with the roofline just above it and light
        // building on both sides. A timber gate at the foot of a wall satisfies none of that.
        if (patch.minY > box.minY + box.height * 0.5) return null
        val roofAbove = (patch.minX..patch.maxX).map { x -> profile[(x - box.minX).coerceIn(0, profile.size - 1)] }
        if ((roofAbove.max() - roofAbove.min()) < patch.width * FRAME_MIN_ROOF_SLOPE) return null
        val gapAbove = patch.minY - roofAbove.average()
        if (gapAbove < 2 || gapAbove > box.height * 0.2) return null
        fun lightMargin(fromX: Int, step: Int, y: Int): Int {
            var n = 0
            var x = fromX
            while (x in box.minX..box.maxX && building[x, y] && (classes[x, y] == PixelClass.WHITE || classes[x, y] == PixelClass.GREY)) { n++; x += step }
            return n
        }
        val rows = (patch.minY + patch.height / 4..patch.maxY - patch.height / 4).step(max(1, patch.height / 8)).toList()
        if (rows.isEmpty()) return null
        val left = rows.map { lightMargin(patch.minX - 1, -1, it) }.sorted()[rows.size / 2]
        val right = rows.map { lightMargin(patch.maxX + 1, 1, it) }.sorted()[rows.size / 2]
        val thickness = min(left, right).toDouble() / box.width
        if (thickness < FRAME_MIN_SHARE || thickness > FRAME_MAX_SHARE) return null
        return PixelBox(patch.minX - left, (patch.minY - gapAbove).roundToInt(), patch.maxX + right, patch.maxY) to thickness
    }

    /** Rows with a long horizontal luma edge across the wall: (y, x0, x1, coverage). */
    private fun horizontalBands(image: RasterImage, building: BinaryMask, box: PixelBox, wallTop: Int, groundY: Int): List<IntArrayLike> {
        val rows = mutableListOf<Triple<Int, IntRange, Double>>()
        for (y in wallTop + 3 until groundY - 3) {
            var first = -1
            var last = -1
            var n = 0
            for (x in box.minX..box.maxX) {
                if (!building[x, y - 1] || !building[x, y + 1]) continue
                if (abs(image.luma(x, y - 1) - image.luma(x, y + 1)) > BAND_EDGE) {
                    n++
                    if (first < 0) first = x
                    last = x
                }
            }
            val coverage = n.toDouble() / box.width
            if (coverage >= BAND_MIN_COVERAGE && first >= 0) rows += Triple(y, first..last, coverage)
        }
        // Merge adjacent rows into one band, keeping the strongest.
        val out = mutableListOf<IntArrayLike>()
        var i = 0
        while (i < rows.size) {
            var j = i
            var best = rows[i]
            while (j + 1 < rows.size && rows[j + 1].first - rows[j].first <= 2) { j++; if (rows[j].third > best.third) best = rows[j] }
            out += IntArrayLike(best.first, best.second.first, best.second.last, best.third)
            i = j + 1
        }
        return out.filter { abs(it.y - wallTop) > 4 && abs(it.y - groundY) > 4 }
    }

    class IntArrayLike(val y: Int, val x0: Int, val x1: Int, val coverage: Double) {
        operator fun component1() = y
        operator fun component2() = x0
        operator fun component3() = x1
        operator fun component4() = coverage
    }

    private fun railings(classes: PictureClasses, building: BinaryMask, box: PixelBox, bands: List<IntArrayLike>): List<PixelBox> {
        if (bands.isEmpty()) return emptyList()
        val pale = classes.mask(PixelClass.GLASS, PixelClass.GREY).and(building)
        val comps = pale.components()
        return (1..comps.count).mapNotNull { label ->
            val pb = comps.boundingBox(label) ?: return@mapNotNull null
            val heightFrac = pb.height.toDouble() / box.height
            val widthFrac = pb.width.toDouble() / box.width
            val mid = (pb.centreY - box.minY).toDouble() / box.height
            val onBand = bands.any { abs(it.y - pb.maxY) <= 4 && it.x0 <= pb.maxX && it.x1 >= pb.minX }
            val aspect = pb.width.toDouble() / pb.height
            val glassShare = classes.count(pb.minX, pb.minY, pb.maxX + 1, pb.maxY + 1, PixelClass.GLASS).toDouble() / comps.sizes[label]
            // A railing is long, low and see-through; a window or a fascia resting on a band is not.
            if (heightFrac in 0.03..0.12 && widthFrac >= 0.15 && aspect >= RAILING_MIN_ASPECT && glassShare >= RAILING_MIN_GLASS && mid in 0.25..0.75 && onBand) pb else null
        }
    }

    private val PixelBox.centreY: Int get() = (minY + maxY) / 2

    private fun norm(pb: PixelBox, w: Int, h: Int) = NormalizedBox(pb.minX.toDouble() / w, pb.minY.toDouble() / h, (pb.maxX + 1).toDouble() / w, (pb.maxY + 1).toDouble() / h)

    private fun fmt(v: Double, digits: Int = 2) = String.format(Locale.ROOT, "%.${digits}f", v)

    // ------------------------------------------------------------- thresholds

    /** Vertical opening radius: strips under 2r+1 px tall (a path, a wisp of cloud) drop out of the building. */
    private const val OPEN_RADIUS_PX = 7
    private const val MARGIN_SCENE_SHARE = 0.02
    /** A row this much of the scene's width covered is the ground the building stands on. */
    private const val GROUND_COVERAGE = 0.9
    private const val GROUND_TOLERANCE = 0.06
    private const val ROOFLINE_TOLERANCE_PX = 4.0
    private const val FLAT_SLOPE = 0.08
    private const val APEX_POINT_SHARE = 0.08
    private const val HIP_FLAT_SHARE = 0.15
    private const val HIP_MAX_FLAT_SHARE = 0.7
    private const val GABLE_MIN_SLOPE = 0.25
    private const val STACK_MIN_RISE_PX = 5
    private const val STACK_TOP_TOLERANCE_PX = 3
    private const val STACK_MIN_SHARE = 0.025
    private const val CLOUD_SKY_SHARE = 0.85
    /** How far beside an opening the wall may start: a reveal and a frame, not a facade. */
    private const val FRAMED_REACH_PX = 6
    private const val DARK_MASS_MIN_HEIGHT = 0.25
    private const val RAILING_MIN_GLASS = 0.35
    private const val STACK_MAX_SHARE = 0.08
    private const val ROOF_GREY_LUMA = 120
    private const val ROOF_MIN_WIDTH_SHARE = 0.35
    private const val ROOF_HANG_TOLERANCE_PX = 4
    private const val ROOF_HANG_SHARE = 0.6
    private const val EAVE_COVERAGE = 0.5
    private const val COVER_MIN_AUTOCORR = 0.22
    private const val RECT_FILL = 0.62
    private const val DARK_MASS_MIN_SHARE = 0.25
    private const val GATE_MIN_SHARE = 0.18
    private const val GATE_MAX_SHARE = 0.5
    private const val GATE_MIN_HEIGHT = 0.2
    private const val DOOR_MAX_SHARE = 0.15
    private const val FRAME_MIN_ROOF_SLOPE = 0.2
    private const val WALL_CONTRAST = 55
    private const val WHITE_WALL_SHARE = 0.15
    private const val CLADDING_MIN_SHARE = 0.008
    private const val FRAME_MIN_SHARE = 0.015
    private const val FRAME_MAX_SHARE = 0.15
    private const val BAND_EDGE = 50
    private const val BAND_MIN_COVERAGE = 0.28
    private const val BAND_EDGE_EXCLUSION = 0.04
    private const val RAILING_MIN_ASPECT = 4.0
}
