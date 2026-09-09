package com.buildplan.app.analyzer.plan

import com.buildplan.app.analyzer.candidate.Polygon
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.raster.BinaryMask
import com.buildplan.app.analyzer.raster.Components
import com.buildplan.app.analyzer.raster.PixelBox
import kotlin.math.abs

/** One enclosed white region of the plan, in pixels. */
data class PixelRegion(
    val label: Int,
    val pixelArea: Int,
    val box: PixelBox,
    /** Outer boundary as a rectilinear polygon along pixel edges, simplified. */
    val outline: List<Pt>,
)

/**
 * Enclosed regions of a plan: the connected components of what is neither
 * structure nor outside, each traced into a rectilinear outline.
 */
object Regions {

    fun extract(sealed: BinaryMask, outside: BinaryMask, minPixels: Int): Pair<Components, List<PixelRegion>> {
        val interior = sealed.or(outside).not()
        val components = interior.components()
        val regions = (1..components.count).mapNotNull { label ->
            val size = components.sizes[label]
            if (size < minPixels) return@mapNotNull null
            val box = components.boundingBox(label) ?: return@mapNotNull null
            val mask = components.maskOf(label)
            PixelRegion(label, size, box, traceOutline(mask, box))
        }
        return components to regions
    }

    /**
     * Traces the outer boundary of a 4-connected pixel set along the cracks
     * between pixels, then simplifies it to axis-aligned corners.
     *
     * Walks lattice points keeping the region on the right: at each lattice
     * point the 2x2 pixel neighbourhood decides the turn. Deterministic and
     * free of diagonal ambiguity because the boundary lives on pixel edges.
     */
    fun traceOutline(mask: BinaryMask, box: PixelBox): List<Pt> {
        // Start at the top-left-most set pixel's top-left lattice corner, heading east.
        var startX = -1
        var startY = -1
        loop@ for (y in box.minY..box.maxY) for (x in box.minX..box.maxX) if (mask[x, y]) { startX = x; startY = y; break@loop }
        if (startX < 0) return emptyList()

        val points = mutableListOf<Pt>()
        var x = startX
        var y = startY
        var dir = 0 // 0 east, 1 south, 2 west, 3 north; lattice point (x,y) is the top-left corner of pixel (x,y)
        val maxSteps = mask.width * mask.height * 4
        var steps = 0
        while (steps < maxSteps) {
            // Pixels around lattice point (x, y): NW=(x-1,y-1) NE=(x,y-1) SW=(x-1,y) SE=(x,y).
            // The region is kept on the right of the direction of travel.
            val nw = mask[x - 1, y - 1]
            val ne = mask[x, y - 1]
            val sw = mask[x - 1, y]
            val se = mask[x, y]
            val outgoing = when (dir) {
                0 -> if (!se) 1 else if (ne) 3 else 0
                1 -> if (!sw) 2 else if (se) 0 else 1
                2 -> if (!nw) 3 else if (sw) 1 else 2
                else -> if (!ne) 0 else if (nw) 2 else 3
            }
            // Back at the start corner heading out the same way: the loop is closed.
            if (steps > 0 && x == startX && y == startY && outgoing == 0) break
            points += Pt(x.toDouble(), y.toDouble())
            dir = outgoing
            when (dir) {
                0 -> x++
                1 -> y++
                2 -> x--
                else -> y--
            }
            steps++
        }
        return simplifyRectilinear(points)
    }

    /** Drops collinear intermediate points, keeping only corners. */
    fun simplifyRectilinear(points: List<Pt>): List<Pt> {
        if (points.size < 4) return points
        val out = mutableListOf<Pt>()
        for (i in points.indices) {
            val prev = points[(i - 1 + points.size) % points.size]
            val cur = points[i]
            val next = points[(i + 1) % points.size]
            val collinear = (abs(prev.x - cur.x) < 1e-9 && abs(cur.x - next.x) < 1e-9) ||
                (abs(prev.z - cur.z) < 1e-9 && abs(cur.z - next.z) < 1e-9)
            if (!collinear) out += cur
        }
        return out
    }

    /**
     * Removes jogs shorter than [tolerancePx] from a rectilinear outline —
     * the one-pixel steps an anti-aliased wall edge leaves — by merging each
     * short edge into its neighbours. Repeats until stable.
     */
    fun smoothJogs(points: List<Pt>, tolerancePx: Double): List<Pt> {
        var current = simplifyRectilinear(points).toMutableList()
        while (current.size > 4) {
            val n = current.size
            var shortest = -1
            var shortestLength = tolerancePx
            for (i in 0 until n) {
                val length = current[i].distanceTo(current[(i + 1) % n])
                if (length < shortestLength) {
                    shortestLength = length
                    shortest = i
                }
            }
            if (shortest < 0) break
            // Edge a->b is the jog. Its neighbours prev->a and b->next are parallel to each
            // other and perpendicular to it; sliding prev->a onto b's line removes the jog
            // while every remaining edge stays axis-aligned.
            val a = current[shortest]
            val b = current[(shortest + 1) % n]
            val prevIndex = (shortest - 1 + n) % n
            val prev = current[prevIndex]
            val horizontalJog = abs(a.z - b.z) < 1e-9
            current[prevIndex] = if (horizontalJog) Pt(b.x, prev.z) else Pt(prev.x, b.z)
            current.removeAt(shortest)
            current = simplifyRectilinear(current).toMutableList()
        }
        return current
    }

    fun toPolygon(points: List<Pt>): Polygon? = if (points.size >= 3) Polygon(points).simplified() else null
}
