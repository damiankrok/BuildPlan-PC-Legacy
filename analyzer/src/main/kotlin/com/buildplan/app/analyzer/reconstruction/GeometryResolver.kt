package com.buildplan.app.analyzer.reconstruction

import com.buildplan.app.analyzer.candidate.*
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.quantity.QuantityTakeoffEngine
import com.buildplan.app.analyzer.reconstruction.internal.PlanarTopology
import kotlin.math.max
import kotlin.math.min

/** All facade clipping and assumptions are resolved here, before either renderer or quantity adapter. */
internal object GeometryResolver {
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
