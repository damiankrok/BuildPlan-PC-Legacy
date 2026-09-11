package com.buildplan.app.analyzer.quantity

import com.buildplan.app.analyzer.candidate.*
import com.buildplan.app.analyzer.fidelity.*
import kotlin.math.abs
import kotlin.math.max

/** Final geometry adapter. No tracing, height guesses, roof solving or vertex edits occur here. */
internal object ResolvedQuantityTakeoff {
    fun compute(c:ProjectAnalysisCandidate,g:ResolvedBuildingGeometry):ProjectQuantities {
        val surfaces=g.surfaces
        fun measure(value:Double,parts:List<ResolvedSurface>,unit:MeasureUnit=MeasureUnit.SQUARE_METER)=Measured(value,unit,
            if(parts.isEmpty()) FactFidelity.SOURCE_DERIVED else FactFidelity.weakest(parts.map { it.fidelity }),
            Provenance.derived(g.lineage,parts.map { it.id }),note="Resolved geometry; ${parts.size} surfaces")
        fun area(parts:List<ResolvedSurface>)=measure(parts.sumOf { it.area },parts)
        fun missing(why:String,unit:MeasureUnit=MeasureUnit.SQUARE_METER)=Measured.missing(unit,why)
        val physicalOpenings=surfaces.filter { it.kind==ResolvedSurfaceKind.OPENING && it.roomId==null }
        val exteriorOpenings=physicalOpenings.filter { it.exterior }
        val exteriorWalls=surfaces.filter { it.kind==ResolvedSurfaceKind.WALL && it.exterior }
        val rooms=c.rooms.map { room ->
            val rs=surfaces.filter { it.roomId==room.id }
            val floors=rs.filter { it.kind==ResolvedSurfaceKind.FLOOR }
            val walls=rs.filter { it.kind==ResolvedSurfaceKind.ROOM_WALL_FACE }
            val openings=rs.filter { it.kind==ResolvedSurfaceKind.OPENING }
            val ceilings=rs.filter { it.kind==ResolvedSurfaceKind.CEILING }
            val flat=ceilings.filter { s -> s.vertices.maxOf { it.y }-s.vertices.minOf { it.y }<1e-6 }
            val base=c.floor(room.floorId)?.floorElevation?.value ?: 0.0
            val volume=ceilings.sumOf { volumeUnder(it.vertices,base) }
            val usable=ceilings.sumOf { s -> val full=planArea(above(s.vertices,base+2.2)); val partial=planArea(above(s.vertices,base+1.4)); full+(partial-full)*0.5 }
            val floorArea=if(room.polygon==null) missing("room ring unresolved") else area(floors)
            RoomQuantities(room.id,floorArea,room.polygon?.let { measure(it.perimeter,floors,MeasureUnit.METER) } ?: missing("room ring unresolved",MeasureUnit.METER),
                walls.map { it.id },if(room.boundary.isEmpty()) missing("room boundary unresolved") else area(walls+openings),area(openings),if(room.boundary.isEmpty()) missing("room boundary unresolved") else area(walls),
                if(room.polygon==null) missing("room ring unresolved") else area(flat),if(room.polygon==null) missing("room ring unresolved") else area(ceilings-flat.toSet()),
                if(room.polygon==null) missing("room ring unresolved") else area(ceilings),if(room.polygon==null) missing("room ring unresolved",MeasureUnit.CUBIC_METER) else measure(volume,ceilings,MeasureUnit.CUBIC_METER),
                if(room.polygon==null) missing("room ring unresolved") else measure(usable,ceilings),if(floorArea.value==null || floorArea.value<=0) missing("floor area unresolved",MeasureUnit.METER) else measure(volume/floorArea.value,ceilings,MeasureUnit.METER),
                room.boundary.map { it.segment.length })
        }
        fun type(s:ResolvedSurface):OpeningType {
            c.openings.firstOrNull { it.id==s.ownerId }?.let { return it.type }
            val group=c.openingGroups.firstOrNull { it.id==s.ownerId } ?: return OpeningType.UNKNOWN
            val semantics=group.evidenceIds.mapNotNull { id -> c.reconstruction?.graph?.nodes?.firstOrNull { it.id==id }?.semantic }
            return when { "GARAGE_GATE_RECTANGLE" in semantics->OpeningType.GARAGE_GATE; group.doorLeaf!=null->OpeningType.DOOR; else->OpeningType.WINDOW }
        }
        val floorQuantities=c.floors.map { floor ->
            val walls=surfaces.filter { it.kind==ResolvedSurfaceKind.WALL && it.floorId==floor.id }
            val exterior=walls.filter { it.exterior }
            val openings=physicalOpenings.filter { it.floorId==floor.id }
            val floorSurfaces=surfaces.filter { it.kind==ResolvedSurfaceKind.FLOOR && it.floorId==floor.id }
            FloorQuantities(floor.id,area(floorSurfaces),area(exterior),area(walls.filter { c.wall(it.ownerId)?.wallClass==WallClass.INTERNAL_LOAD_BEARING }),
                area(walls.filter { c.wall(it.ownerId)?.wallClass==WallClass.PARTITION }),area(exterior+openings.filter { it.exterior }),area(exterior),openings.groupBy(::type).mapValues { (_,v)->v.sumOf { it.area } })
        }
        val roof=surfaces.filter { it.kind==ResolvedSurfaceKind.ROOF }
        val gross=area(exteriorWalls+exteriorOpenings); val net=area(exteriorWalls); val opening=area(exteriorOpenings)
        val mappedSurfaces=surfaces.filter { it.kind in setOf(ResolvedSurfaceKind.WALL,ResolvedSurfaceKind.OPENING,ResolvedSurfaceKind.ROOM_WALL_FACE,ResolvedSurfaceKind.FLOOR,ResolvedSurfaceKind.CEILING,ResolvedSurfaceKind.ROOF) }.map { s ->
            val type=when(s.kind) { ResolvedSurfaceKind.WALL->if(s.exterior) SurfaceType.FACADE else SurfaceType.WALL_FACE; ResolvedSurfaceKind.OPENING->SurfaceType.OPENING; ResolvedSurfaceKind.ROOM_WALL_FACE->SurfaceType.WALL_FACE
                ResolvedSurfaceKind.FLOOR->SurfaceType.FLOOR; ResolvedSurfaceKind.ROOF->SurfaceType.ROOF_FACET
                else->if(s.vertices.maxOf { it.y }-s.vertices.minOf { it.y }<1e-6) SurfaceType.CEILING_FLAT else SurfaceType.CEILING_SLOPED }
            val value=area(listOf(s))
            MeasuredSurfaceCandidate(s.id,type,s.ownerId,s.roomId,if(s.kind==ResolvedSurfaceKind.WALL) s.ownerId else null,null,s.exterior,g.lineage,value,measure(0.0,emptyList()),value,
                if(s.kind==ResolvedSurfaceKind.OPENING && s.roomId!=null) "opening intersection deducted from this room face; separate from physical joinery" else "resolved polygon area; wall pieces are net of resolved holes; no second deduction")
        }
        val roomFloors=surfaces.filter { it.kind==ResolvedSurfaceKind.FLOOR }
        val roofCandidate=c.roof
        fun length(value:Double)=measure(value,roof,MeasureUnit.METER)
        val notes=mutableListOf("geometryLineage=${g.lineage}","All quantities recomputed from final resolved surfaces. Structural walls counted once; room finish faces counted once per room side; opening unions deducted during geometry resolution.",
            "Roof total includes primary and secondary roof surfaces; source publication scope must be checked before comparison.","Internal structural walls retain traced gap semantics; internal lintel recovery remains unresolved.")
        c.rooms.forEach { room ->
            val floorArea=surfaces.filter { it.roomId==room.id && it.kind==ResolvedSurfaceKind.FLOOR }.sumOf { planArea(it.vertices) }
            val ceilingArea=surfaces.filter { it.roomId==room.id && it.kind==ResolvedSurfaceKind.CEILING }.sumOf { planArea(it.vertices) }
            if(abs(floorArea-ceilingArea)>0.01) notes+="Floor/ceiling plan coverage residual ${room.id}: ${floorArea-ceilingArea} m2"
        }
        return ProjectQuantities(mappedSurfaces,rooms,floorQuantities,roof.map { it.id to it.area },if(roof.isEmpty()) missing("roof unresolved") else area(roof),
            length(roofCandidate?.ridgeLines?.sumOf { it.length } ?: 0.0),length(roofCandidate?.hipLines?.sumOf { it.length } ?: 0.0),length(roofCandidate?.eaveLength?.value ?: 0.0),
            opening,gross,net,net,area(roomFloors),FacadeScope(net,gross,net,opening,
                missing("gable subtotal not separated from final facade surfaces"),
                area(surfaces.filter { it.kind==ResolvedSurfaceKind.ROOM_WALL_FACE && it.exterior && c.room(it.roomId.orEmpty())?.kind==com.buildplan.app.analyzer.site.RoomKind.GARAGE }),
                missing("secondary facade subtotal requires mass-face ownership"),missing("terrain/plinth boundary is not source-resolved")),notes+g.diagnostics)
    }

    private fun planArea(vertices:List<Pt3>):Double = if(vertices.size<3) 0.0 else abs(vertices.indices.sumOf { i -> val a=vertices[i]; val b=vertices[(i+1)%vertices.size]; a.x*b.z-b.x*a.z })*0.5
    private fun volumeUnder(vertices:List<Pt3>,base:Double):Double {
        val a=vertices.first()
        return (1 until vertices.lastIndex).sumOf { i -> val b=vertices[i]; val c=vertices[i+1]
            val area=((b.x-a.x)*(c.z-a.z)-(b.z-a.z)*(c.x-a.x))*0.5
            area*((a.y+b.y+c.y)/3-base)
        }.let(::abs)
    }
    private fun above(vertices:List<Pt3>,level:Double):List<Pt3> = buildList {
        vertices.indices.forEach { i -> val a=vertices[i]; val b=vertices[(i+1)%vertices.size]
            if(a.y>=level) add(a)
            if((a.y<level)!=(b.y<level)) { val t=(level-a.y)/(b.y-a.y); add(Pt3(a.x+(b.x-a.x)*t,level,a.z+(b.z-a.z)*t)) }
        }
    }
}
