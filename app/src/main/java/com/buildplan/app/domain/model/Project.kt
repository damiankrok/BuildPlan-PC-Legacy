package com.buildplan.app.domain.model

/**
 * A construction project — the root of the building structure.
 *
 * For the MVP a project has exactly one building, so the relationship is a
 * plain non-null property rather than a list that would always hold one entry.
 *
 * Ownership, users and permissions are intentionally absent: there is no
 * authentication in the product yet, and modelling an owner now would be
 * guessing at a design that authentication will actually decide.
 *
 * Stages, costs and budgets are not held here. They are separate dimensions of
 * organisation that reference a project by [ProjectId], which keeps the project
 * aggregate small and avoids loading every cost to open a building.
 */
data class Project(
    val id: ProjectId,
    val name: String,
    val building: Building,
) {
    init {
        requireDomainName(name, "Project name")
    }
}
