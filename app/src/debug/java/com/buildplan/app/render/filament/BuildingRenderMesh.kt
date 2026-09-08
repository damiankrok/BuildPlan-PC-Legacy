package com.buildplan.app.render.filament

import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.geometry.BuildingGeometryPrimitive
import com.buildplan.app.geometry.GablePanelGeometry
import com.buildplan.app.geometry.GeometryTolerance
import com.buildplan.app.geometry.LocalBounds
import com.buildplan.app.geometry.ModelPoint
import com.buildplan.app.geometry.OpeningPanelGeometry
import com.buildplan.app.geometry.PlanPoint
import com.buildplan.app.geometry.RoofFacetGeometry
import com.buildplan.app.geometry.SlabGeometry
import com.buildplan.app.geometry.WallGeometry
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * One geometry primitive baked into the triangles a GPU can draw.
 *
 * ## Why baking lives here and not in `geometry/`
 *
 * `geometry/` stores high-level truth — a wall is a centreline with a height and
 * a thickness. How many triangles that becomes, which way they wind and whether
 * their normals are shared or flat are renderer decisions, and a second renderer
 * would make them differently. So the model keeps the centreline, and this
 * adapter — renderer-facing, and living in `src/debug` with the spike — keeps
 * the triangles.
 *
 * ## The join stays the element id
 *
 * A mesh carries [elementId] and nothing else from the semantic model. It does
 * not know its floor, its rooms, its kind or whether it is currently hidden,
 * exactly as [BuildingGeometryPrimitive] does not. Which meshes are on screen is
 * decided upstream by `BuildingElementSelection.kt` and reaches the renderer
 * through the single `primitivesOf` bridge.
 *
 * ## One element, many meshes
 *
 * The relation is one-to-many, inherited unchanged from the geometry layer: the
 * gable roof is one [BuildingElementId] and two facets, so it bakes to two
 * meshes that answer with the same id. Nothing here collapses or splits that.
 *
 * Positions and normals are flat `xyz` triples in the building's local metres,
 * one vertex per corner per face — faces are never welded, so every triangle
 * keeps the flat normal of the face it belongs to, which is what makes a
 * technical model read as faceted rather than smoothed.
 */
class BuildingRenderMesh internal constructor(
    val elementId: BuildingElementId,
    val positions: FloatArray,
    val normals: FloatArray,
    val indices: IntArray,
    /**
     * The shape's feature edges as a line list: its creases, its open
     * boundaries and the reveals round its holes — never the seam between two
     * coplanar faces, and never a triangulation diagonal.
     *
     * It is what turns a solid block into a technical drawing. A lit surface
     * alone tells the eye where a face is but not where it ends — two walls
     * meeting at a shallow angle, or a window head against the wall above it,
     * differ by a few percent of grey and vanish at most viewing angles. Drawing
     * the boundaries on top restores the line every architectural drawing has.
     *
     * Shared edges are deduplicated by position, so the seam where two faces of
     * one prism meet is one line rather than two coincident ones; and where two
     * faces meet in one plane there is no line at all, because the owner sees
     * one surface there. How that is decided is in `MeshAccumulator`.
     */
    val edgeIndices: IntArray,
    /** How this mesh should read: as building fabric, or as the glass in a hole. */
    val style: MeshStyle,
    /** Centre of the axis-aligned box the renderer culls against. */
    val boundsCenter: FloatArray,
    /** Half-size of that box, never zero on any axis — see [nonDegenerateHalfExtent]. */
    val boundsHalfExtent: FloatArray,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3
    val edgeCount: Int get() = edgeIndices.size / 2
}

/**
 * How a baked mesh should be shaded.
 *
 * Derived from the *primitive type*, never from the element's
 * [com.buildplan.app.domain.model.BuildingElementKind]. That distinction is the
 * whole point: "this shape is a thin planar fill of a hole" is a fact about the
 * geometry, and the renderer is allowed to know it. "This element is a window"
 * is a fact about the domain, and if the renderer knew that it would be a second
 * place where the semantics live.
 *
 * They agree here anyway, but they would stop agreeing the moment a shape was
 * used somewhere the kind did not follow — and then the renderer would be the
 * one that was wrong.
 */
enum class MeshStyle {
    /** Walls, slabs, roofs, gables: opaque building fabric. */
    SOLID,

    /** A pane, a leaf, a rooflight: read as an opening rather than as more wall. */
    GLAZING,
}

/** Bakes every primitive in order, so draw order stays the geometry's order. */
fun List<BuildingGeometryPrimitive>.toRenderMeshes(): List<BuildingRenderMesh> =
    map { it.toRenderMesh() }

/**
 * Bakes one primitive.
 *
 * Walls and slabs are prisms: a plan outline extruded between two levels and
 * closed with a cap at each end — a wall with openings is several such prisms,
 * one per solid stretch of it. A roof facet, a gable panel and the pane filling
 * an opening are already planar, so each is triangulated where it is.
 *
 * Caps are fan-triangulated from the first vertex, which is correct for a convex
 * outline and wrong for a concave one. Every outline the model can currently
 * produce is a rectangle; a real plan parser will bring concave rooms with it,
 * and an ear-clipping pass along with them. Writing one now would be untestable
 * against anything.
 */
fun BuildingGeometryPrimitive.toRenderMesh(): BuildingRenderMesh {
    val mesh = MeshAccumulator()
    when (this) {
        is WallGeometry -> mesh.addWall(this)
        is SlabGeometry -> mesh.addPrism(outline, elevation, topElevation)
        is RoofFacetGeometry -> mesh.addPlanarFacet(vertices)
        is GablePanelGeometry -> mesh.addPlanarPanel(vertices)
        is OpeningPanelGeometry -> mesh.addPlanarPanel(vertices)
    }
    val style = if (this is OpeningPanelGeometry) MeshStyle.GLAZING else MeshStyle.SOLID
    return mesh.build(elementId, style, bounds)
}

/**
 * Bakes a set of planar polygons, each extruded [depth] along the shared unit
 * [axis] and centred on its own plane, into one solid mesh under [elementId].
 *
 * The one bake that is not a primitive's own: it is what a presentation
 * layer — the bars of an opening frame — uses to become an ordinary mesh
 * with the same outline rules, the same id join and the same shading as a
 * wall. Returns null when nothing encloses any area.
 */
internal fun bakeExtrudedPolygons(
    elementId: BuildingElementId,
    polygons: List<List<ModelPoint>>,
    axis: DoubleArray,
    depth: Double,
): BuildingRenderMesh? {
    val mesh = MeshAccumulator()
    val corners = ArrayList<ModelPoint>()
    polygons.forEach { polygon ->
        val front = polygon.map { it.along(axis, depth / 2.0) }
        val back = polygon.map { it.along(axis, -depth / 2.0) }
        if (mesh.addExtrudedPolygon(front, back, axis)) {
            corners += front
            corners += back
        }
    }
    if (corners.isEmpty()) return null
    return mesh.build(elementId, MeshStyle.SOLID, LocalBounds.around(corners))
}

private fun ModelPoint.along(axis: DoubleArray, distance: Double): ModelPoint =
    ModelPoint(x + axis[0] * distance, y + axis[1] * distance, z + axis[2] * distance)

/**
 * Collects faces into flat vertex, normal and index arrays.
 *
 * Every face is added with the outward normal it should be shaded by, and its
 * winding is then corrected to match that normal rather than assumed: the
 * spike's material is double-sided, and a double-sided material decides which
 * way to flip a normal from the triangle's facing. A face wound against its own
 * normal would be lit from inside the wall.
 */
private class MeshAccumulator {

    private val positions = ArrayList<Float>()
    private val normals = ArrayList<Float>()
    private val indices = ArrayList<Int>()

    /** The first vertex index seen at each distinct position, for edge dedup. */
    private val vertexAtPosition = HashMap<Long, Int>()

    /** The position each canonical vertex index stands at. */
    private val canonicalPoints = HashMap<Int, ModelPoint>()

    /**
     * Every undirected polygon boundary, packed as the lower index in the high
     * word, with the normal of each face that has it on its boundary.
     *
     * Faces are recorded rather than the edge alone because which edges are
     * *drawn* is decided at build time from them — see [featureEdges].
     */
    private val edgeFaces = LinkedHashMap<Long, MutableList<FloatArray>>()

    /**
     * A wall, split around its openings.
     *
     * A hole is made by *not baking* the piece of wall it occupies: the wall
     * becomes the stretches either side of each opening at full height, plus the
     * sill and the head over the opening itself. Every reveal face — jamb, head,
     * sill — then falls out as an end cap of a neighbouring stretch, which is
     * why nothing here draws one explicitly and why they are always exactly
     * flush with the hole.
     */
    fun addWall(wall: WallGeometry) {
        if (wall.openings.isEmpty()) {
            addPrism(wall.footprint(), wall.baseElevation, wall.topElevation)
            return
        }
        var solidFrom = 0.0
        wall.openings.sortedBy { it.distanceFromStart }.forEach { opening ->
            addWallBand(wall, solidFrom, opening.distanceFromStart, wall.baseElevation, wall.topElevation)
            addWallBand(
                wall,
                opening.distanceFromStart,
                opening.distanceToEnd,
                wall.baseElevation,
                opening.sillElevation,
            )
            addWallBand(
                wall,
                opening.distanceFromStart,
                opening.distanceToEnd,
                opening.headElevation,
                wall.topElevation,
            )
            solidFrom = opening.distanceToEnd
        }
        addWallBand(wall, solidFrom, wall.length, wall.baseElevation, wall.topElevation)
    }

    /**
     * One solid stretch of a wall. Bands with no width or no height are skipped
     * rather than baked flat: a door reaching the floor has no sill band, and a
     * window flush with a corner has no pier beside it.
     */
    private fun addWallBand(
        wall: WallGeometry,
        fromDistance: Double,
        toDistance: Double,
        bottomY: Double,
        topY: Double,
    ) {
        if (toDistance - fromDistance <= GeometryTolerance.LENGTH_METERS) return
        if (topY - bottomY <= GeometryTolerance.LENGTH_METERS) return
        addPrism(wall.footprintBetween(fromDistance, toDistance), bottomY, topY)
    }

    /** A plan outline extruded from [bottomY] to [topY], with both caps closed. */
    fun addPrism(plan: List<PlanPoint>, bottomY: Double, topY: Double) {
        val outline = plan.withoutRepeatedVertices()
        if (outline.size < 3) return

        val positiveWinding = outline.signedPlanArea() > 0.0
        addPolygon(outline.map { it.at(topY) }, floatArrayOf(0f, 1f, 0f))
        addPolygon(outline.map { it.at(bottomY) }, floatArrayOf(0f, -1f, 0f))

        outline.indices.forEach { index ->
            val from = outline[index]
            val to = outline[(index + 1) % outline.size]
            val normal = outwardSideNormal(from, to, positiveWinding) ?: return@forEach
            addPolygon(
                listOf(from.at(bottomY), to.at(bottomY), to.at(topY), from.at(topY)),
                normal,
            )
        }
    }

    /**
     * A prism between two parallel copies of one polygon — [front] offset
     * along [axis], [back] against it — closed with a cap at each end and a
     * side face per edge, every side facing away from the prism's centre.
     * Returns false, and adds nothing, for a polygon that encloses no area.
     */
    fun addExtrudedPolygon(front: List<ModelPoint>, back: List<ModelPoint>, axis: DoubleArray): Boolean {
        val usableFront = front.withoutRepeatedVertices()
        if (usableFront.size < 3 || usableFront.newellUnitNormal() == null) return false
        val usableBack = back.withoutRepeatedVertices()
        if (usableBack.size != usableFront.size) return false

        val forward = floatArrayOf(axis[0].toFloat(), axis[1].toFloat(), axis[2].toFloat())
        addPolygon(usableFront, forward)
        addPolygon(usableBack, floatArrayOf(-forward[0], -forward[1], -forward[2]))

        val centreX = usableFront.sumOf { it.x } / usableFront.size
        val centreY = usableFront.sumOf { it.y } / usableFront.size
        val centreZ = usableFront.sumOf { it.z } / usableFront.size
        usableFront.indices.forEach { index ->
            val next = (index + 1) % usableFront.size
            val from = usableFront[index]
            val to = usableFront[next]
            val edgeX = to.x - from.x
            val edgeY = to.y - from.y
            val edgeZ = to.z - from.z
            // edge × axis, then turned to point away from the polygon's centre.
            var sideX = edgeY * axis[2] - edgeZ * axis[1]
            var sideY = edgeZ * axis[0] - edgeX * axis[2]
            var sideZ = edgeX * axis[1] - edgeY * axis[0]
            val length = sqrt(sideX * sideX + sideY * sideY + sideZ * sideZ)
            if (length <= GeometryTolerance.LENGTH_METERS) return@forEach
            sideX /= length
            sideY /= length
            sideZ /= length
            val midX = (from.x + to.x) / 2.0 - centreX
            val midY = (from.y + to.y) / 2.0 - centreY
            val midZ = (from.z + to.z) / 2.0 - centreZ
            if (midX * sideX + midY * sideY + midZ * sideZ < 0.0) {
                sideX = -sideX
                sideY = -sideY
                sideZ = -sideZ
            }
            addPolygon(
                listOf(usableBack[index], usableBack[next], usableFront[next], usableFront[index]),
                floatArrayOf(sideX.toFloat(), sideY.toFloat(), sideZ.toFloat()),
            )
        }
        return true
    }

    /**
     * A single planar polygon, shaded by its own normal flipped to point
     * upwards: a roof facet is seen from above, and which way its vertices
     * happened to be written is not a fact about the roof.
     */
    fun addPlanarFacet(vertices: List<ModelPoint>) {
        val usable = vertices.withoutRepeatedVertices()
        if (usable.size < 3) return
        val normal = usable.newellUnitNormal() ?: return
        val upwards = if (normal[1] < 0f) {
            floatArrayOf(-normal[0], -normal[1], -normal[2])
        } else {
            normal
        }
        addPolygon(usable, upwards)
    }

    /**
     * A single planar panel with no thickness — a gable, a window pane, a
     * rooflight — shaded by its own normal exactly as written.
     *
     * No flip, unlike [addPlanarFacet]: "outwards" has no meaning for a lone
     * sheet. A gable is seen from one side or the other depending on where the
     * camera is, and a pane is seen from both at once, so the normal is left
     * alone and the spike's double-sided material resolves the facing per
     * fragment.
     */
    fun addPlanarPanel(vertices: List<ModelPoint>) {
        val usable = vertices.withoutRepeatedVertices()
        if (usable.size < 3) return
        val normal = usable.newellUnitNormal() ?: return
        addPolygon(usable, normal)
    }

    private fun addPolygon(points: List<ModelPoint>, normal: FloatArray) {
        if (points.size < 3) return
        val ordered = if (points.newellDot(normal) < 0.0) points.reversed() else points
        val base = positions.size / 3

        ordered.forEach { point ->
            positions += point.x.toFloat()
            positions += point.y.toFloat()
            positions += point.z.toFloat()
            normals += normal[0]
            normals += normal[1]
            normals += normal[2]
        }

        for (corner in 1 until ordered.size - 1) {
            indices += base
            indices += base + corner
            indices += base + corner + 1
        }

        // The face's own boundary, keyed by distinct pair of positions so the
        // seam two faces share is one entry, and tagged with this face's
        // normal so the build can tell a crease from a seam.
        ordered.indices.forEach { corner ->
            val from = canonicalVertex(ordered[corner], base + corner)
            val next = (corner + 1) % ordered.size
            val to = canonicalVertex(ordered[next], base + next)
            if (from != to) {
                edgeFaces.getOrPut(edgeKey(from, to)) { ArrayList(2) } += normal
            }
        }
    }

    /**
     * The vertex index this position was first written at.
     *
     * Faces are never welded — every vertex keeps its own face normal, which is
     * what makes the shading faceted — so the same corner appears in the buffer
     * several times over. For a line it makes no difference which of them is
     * used, and picking one consistently is what lets an edge be recognised as
     * one already drawn.
     */
    private fun canonicalVertex(point: ModelPoint, index: Int): Int =
        vertexAtPosition.getOrPut(point.positionKey()) {
            canonicalPoints[index] = point
            index
        }

    /**
     * The edges worth drawing: creases, boundaries and opening reveals — and
     * never the seam between two coplanar faces of one shape.
     *
     * A wall with a window is baked as four boxes, and an eaves wall that runs
     * past the gables as three; their outer faces lie in one plane, and the
     * lines where the boxes meet are not lines on the building. Drawn, they
     * were the clutter the owner saw on every facade: a jamb carried up to the
     * wall head and down to the floor, a seam across the wall where the cheek
     * begins, a seam across every floor slab.
     *
     * Two steps. First every edge is split wherever another vertex of the
     * mesh lies on it, because a pier's full-height corner and the head
     * box's short corner above the opening are the same line of the wall
     * with different endpoints, and only their common piece is a seam. Then a
     * piece is dropped if two of the faces it borders share a normal: they
     * are one surface, and a surface has no line across itself. Everything
     * else — the ridge, the box corners, the reveal round a hole, the free
     * edge of a roof plane — has a crease or an open side and is kept.
     *
     * Deterministic and bounded: a mesh's own vertices against its own edges,
     * and nothing across meshes, so two elements that abut still each keep
     * their outline.
     */
    private fun featureEdges(): IntArray {
        val pieces = LinkedHashMap<Long, MutableList<FloatArray>>()
        edgeFaces.forEach { (key, faces) ->
            val from = (key ushr Int.SIZE_BITS).toInt()
            val to = key.toInt()
            val start = canonicalPoints.getValue(from)
            val end = canonicalPoints.getValue(to)
            val along = canonicalPoints.entries
                .filter { (index, point) -> index != from && index != to && point.liesStrictlyBetween(start, end) }
                .sortedBy { (_, point) -> point.parameterAlong(start, end) }
                .map { it.key }
            (listOf(from) + along + listOf(to)).zipWithNext { a, b ->
                pieces.getOrPut(edgeKey(a, b)) { ArrayList(2) } += faces
            }
        }
        val kept = pieces.filterValues { faces -> !faces.hasCoplanarPair() }.keys
        return IntArray(kept.size * 2).also { packed ->
            kept.forEachIndexed { edge, key ->
                packed[edge * 2] = (key ushr Int.SIZE_BITS).toInt()
                packed[edge * 2 + 1] = key.toInt()
            }
        }
    }

    fun build(
        elementId: BuildingElementId,
        style: MeshStyle,
        bounds: LocalBounds,
    ): BuildingRenderMesh =
        BuildingRenderMesh(
            elementId = elementId,
            style = style,
            positions = positions.toFloatArray(),
            normals = normals.toFloatArray(),
            indices = indices.toIntArray(),
            edgeIndices = featureEdges(),
            boundsCenter = floatArrayOf(
                bounds.center.x.toFloat(),
                bounds.center.y.toFloat(),
                bounds.center.z.toFloat(),
            ),
            boundsHalfExtent = floatArrayOf(
                nonDegenerateHalfExtent(bounds.sizeX),
                nonDegenerateHalfExtent(bounds.sizeY),
                nonDegenerateHalfExtent(bounds.sizeZ),
            ),
        )
}

/**
 * Half of [size], never zero.
 *
 * A roof facet is a surface with no thickness, so its box is flat on one axis. A
 * zero half-extent is a box a culler can decide the camera never sees, which
 * shows up as a roof that vanishes at certain angles.
 */
internal fun nonDegenerateHalfExtent(size: Double): Float =
    maxOf(size / 2.0, GeometryTolerance.LENGTH_METERS).toFloat()

/**
 * A position quantised to a tenth of a millimetre and packed into one key.
 *
 * Quantised because two corners that a metre of arithmetic has left a
 * nanometre apart are the same corner of the same building, and an exact
 * comparison would draw their shared edge twice. A tenth of a millimetre is far
 * finer than any coordinate the model expresses and far coarser than the noise.
 */
private fun ModelPoint.positionKey(): Long {
    var key = Math.round(x * POSITION_QUANTUM)
    key = key * POSITION_HASH_PRIME + Math.round(y * POSITION_QUANTUM)
    return key * POSITION_HASH_PRIME + Math.round(z * POSITION_QUANTUM)
}

/** Tenths of a millimetre per metre. */
private const val POSITION_QUANTUM = 10_000.0

/** An odd multiplier large enough that three quantised coordinates rarely collide. */
private const val POSITION_HASH_PRIME = 1_000_003L

/**
 * The outward horizontal normal of the side face on edge [from] to [to], or null
 * for a degenerate edge.
 *
 * For an outline whose signed plan area is positive, `(dz, -dx)` points away
 * from the interior; a negatively wound outline is the same edge walked the
 * other way, so the normal flips with it.
 */
private fun outwardSideNormal(
    from: PlanPoint,
    to: PlanPoint,
    positiveWinding: Boolean,
): FloatArray? {
    val dx = to.x - from.x
    val dz = to.z - from.z
    val length = hypot(dx, dz)
    if (length <= GeometryTolerance.LENGTH_METERS) return null
    val sign = if (positiveWinding) 1.0 else -1.0
    return floatArrayOf((sign * dz / length).toFloat(), 0f, (-sign * dx / length).toFloat())
}

/** Signed shoelace area: positive for one winding, negative for the other. */
private fun List<PlanPoint>.signedPlanArea(): Double =
    indices.sumOf { index ->
        val current = this[index]
        val next = this[(index + 1) % size]
        current.x * next.z - next.x * current.z
    } / 2.0

/** Newell's normal, normalised, or null when the polygon encloses no area. */
private fun List<ModelPoint>.newellUnitNormal(): FloatArray? {
    val normal = newellNormal()
    val length = sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2])
    if (length <= GeometryTolerance.LENGTH_METERS) return null
    return floatArrayOf(
        (normal[0] / length).toFloat(),
        (normal[1] / length).toFloat(),
        (normal[2] / length).toFloat(),
    )
}

/** How far this polygon's own normal agrees with [normal]; negative means wound the other way. */
private fun List<ModelPoint>.newellDot(normal: FloatArray): Double {
    val own = newellNormal()
    return own[0] * normal[0] + own[1] * normal[1] + own[2] * normal[2]
}

private fun List<ModelPoint>.newellNormal(): DoubleArray {
    var nx = 0.0
    var ny = 0.0
    var nz = 0.0
    indices.forEach { index ->
        val current = this[index]
        val next = this[(index + 1) % size]
        nx += (current.y - next.y) * (current.z + next.z)
        ny += (current.z - next.z) * (current.x + next.x)
        nz += (current.x - next.x) * (current.y + next.y)
    }
    return doubleArrayOf(nx, ny, nz)
}

/**
 * Drops vertices repeating the one before them, and a last vertex closing back
 * onto the first. An outline may be written open or closed; a repeated corner
 * bakes a zero-area side face whose normal cannot be normalised.
 */
private fun List<PlanPoint>.withoutRepeatedVertices(): List<PlanPoint> {
    val kept = filterIndexed { index, point -> index == 0 || !point.coincidesWith(this[index - 1]) }
    return if (kept.size > 1 && kept.first().coincidesWith(kept.last())) kept.dropLast(1) else kept
}

/** [withoutRepeatedVertices] for 3D vertices. */
@JvmName("withoutRepeatedModelVertices")
private fun List<ModelPoint>.withoutRepeatedVertices(): List<ModelPoint> {
    val kept = filterIndexed { index, point -> index == 0 || !point.coincidesWith(this[index - 1]) }
    return if (kept.size > 1 && kept.first().coincidesWith(kept.last())) kept.dropLast(1) else kept
}

/** An undirected edge between two vertex indices, the lower one in the high word. */
private fun edgeKey(a: Int, b: Int): Long =
    (minOf(a, b).toLong() shl Int.SIZE_BITS) or maxOf(a, b).toLong()

/** Whether two of these normals agree closely enough to be one plane. */
private fun List<FloatArray>.hasCoplanarPair(): Boolean {
    for (first in indices) {
        for (second in first + 1 until size) {
            val a = this[first]
            val b = this[second]
            if (a[0] * b[0] + a[1] * b[1] + a[2] * b[2] > COPLANAR_DOT) return true
        }
    }
    return false
}

/** Where this point falls along [start] to [end], as a fraction of that length. */
private fun ModelPoint.parameterAlong(start: ModelPoint, end: ModelPoint): Double {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val dz = end.z - start.z
    val lengthSquared = dx * dx + dy * dy + dz * dz
    if (lengthSquared <= 0.0) return 0.0
    return ((x - start.x) * dx + (y - start.y) * dy + (z - start.z) * dz) / lengthSquared
}

/**
 * Whether this point lies on the open segment [start] to [end]: on its line
 * within [COLLINEAR_TOLERANCE_METERS], and strictly inside its ends.
 */
private fun ModelPoint.liesStrictlyBetween(start: ModelPoint, end: ModelPoint): Boolean {
    val t = parameterAlong(start, end)
    if (t <= 0.0 || t >= 1.0) return false
    val px = start.x + (end.x - start.x) * t
    val py = start.y + (end.y - start.y) * t
    val pz = start.z + (end.z - start.z) * t
    val offX = x - px
    val offY = y - py
    val offZ = z - pz
    return offX * offX + offY * offY + offZ * offZ <=
        COLLINEAR_TOLERANCE_METERS * COLLINEAR_TOLERANCE_METERS
}

/** Cosine above which two face normals are the same plane: about a quarter of a degree. */
private const val COPLANAR_DOT = 0.99999f

/** How far off a line a vertex may sit and still split the edge through it. */
private const val COLLINEAR_TOLERANCE_METERS = 1e-4
