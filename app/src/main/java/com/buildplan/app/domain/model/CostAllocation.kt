package com.buildplan.app.domain.model

/**
 * What a cost is charged to.
 *
 * A typed hierarchy rather than one loose `targetId: String`, because a bare
 * string cannot say whether "f-1" is a floor or a formality, and nothing would
 * stop a room id being stored where a stage id was meant.
 */
sealed interface CostTarget {

    /** The project as a whole — a cost that cannot be attributed more narrowly. */
    data class ProjectTarget(val projectId: ProjectId) : CostTarget

    /** A stage of construction. */
    data class StageTarget(val stageId: StageId) : CostTarget

    /** One storey. */
    data class FloorTarget(val floorId: FloorId) : CostTarget

    /** One room. */
    data class RoomTarget(val roomId: RoomId) : CostTarget

    /** One physical element, such as a specific window. */
    data class BuildingElementTarget(val elementId: BuildingElementId) : CostTarget
}

/**
 * How firmly a cost is attached to its target.
 *
 * The three kinds stay distinct because they carry different trust: a receipt
 * for one window is not the same claim as a guess spread across a floor, and
 * statistics must be able to tell them apart.
 */
enum class CostAllocationKind {

    /** Known to belong to this target, e.g. an invoice for exactly this item. */
    DIRECT,

    /** A share of the cost spread onto this target. Carries an explicit share. */
    DISTRIBUTED,

    /** Attributed by judgement, not by evidence. */
    ESTIMATED,
}

/**
 * A charge of one cost against one target.
 *
 * Provisional. How a cost is genuinely split is DEC-COST-ALLOCATION-001, open
 * until STAGE-015; the shape below is kept as first modelled and is not
 * extended here. An allocation carries a share, never a Money amount of its
 * own, so no allocation can turn one 1000 PLN cost into 2000 PLN of total.
 *
 * Shares are expressed in basis points — 10 000 bp is 100% — so the split is
 * exact integer arithmetic. A Double share would make 1/3 unrepresentable and
 * leave rounding dust that never adds back up to the invoice.
 *
 * @property shareBasisPoints required for [CostAllocationKind.DISTRIBUTED] and
 *   forbidden for the other kinds, where a share would be meaningless.
 */
data class CostAllocation(
    val target: CostTarget,
    val kind: CostAllocationKind,
    val shareBasisPoints: Int? = null,
) {
    init {
        when (kind) {
            CostAllocationKind.DISTRIBUTED -> {
                val share = requireNotNull(shareBasisPoints) {
                    "A DISTRIBUTED allocation must state its share in basis points"
                }
                require(share in 1..FULL_SHARE_BASIS_POINTS) {
                    "Allocation share must be within 1..$FULL_SHARE_BASIS_POINTS bp, was $share"
                }
            }

            CostAllocationKind.DIRECT,
            CostAllocationKind.ESTIMATED,
            -> require(shareBasisPoints == null) {
                "Only a DISTRIBUTED allocation carries a share, but $kind declared $shareBasisPoints bp"
            }
        }
    }

    companion object {
        /** 100% expressed in basis points. */
        const val FULL_SHARE_BASIS_POINTS: Int = 10_000

        fun direct(target: CostTarget): CostAllocation =
            CostAllocation(target, CostAllocationKind.DIRECT)

        fun estimated(target: CostTarget): CostAllocation =
            CostAllocation(target, CostAllocationKind.ESTIMATED)

        fun distributed(target: CostTarget, shareBasisPoints: Int): CostAllocation =
            CostAllocation(target, CostAllocationKind.DISTRIBUTED, shareBasisPoints)
    }
}
