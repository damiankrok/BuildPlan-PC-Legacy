package com.buildplan.app.analyzer.reconstruction

import com.buildplan.app.analyzer.candidate.*
import com.buildplan.app.analyzer.fidelity.*
import com.buildplan.app.analyzer.reconstruction.internal.PlanarTopology
import kotlin.math.max
import kotlin.math.min

/** Vertical intervals are independent of XY overlap. A slab is never an enclosed volume. */
internal object MassSolver {
    /** Source-backed horizontal features remain competing mass uses until scored. */
    fun featureAlternatives(c:ProjectAnalysisCandidate,features:List<FacadeFeatureCandidate>):List<Pair<String,ProjectAnalysisCandidate>> {
        val state=c.reconstruction ?: return listOf("FEATURE" to c.copy(facadeFeatures=(c.facadeFeatures+features).distinctBy { it.id }))
        val slabs=features.filter { it.kind==FacadeFeatureKind.BALCONY || it.kind==FacadeFeatureKind.CANOPY }
        if(slabs.isEmpty()) return listOf("FEATURE" to c.copy(facadeFeatures=(c.facadeFeatures+features).distinctBy { it.id }))
        return listOf(MassUse.SLAB,MassUse.OPEN_COVERED,MassUse.FACADE_PROJECTION,MassUse.ENCLOSED).map { use ->
            val chosen=features.map { if(it in slabs && use==MassUse.FACADE_PROJECTION) it.copy(depth=0.02) else it }
            val regions=slabs.mapNotNull { s ->
                val facade=c.facadeEnvelopes.firstOrNull { it.id==s.facadeId } ?: return@mapNotNull null
                val d=(facade.segment.b-facade.segment.a)*(1/facade.segment.length)
                val n=FacadeReconstruction.outward(facade,c)
                val depth=if(use==MassUse.FACADE_PROJECTION) 0.02 else s.depth
                val b=s.profile.bounds; val a=facade.segment.a+d*b.minX; val end=facade.segment.a+d*b.maxX
                val footprint=Polygon(listOf(a,end,end+n*depth,a+n*depth)).normalisedWinding()
                val base=if(use==MassUse.ENCLOSED || use==MassUse.OPEN_COVERED) c.levels.groundFloor.value ?: b.minZ else b.minZ
                MassRegion("feature-mass:${s.id}",footprint,base,max(base+0.01,b.maxZ),use,RoofFamily.FLAT,s.floorId,s.evidenceIds,s.confidence)
            }
            use.name to c.copy(facadeFeatures=(c.facadeFeatures+chosen).distinctBy { it.id },reconstruction=state.copy(masses=topology(state.masses.filterNot { m -> regions.any { it.id==m.id } }+regions)))
        }
    }

    fun sourceFeatureScore(c:ProjectAnalysisCandidate):Double? {
        val values=c.reconstruction?.masses.orEmpty().filter { it.id.startsWith("feature-mass:") }.mapNotNull { m ->
            val feature=c.facadeFeatures.firstOrNull { "feature-mass:${it.id}"==m.id } ?: return@mapNotNull null
            val hasRailing=c.facadeFeatures.any { it.kind==FacadeFeatureKind.RAILING && it.facadeId==feature.facadeId }
            when(m.use) { MassUse.SLAB->1.0; MassUse.OPEN_COVERED->if(hasRailing) 0.75 else 1.0; MassUse.FACADE_PROJECTION->0.35; MassUse.ENCLOSED->0.0; MassUse.TERRACE->0.5 }
        }
        return values.takeIf { it.isNotEmpty() }?.average()
    }
    fun topology(regions: List<MassRegion>): List<MassRegion> = regions.map { a ->
        val near = regions.filter { it.id!=a.id && PlanarTopology.distance(a.outline,it.outline)<0.05 }
        a.copy(adjacentIds=near.filter { min(it.top,a.top)>=max(it.base,a.base)-0.05 }.map { it.id },
            overlappingIds=near.filter { PlanarTopology.intersection(a.outline,it.outline).sumOf { p->p.area }>0.01 }.map { it.id })
    }
    fun violations(regions: List<MassRegion>): List<String> = buildList {
        regions.forEach { a ->
            if(!PlanarTopology.valid(a.outline)) add("invalid mass ring:${a.id}")
            if(a.use==MassUse.ENCLOSED && regions.size>1 && a.adjacentIds.isEmpty()) add("unsupported disconnected enclosed mass:${a.id}")
        }
        regions.forEachIndexed { i,a -> regions.drop(i+1).forEach { b ->
            if(a.use==MassUse.ENCLOSED && b.use==MassUse.ENCLOSED && min(a.top,b.top)-max(a.base,b.base)>0.02 &&
                PlanarTopology.intersection(a.outline,b.outline).sumOf { it.area }>0.02) add("enclosed volume overlap:${a.id}/${b.id}")
        } }
    }

    fun hypotheses(c: ProjectAnalysisCandidate, graph: EvidenceGraph, policy: ReconstructionPolicy): List<BuildingHypothesis> {
        val floors=c.floors.filter { it.footprint?.let(PlanarTopology::valid)==true }.sortedBy { it.order }
        if(floors.isEmpty()) return listOf(BuildingHypothesis("trace",emptyList(),c,emptyList()))
        val layers=mutableListOf<MassRegion>()
        floors.forEachIndexed { i,f ->
            val base=f.floorElevation.value ?: 0.0
            val next=floors.getOrNull(i+1)
            val top=next?.floorElevation?.value ?: c.roof?.eaveElevation?.value ?: (base+(f.clearHeight.value ?: 2.7))
            val overlap=next?.footprint?.let { PlanarTopology.intersection(f.footprint!!,it) }.orEmpty()
            val lower=next?.footprint?.let { PlanarTopology.difference(f.footprint!!,it) }.orEmpty()
            val parts=if(next==null) listOf(f.footprint!!) else overlap
            parts.filter { it.area>0.02 }.forEachIndexed { n,p -> layers+=MassRegion("${f.id}-body-$n",p,base,max(base+0.1,top),MassUse.ENCLOSED,
                if(next==null) c.roof?.family ?: RoofFamily.UNKNOWN else RoofFamily.FLAT,f.id,listOf(f.id),0.75) }
            lower.filter { it.area>0.02 }.forEachIndexed { n,p ->
                val roofKind=if(c.roof!=null && PlanarTopology.intersection(p,c.roof.outline).sumOf { it.area } / p.area > 0.8) c.roof.family else RoofFamily.FLAT
                layers+=MassRegion("${f.id}-lower-$n",p,base,max(base+0.1,top),MassUse.ENCLOSED,roofKind,f.id,listOf(f.id),0.65)
            }
        }
        val regions=topology(layers)
        val decomposed=c.roof?.let { roof ->
            val lower=regions.filter { it.id.contains("-lower-") }.flatMap { region ->
                PlanarTopology.difference(region.outline,roof.outline).filter { it.area>0.02 }.mapIndexed { i,p -> region.copy(id="${region.id}-exposed-$i",outline=p,roof=RoofFamily.FLAT) }
            }
            // Do not invent replacement roof levels or extend a roof beyond structural closure.
            if(lower.isEmpty()) c else {
                val secondary=lower.map { mass ->
                    val previous=roof.secondaryMasses.maxByOrNull { PlanarTopology.intersection(it.outline,mass.outline).sumOf { p->p.area } }
                    val level=previous?.topElevation ?: Measured(mass.top,MeasureUnit.METER,FactFidelity.TRACE_UNCERTAIN,
                        Provenance(c.floor(mass.floorId)?.planAssetUrl,"storey difference","lower roof at next floor level"),uncertainty=0.25)
                    val facet=RoofFacetCandidate("${mass.id}-roof",mass.outline.vertices.map { Pt3(it.x,level.requireValue(),it.z) },mass.outline.area,mass.outline.edges.first())
                    SecondaryRoofMass(mass.id,mass.outline,RoofFamily.FLAT,listOf(facet),level,level.fidelity,"structural closure minus upper closure; independently owned lower roof")
                }
                // totalArea retains the established PRIMARY roof semantics. The final resolved
                // quantity adapter separately sums primary and secondary facet surfaces.
                c.copy(roof=roof.copy(secondaryMasses=secondary))
            }
        } ?: c
        return listOf(BuildingHypothesis("plan-volumes",regions,decomposed,graph.nodes.filter { it.kind==EvidenceKind.STOREY }.map { it.id },hardViolations=violations(regions))).take(policy.initialHypotheses)
    }
}
