package com.buildplan.app.analyzer.reconstruction

import com.buildplan.app.analyzer.candidate.*
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.reconstruction.internal.PlanarTopology
import com.buildplan.app.analyzer.site.SourcePackage
import kotlin.math.abs
import kotlin.math.min

internal data class ReconstructionConstraints(val floors:Map<String,Polygon>,val storeys:Int,val roofFamily:RoofFamily?,val exactWidths:Map<String,Pair<Double,Double>>,val exactFields:Map<String,Measured> = emptyMap()) {
    fun violations(c:ProjectAnalysisCandidate):List<String> = buildList {
        if(c.floors.size!=storeys) add("exact storey count contradicted")
        if(roofFamily!=null && c.roof?.family!=roofFamily) add("published roof family contradicted")
        floors.forEach { (id,p) ->
            val current=c.floor(id)?.footprint
            if(current==null || !PlanarTopology.valid(current)) add("missing or invalid plan closure:$id")
            else if(PlanarTopology.difference(p,current).sumOf { it.area }+PlanarTopology.difference(current,p).sumOf { it.area }>0.001) add("fixed plan geometry contradicted:$id")
        }
        exactWidths.forEach { (id,metric) -> val current=c.openings.firstOrNull { it.id==id }?.width?.value
            if(current==null || abs(current-metric.first)>metric.second) add("exact opening dimension contradicted:$id") }
        val currentFields=fields(c)
        exactFields.forEach { (name,metric) -> val value=currentFields[name]?.value
            if(value==null || abs(value-metric.requireValue())>(metric.uncertainty ?: 0.01)) add("exact metric contradicted:$name") }
        c.openings.forEach { o -> if((o.width.value ?: 1.0)<=0 || (o.height.value ?: 1.0)<=0) add("non-positive opening dimension:${o.id}") }
        c.reconstruction?.masses.orEmpty().filter { it.use==MassUse.ENCLOSED }.forEach { mass ->
            val footprint=floors[mass.floorId]
            if(footprint!=null && PlanarTopology.difference(mass.outline,footprint).sumOf { it.area }>0.02) add("enclosed mass outside structural plan closure:${mass.id}")
        }
    }
    companion object {
        private fun fields(c:ProjectAnalysisCandidate)=c.dimensions.associate { "${it.scope}:${it.name}" to it.measured }+
            listOfNotNull("level:height" to c.levels.buildingHeight,"level:ridge" to c.levels.ridge,"level:eave" to c.levels.eave,c.roof?.pitchDegrees?.let { "roof:pitch" to it }).toMap()
        fun from(c:ProjectAnalysisCandidate,source:SourcePackage)=ReconstructionConstraints(c.floors.mapNotNull { f->f.footprint?.let { f.id to it } }.toMap(),
            source.floors.size.takeIf { it>0 } ?: c.floors.size,c.roof?.family?.takeIf { it!=RoofFamily.UNKNOWN },
            c.openings.filter { it.width.fidelity==FactFidelity.SOURCE_EXACT }.associate { it.id to (it.width.requireValue() to (it.width.uncertainty ?: 0.02)) },fields(c).filterValues { it.fidelity==FactFidelity.SOURCE_EXACT && it.value!=null })
    }
}

internal data class RepairProposal(val action:String,val candidate:ProjectAnalysisCandidate)
internal data class ScoredHypothesis(val id:String,val candidate:ProjectAnalysisCandidate,val score:SourceScore)

/** Pure deterministic bounded improvement kernel, shared by runtime and synthetic constraint tests. */
internal object BoundedRepair {
    data class Result(val selected:ScoredHypothesis,val trace:List<RepairRecord>,val evaluated:Int)
    fun solve(initial:ScoredHypothesis,policy:ReconstructionPolicy,
        propose:(ProjectAnalysisCandidate)->List<RepairProposal>,evaluate:(String,ProjectAnalysisCandidate)->ScoredHypothesis):Result {
        var best=initial; var evaluated=0
        val trace=mutableListOf<RepairRecord>(); val seen=mutableSetOf(initial.candidate.copy(selfVerification=null,resolvedGeometry=null).hashCode())
        for(cycle in 1..policy.repairCycles) {
            val proposals=propose(best.candidate).sortedBy { it.action }.take(policy.localAlternatives)
            var chosen:ScoredHypothesis?=null
            var acceptedIndex:Int?=null
            for(proposal in proposals) {
                if(evaluated>=policy.hypothesisEvaluations-policy.initialHypotheses) break
                val key=proposal.candidate.copy(selfVerification=null,resolvedGeometry=null).hashCode()
                if(!seen.add(key)) continue
                val trial=evaluate(proposal.action,proposal.candidate); evaluated++
                val complete=trial.score.scores.keys.containsAll(best.score.scores.keys)
                val preservesPlan=listOf("planTopologyScore","massingScore","roomTopologyScore").all { name -> (trial.score.scores[name] ?: 0.0)+policy.improvementEpsilon >= (best.score.scores[name] ?: 0.0) }
                val better=trial.score.hard.isEmpty() && complete && preservesPlan && trial.score.overall>best.score.overall+policy.improvementEpsilon
                val reason=when { trial.score.hard.isNotEmpty()->trial.score.hard.joinToString(); !complete->"source coverage or projection budget incomplete"; !preservesPlan->"stronger plan evidence worsened"; !better->"no source score improvement above epsilon"; else->"valid improvement; compared with sibling proposals" }
                trace+=RepairRecord(cycle,proposal.action,best.score.overall,trial.score.overall,false,reason)
                if(better && (chosen==null || trial.score.overall>chosen!!.score.overall+policy.improvementEpsilon)) { chosen=trial; acceptedIndex=trace.lastIndex }
            }
            if(chosen==null) {
                if(proposals.isEmpty()) trace+=RepairRecord(cycle,"CONVERGED",best.score.overall,best.score.overall,false,"no remaining source-backed local alternatives")
                break
            }
            val index=acceptedIndex!!; trace[index]=trace[index].copy(accepted=true,reason="hard constraints valid; source score improved; stronger plan evidence preserved")
            best=chosen!!
        }
        return Result(best,trace,evaluated)
    }
}

internal object ReconstructionSearch {
    fun solve(input:ProjectAnalysisCandidate,source:SourcePackage):ProjectAnalysisCandidate {
        val policy=input.reconstruction?.policy ?: ReconstructionPolicy()
        val constraints=ReconstructionConstraints.from(input,source)
        val mapped=FacadeReconstruction.mapElevations(input)
        val featured=FacadeReconstruction.reconstruct(mapped)
        val target=featured.copy(roofElements=RoofEvidenceSolver.solve(featured))
        val budget=ProjectionBudget(policy.cameraProjections)
        var cameras=emptyMap<String,CameraFit>()
        fun evaluate(id:String,c:ProjectAnalysisCandidate):ScoredHypothesis {
            val linked=EvidenceGraphBuilder.withResolvedFeatures(c)
            val candidate=linked.copy(resolvedGeometry=GeometryResolver.resolve(linked))
            val score=SourceScorer.score(candidate,source,budget,cameras)
            if(cameras.isEmpty()) cameras=score.cameras
            val hard=constraints.violations(candidate)+score.hard
            return ScoredHypothesis(id,candidate,score.copy(hard=hard,overall=if(hard.isEmpty()) score.overall else 0.0))
        }
        val initial=listOf(evaluate("plan-volumes",mapped.copy(openingGroups=emptyList(),facadeFeatures=emptyList())),
            evaluate("source-opening-groups",target.copy(facadeFeatures=emptyList(),roofElements=emptyList()))).take(policy.initialHypotheses)
        val ranked=initial.filter { it.score.hard.isEmpty() }.sortedWith(compareByDescending<ScoredHypothesis> { it.score.overall }.thenBy { it.id })
        val first=ranked.firstOrNull() ?: initial.first()
        val repaired=BoundedRepair.solve(first,policy,propose={ c -> buildList {
            if(c.openingGroups!=target.openingGroups) add(RepairProposal("EXPAND_SOURCE_OPENING_GROUPS",c.copy(openingGroups=target.openingGroups)))
            val families=target.facadeFeatures.groupBy { if(it.kind==FacadeFeatureKind.BALCONY || it.kind==FacadeFeatureKind.RAILING) "BALCONY_RAILING" else it.kind.name }
            families.forEach { (name,features) -> if(features.any { f->c.facadeFeatures.none { it.id==f.id } }) {
                MassSolver.featureAlternatives(c,features).forEach { (use,candidate) -> add(RepairProposal("ADD_SOURCE_$name:$use",candidate)) }
            } }
            target.roofElements.groupBy { it.kind }.forEach { (kind,elements) ->
                if(elements.any { e->c.roofElements.none { it.id==e.id } }) add(RepairProposal("ADD_SOURCE_ROOF_$kind",c.copy(roofElements=(c.roofElements+elements).distinctBy { it.id })))
            }
        } },evaluate=::evaluate)
        val best=repaired.selected
        val runnerUp=ranked.firstOrNull { it.id!=best.id }
        val margin=runnerUp?.let { (best.score.overall-it.score.overall).coerceIn(0.0,1.0) } ?: 0.0
        val summaries=input.selfVerification?.hypotheses.orEmpty().filter { it.hardViolations.isNotEmpty() }+initial.map { ReconstructionHypothesisScore(it.id,it.score.overall,it.score.hard,it.score.scores) }+
            listOf(ReconstructionHypothesisScore(best.id,best.score.overall,best.score.hard,best.score.scores))
        val diagnostics=(input.selfVerification?.unresolvedDiagnostics.orEmpty().filterNot { it.contains("Perspective") || it.contains("partial self-verification") }+best.score.diagnostics+best.candidate.resolvedGeometry!!.diagnostics).distinct()
        val qa=SelfVerificationResult(input.selfVerification?.sourceCoverage.orEmpty(),best.id,summaries,
            best.score.overall*min(1.0,best.score.scores.size/13.0),margin,diagnostics,maxOf(1,repaired.trace.maxOfOrNull { it.cycle } ?: 0),
            best.score.scores+("overallScore" to best.score.overall),best.score.residuals,best.score.hard,repaired.trace,
            mapOf("initialHypotheses" to initial.size,"hypothesisEvaluations" to (initial.size+repaired.evaluated),"cameraProjections" to budget.used,"acceptedRepairs" to repaired.trace.count { it.accepted }))
        return best.candidate.copy(selfVerification=qa)
    }
}
