package com.buildplan.app.analyzer.roof

import com.buildplan.app.analyzer.candidate.Polygon
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.candidate.Pt3
import com.buildplan.app.analyzer.candidate.RoofCandidate
import com.buildplan.app.analyzer.candidate.RoofFacetCandidate
import com.buildplan.app.analyzer.candidate.RoofFamily
import com.buildplan.app.analyzer.candidate.SecondaryRoofMass
import com.buildplan.app.analyzer.candidate.Segment
import com.buildplan.app.analyzer.candidate.Segment3
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.site.PublishedRoofFamily
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.tan

/**
 * Turns a roof outline, a published family and a published pitch into a
 * facet candidate through [RectilinearSkeleton].
 *
 * Which edges are gables is not guessed from a formula: for a gable family
 * the two candidate orientations (ridge along either axis of the outline's
 * bounding box) are both solved, and the one whose facet area reconciles
 * with the published roof area wins — the same reconciliation the hand
 * trace used to settle the ridge direction. When no roof area is published
 * the ridge follows the longer axis and the choice is recorded as an
 * assumption. Hip families sweep every edge.
 */
object RoofSolver {

    data class Input(
        val outline: Polygon,
        val family: PublishedRoofFamily,
        val pitchDegrees: Measured,
        val eaveElevation: Measured,
        val publishedRoofArea: Measured?,
        /** Parts of the ground footprint the roof outline does not cover, each a flat secondary mass. */
        val uncoveredMasses: List<Polygon>,
        val secondaryTopElevation: Measured,
    )

    fun solve(input: Input): RoofCandidate? {
        val pitch = input.pitchDegrees.value ?: return null
        val eave = input.eaveElevation.value ?: return null
        val ring = simplifyOutline(input.outline)
        if (ring.size < 4) return null
        val family = when (input.family) {
            PublishedRoofFamily.GABLE -> RoofFamily.GABLE
            PublishedRoofFamily.HIP, PublishedRoofFamily.MULTI_HIP -> RoofFamily.HIP
            PublishedRoofFamily.FLAT -> RoofFamily.FLAT
            else -> RoofFamily.UNKNOWN
        }
        if (family == RoofFamily.FLAT) return flat(input, ring, eave)

        val tanP = tan(Math.toRadians(pitch))
        val cosP = cos(Math.toRadians(pitch))

        data class Attempt(val gables: List<Int>, val result: RectilinearSkeleton.Result, val area: Double, val note: String)
        val attempts = mutableListOf<Attempt>()
        fun attempt(gables: List<Int>, note: String) {
            val speeds = ring.indices.map { if (it in gables) 0.0 else 1.0 }
            val result = RectilinearSkeleton.solve(ring, speeds)
            val area = result.facets.sumOf { Polygon(it.ring).area } / cosP
            attempts += Attempt(gables, result, area, note)
        }
        if (family == RoofFamily.GABLE) {
            attempt(extremeEdges(ring, alongX = false), "gable ends on the north and south extremes (ridge along Z)")
            attempt(extremeEdges(ring, alongX = true), "gable ends on the west and east extremes (ridge along X)")
        } else {
            attempt(emptyList(), if (family == RoofFamily.HIP) "every eave sweeps: hipped" else "family unknown; solved as hipped")
        }
        val published = input.publishedRoofArea?.value
        val complete = attempts.filter { it.result.complete }.ifEmpty { attempts }
        // The published roof area settles the ridge direction only when the two orientations
        // differ in area — over a rectangle they do not (L × W / cos(pitch) either way), and
        // then the ridge is assumed along the longer axis, which is the common construction,
        // and the assumption is said.
        val bounds = Polygon(ring).bounds
        val longerAxisIsZ = bounds.depth >= bounds.width
        var tieBroken = false
        val chosen = if (published != null) {
            val errors = complete.map { abs(it.area - published) / published }
            val best = errors.min()
            val within = complete.filterIndexed { i, _ -> errors[i] - best < 0.02 }
            if (within.size > 1) {
                tieBroken = true
                within.firstOrNull { it.note.contains(if (longerAxisIsZ) "ridge along Z" else "ridge along X") } ?: within.first()
            } else complete[errors.indexOf(best)]
        } else {
            tieBroken = family == RoofFamily.GABLE
            complete.firstOrNull { it.note.contains(if (longerAxisIsZ) "ridge along Z" else "ridge along X") } ?: complete.first()
        }

        val fidelity = when {
            !chosen.result.complete -> FactFidelity.TRACE_UNCERTAIN
            family == RoofFamily.GABLE && tieBroken -> FactFidelity.DISPLAY_ASSUMPTION
            family == RoofFamily.GABLE -> FactFidelity.SOURCE_DERIVED
            family == RoofFamily.UNKNOWN -> FactFidelity.DISPLAY_ASSUMPTION
            else -> FactFidelity.SOURCE_DERIVED
        }
        val facets = chosen.result.facets.mapIndexed { i, f ->
            val verts = f.ring.mapIndexed { k, p -> Pt3(p.x, eave + f.times[k] * tanP, p.z) }
            val eaveEdge = Segment(ring[f.edge], ring[(f.edge + 1) % ring.size])
            RoofFacetCandidate("roof-facet-${i + 1}", verts, Polygon(f.ring).area / cosP, eaveEdge)
        }
        val ridges = chosen.result.arcs.filter { it.isRidgeLike }.map { a -> Segment3(Pt3(a.a.x, eave + a.ta * tanP, a.a.z), Pt3(a.b.x, eave + a.tb * tanP, a.b.z)) }
        val hips = chosen.result.arcs.filter { !it.outside && !it.isRidgeLike }.map { a -> Segment3(Pt3(a.a.x, eave + a.ta * tanP, a.a.z), Pt3(a.b.x, eave + a.tb * tanP, a.b.z)) }
        val ridgeY = eave + chosen.result.maxTime * tanP
        val eaveLength = ring.indices.filter { it !in chosen.gables }.sumOf { ring[it].distanceTo(ring[(it + 1) % ring.size]) }

        val alternatives = attempts.filter { it !== chosen }.joinToString { "${it.note}: ${"%.1f".format(java.util.Locale.ROOT, it.area)} m2" }
        val note = buildString {
            append(chosen.note)
            if (tieBroken) append(" — ridge direction assumed along the longer axis; the published roof area does not tell the two orientations apart")
            append("; facet area ${"%.1f".format(java.util.Locale.ROOT, chosen.area)} m2")
            if (published != null) append(" vs published ${"%.1f".format(java.util.Locale.ROOT, published)} m2 (${"%.1f".format(java.util.Locale.ROOT, (chosen.area / published - 1) * 100)} %)")
            if (alternatives.isNotBlank()) append("; rejected: $alternatives")
            if (!chosen.result.complete) append("; skeleton incomplete: ${chosen.result.note}")
        }
        val inputs = listOf(input.pitchDegrees, input.eaveElevation)
        return RoofCandidate(
            family = family,
            pitchDegrees = input.pitchDegrees,
            outline = Polygon(ring),
            eaveElevation = input.eaveElevation,
            ridgeElevation = Measured.derived(ridgeY, MeasureUnit.METER, "eave + skeleton rise * tan(pitch)", inputs),
            facets = facets,
            totalArea = Measured(
                chosen.area, MeasureUnit.SQUARE_METER, fidelity,
                Provenance.derived("sum of skeleton facets / cos(pitch)", listOf(input.pitchDegrees.provenance.method, "roof outline from the plan")),
                note = note,
            ),
            ridgeLines = ridges,
            hipLines = hips,
            eaveLength = Measured.derived(eaveLength, MeasureUnit.METER, "sum of sweeping outline edges", listOf(input.eaveElevation)),
            gableEdgeIndices = chosen.gables,
            fidelity = fidelity,
            note = note,
            secondaryMasses = input.uncoveredMasses.mapIndexed { i, mass -> flatMass("roof-mass-${i + 1}", mass, input.secondaryTopElevation) },
        )
    }

    private fun flat(input: Input, ring: List<Pt>, eave: Double): RoofCandidate = RoofCandidate(
        family = RoofFamily.FLAT,
        pitchDegrees = input.pitchDegrees,
        outline = Polygon(ring),
        eaveElevation = input.eaveElevation,
        ridgeElevation = input.eaveElevation,
        facets = listOf(RoofFacetCandidate("roof-facet-1", ring.map { Pt3(it.x, eave, it.z) }, Polygon(ring).area, Segment(ring[0], ring[1]))),
        totalArea = Measured.derived(Polygon(ring).area, MeasureUnit.SQUARE_METER, "flat roof plan area", listOf(input.eaveElevation)),
        ridgeLines = emptyList(),
        hipLines = emptyList(),
        eaveLength = Measured.derived(Polygon(ring).perimeter, MeasureUnit.METER, "outline perimeter", listOf(input.eaveElevation)),
        gableEdgeIndices = emptyList(),
        fidelity = FactFidelity.SOURCE_DERIVED,
        note = "flat roof over the outline",
        secondaryMasses = input.uncoveredMasses.mapIndexed { i, mass -> flatMass("roof-mass-${i + 1}", mass, input.secondaryTopElevation) },
    )

    private fun flatMass(id: String, mass: Polygon, top: Measured): SecondaryRoofMass = SecondaryRoofMass(
        id = id,
        outline = mass,
        family = RoofFamily.FLAT,
        facets = listOf(RoofFacetCandidate("$id-facet", mass.vertices.map { Pt3(it.x, top.value ?: 0.0, it.z) }, mass.area, Segment(mass.vertices[0], mass.vertices[1]))),
        topElevation = top,
        fidelity = FactFidelity.DISPLAY_ASSUMPTION,
        note = "part of the ground footprint outside the roof outline; drawn with a flat top at the storey level because the source states no roof for it",
    )

    /**
     * The edges on the two extremes of the outline along one axis — the
     * candidates for gable ends. For a rectangle that is exactly two edges;
     * for a footprint with steps it is every edge lying on the extreme line.
     */
    private fun extremeEdges(ring: List<Pt>, alongX: Boolean): List<Int> {
        val n = ring.size
        val minV = if (alongX) ring.minOf { it.x } else ring.minOf { it.z }
        val maxV = if (alongX) ring.maxOf { it.x } else ring.maxOf { it.z }
        return ring.indices.filter { i ->
            val a = ring[i]
            val b = ring[(i + 1) % n]
            val onLine = if (alongX) abs(a.x - b.x) < 1e-9 && (abs(a.x - minV) < 1e-6 || abs(a.x - maxV) < 1e-6)
            else abs(a.z - b.z) < 1e-9 && (abs(a.z - minV) < 1e-6 || abs(a.z - maxV) < 1e-6)
            onLine
        }
    }

    /**
     * A roof spans a shallow recess in the wall line — a sheltered entrance, a
     * portal between two cheeks, a loggia — rather than dipping into it. A
     * rectangular notch (in, along, out, with the in and out edges of equal
     * length) no deeper than [maxDepthM] and wider than it is deep is filled;
     * a deep re-entrant corner of an L or T footprint is left alone, because
     * there the roof really does turn.
     */
    fun fillShallowNotches(ring: List<Pt>, maxDepthM: Double): List<Pt> {
        var current = ring
        var changed = true
        var guard = 0
        while (changed && guard++ < 50) {
            changed = false
            val n = current.size
            if (n < 6) break
            for (i in 0 until n) {
                val a = current[i]
                val b = current[(i + 1) % n]
                val c = current[(i + 2) % n]
                val d = current[(i + 3) % n]
                val inLen = a.distanceTo(b)
                val outLen = c.distanceTo(d)
                val width = b.distanceTo(c)
                if (abs(inLen - outLen) > 1e-6 || inLen > maxDepthM || width < 2 * inLen) continue
                // In and out must be anti-parallel and the bottom perpendicular to them.
                val inDx = b.x - a.x; val inDz = b.z - a.z
                val outDx = d.x - c.x; val outDz = d.z - c.z
                if (abs(inDx + outDx) > 1e-6 || abs(inDz + outDz) > 1e-6) continue
                // The notch must be concave: the bottom lies inside the hull of a and d, i.e. the
                // polygon interior is on the far side of the line a-d from b and c.
                val poly = Polygon(current)
                val mid = Pt((a.x + d.x) / 2, (a.z + d.z) / 2)
                val towardsBottom = Pt(mid.x + (b.x - a.x) * 0.5, mid.z + (b.z - a.z) * 0.5)
                if (poly.contains(towardsBottom)) continue // interior on the notch side: this is a bump, not a notch
                current = current.filterIndexed { k, _ -> k != (i + 1) % n && k != (i + 2) % n }
                changed = true
                break
            }
        }
        return Polygon(current).simplified().vertices
    }

    /**
     * Roof planes do not follow every jog a wall trace has. Jogs shorter than
     * [minEdgeM] are removed the same way the plan trace removes pixel steps.
     */
    fun simplifyOutline(polygon: Polygon, minEdgeM: Double = 0.35, maxNotchDepthM: Double = 1.5): List<Pt> {
        var current = fillShallowNotches(polygon.simplified().vertices, maxNotchDepthM).toMutableList()
        while (current.size > 4) {
            val n = current.size
            var shortest = -1
            var shortestLength = minEdgeM
            for (i in 0 until n) {
                val length = current[i].distanceTo(current[(i + 1) % n])
                if (length < shortestLength) { shortestLength = length; shortest = i }
            }
            if (shortest < 0) break
            val a = current[shortest]
            val b = current[(shortest + 1) % n]
            val prevIndex = (shortest - 1 + n) % n
            val prev = current[prevIndex]
            val horizontalJog = abs(a.z - b.z) < 1e-9
            current[prevIndex] = if (horizontalJog) Pt(b.x, prev.z) else Pt(prev.x, b.z)
            current.removeAt(shortest)
            current = Polygon(current).simplified().vertices.toMutableList()
        }
        // Snap to axis: a trace is axis-aligned up to floating noise.
        for (i in current.indices) {
            val a = current[i]
            val b = current[(i + 1) % current.size]
            if (abs(a.x - b.x) > 1e-9 && abs(a.z - b.z) > 1e-9) {
                current[(i + 1) % current.size] = if (abs(a.x - b.x) < abs(a.z - b.z)) Pt(a.x, b.z) else Pt(b.x, a.z)
            }
        }
        return Polygon(current).simplified().vertices
    }
}
