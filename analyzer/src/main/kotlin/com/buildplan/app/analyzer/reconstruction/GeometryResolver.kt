package com.buildplan.app.analyzer.reconstruction

import com.buildplan.app.analyzer.candidate.*
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.quantity.QuantityTakeoffEngine
import com.buildplan.app.analyzer.reconstruction.internal.PlanarTopology
import com.buildplan.app.analyzer.roof.RoofHeightField
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** All facade clipping and assumptions are resolved here, before either renderer or quantity adapter. */
internal object GeometryResolver {
    fun lineage(c:ProjectAnalysisCandidate):String {
        // Data-class text contains enum names, unlike hashCode which includes JVM-specific enum identity.
        val basis=c.copy(resolvedGeometry=null,selfVerification=null).toString().toByteArray(Charsets.UTF_8)
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(basis).joinToString("") { "%02x".format(it) }
        return "final-resolution:${c.reconstruction?.policy?.version ?: "unversioned"}:$digest"
    }
    fun resolve(c:ProjectAnalysisCandidate):ResolvedBuildingGeometry {
        val exterior=facades(c)
        val surfaces=exterior.surfaces.toMutableList()
        val diagnostics=exterior.diagnostics.toMutableList()
        val roof=c.roof?.let(::RoofHeightField)
        val topFloor=c.floors.maxByOrNull { it.order }?.id
        fun add(id:String,owner:String,floor:String?,kind:ResolvedSurfaceKind,vertices:List<Pt3>,thickness:Double,evidence:List<String>,fidelity:FactFidelity,room:String?=null,external:Boolean=false) {
            val s=ResolvedSurface(id,owner,floor,kind,vertices,thickness,evidence,fidelity,room,external)
            if(s.area>1e-9) surfaces+=s
        }
        c.walls.filter { !it.touchesOutside || c.facadeEnvelopes.none { f -> f.floorId==it.floorId } }.forEach { w ->
            val f=c.floor(w.floorId) ?: return@forEach
            val base=w.baseElevation.value ?: f.floorElevation.value ?: 0.0
            val clear=w.height.value ?: f.clearHeight.value ?: 2.7
            val a=w.centreline.a; val b=w.centreline.b
            if(w.centreline.length<0.02) return@forEach
            fun top(p:Pt)=if(w.floorId==topFloor) min(base+clear,roof?.heightAt(p) ?: (base+clear)) else base+clear
            val vertices=listOf(Pt3(a.x,base,a.z),Pt3(b.x,base,b.z),Pt3(b.x,max(base,top(b)),b.z),Pt3(a.x,max(base,top(a)),a.z))
            add("${w.id}:solid",w.id,w.floorId,ResolvedSurfaceKind.WALL,vertices,w.thickness.value ?: 0.12,listOf(w.id),w.fidelity)
        }
        c.openings.filter { !it.exterior }.forEach { o ->
            val gap=AutomaticReconstruction.openingSegment(c,o) ?: return@forEach
            val f=c.floor(o.floorId) ?: return@forEach
            if(f.footprint?.contains((gap.a+gap.b)*0.5)==false) return@forEach
            val base=(f.floorElevation.value ?: 0.0)+(o.sillHeight.value ?: if(o.type==OpeningType.WINDOW) 0.9 else 0.0)
            val height=o.height.value ?: QuantityTakeoffEngine.DEFAULT_ASSUMED_OPENING_HEIGHTS[o.type] ?: 1.5
            add("${o.id}:opening",o.id,o.floorId,ResolvedSurfaceKind.OPENING,listOf(Pt3(gap.a.x,base,gap.a.z),Pt3(gap.b.x,base,gap.b.z),Pt3(gap.b.x,base+height,gap.b.z),Pt3(gap.a.x,base+height,gap.a.z)),0.0,listOf(o.id),
                if(o.height.value==null) FactFidelity.DISPLAY_ASSUMPTION else o.height.fidelity)
        }
        val physicalOpenings=surfaces.filter { it.kind==ResolvedSurfaceKind.OPENING }
        val roofFacets=c.roof?.let { it.facets+it.secondaryMasses.flatMap { m->m.facets } }.orEmpty()
        roofFacets.forEach { facet -> add(facet.id,if(c.roof!!.facets.any { it.id==facet.id }) "roof" else facet.id,null,ResolvedSurfaceKind.ROOF,facet.vertices,0.0,listOf(facet.id),c.roof.fidelity,external=true) }
        c.floors.forEach { f ->
            val base=f.floorElevation.value ?: 0.0
            val clear=if(f.id==topFloor) c.levels.atticFlatCeilingHeight.value ?: f.clearHeight.value ?: 2.7 else f.clearHeight.value ?: 2.7
            fun ceiling(p:Pt)=if(f.id==topFloor) min(base+clear,roof?.heightAt(p) ?: (base+clear)).coerceAtLeast(base) else base+clear
            val slab=c.levels.upperSlabThickness.value ?: 0.25
            f.footprint?.let { p ->
                PlanarTopology.convexParts(p).forEachIndexed { i,part ->
                    add("${f.id}:slab:$i","${f.id}-slab",f.id,ResolvedSurfaceKind.SLAB,part.vertices.map { Pt3(it.x,base,it.z) },slab,listOf(f.id),c.levels.upperSlabThickness.fidelity)
                    add("${f.id}:slab-bottom:$i","${f.id}-slab",f.id,ResolvedSurfaceKind.SLAB,part.vertices.reversed().map { Pt3(it.x,base-slab,it.z) },slab,listOf(f.id),c.levels.upperSlabThickness.fidelity)
                }
                p.edges.forEachIndexed { i,e -> add("${f.id}:slab-edge:$i","${f.id}-slab",f.id,ResolvedSurfaceKind.SLAB,listOf(Pt3(e.a.x,base-slab,e.a.z),Pt3(e.b.x,base-slab,e.b.z),Pt3(e.b.x,base,e.b.z),Pt3(e.a.x,base,e.a.z)),slab,listOf(f.id),c.levels.upperSlabThickness.fidelity) }
            }
            f.rooms.forEach roomLoop@ { r ->
                val room=r.polygon ?: run { diagnostics+="Unresolved room geometry:${r.id}"; return@roomLoop }
                PlanarTopology.convexParts(room).forEachIndexed { i,p -> add("${r.id}:floor:$i",r.id,f.id,ResolvedSurfaceKind.FLOOR,p.vertices.map { Pt3(it.x,base,it.z) },0.0,listOf(r.id),r.matchConfidence,r.id) }
                var remaining=listOf(room)
                var index=0
                if(f.id==topFloor) roofFacets.forEach facetLoop@ { facet ->
                    val plan=Polygon(facet.vertices.map { Pt(it.x,it.z) })
                    if(!PlanarTopology.valid(plan)) return@facetLoop
                    val plane=planeHeight(facet.vertices) ?: return@facetLoop
                    val pieces=remaining.flatMap { PlanarTopology.intersection(it,plan) }
                    remaining=remaining.flatMap { PlanarTopology.difference(it,plan) }
                    pieces.flatMap(PlanarTopology::convexParts).forEach { piece ->
                        val heights=piece.vertices.map { plane(it)-base-clear }
                        val clipped=if(heights.all { it<=1e-9 } || heights.all { it>=-1e-9 }) listOf(piece)
                            else listOfNotNull(clip(piece) { base+clear-plane(it) },clip(piece) { plane(it)-base-clear })
                        clipped.forEach { p -> PlanarTopology.convexParts(p).forEach { part ->
                            add("${r.id}:ceiling:${index++}",r.id,f.id,ResolvedSurfaceKind.CEILING,part.vertices.map { Pt3(it.x,min(base+clear,plane(it)).coerceAtLeast(base),it.z) },0.0,listOf(r.id,facet.id),FactFidelity.weakest(listOf(r.matchConfidence,c.roof!!.fidelity)),r.id)
                        } }
                    }
                }
                remaining.forEach { p -> PlanarTopology.convexParts(p).forEach { part -> add("${r.id}:ceiling:${index++}",r.id,f.id,ResolvedSurfaceKind.CEILING,part.vertices.map { Pt3(it.x,base+clear,it.z) },0.0,listOf(r.id),f.clearHeight.fidelity,r.id) } }
                r.boundary.forEachIndexed boundaryLoop@ { boundaryIndex,boundary ->
                    val edge=boundary.segment; val length=edge.length
                    if(length<0.01) return@boundaryLoop
                    val dir=(edge.b-edge.a)*(1/length)
                    val profile=Polygon(listOf(Pt(0.0,base),Pt(length,base),Pt(length,ceiling(edge.b)),Pt(0.0,ceiling(edge.a))))
                    if(!PlanarTopology.valid(profile)) return@boundaryLoop
                    fun local(p:Pt3)=Pt((p.x-edge.a.x)*dir.x+(p.z-edge.a.z)*dir.z,p.y)
                    fun world(p:Polygon)=p.vertices.map { Pt3(edge.a.x+dir.x*it.x,it.z,edge.a.z+dir.z*it.x) }
                    val thickness=c.wall(boundary.wallId.orEmpty())?.thickness?.value ?: 0.25
                    val cuts=physicalOpenings.filter { o -> o.floorId==f.id && o.vertices.all { p -> abs((p.x-edge.a.x)*dir.z-(p.z-edge.a.z)*dir.x)<=thickness*0.6+0.05 } }
                        .flatMap { o -> val p=Polygon(o.vertices.map(::local)); if(PlanarTopology.valid(p)) PlanarTopology.intersection(profile,p).map { it to o } else emptyList() }
                    var net=listOf(profile)
                    var holes=listOf<Polygon>()
                    cuts.forEach { (p,o) ->
                        var unique=listOf(p); holes.forEach { previous -> unique=unique.flatMap { PlanarTopology.difference(it,previous) } }
                        unique.forEachIndexed { j,h -> add("${r.id}:face:$boundaryIndex:deduction:${o.id}:$j","${r.id}:face:$boundaryIndex",f.id,ResolvedSurfaceKind.OPENING,world(h),0.0,o.evidenceIds,o.fidelity,r.id) }
                        holes=holes+unique; net=net.flatMap { PlanarTopology.difference(it,p) }
                    }
                    net.flatMap(PlanarTopology::convexParts).forEachIndexed { j,p -> add("${r.id}:face:$boundaryIndex:net:$j","${r.id}:face:$boundaryIndex",f.id,ResolvedSurfaceKind.ROOM_WALL_FACE,world(p),0.0,listOf(r.id)+cuts.flatMap { it.second.evidenceIds },r.matchConfidence,r.id,boundary.faceOutside) }
                }
            }
        }
        c.stairs.forEach { s ->
            val base=c.floor(s.floorId)?.floorElevation?.value ?: 0.0
            val p=listOf(Pt3(s.zone.minX,base+0.15,s.zone.minZ),Pt3(s.zone.maxX,base+0.15,s.zone.minZ),Pt3(s.zone.maxX,base+0.15,s.zone.maxZ),Pt3(s.zone.minX,base+0.15,s.zone.maxZ))
            add("${s.id}:zone",s.id,s.floorId,ResolvedSurfaceKind.STAIR,p,0.15,listOf(s.id),s.fidelity)
        }
        return ResolvedBuildingGeometry(surfaces,lineage(c),diagnostics)
    }

    private fun planeHeight(vertices:List<Pt3>):((Pt)->Double)? {
        val a=vertices.first()
        for(i in 1 until vertices.lastIndex) {
            val b=vertices[i]; val c=vertices[i+1]
            val nx=(b.y-a.y)*(c.z-a.z)-(b.z-a.z)*(c.y-a.y)
            val ny=(b.z-a.z)*(c.x-a.x)-(b.x-a.x)*(c.z-a.z)
            val nz=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
            if(abs(ny)>1e-9) return { p -> a.y-(nx*(p.x-a.x)+nz*(p.z-a.z))/ny }
        }
        return null
    }
    /** Half-plane clipping of a simple polygon by a linear height function. */
    private fun clip(p:Polygon,inside:(Pt)->Double):Polygon? {
        val out=mutableListOf<Pt>()
        p.edges.forEach { e -> val a=inside(e.a); val b=inside(e.b)
            if(a>=-1e-9) out+=e.a
            if((a<0)!=(b<0) && abs(a-b)>1e-12) out+=e.a+(e.b-e.a)*(a/(a-b))
        }
        val points=out.distinct()
        return if(points.size>=3) Polygon(points).takeIf { it.area>1e-8 } else null
    }

    fun facades(c:ProjectAnalysisCandidate):ResolvedBuildingGeometry {
        val surfaces=mutableListOf<ResolvedSurface>()
        val diagnostics=mutableListOf<String>()
        c.facadeEnvelopes.forEach { f ->
            if(f.segment.length<0.05 || f.baseLevel.value==null || f.topProfile.size<2) return@forEach
            val wall=FacadeReconstruction.wallPolygon(f)
            if(!PlanarTopology.valid(wall)) { diagnostics+="Invalid facade ring:${f.id}"; return@forEach }
            val groups=c.openingGroups.filter { it.facadeId==f.id }
            val claimed=groups.flatMap { it.memberOpeningIds }.toSet()
            data class Hole(val id:String,val polygon:Polygon,val evidence:List<String>,val fidelity:FactFidelity)
            val holes=groups.map { Hole(it.id,it.outer,it.evidenceIds,it.fidelity) }.toMutableList()
            f.openingIds.filter { it !in claimed }.forEach openingLoop@ { id ->
                val o=c.openings.firstOrNull { it.id==id } ?: return@openingLoop
                val gap=AutomaticReconstruction.openingSegment(c,o) ?: return@openingLoop
                val a=min(FacadeReconstruction.along(f,gap.a),FacadeReconstruction.along(f,gap.b)).coerceIn(0.0,f.segment.length)
                val b=max(FacadeReconstruction.along(f,gap.a),FacadeReconstruction.along(f,gap.b)).coerceIn(0.0,f.segment.length)
                val base=f.baseLevel.requireValue()+(o.sillHeight.value ?: if(o.type==OpeningType.WINDOW) 0.9 else 0.0)
                val height=o.height.value ?: QuantityTakeoffEngine.DEFAULT_ASSUMED_OPENING_HEIGHTS[o.type] ?: 1.5
                if(b-a<0.02 || height<=0.0) return@openingLoop
                val p=Polygon(listOf(Pt(a,base),Pt(b,base),Pt(b,base+height),Pt(a,base+height)))
                val clipped=PlanarTopology.intersection(p,wall).maxByOrNull { it.area } ?: return@openingLoop
                if(holes.any { PlanarTopology.intersection(it.polygon,clipped).sumOf { p->p.area }>0.01 }) {
                    diagnostics+="Trace opening $id overlaps a source group; source group retained"; return@openingLoop
                }
                holes+=Hole(id,clipped,listOf(id),if(o.height.value==null || o.sillHeight.value==null) FactFidelity.DISPLAY_ASSUMPTION else FactFidelity.weakest(listOf(o.height.fidelity,o.sillHeight.fidelity)))
            }
            val direction=(f.segment.b-f.segment.a)*(1/f.segment.length)
            val outward=Pt(direction.z,-direction.x)
            fun points(p:Polygon,depth:Double=0.0)=p.vertices.map { Pt3(f.segment.a.x+direction.x*it.x+outward.x*depth,it.z,f.segment.a.z+direction.z*it.x+outward.z*depth) }
            var solid=listOf(wall)
            holes.forEach { hole -> solid=solid.flatMap { PlanarTopology.difference(it,hole.polygon) } }
            solid=solid.flatMap(PlanarTopology::convexParts)
            solid.forEachIndexed { i,p -> surfaces+=ResolvedSurface("${f.id}:solid:$i",f.id,f.floorId,ResolvedSurfaceKind.WALL,points(p),f.thickness.value ?: 0.25,
                listOf(f.floorId)+holes.flatMap { it.evidence },FactFidelity.weakest(listOf(f.baseLevel.fidelity,f.thickness.fidelity)+holes.map { it.fidelity }),exterior=true) }
            holes.forEach { h -> surfaces+=ResolvedSurface("${f.id}:opening:${h.id}",h.id,f.floorId,ResolvedSurfaceKind.OPENING,points(h.polygon),0.0,h.evidence,h.fidelity,exterior=true) }
            c.facadeFeatures.filter { it.facadeId==f.id }.forEach { feature ->
                val profiles=if(feature.kind==FacadeFeatureKind.FRAME) {
                    val b=feature.profile.bounds; val border=min(b.width,b.depth)*0.06
                    if(b.width<=2*border || b.depth<=2*border) listOf(feature.profile) else {
                        var ring=listOf(feature.profile)
                        PlanarTopology.inset(feature.profile,border).forEach { inner -> ring=ring.flatMap { PlanarTopology.difference(it,inner) } }
                        ring
                    }
                } else listOf(feature.profile)
                profiles.forEachIndexed { i,p ->
                    surfaces+=ResolvedSurface("${feature.id}:front:$i",feature.id,f.floorId,ResolvedSurfaceKind.FEATURE,points(p,feature.depth),0.0,feature.evidenceIds,feature.fidelity,exterior=true)
                    p.edges.forEachIndexed { j,e ->
                        val a=points(Polygon(listOf(e.a,e.b,e.b)),0.0); val b=points(Polygon(listOf(e.a,e.b,e.b)),feature.depth)
                        surfaces+=ResolvedSurface("${feature.id}:edge:$i:$j",feature.id,f.floorId,ResolvedSurfaceKind.FEATURE,listOf(a[0],a[1],b[1],b[0]),0.0,feature.evidenceIds,feature.depthFidelity,exterior=true)
                    }
                }
            }
        }
        return ResolvedBuildingGeometry(surfaces,"facade-resolution:${c.reconstruction?.policy?.version ?: "unversioned"}",diagnostics)
    }
}
