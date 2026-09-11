package com.buildplan.app.analyzer.reconstruction

import com.buildplan.app.analyzer.candidate.*
import com.buildplan.app.analyzer.reconstruction.internal.PlanarTopology
import com.buildplan.app.analyzer.site.SourcePackage
import kotlin.math.*

internal data class SourceScore(val scores:Map<String,Double>,val residuals:Map<String,Double>,val hard:List<String>,val overall:Double,val diagnostics:List<String>,val cameras:Map<String,CameraFit> = emptyMap())
internal object SourceScorer {
    fun score(c:ProjectAnalysisCandidate,source:SourcePackage,budget:ProjectionBudget,cameras:Map<String,CameraFit> = emptyMap()):SourceScore {
        val scores=linkedMapOf<String,Double>(); val residuals=linkedMapOf<String,Double>(); val hard=mutableListOf<String>(); val diagnostics=mutableListOf<String>()
        val policy=c.reconstruction?.policy ?: ReconstructionPolicy()
        val weights=linkedMapOf<String,Double>()
        fun add(name:String,value:Double,cls:EvidenceClass) { scores[name]=value.coerceIn(0.0,1.0); weights[name]=policy.weights.getValue(cls) }
        val masses=c.reconstruction?.masses.orEmpty()
        hard+=MassSolver.violations(masses)
        c.floors.forEach { floor -> if(floor.footprint?.let(PlanarTopology::valid)==false) hard+="invalid floor polygon:${floor.id}" }
        c.rooms.forEach { room -> if(room.polygon?.let(PlanarTopology::valid)==false) hard+="invalid room polygon:${room.id}" }
        c.openingGroups.forEach { g ->
            val facade=c.facadeEnvelopes.firstOrNull { it.id==g.facadeId }
            if(facade==null) hard+="opening without owning wall:${g.id}" else if(PlanarTopology.difference(g.outer,FacadeReconstruction.wallPolygon(facade)).sumOf { it.area }>0.001) hard+="opening outside owning wall:${g.id}"
        }
        if(source.floors.isNotEmpty() && source.floors.size!=c.floors.size) hard+="published floor count contradicted"
        if(c.roof?.facets?.any { it.areaM2<=0 }==true) hard+="non-positive roof facet"
        val planCoverage=c.floors.mapNotNull { f ->
            val p=f.footprint ?: return@mapNotNull null
            val area=masses.filter { it.floorId==f.id && it.use==MassUse.ENCLOSED }.sumOf { PlanarTopology.intersection(p,it.outline).sumOf { part->part.area } }
            if(masses.isEmpty()) null else (1-abs(area-p.area)/p.area).coerceIn(0.0,1.0)
        }
        if(planCoverage.isNotEmpty()) add("planTopologyScore",planCoverage.average(),EvidenceClass.PLAN_SECTION)
        if(masses.isNotEmpty()) add("massingScore",planCoverage.takeIf { it.isNotEmpty() }?.average() ?: 0.0,EvidenceClass.PLAN_SECTION)
        val expectedRooms=source.floors.sumOf { it.rooms.size }
        if(expectedRooms>0) add("roomTopologyScore",c.rooms.count { it.polygon!=null }.toDouble()/expectedRooms,EvidenceClass.PLAN_SECTION)
        val assets=c.visual.assets.filter { it.structuralMask!=null }.sortedByDescending { it.widthPx*it.heightPx }
        // A page thumbnail is not an independent view. Near-identical normalized masks
        // are scored once, retaining the largest asset as the observation owner.
        val unique=mutableListOf<VisualAssetEvidence>()
        assets.forEach { a -> if(unique.none { b -> a.viewpoint==b.viewpoint && a.role==b.role && a.structuralMask!!.size==b.structuralMask!!.size && ProjectionScorer.iou(a.structuralMask.decode(),b.structuralMask.decode())>0.94 }) unique+=a }
        val fits=unique.mapNotNull { a -> ProjectionScorer.fit(c,a,budget,cameras[a.assetUrl])?.let { a to it } }
        listOf(VisualViewpoint.ORTHOGRAPHIC_ELEVATION to "elevation",VisualViewpoint.PERSPECTIVE_RENDER to "render").forEach { (view,prefix) ->
            val group=fits.filter { it.first.viewpoint==view }
            if(group.isEmpty()) { diagnostics+="$prefix structural projection score unavailable"; return@forEach }
            val cls=if(view==VisualViewpoint.ORTHOGRAPHIC_ELEVATION) EvidenceClass.ELEVATION else EvidenceClass.RENDER
            add("${prefix}SilhouetteScore",group.map { it.second.silhouetteIoU }.average(),cls)
            residuals["${prefix}EdgeDistanceNormalized"]=group.map { it.second.edgeResidual }.average()
            residuals["${prefix}RooflineResidualNormalized"]=group.map { it.second.rooflineResidual }.average()
            add("${prefix}EdgeScore",1-residuals.getValue("${prefix}EdgeDistanceNormalized"),cls)
            group.forEachIndexed { i,(_,fit) -> residuals["$prefix.$i.yaw"]=fit.yaw; residuals["$prefix.$i.pitch"]=fit.pitch; residuals["$prefix.$i.distanceFactor"]=fit.distanceFactor }
        }
        val openingKinds=setOf(VisualObservationKind.OPENING_RECTANGLE,VisualObservationKind.DOOR_RECTANGLE,VisualObservationKind.GARAGE_GATE_RECTANGLE)
        val openingErrors=mutableListOf<Double>(); val countDifferences=mutableListOf<Double>(); val levelErrors=mutableListOf<Double>()
        val bounds=c.floors.mapNotNull { it.footprint?.bounds }.reduceOrNull { a,b->a.union(b) }
        val terrain=c.levels.terrain.value ?: 0.0; val height=(c.levels.ridge.value ?: 0.0)-terrain
        if(bounds!=null && height>0) c.visual.facades.filter { it.isSettled }.forEach { assignment ->
            val asset=c.visual.asset(assignment.assetUrl) ?: return@forEach; val box=asset.silhouette ?: return@forEach
            val observations=asset.observations.filter { it.kind in openingKinds && it.confidence>=0.55 }.map { it.bounds.relativeTo(box) }
            val surfaces=c.resolvedGeometry?.surfaces.orEmpty().filter { it.kind==ResolvedSurfaceKind.OPENING && it.exterior &&
                c.facadeEnvelopes.any { f-> f.id==it.id.substringBefore(":opening:") && FacadeReconstruction.side(f,c)==assignment.sides.single() } }
            val centroids=surfaces.map { s ->
                val x=s.vertices.map { it.x }.average(); val z=s.vertices.map { it.z }.average(); val y=s.vertices.map { it.y }.average()
                Pt(FacadeReconstruction.fraction(Pt(x,z),assignment.sides.single(),bounds),1-(y-terrain)/height)
            }.toMutableList()
            if(observations.isNotEmpty()) {
                countDifferences+=abs(observations.size-centroids.size).toDouble()
                observations.sortedBy { it.centreX }.forEach { o ->
                    val p=Pt(o.centreX,o.centreY); val nearest=centroids.minByOrNull { it.distanceTo(p) }
                    openingErrors+=nearest?.distanceTo(p) ?: 1.0; if(nearest!=null) centroids.remove(nearest)
                }
                // Unmatched candidate openings are false positives, not free geometry.
                repeat(centroids.size) { openingErrors+=1.0 }
            }
            asset.of(VisualObservationKind.EAVE_LINE).forEach { o ->
                val observed=terrain+(1-o.bounds.relativeTo(box).centreY)*height
                val levels=listOfNotNull(c.roof?.eaveElevation?.value)+c.roof?.secondaryMasses.orEmpty().mapNotNull { it.topElevation.value }
                if(levels.isNotEmpty()) levelErrors+=levels.minOf { abs(it-observed) }
            }
        }
        if(openingErrors.isNotEmpty()) {
            residuals["openingCentroidErrorNormalized"]=openingErrors.average(); residuals["openingCountDifference"]=countDifferences.sum()
            add("elevationOpeningScore",1-openingErrors.average(),EvidenceClass.ELEVATION)
        }
        if(levelErrors.isNotEmpty()) { residuals["elevationLevelResidualMeters"]=levelErrors.average(); add("elevationLevelScore",1-levelErrors.average()/height.coerceAtLeast(1.0),EvidenceClass.ELEVATION) }
        val featureMap=mapOf(VisualObservationKind.RAILING_STRIP to FacadeFeatureKind.RAILING,VisualObservationKind.FRAME_OR_PORTAL to FacadeFeatureKind.FRAME,
            VisualObservationKind.HORIZONTAL_BAND to FacadeFeatureKind.BAND,VisualObservationKind.ROOF_STACK to FacadeFeatureKind.STACK,VisualObservationKind.ROOFLIGHT_PATCH to FacadeFeatureKind.ROOFLIGHT)
        val renderFeatures=unique.filter { it.viewpoint==VisualViewpoint.PERSPECTIVE_RENDER }.flatMap { a -> a.observations.filter { it.kind in featureMap && it.confidence>=0.55 } }
        fun roofKind(kind:VisualObservationKind)=when(kind) { VisualObservationKind.ROOF_STACK->RoofElementKind.STACK; VisualObservationKind.ROOFLIGHT_PATCH->RoofElementKind.ROOFLIGHT; else->null }
        if(renderFeatures.isNotEmpty()) add("renderFeatureScore",renderFeatures.map { o -> if(c.facadeFeatures.any { it.kind==featureMap[o.kind] } || c.roofElements.any { it.kind==roofKind(o.kind) }) 1.0 else 0.0 }.average(),EvidenceClass.RENDER)
        val elevationFeatures=unique.filter { it.viewpoint==VisualViewpoint.ORTHOGRAPHIC_ELEVATION }.flatMap { a -> a.observations.mapIndexedNotNull { i,o ->
            if(o.kind in featureMap && o.confidence>=0.55) "visual:${a.assetUrl}:$i" to o else null } }
        if(elevationFeatures.isNotEmpty()) add("elevationFeatureScore",elevationFeatures.map { (id,o) ->
            if(c.facadeFeatures.any { it.kind==featureMap[o.kind] && id in it.evidenceIds } || c.roofElements.any { it.kind==roofKind(o.kind) && id in it.evidenceIds }) 1.0 else 0.0
        }.average(),EvidenceClass.ELEVATION)
        if(fits.isNotEmpty()) add("roofScore",1-fits.map { it.second.rooflineResidual }.average(),EvidenceClass.ELEVATION)
        diagnostics+="Section geometry score unavailable: numeric level extraction is incomplete"
        if(c.stairs.any { it.flights.isEmpty() }) diagnostics+="Stair flights remain unresolved"
        val overall=if(scores.isEmpty() || hard.isNotEmpty()) 0.0 else scores.entries.sumOf { it.value*weights.getValue(it.key) }/weights.values.sum()
        return SourceScore(scores,residuals,hard,overall,diagnostics,fits.associate { it.first.assetUrl to it.second })
    }
}
