package com.buildplan.app.analyzer.preview

import com.buildplan.app.analyzer.candidate.OpeningType
import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.candidate.ResolvedSurfaceKind
import com.buildplan.app.analyzer.service.CandidateGeometryQueries
import com.buildplan.app.geometry.GablePanelGeometry
import com.buildplan.app.geometry.WallOpening
import com.buildplan.app.domain.model.Building
import com.buildplan.app.domain.model.BuildingElement
import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.BuildingElementKind
import com.buildplan.app.domain.model.BuildingElementScope
import com.buildplan.app.domain.model.BuildingId
import com.buildplan.app.domain.model.Floor
import com.buildplan.app.domain.model.FloorId
import com.buildplan.app.domain.model.Room
import com.buildplan.app.domain.model.RoomId
import com.buildplan.app.domain.units.Quantity
import com.buildplan.app.domain.units.UnitOfMeasure
import com.buildplan.app.geometry.BuildingGeometry
import com.buildplan.app.geometry.BuildingGeometryPrimitive
import com.buildplan.app.geometry.ModelPoint
import com.buildplan.app.geometry.OpeningPanelGeometry
import com.buildplan.app.geometry.PlanPoint
import com.buildplan.app.geometry.RoofFacetGeometry
import com.buildplan.app.geometry.SlabGeometry
import com.buildplan.app.geometry.WallGeometry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A *preview* of an analyzer candidate on the existing renderer: the candidate
 * mapped onto a throwaway `Building` and `BuildingGeometry` so that the
 * Filament canvas can draw it with the same visibility path every model uses.
 *
 * This is not promotion. The building built here lives in the Lab's memory,
 * carries `cand-` ids, is never written anywhere and never touches the
 * canonical reference model. Every shape comes from a candidate value;
 * where the candidate has an assumption (opening heights) the preview draws
 * the assumption, because a preview is for looking, and the snapshot beside
 * it says which numbers are assumed.
 */
internal class CandidateGeometry private constructor(
    val building: Building,
    val geometry: BuildingGeometry,
    val atticId: FloorId,
    val roofId: BuildingElementId?,
) {

    companion object {

        private const val SLAB_THICKNESS = 0.25
        private const val WINDOW_SILL = 0.90
        private val assumedHeights = mapOf(
            OpeningType.DOOR to 2.05, OpeningType.WINDOW to 1.50, OpeningType.GARAGE_GATE to 2.20,
            OpeningType.PASSAGE to 2.05, OpeningType.ROOFLIGHT to 1.18, OpeningType.UNKNOWN to 1.50,
        )

        /** Null when the candidate has no floor with geometry. */
        fun of(candidate: ProjectAnalysisCandidate): CandidateGeometry? {
            val floorsByOrder = candidate.floors.filter { it.calibration != null }.sortedBy { it.order }
            if (floorsByOrder.isEmpty()) return null
            val roofField = candidate.roof?.let(CandidateGeometryQueries::roofHeights)
            val topFloorId = floorsByOrder.last().id
            fun fid(id: String) = FloorId("cand-$id")
            fun rid(id: String) = RoomId("cand-$id")
            fun eid(id: String) = BuildingElementId("cand-$id")

            val floors = floorsByOrder.map { f ->
                Floor(
                    id = fid(f.id),
                    name = f.name,
                    order = f.order,
                    elevation = f.floorElevation.value?.let { Quantity(it, UnitOfMeasure.METER) },
                    height = f.clearHeight.value?.takeIf { it > 0 }?.let { Quantity(it, UnitOfMeasure.METER) },
                    rooms = f.rooms.map { r -> Room(rid(r.id), r.name, r.plannedArea.value?.takeIf { it > 0 }?.let { Quantity(it, UnitOfMeasure.SQUARE_METER) }) },
                )
            }
            val roomsByFloor = floorsByOrder.associate { f -> f.id to f.rooms.map { it.id }.toSet() }

            val elements = mutableListOf<BuildingElement>()
            val primitives = mutableListOf<BuildingGeometryPrimitive>()
            val facadePanes = mutableMapOf<String, List<ModelPoint>>()
            val resolved=CandidateGeometryQueries.resolvedGeometry(candidate)
            resolved?.surfaces?.filter { it.roomId==null }?.groupBy { it.ownerId }?.forEach { (owner,surfaces) ->
                val first=surfaces.first()
                val id=eid(owner)
                val kind=when(first.kind) { ResolvedSurfaceKind.WALL -> BuildingElementKind.WALL; ResolvedSurfaceKind.OPENING -> BuildingElementKind.WINDOW
                    ResolvedSurfaceKind.ROOF -> BuildingElementKind.ROOF; ResolvedSurfaceKind.SLAB -> BuildingElementKind.SLAB; ResolvedSurfaceKind.STAIR -> BuildingElementKind.STAIRS; else -> BuildingElementKind.OTHER }
                elements+=BuildingElement(id,kind,owner,first.floorId?.let { BuildingElementScope.OnFloor(fid(it)) } ?: BuildingElementScope.WholeBuilding)
                surfaces.forEach { s ->
                    if(s.kind==ResolvedSurfaceKind.OPENING && candidate.openings.any { it.id==s.ownerId && it.type==OpeningType.PASSAGE }) return@forEach
                    val points=s.vertices.map { ModelPoint(it.x,it.y,it.z) }
                    val planArea=points.indices.sumOf { i -> val a=points[i]; val b=points[(i+1)%points.size]; a.x*b.z-b.x*a.z }*0.5
                    if(s.kind==ResolvedSurfaceKind.OPENING) primitives+=OpeningPanelGeometry(id,points)
                    else if(abs(planArea)<1e-8) primitives+=GablePanelGeometry(id,points)
                    else primitives+=RoofFacetGeometry(id,points)
                }
            }
            if(resolved?.lineage?.startsWith("final-resolution:")==true) {
                val building=Building(BuildingId("cand-building"),floors,elements)
                val geometry=BuildingGeometry(primitives)
                geometry.requireElementsIn(building)
                return CandidateGeometry(building,geometry,fid(topFloorId),if(candidate.roof!=null) eid("roof") else null)
            }

            // Continuous exterior envelopes retain lintels and roof-following gables.
            // Opening positions are measured on the envelope, not clamped to a trace fragment.
            (if(candidate.resolvedGeometry==null) candidate.facadeEnvelopes else emptyList()).forEach { facade ->
                val a = facade.segment.a
                val b = facade.segment.b
                val length = facade.segment.length
                val base = facade.baseLevel.value ?: return@forEach
                val profile = facade.topProfile
                if (length < 0.05 || profile.size < 2) return@forEach
                val top = profile.maxOf { it.y }
                if (top <= base + 0.05) return@forEach
                val id = eid(facade.id)
                fun along(p: Pt) = ((p.x - a.x) * (b.x - a.x) + (p.z - a.z) * (b.z - a.z)) / length
                fun point(d: Double, y: Double) = ModelPoint(a.x + (b.x - a.x) * d / length, y, a.z + (b.z - a.z) * d / length)
                val roofProfile = profile.map { along(Pt(it.x, it.z)) to it.y }.sortedBy { it.first }
                fun roofY(d: Double): Double {
                    val pair = roofProfile.zipWithNext().firstOrNull { d >= it.first.first - 1e-6 && d <= it.second.first + 1e-6 } ?: return roofProfile.last().second
                    val span = pair.second.first - pair.first.first
                    return if (span < 1e-6) pair.first.second else pair.first.second + (pair.second.second - pair.first.second) * (d - pair.first.first) / span
                }
                val holes = facade.openingIds.mapNotNull { openingId ->
                    val o = candidate.openings.firstOrNull { it.id == openingId } ?: return@mapNotNull null
                    val gap = CandidateGeometryQueries.openingSegment(candidate, o) ?: return@mapNotNull null
                    val start = min(along(gap.a), along(gap.b)).coerceIn(0.0, length)
                    val end = max(along(gap.a), along(gap.b)).coerceIn(0.0, length)
                    val sill = base + (o.sillHeight.value ?: if (o.type == OpeningType.WINDOW) WINDOW_SILL else 0.0)
                    val height = min(o.height.value ?: assumedHeights[o.type] ?: 1.5, top - sill)
                    if (end - start < 0.05 || height < 0.05) null else WallOpening(eid(o.id), start, end - start, sill, height)
                }.sortedBy { it.distanceFromStart }.fold(mutableListOf<WallOpening>()) { accepted, hole ->
                    if (accepted.isEmpty() || hole.distanceFromStart >= accepted.last().distanceToEnd) accepted += hole
                    accepted
                }
                elements += BuildingElement(id, BuildingElementKind.WALL, "Elewacja ${facade.id}", BuildingElementScope.OnFloor(fid(facade.floorId)))
                val cuts = (roofProfile.map { it.first } + holes.flatMap { listOf(it.distanceFromStart, it.distanceToEnd) } + roofProfile.zipWithNext().flatMap { (p, q) ->
                    holes.flatMap { listOf(it.sillElevation, it.headElevation) }.mapNotNull { y ->
                        if (abs(q.second - p.second) < 1e-6 || y <= min(p.second, q.second) || y >= max(p.second, q.second)) null
                        else p.first + (q.first - p.first) * (y - p.second) / (q.second - p.second)
                    }
                }).sorted().fold(mutableListOf<Double>()) { out, d -> if (out.isEmpty() || d - out.last() > 1e-5) out += d; out }
                fun solid(start: Double, end: Double, low: Double, highA: Double, highB: Double) {
                    val pa = point(start, low); val pb = point(end, low)
                    val flat = min(highA, highB)
                    if (flat > low + 1e-5) primitives += WallGeometry(id, PlanPoint(pa.x, pa.z), PlanPoint(pb.x, pb.z), low, flat - low, facade.thickness.value ?: 0.25)
                    if (max(highA, highB) > max(flat, low) + 1e-5) {
                        val vertices = listOf(point(start, max(flat, low)), point(end, max(flat, low)), point(end, max(highB, low)), point(start, max(highA, low))).distinct()
                        if (vertices.size >= 3) primitives += GablePanelGeometry(id, vertices)
                    }
                }
                cuts.zipWithNext().forEach { (start, end) ->
                    val hole = holes.firstOrNull { (start + end) / 2 >= it.distanceFromStart && (start + end) / 2 <= it.distanceToEnd }
                    val ya = roofY(start); val yb = roofY(end)
                    if (hole == null) solid(start, end, base, ya, yb) else {
                        solid(start, end, base, min(ya, hole.sillElevation), min(yb, hole.sillElevation))
                        if (max(ya, yb) > hole.headElevation) solid(start, end, hole.headElevation, ya, yb)
                    }
                }
                holes.forEach { hole ->
                    val topPoints = (listOf(hole.distanceFromStart, hole.distanceToEnd) + cuts.filter { it > hole.distanceFromStart && it < hole.distanceToEnd }).distinct().sortedDescending()
                        .map { point(it, max(hole.sillElevation + 0.01, min(roofY(it), hole.headElevation))) }
                    facadePanes[hole.elementId.value.removePrefix("cand-")] = listOf(point(hole.distanceFromStart, hole.sillElevation), point(hole.distanceToEnd, hole.sillElevation)) + topPoints
                }
            }

            // Slabs: one plate per storey from its footprint, the ground one under the floor level.
            floorsByOrder.forEach { f ->
                val footprint = f.footprint ?: return@forEach
                val base = (f.floorElevation.value ?: 0.0) - SLAB_THICKNESS
                val id = eid("${f.id}-slab")
                elements += BuildingElement(id, BuildingElementKind.SLAB, "Strop ${f.name}", BuildingElementScope.OnFloor(fid(f.id)))
                primitives += SlabGeometry(id, footprint.vertices.map { PlanPoint(it.x, it.z) }, base, SLAB_THICKNESS)
            }

            // Walls: the traced pieces, to the storey's clear height or up to the roof underside on the top storey.
            candidate.walls.forEach { w ->
                if (w.touchesOutside && candidate.facadeEnvelopes.any { it.floorId == w.floorId }) return@forEach
                val floor = floorsByOrder.firstOrNull { it.id == w.floorId } ?: return@forEach
                val base = w.baseElevation.value ?: floor.floorElevation.value ?: 0.0
                val clear = w.height.value ?: floor.clearHeight.value ?: 2.7
                val thickness = w.thickness.value ?: 0.12
                val a = w.centreline.a
                val b = w.centreline.b
                if (a.distanceTo(b) < 0.05 || thickness < 0.02) return@forEach
                var height = clear
                if (w.floorId == topFloorId && roofField != null) {
                    val mid = Pt((a.x + b.x) / 2, (a.z + b.z) / 2)
                    val samples = listOf(0.1, 0.5, 0.9).mapNotNull { t -> roofField(Pt(a.x + (b.x - a.x) * t, a.z + (b.z - a.z) * t)) }
                    val roofY = samples.minOrNull() ?: roofField(mid)
                    if (roofY != null) height = min(clear, max(0.3, roofY - base))
                }
                if (height < 0.05) return@forEach
                val id = eid(w.id)
                val rooms = (w.roomIdsLeft + w.roomIdsRight).filter { it in roomsByFloor[w.floorId].orEmpty() }.map(::rid).toSet()
                elements += BuildingElement(id, BuildingElementKind.WALL, "Ściana ${w.id} (${w.wallClass.name.lowercase()})", BuildingElementScope.OnFloor(fid(w.floorId)), rooms)
                primitives += WallGeometry(id, PlanPoint(a.x, a.z), PlanPoint(b.x, b.z), base, height, thickness)
            }

            // Openings: a pane spanning the gap on the wall line, at the candidate's (assumed) height.
            candidate.openings.forEach { o ->
                if(candidate.resolvedGeometry!=null && o.exterior) return@forEach
                if (candidate.facadeEnvelopes.isNotEmpty() && ((o.linkedRoomIds.isEmpty() && o.id !in facadePanes) || (o.exterior && o.id !in facadePanes))) return@forEach
                val wall = candidate.wall(o.wallId) ?: return@forEach
                val floor = floorsByOrder.firstOrNull { it.id == o.floorId } ?: return@forEach
                val width = o.width.value ?: return@forEach
                val d = o.distanceAlongWall.value ?: return@forEach
                val height = o.height.value ?: assumedHeights[o.type] ?: return@forEach
                val sill = o.sillHeight.value ?: if (o.type == OpeningType.WINDOW) WINDOW_SILL else 0.0
                val base = (floor.floorElevation.value ?: 0.0)
                val a = wall.centreline.a
                val b = wall.centreline.b
                val len = a.distanceTo(b)
                if (len < 1e-6) return@forEach
                val ux = (b.x - a.x) / len
                val uz = (b.z - a.z) / len
                val p0 = Pt(a.x + ux * d, a.z + uz * d)
                val p1 = Pt(a.x + ux * (d + width), a.z + uz * (d + width))
                if (candidate.facadeEnvelopes.isNotEmpty() && !o.exterior && floor.footprint?.contains(Pt((p0.x + p1.x) / 2, (p0.z + p1.z) / 2)) == false) return@forEach
                val y0 = base + sill
                val y1 = y0 + height
                val id = eid(o.id)
                val kind = when (o.type) {
                    OpeningType.WINDOW, OpeningType.ROOFLIGHT -> BuildingElementKind.WINDOW
                    OpeningType.PASSAGE -> BuildingElementKind.OTHER
                    else -> BuildingElementKind.DOOR
                }
                val rooms = o.linkedRoomIds.filter { it in roomsByFloor[o.floorId].orEmpty() }.map(::rid).toSet()
                elements += BuildingElement(id, kind, "Otwór ${o.id} (${o.type.name.lowercase()}, ${"%.2f".format(java.util.Locale.ROOT, width)} m)", BuildingElementScope.OnFloor(fid(o.floorId)), rooms)
                primitives += OpeningPanelGeometry(
                    id,
                    facadePanes[o.id] ?: listOf(ModelPoint(p0.x, y0, p0.z), ModelPoint(p1.x, y0, p1.z), ModelPoint(p1.x, y1, p1.z), ModelPoint(p0.x, y1, p0.z)),
                )
            }

            // Stairs: the zone as a low plate, so the well is visible without pretending to know the treads.
            candidate.stairs.forEach { s ->
                val floor = floorsByOrder.firstOrNull { it.id == s.floorId } ?: return@forEach
                val base = floor.floorElevation.value ?: 0.0
                val id = eid(s.id)
                elements += BuildingElement(id, BuildingElementKind.STAIRS, "Schody ${s.id}", BuildingElementScope.OnFloor(fid(s.floorId)))
                primitives += SlabGeometry(id, listOf(PlanPoint(s.zone.minX, s.zone.minZ), PlanPoint(s.zone.maxX, s.zone.minZ), PlanPoint(s.zone.maxX, s.zone.maxZ), PlanPoint(s.zone.minX, s.zone.maxZ)), base, 0.15)
            }

            // Roof: every facet under one element; secondary flat masses as their own roofs.
            var roofId: BuildingElementId? = null
            candidate.roof?.let { roof ->
                val id = eid("roof")
                roofId = id
                elements += BuildingElement(id, BuildingElementKind.ROOF, "Dach (${roof.family.name.lowercase()}, ${roof.facets.size} połaci)", BuildingElementScope.WholeBuilding)
                roof.facets.forEach { f ->
                    val verts = f.vertices.map { ModelPoint(it.x, it.y, it.z) }
                    if (distinct(verts) >= 3) primitives += RoofFacetGeometry(id, verts)
                }
                roof.secondaryMasses.forEachIndexed { i, m ->
                    val mid = eid("roof-mass-${i + 1}")
                    val top = m.topElevation.value ?: 0.0
                    elements += BuildingElement(mid, BuildingElementKind.ROOF, "Dach płaski ${i + 1} (założenie)", BuildingElementScope.WholeBuilding)
                    primitives += SlabGeometry(mid, m.outline.vertices.map { PlanPoint(it.x, it.z) }, top - SLAB_THICKNESS, SLAB_THICKNESS)
                }
            }

            val building = Building(BuildingId("cand-building"), floors, elements)
            val geometry = BuildingGeometry(primitives)
            geometry.requireElementsIn(building)
            return CandidateGeometry(building, geometry, fid(topFloorId), roofId)
        }

        private fun distinct(points: List<ModelPoint>): Int =
            points.filterIndexed { i, p -> points.take(i).none { q -> abs(q.x - p.x) < 1e-6 && abs(q.y - p.y) < 1e-6 && abs(q.z - p.z) < 1e-6 } }.size
    }
}
