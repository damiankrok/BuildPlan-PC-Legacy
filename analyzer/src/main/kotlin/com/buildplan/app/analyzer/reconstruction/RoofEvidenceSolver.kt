package com.buildplan.app.analyzer.reconstruction

import com.buildplan.app.analyzer.candidate.*
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.reconstruction.internal.PlanarTopology
import com.buildplan.app.analyzer.roof.RoofHeightField
import kotlin.math.*

/** Secondary elements never select or distort the primary roof family. */
internal object RoofEvidenceSolver {
    private data class Observation(val id:String,val side:FacadeSide,val box:NormalizedBox,val confidence:Double,val kind:VisualObservationKind)
    fun solve(c:ProjectAnalysisCandidate):List<RoofElementCandidate> {
        val roof=c.roof ?: return emptyList()
        val field=RoofHeightField(roof)
        val bounds=c.floors.mapNotNull { it.footprint?.bounds }.reduceOrNull { a,b->a.union(b) } ?: return emptyList()
        val ground=c.levels.terrain.value ?: return emptyList(); val height=(c.levels.ridge.value ?: return emptyList())-ground
        if(height<=0) return emptyList()
        fun horizontal(u:Double,side:FacadeSide):Double {
            val fraction=if(side==FacadeSide.NORTH || side==FacadeSide.EAST) 1-u else u
            return if(side==FacadeSide.NORTH || side==FacadeSide.SOUTH) bounds.minX+fraction*bounds.width else bounds.minZ+fraction*bounds.depth
        }
        fun y(v:Double)=ground+(1-v)*height
        val observations=c.visual.facades.filter { it.isSettled }.flatMap { assignment ->
            val asset=c.visual.asset(assignment.assetUrl) ?: return@flatMap emptyList()
            val box=asset.silhouette ?: return@flatMap emptyList()
            asset.observations.mapIndexedNotNull { i,o ->
                if(o.kind !in setOf(VisualObservationKind.ROOF_STACK,VisualObservationKind.ROOFLIGHT_PATCH) || o.confidence<0.6) null
                else Observation("visual:${asset.assetUrl}:$i",assignment.sides.single(),o.bounds.relativeTo(box),o.confidence,o.kind)
            }
        }
        val elements=mutableListOf<RoofElementCandidate>()
        val stacks=observations.filter { it.kind==VisualObservationKind.ROOF_STACK && it.box.width in 0.003..0.15 }
        val x=stacks.filter { it.side==FacadeSide.NORTH || it.side==FacadeSide.SOUTH }
        val z=stacks-x.toSet()
        data class Pairing(val a:Observation,val b:Observation,val p:Pt,val top:Double,val cost:Double)
        val pairings=x.flatMap { a -> z.mapNotNull { b ->
            val p=Pt(horizontal(a.box.centreX,a.side),horizontal(b.box.centreX,b.side))
            val top=(y(a.box.top)+y(b.box.top))/2
            val local=field.heightAt(p) ?: return@mapNotNull null
            val mismatch=abs(y(a.box.top)-y(b.box.top))
            if(!roof.outline.contains(p) || mismatch>height*0.08 || top-local<0.12 || top-local>height*0.4) null
            else Pairing(a,b,p,top,mismatch/height)
        } }.sortedWith(compareBy<Pairing> { it.cost }.thenBy { it.a.id }.thenBy { it.b.id }).take(32)
        val used=mutableSetOf<String>()
        pairings.forEach { pair ->
            if(pair.a.id in used || pair.b.id in used) return@forEach
            val wx=pair.a.box.width*bounds.width; val wz=pair.b.box.width*bounds.depth
            val box=Box(pair.p.x-wx/2,pair.p.z-wz/2,pair.p.x+wx/2,pair.p.z+wz/2)
            val p=StairTopologySolver.polygon(box)
            if(elements.any { PlanarTopology.distance(it.footprint,p)<0.2 }) return@forEach
            if(PlanarTopology.difference(p,roof.outline).sumOf { it.area }>0.01) return@forEach
            val support=stacks.filter { o ->
                val expected=if(o.side==FacadeSide.NORTH || o.side==FacadeSide.SOUTH) pair.p.x else pair.p.z
                abs(horizontal(o.box.centreX,o.side)-expected)<max(wx,wz) && abs(y(o.box.top)-pair.top)<height*0.08
            }
            used+=support.map { it.id }
            val facet=roof.facets.firstOrNull { Polygon(it.vertices.map { v->Pt(v.x,v.z) }).contains(pair.p) } ?: return@forEach
            elements+=RoofElementCandidate("stack-${elements.size}",RoofElementKind.STACK,facet.id,p.vertices.map { Pt3(it.x,pair.top,it.z) },p,support.map { it.id },support.map { it.confidence }.average(),FactFidelity.TRACE_UNCERTAIN)
        }
        observations.filter { it.kind==VisualObservationKind.ROOFLIGHT_PATCH }.take(16).forEach { o ->
            val box=o.box
            val uv=listOf(Pt(horizontal(box.left,o.side),y(box.top)),Pt(horizontal(box.right,o.side),y(box.top)),Pt(horizontal(box.right,o.side),y(box.bottom)),Pt(horizontal(box.left,o.side),y(box.bottom)))
            val options=roof.facets.mapNotNull { facet ->
                val points=uv.map { backProject(it,o.side,facet) ?: return@mapNotNull null }
                val p=Polygon(points.map { Pt(it.x,it.z) })
                val own=Polygon(facet.vertices.map { Pt(it.x,it.z) })
                if(!PlanarTopology.valid(p) || PlanarTopology.difference(p,own).sumOf { it.area }>0.01) null else facet to points
            }
            val best=options.minByOrNull { (_,p) -> when(o.side) { FacadeSide.NORTH->p.map { it.z }.average(); FacadeSide.SOUTH->-p.map { it.z }.average(); FacadeSide.WEST->p.map { it.x }.average(); FacadeSide.EAST->-p.map { it.x }.average() } } ?: return@forEach
            val p=Polygon(best.second.map { Pt(it.x,it.z) })
            if(elements.any { PlanarTopology.intersection(it.footprint,p).sumOf { q->q.area }>0.01 }) return@forEach
            elements+=RoofElementCandidate("rooflight-${elements.size}",RoofElementKind.ROOFLIGHT,best.first.id,best.second,p,listOf(o.id),o.confidence,FactFidelity.TRACE_UNCERTAIN)
        }
        return elements
    }

    /** Orthographic facade coordinate plus height intersects one roof plane. */
    fun backProject(uy:Pt,side:FacadeSide,facet:RoofFacetCandidate):Pt3? {
        val a=facet.vertices[0]; val b=facet.vertices[1]; val c=facet.vertices[2]
        val nx=(b.y-a.y)*(c.z-a.z)-(b.z-a.z)*(c.y-a.y)
        val ny=(b.z-a.z)*(c.x-a.x)-(b.x-a.x)*(c.z-a.z)
        val nz=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
        return if(side==FacadeSide.NORTH || side==FacadeSide.SOUTH) {
            if(abs(nz)<1e-9) null else Pt3(uy.x,uy.z,a.z-(nx*(uy.x-a.x)+ny*(uy.z-a.y))/nz)
        } else if(abs(nx)<1e-9) null else Pt3(a.x-(nz*(uy.x-a.z)+ny*(uy.z-a.y))/nx,uy.z,uy.x)
    }
}
