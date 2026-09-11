package com.buildplan.app.analyzer.service

import com.buildplan.app.analyzer.candidate.*
import com.buildplan.app.analyzer.reconstruction.AutomaticReconstruction
import com.buildplan.app.analyzer.roof.RoofHeightField

/** Read-only spatial queries over a report. No new analysis, mutation, or promotion. */
object CandidateGeometryQueries {
    fun resolvedGeometry(candidate:ProjectAnalysisCandidate):ResolvedBuildingGeometry? = candidate.resolvedGeometry?.let {
        if(!it.lineage.startsWith("final-resolution:") || it.lineage==com.buildplan.app.analyzer.reconstruction.GeometryResolver.lineage(candidate)) it
        else com.buildplan.app.analyzer.reconstruction.GeometryResolver.resolve(candidate)
    }
    fun openingSegment(candidate: ProjectAnalysisCandidate, opening: OpeningCandidate): Segment? =
        AutomaticReconstruction.openingSegment(candidate, opening)

    fun roofHeights(roof: RoofCandidate): (Pt) -> Double? {
        val field = RoofHeightField(roof)
        return field::heightAt
    }
}
