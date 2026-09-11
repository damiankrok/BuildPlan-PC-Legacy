package com.buildplan.app.analyzer.lab

import com.buildplan.app.analyzer.candidate.OpeningType
import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.candidate.Pt
import com.buildplan.app.analyzer.roof.RoofHeightField
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
import com.buildplan.app.presentation.DecompositionProfile
import com.buildplan.app.presentation.OpeningFrameProfile
import com.buildplan.app.presentation.RoofCoverProfile
import com.buildplan.app.reference.visual.VisualSurfaceRole
import com.buildplan.app.render.filament.PresetFocus
import com.buildplan.app.render.filament.SceneModel
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
internal class BaselineCandidatePreview private constructor(
    override val building: Building,
    override val geometry: BuildingGeometry,
    override val atticId: FloorId,
    val roofId: BuildingElementId?,
) : SceneModel {

    override val surfaceRoles: Map<BuildingElementId, VisualSurfaceRole> get() = emptyMap()
    override val decomposition: DecompositionProfile get() = DecompositionProfile.NONE
    override val roofCover: RoofCoverProfile get() = RoofCoverProfile.NONE
    override val openingFrames: OpeningFrameProfile get() = OpeningFrameProfile.NONE
    override fun focusElementId(focus: PresetFocus): BuildingElementId? = if (focus == PresetFocus.ROOF) roofId else null

    companion object {

        private const val SLAB_THICKNESS = 0.25
        private const val WINDOW_SILL = 0.90
        private val assumedHeights = mapOf(
            OpeningType.DOOR to 2.05, OpeningType.WINDOW to 1.50, OpeningType.GARAGE_GATE to 2.20,
            OpeningType.PASSAGE to 2.05, OpeningType.ROOFLIGHT to 1.18, OpeningType.UNKNOWN to 1.50,
        )

        /** Null when the candidate has no floor with geometry. */
        fun of(candidate: ProjectAnalysisCandidate): BaselineCandidatePreview? {
            val floorsByOrder = candidate.floors.filter { it.calibration != null }.sortedBy { it.order }
            if (floorsByOrder.isEmpty()) return null
            val roofField = candidate.roof?.let { RoofHeightField(it) }
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
                    val samples = listOf(0.1, 0.5, 0.9).mapNotNull { t -> roofField.heightAt(Pt(a.x + (b.x - a.x) * t, a.z + (b.z - a.z) * t)) }
                    val roofY = samples.minOrNull() ?: roofField.heightAt(mid)
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
                    listOf(ModelPoint(p0.x, y0, p0.z), ModelPoint(p1.x, y0, p1.z), ModelPoint(p1.x, y1, p1.z), ModelPoint(p0.x, y1, p0.z)),
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
            return BaselineCandidatePreview(building, geometry, fid(topFloorId), roofId)
        }

        private fun distinct(points: List<ModelPoint>): Int =
            points.filterIndexed { i, p -> points.take(i).none { q -> abs(q.x - p.x) < 1e-6 && abs(q.y - p.y) < 1e-6 && abs(q.z - p.z) < 1e-6 } }.size
    }
}
