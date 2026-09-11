package com.buildplan.app.analyzer.reconstruction

import com.buildplan.app.analyzer.candidate.*
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.reconstruction.internal.PlanarTopology
import com.buildplan.app.analyzer.visual.FacadeMapper
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object FacadeReconstruction {
    fun side(f:FacadeEnvelopeCandidate,c:ProjectAnalysisCandidate):FacadeSide {
        val center=c.floor(f.floorId)?.footprint?.centroid ?: Pt(0.0,0.0)
        val mid=(f.segment.a+f.segment.b)*0.5
        return if(abs(f.segment.a.x-f.segment.b.x)>abs(f.segment.a.z-f.segment.b.z)) {
            if(mid.z<center.z) FacadeSide.NORTH else FacadeSide.SOUTH
        } else if(mid.x<center.x) FacadeSide.WEST else FacadeSide.EAST
    }
    private fun bounds(c:ProjectAnalysisCandidate)=c.floors.mapNotNull { it.footprint?.bounds }.reduceOrNull { a,b->a.union(b) }
    fun fraction(p:Pt,side:FacadeSide,b:Box):Double {
        val u=if(side==FacadeSide.NORTH || side==FacadeSide.SOUTH) (p.x-b.minX)/b.width else (p.z-b.minZ)/b.depth
        return if(side==FacadeSide.NORTH || side==FacadeSide.EAST) 1-u else u
    }
    fun along(f:FacadeEnvelopeCandidate,p:Pt)=((p.x-f.segment.a.x)*(f.segment.b.x-f.segment.a.x)+(p.z-f.segment.a.z)*(f.segment.b.z-f.segment.a.z))/f.segment.length
    fun wallPolygon(f:FacadeEnvelopeCandidate):Polygon = Polygon(listOf(Pt(0.0,f.baseLevel.requireValue()),Pt(f.segment.length,f.baseLevel.requireValue()))+
        f.topProfile.map { Pt(along(f,Pt(it.x,it.z)),it.y) }.sortedByDescending { it.x }).simplified()
    private val openingKinds=setOf(VisualObservationKind.OPENING_RECTANGLE,VisualObservationKind.DOOR_RECTANGLE,VisualObservationKind.GARAGE_GATE_RECTANGLE)

    /** Four-sided global assignment, with metadata as a prior and source projection matching. */
    fun mapElevations(c:ProjectAnalysisCandidate):ProjectAnalysisCandidate {
        val assignments=c.visual.facades
        if(assignments.isEmpty() || assignments.size>4) return c
        val b=bounds(c) ?: return c
        val sides=FacadeMapper.of(c)
        fun cost(a:FacadeAssignment,s:FacadeSide):Double {
            val asset=c.visual.asset(a.assetUrl) ?: return 1.0
            val box=asset.silhouette ?: return 1.0
            val source=asset.observations.filter { it.kind in openingKinds }.map { it.bounds.relativeTo(box).centreX }
            val projected=c.openings.filter { it.exterior && sides.sideOf(it)==s }.mapNotNull { AutomaticReconstruction.openingSegment(c,it)?.let { gap->fraction((gap.a+gap.b)*0.5,s,b) } }
            val residual=if(source.isEmpty() || projected.isEmpty()) 0.5 else source.map { u->projected.minOf { abs(it-u) } }.average()
            val single=c.copy(visual=c.visual.copy(facades=listOf(a.copy(sides=listOf(s)))))
            val silhouette=AutomaticReconstruction.elevationScore(single) ?: 0.5
            val prior=if(s in a.sides) 0.0 else a.confidence*0.15
            return residual+(1-silhouette)+prior
        }
        fun permutations(values:List<FacadeSide>):List<List<FacadeSide>> = if(values.isEmpty()) listOf(emptyList()) else values.flatMap { first->
            permutations(values-first).map { listOf(first)+it } }
        val ranked=permutations(FacadeSide.entries).map { mapping->mapping to assignments.indices.sumOf { cost(assignments[it],mapping[it]) } }.sortedBy { it.second }
        val best=ranked.first()
        val margin=ranked.getOrNull(1)?.let { it.second-best.second } ?: 0.0
        return c.copy(visual=c.visual.copy(facades=assignments.mapIndexed { i,a->a.copy(sides=listOf(best.first[i]),confidence=max(0.6,a.confidence),
            reason="global elevation assignment from silhouette, opening positions and metadata; margin=$margin") }))
    }

    fun reconstruct(input:ProjectAnalysisCandidate):ProjectAnalysisCandidate {
        val c=mapElevations(input)
        val b=bounds(c) ?: return c
        val terrain=c.levels.terrain.value ?: return c
        val height=(c.levels.ridge.value ?: return c)-terrain
        if(height<=0) return c
        val groups=mutableListOf<OpeningGroupCandidate>()
        val features=mutableListOf<FacadeFeatureCandidate>()
        c.visual.facades.filter { it.isSettled }.forEach { assignment->
            val asset=c.visual.asset(assignment.assetUrl) ?: return@forEach
            val box=asset.silhouette ?: return@forEach
            val side=assignment.sides.single()
            val facades=c.facadeEnvelopes.filter { side(it,c)==side && it.segment.length>0.1 }
            asset.observations.forEachIndexed { index,observation->
                if(observation.confidence<0.55) return@forEachIndexed
                val fraction=observation.bounds.relativeTo(box)
                val sourceId="visual:${asset.assetUrl}:$index"
                facades.forEach facadeLoop@ { facade->
                    val u0=fraction(facade.segment.a,side,b); val u1=fraction(facade.segment.b,side,b)
                    if(abs(u1-u0)<1e-6) return@facadeLoop
                    fun toLocal(p:Pt)=Pt((p.x-u0)/(u1-u0)*facade.segment.length,terrain+(1-p.z)*height)
                    val points=if(observation.outline.size>=3) observation.outline.map { Pt((it.x-box.left)/box.width,(it.z-box.top)/box.height) }
                        else listOf(Pt(fraction.left,fraction.top),Pt(fraction.right,fraction.top),Pt(fraction.right,fraction.bottom),Pt(fraction.left,fraction.bottom))
                    val raw=Polygon(points.map(::toLocal)).normalisedWinding()
                    if(!PlanarTopology.valid(raw)) return@facadeLoop
                    val wall=wallPolygon(facade)
                    val clipped=PlanarTopology.intersection(raw,wall).maxByOrNull { it.area } ?: return@facadeLoop
                    if(clipped.area<0.08) return@facadeLoop
                    val fidelity=FactFidelity.weakest(listOf(observation.fidelity,c.levels.ridge.fidelity,c.levels.terrain.fidelity))
                    if(observation.kind in openingKinds) {
                        if(clipped.area/raw.area<0.55) return@facadeLoop
                        val members=facade.openingIds.filter { id -> c.openings.firstOrNull { it.id==id }?.let { o->
                            AutomaticReconstruction.openingSegment(c,o)?.let { gap ->
                                val start=min(along(facade,gap.a),along(facade,gap.b)); val end=max(along(facade,gap.a),along(facade,gap.b))
                                val overlap=min(end,clipped.bounds.maxX)-max(start,clipped.bounds.minX)
                                overlap>0 && overlap/(end-start)>0.5
                            } }==true }
                        // Exact labelled widths and heights are hard, traced raster gaps are uncertain.
                        if(members.any { id -> val o=c.openings.first { it.id==id }; o.width.fidelity==FactFidelity.SOURCE_EXACT && abs(o.width.requireValue()-clipped.bounds.width)>(o.width.uncertainty ?: 0.02) }) return@facadeLoop
                        if(groups.any { it.facadeId==facade.id && PlanarTopology.intersection(it.outer,clipped).sumOf { p->p.area }/min(it.outer.area,clipped.area)>0.5 }) return@facadeLoop
                        val id="${facade.id}-group-$index"
                        groups+=OpeningGroupCandidate(id,facade.id,facade.floorId,clipped,members,listOf(clipped),
                            if(observation.kind==VisualObservationKind.DOOR_RECTANGLE) clipped else null,emptyList(),listOf(sourceId),observation.confidence,fidelity)
                    } else {
                        val kind=when(observation.kind) {
                            VisualObservationKind.FRAME_OR_PORTAL->FacadeFeatureKind.FRAME
                            VisualObservationKind.HORIZONTAL_BAND->FacadeFeatureKind.BAND
                            VisualObservationKind.RAILING_STRIP->FacadeFeatureKind.RAILING
                            else->null
                        } ?: return@facadeLoop
                        features+=FacadeFeatureCandidate("${facade.id}-feature-$index",kind,facade.id,facade.floorId,clipped,
                            if(kind==FacadeFeatureKind.FRAME) 0.15 else 0.08,listOf(sourceId),observation.confidence,fidelity)
                        if(kind==FacadeFeatureKind.RAILING && clipped.bounds.minZ>(c.levels.groundFloor.value ?: 0.0)+0.4) {
                            val cb=clipped.bounds
                            val slab=Polygon(listOf(Pt(cb.minX,cb.minZ-0.18),Pt(cb.maxX,cb.minZ-0.18),Pt(cb.maxX,cb.minZ),Pt(cb.minX,cb.minZ)))
                            features+=FacadeFeatureCandidate("${facade.id}-balcony-$index",FacadeFeatureKind.BALCONY,facade.id,facade.floorId,slab,0.7,listOf(sourceId),observation.confidence,FactFidelity.TRACE_UNCERTAIN)
                        }
                    }
                }
            }
        }
        return c.copy(openingGroups=groups,facadeFeatures=features)
    }
}
