package com.buildplan.app.domain.model

import com.buildplan.app.domain.money.Money
import java.time.LocalDate

/**
 * The nature of an expense.
 *
 * A fixed set, because these are the groupings the product reports on. Anything
 * finer is the user's own [CostCategory].
 */
enum class CostType {
    MATERIAL,
    LABOR,
    SERVICE,
    TRANSPORT,
    RENTAL,
    FORMALITIES,
    OTHER,
}

/**
 * A user-defined grouping such as "Okna" or "Fundamenty".
 *
 * Kept as a validated wrapper rather than a fixed enum: no two builds group
 * their spending the same way, and inventing a taxonomy now would be a guess.
 * The wrapper exists only to keep blank categories out of the model.
 */
@JvmInline
value class CostCategory(val value: String) {
    init {
        require(value.isNotBlank()) { "CostCategory must not be blank" }
    }
}

/**
 * A single recorded expense.
 *
 * [date] is a [LocalDate]: an expense happens on a calendar day, with no time
 * of day and no timezone to get wrong. The Android date types are avoided so
 * the domain stays testable on a plain JVM.
 *
 * @property stageId the stage the cost was incurred in, when known. This is the
 *   cost's own classification and is separate from any
 *   [CostTarget.StageTarget] in [allocations], which says where the money is
 *   charged. Reconciling the two is deferred — see the note on [allocations].
 * @property allocations where this cost is charged. May be empty: a cost can be
 *   recorded before anyone decides what it belongs to. Targets must be distinct,
 *   and distributed shares may not exceed 100% in total. Requiring them to sum
 *   to exactly 100%, and deciding how a remainder is spread, is STAGE-015.
 */
data class Cost(
    val id: CostId,
    val projectId: ProjectId,
    val name: String,
    val amount: Money,
    val date: LocalDate,
    val type: CostType,
    val category: CostCategory? = null,
    val note: String? = null,
    val stageId: StageId? = null,
    val allocations: List<CostAllocation> = emptyList(),
) {
    init {
        requireDomainName(name, "Cost name")
        requireOptionalText(note, "Cost note")

        val targets = allocations.map { it.target }
        require(targets.size == targets.toSet().size) {
            "A cost cannot be allocated to the same target twice"
        }

        val distributedShare = allocations
            .filter { it.kind == CostAllocationKind.DISTRIBUTED }
            .sumOf { it.shareBasisPoints ?: 0 }
        require(distributedShare <= CostAllocation.FULL_SHARE_BASIS_POINTS) {
            "Distributed allocations total $distributedShare bp, more than the " +
                "${CostAllocation.FULL_SHARE_BASIS_POINTS} bp available"
        }
    }
}
