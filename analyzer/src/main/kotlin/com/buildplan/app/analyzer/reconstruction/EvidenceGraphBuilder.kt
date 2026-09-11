package com.buildplan.app.analyzer.reconstruction

import com.buildplan.app.analyzer.candidate.*
import com.buildplan.app.analyzer.fidelity.*
import com.buildplan.app.analyzer.site.SourcePackage

internal object EvidenceGraphBuilder {
    fun withResolvedFeatures(c:ProjectAnalysisCandidate):ProjectAnalysisCandidate {
        val state=c.reconstruction ?: return c
        val nodes=state.graph.nodes.filterNot { it.id.startsWith("resolved:") }.toMutableList()
        val edges=state.graph.edges.filterNot { it.from.startsWith("resolved:") || it.to.startsWith("resolved:") || it.reason=="resolved multi-view correspondence" }.toMutableList()
        fun add(id:String,kind:EvidenceKind,geometry:List<Pt>,evidence:List<String>,confidence:Double,semantic:String,frame:String="model-metres") {
            nodes+=EvidenceNode("resolved:$id",kind,evidence.firstOrNull().orEmpty(),EvidenceClass.ASSUMPTION,FactFidelity.TRACE_UNCERTAIN,confidence,"resolved architectural hypothesis",null,geometry=geometry,semantic=semantic,frame=frame)
            evidence.filter { e -> nodes.any { it.id==e } }.forEach { e -> edges+=EvidenceEdge(e,"resolved:$id",EvidenceRelation.SUPPORTS,confidence,"source supports resolved feature") }
        }
        state.masses.forEach { m -> add(m.id,EvidenceKind.MASS_REGION,m.outline.vertices,m.evidenceIds,m.confidence,"${m.use}:${m.base}..${m.top}") }
        c.facadeEnvelopes.forEach { f -> add(f.id,EvidenceKind.FACADE,listOf(f.segment.a,f.segment.b),listOf(f.floorId),0.75,"facade plane") }
        c.openingGroups.forEach { g -> add(g.id,EvidenceKind.OPENING,g.outer.vertices,g.evidenceIds+g.memberOpeningIds,g.confidence,"opening group","facade-metres:${g.facadeId}") }
        c.facadeFeatures.forEach { f -> add(f.id,EvidenceKind.ELEVATION_FEATURE,f.profile.vertices,f.evidenceIds,f.confidence,f.kind.name,"facade-metres:${f.facadeId}") }
        c.roofElements.forEach { e ->
            add(e.id,EvidenceKind.ROOF_REGION,e.footprint.vertices,e.evidenceIds,e.confidence,e.kind.name)
            val known=e.evidenceIds.filter { id->nodes.any { it.id==id } }
            known.zipWithNext().forEach { (a,b) -> edges+=EvidenceEdge(a,b,EvidenceRelation.SAME_PHYSICAL_FEATURE,e.confidence,"resolved multi-view correspondence") }
        }
        val ids=nodes.map { it.id }.toSet()
        state.masses.forEach { m -> m.adjacentIds.forEach { other ->
            if("resolved:$other" in ids) edges+=EvidenceEdge("resolved:${m.id}","resolved:$other",EvidenceRelation.ADJACENT_TO,0.8,"registered mass contact")
        } }
        return c.copy(reconstruction=state.copy(graph=EvidenceGraph(nodes,edges)))
    }
    fun build(c: ProjectAnalysisCandidate, source: SourcePackage): EvidenceGraph {
        val nodes = mutableListOf<EvidenceNode>()
        val edges = mutableListOf<EvidenceEdge>()
        fun link(a: String, b: String, relation: EvidenceRelation, why: String) { edges += EvidenceEdge(a,b,relation,1.0,why) }
        val assetIds = source.assets.assets.distinctBy { it.url }.mapIndexed { i,a ->
            val id = "asset:$i"
            val cls = when { a.role.isPlan || a.role.name == "SECTION" -> EvidenceClass.PLAN_SECTION; a.role.isElevation -> EvidenceClass.ELEVATION; else -> EvidenceClass.RENDER }
            nodes += EvidenceNode(id,EvidenceKind.SOURCE_ASSET,a.url,cls,FactFidelity.SOURCE_EXACT,1.0,"page asset manifest",null,semantic=a.role.name)
            a.url to id
        }.toMap()
        source.assets.assets.filter { it.sha256 != null }.groupBy { it.sha256 }.values.forEach { duplicates ->
            duplicates.distinctBy { it.url }.zipWithNext().forEach { (a,b) -> link(assetIds.getValue(a.url),assetIds.getValue(b.url),EvidenceRelation.SAME_PHYSICAL_FEATURE,"identical source bytes") }
        }
        source.scalars.forEachIndexed { i,f -> nodes += EvidenceNode("fact:$i",EvidenceKind.PUBLISHED_FACT,f.measured.provenance.sourceUrl ?: source.identity.canonicalUrl,
            EvidenceClass.EXACT_METRIC,f.measured.fidelity,1.0,f.measured.provenance.method,f.measured.uncertainty,measurement=f.measured,semantic=f.key.name) }
        c.dimensions.forEachIndexed { i,d -> nodes += EvidenceNode("dimension:$i",EvidenceKind.DIMENSION,d.measured.provenance.sourceUrl.orEmpty(),
            if (d.measured.fidelity == FactFidelity.SOURCE_EXACT) EvidenceClass.EXACT_METRIC else EvidenceClass.PLAN_SECTION,
            d.measured.fidelity,0.8,d.measured.provenance.method,d.measured.uncertainty,measurement=d.measured,semantic="${d.scope}/${d.name}") }
        c.floors.forEach { f ->
            val uncertainty = f.calibration?.pixelsPerMeter?.value?.let { 2.0 / it }
            nodes += EvidenceNode(f.id,EvidenceKind.STOREY,f.planAssetUrl.orEmpty(),EvidenceClass.PLAN_SECTION,f.floorElevation.fidelity,0.8,
                "registered structural plan closure",uncertainty,geometry=f.footprint?.vertices.orEmpty(),measurement=f.floorElevation,semantic=f.order.toString())
            assetIds[f.planAssetUrl]?.let { link(f.id,it,EvidenceRelation.DERIVED_FROM,"plan registration") }
            f.rooms.forEach { r ->
                nodes += EvidenceNode(r.id,EvidenceKind.PLAN_REGION,f.planAssetUrl.orEmpty(),EvidenceClass.PLAN_SECTION,r.matchConfidence,0.7,"closed room region",uncertainty,geometry=r.polygon?.vertices.orEmpty(),measurement=r.plannedArea,semantic=r.kind.name)
                link(r.id,f.id,EvidenceRelation.BELONGS_TO_STOREY,"room on plan")
            }
        }
        c.walls.forEach { w ->
            nodes += EvidenceNode(w.id,EvidenceKind.WALL_LINE,c.floor(w.floorId)?.planAssetUrl.orEmpty(),EvidenceClass.PLAN_SECTION,w.fidelity,0.75,
                "structural wall trace",w.thickness.uncertainty,geometry=listOf(w.centreline.a,w.centreline.b),measurement=w.thickness,semantic=w.wallClass.name)
            link(w.id,w.floorId,EvidenceRelation.BELONGS_TO_STOREY,"wall on registered plan")
            (w.roomIdsLeft+w.roomIdsRight).filter { id -> nodes.any { it.id==id } }.forEach { link(w.id,it,EvidenceRelation.BOUNDS_MASS,"region boundary wall") }
        }
        c.openings.forEach { o ->
            nodes += EvidenceNode(o.id,EvidenceKind.OPENING,c.floor(o.floorId)?.planAssetUrl.orEmpty(),EvidenceClass.PLAN_SECTION,o.width.fidelity,0.65,
                "gap on structural wall",o.width.uncertainty,geometry=AutomaticReconstruction.openingSegment(c,o)?.let { listOf(it.a,it.b) }.orEmpty(),measurement=o.width,semantic=o.type.name)
            if (c.wall(o.wallId)!=null) link(o.id,o.wallId,EvidenceRelation.CONSTRAINS,"opening belongs to wall")
        }
        c.stairs.forEach { s -> nodes += EvidenceNode(s.id,EvidenceKind.STAIR,c.floor(s.floorId)?.planAssetUrl.orEmpty(),EvidenceClass.PLAN_SECTION,s.fidelity,0.65,"tread regularity and floor association",null,
            geometry=listOf(Pt(s.zone.minX,s.zone.minZ),Pt(s.zone.maxX,s.zone.minZ),Pt(s.zone.maxX,s.zone.maxZ),Pt(s.zone.minX,s.zone.maxZ)),semantic=s.direction) }
        c.visual.assets.forEach { a -> a.observations.forEachIndexed { i,o ->
            val id = "visual:${a.assetUrl}:$i"
            val b = o.bounds
            nodes += EvidenceNode(id,if(a.viewpoint==VisualViewpoint.ORTHOGRAPHIC_ELEVATION) EvidenceKind.ELEVATION_FEATURE else EvidenceKind.RENDER_FEATURE,
                a.assetUrl,if(a.viewpoint==VisualViewpoint.ORTHOGRAPHIC_ELEVATION) EvidenceClass.ELEVATION else EvidenceClass.RENDER,
                o.fidelity,o.confidence,o.method,2.0 / maxOf(a.widthPx,a.heightPx,1),geometry=o.outline.ifEmpty { listOf(Pt(b.left,b.top),Pt(b.right,b.top),Pt(b.right,b.bottom),Pt(b.left,b.bottom)) },frame="normalized-image",semantic=o.kind.name)
            assetIds[a.assetUrl]?.let { link(id,it,EvidenceRelation.DERIVED_FROM,"raster feature extraction") }
        } }
        c.visual.conflicts.forEach { conflict ->
            val observation = "visual:${conflict.assetUrl}:${conflict.observationIndex}"
            if(nodes.any { it.id==observation }) conflict.subjectIds.filter { id -> nodes.any { it.id==id } }.forEach {
                link(observation,it,EvidenceRelation.CONTRADICTS,conflict.impact)
            }
        }
        val conflicted = edges.filter { it.relation==EvidenceRelation.CONTRADICTS }.flatMap { listOf(it.from,it.to) }.toSet()
        return EvidenceGraph(nodes.map { if(it.id in conflicted) it.copy(contradiction=ContradictionStatus.UNRESOLVED) else it },edges)
    }
}
