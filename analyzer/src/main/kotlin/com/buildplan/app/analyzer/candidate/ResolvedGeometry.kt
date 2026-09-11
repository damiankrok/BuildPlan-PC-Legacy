package com.buildplan.app.analyzer.candidate

import com.buildplan.app.analyzer.fidelity.FactFidelity

/** Facade coordinates: x is distance from the facade segment start; z is absolute elevation. */
data class OpeningGroupCandidate(val id:String,val facadeId:String,val floorId:String,val outer:Polygon,
    val memberOpeningIds:List<String>,val panels:List<Polygon>,val doorLeaf:Polygon?,val mullions:List<Segment>,
    val evidenceIds:List<String>,val confidence:Double,val fidelity:FactFidelity)
enum class FacadeFeatureKind { BALCONY, FRAME, BAND, CANOPY, RAILING, PIER, ROOFLIGHT, STACK }
enum class RoofElementKind { STACK, ROOFLIGHT }
data class RoofElementCandidate(val id:String,val kind:RoofElementKind,val roofFacetId:String,val vertices:List<Pt3>,
    val footprint:Polygon,val evidenceIds:List<String>,val confidence:Double,val fidelity:FactFidelity)
data class FacadeFeatureCandidate(val id:String,val kind:FacadeFeatureKind,val facadeId:String,val floorId:String,
    val profile:Polygon,val depth:Double,val evidenceIds:List<String>,val confidence:Double,val fidelity:FactFidelity,
    val depthFidelity:FactFidelity=FactFidelity.DISPLAY_ASSUMPTION)
enum class ResolvedSurfaceKind { WALL, OPENING, ROOF, FLOOR, CEILING, ROOM_WALL_FACE, FEATURE, STAIR, SLAB }
data class ResolvedSurface(val id:String,val ownerId:String,val floorId:String?,val kind:ResolvedSurfaceKind,
    val vertices:List<Pt3>,val thickness:Double,val evidenceIds:List<String>,val fidelity:FactFidelity,
    val roomId:String?=null,val exterior:Boolean=false,val areaFidelity:FactFidelity=fidelity) {
    init { require(vertices.size>=3 && thickness>=0 && thickness.isFinite()) }
    val area:Double get() {
        var nx=0.0; var ny=0.0; var nz=0.0
        vertices.indices.forEach { i -> val a=vertices[i]; val b=vertices[(i+1)%vertices.size]
            nx+=(a.y-b.y)*(a.z+b.z); ny+=(a.z-b.z)*(a.x+b.x); nz+=(a.x-b.x)*(a.y+b.y) }
        return kotlin.math.sqrt(nx*nx+ny*ny+nz*nz)*0.5
    }
}
/** A resolved surface has one owner and one geometry; room finish faces are explicitly separate surfaces. */
data class StairTopologyCandidate(val stairId:String,val fromFloorId:String?,val toFloorId:String?,val stairwell:Polygon,
    val flights:List<Polygon>,val landings:List<Polygon>,val slabOpenings:List<Polygon>,val direction:String,
    val accepted:Boolean,val diagnostics:List<String>)

data class ResolvedBuildingGeometry(val surfaces:List<ResolvedSurface>,val lineage:String,val diagnostics:List<String>,val stairTopology:List<StairTopologyCandidate> = emptyList()) {
    init { require(surfaces.map { it.id }.distinct().size==surfaces.size) }
}
