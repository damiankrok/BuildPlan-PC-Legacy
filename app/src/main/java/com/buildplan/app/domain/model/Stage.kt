package com.buildplan.app.domain.model

/**
 * A stage of construction, such as foundations or the roof.
 *
 * Stages are one of the two dimensions costs are organised along (the other
 * being the building structure), so a stage references its project by id rather
 * than being nested inside it.
 *
 * No default set of stages is defined here. A starter template is a product
 * decision about how Polish house construction is usually divided, and it
 * belongs to the stage that builds the stage-planning feature, not to the
 * canonical model.
 *
 * @property order explicit position in the construction sequence.
 */
data class Stage(
    val id: StageId,
    val projectId: ProjectId,
    val name: String,
    val order: Int,
) {
    init {
        requireDomainName(name, "Stage name")
        requireOrder(order, "Stage order")
    }
}
