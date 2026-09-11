package com.buildplan.app.analyzer.service

import com.buildplan.app.analyzer.candidate.*
import com.buildplan.app.analyzer.reconstruction.AutomaticReconstruction
import com.buildplan.app.analyzer.roof.RoofHeightField

/** Read-only spatial queries over a report. No new analysis, mutation, or promotion. */
object CandidateGeometryQueries {
    fun openingSegment(candidate: ProjectAnalysisCandidate, opening: OpeningCandidate): Segment? =
        AutomaticReconstruction.openingSegment(candidate, opening)

    fun roofHeights(roof: RoofCandidate): (Pt) -> Double? {
        val field = RoofHeightField(roof)
        return field::heightAt
    }
}
