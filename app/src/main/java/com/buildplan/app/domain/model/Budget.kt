package com.buildplan.app.domain.model

import com.buildplan.app.domain.money.Money

/**
 * The amount planned for a project.
 *
 * Holds the planned figure and nothing else. There is deliberately no `spent`,
 * `remaining` or `usedPercent` stored here: those follow from the recorded
 * costs, and a stored copy would drift out of step with them the moment a cost
 * is edited, leaving two answers to the same question. They will be derived
 * from [Cost] when the reporting stage needs them.
 *
 * A budget covers the whole project. Per-stage and per-category budgets are not
 * modelled yet — the product has no feature that sets one, and adding a scope
 * hierarchy with no caller would be abstraction ahead of need.
 */
data class Budget(
    val id: BudgetId,
    val projectId: ProjectId,
    val amount: Money,
)
