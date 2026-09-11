package com.buildplan.app.analyzer.lab

import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.preview.CandidateGeometry
import com.buildplan.app.presentation.DecompositionProfile
import com.buildplan.app.presentation.OpeningFrameProfile
import com.buildplan.app.presentation.RoofCoverProfile
import com.buildplan.app.reference.visual.VisualSurfaceRole
import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.render.filament.PresetFocus
import com.buildplan.app.render.filament.SceneModel

/** Filament adapter over the same candidate geometry used by the release preview. */
internal class CandidatePreview private constructor(private val candidate: CandidateGeometry) : SceneModel {
    override val building get() = candidate.building
    override val geometry get() = candidate.geometry
    override val atticId get() = candidate.atticId
    val roofId get() = candidate.roofId
    override val surfaceRoles: Map<BuildingElementId, VisualSurfaceRole> get() = emptyMap()
    override val decomposition get() = DecompositionProfile.NONE
    override val roofCover get() = RoofCoverProfile.NONE
    override val openingFrames get() = OpeningFrameProfile.NONE
    override fun focusElementId(focus: PresetFocus) = if (focus == PresetFocus.ROOF) roofId else null
    companion object {
        fun of(candidate: ProjectAnalysisCandidate): CandidatePreview? = CandidateGeometry.of(candidate)?.let(::CandidatePreview)
    }
}
