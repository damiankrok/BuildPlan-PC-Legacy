package com.buildplan.app.domain

import com.buildplan.app.domain.model.Cost
import com.buildplan.app.domain.model.CostAllocation
import com.buildplan.app.domain.model.CostAllocationKind
import com.buildplan.app.domain.model.CostCategory
import com.buildplan.app.domain.model.CostId
import com.buildplan.app.domain.model.CostTarget
import com.buildplan.app.domain.model.CostType
import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.FloorId
import com.buildplan.app.domain.model.ProjectId
import com.buildplan.app.domain.model.RoomId
import com.buildplan.app.domain.model.StageId
import com.buildplan.app.domain.money.CurrencyCode
import com.buildplan.app.domain.money.Money
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** DOM002-10 and DOM002-12..14 - the cost contract. */
class CostModelTest {

    private val projectId = ProjectId("p-1")

    private fun cost(
        allocations: List<CostAllocation> = emptyList(),
        stageId: StageId? = null,
    ) = Cost(
        id = CostId("c-1"),
        projectId = projectId,
        name = "Okna PCV",
        amount = Money.ofMajorUnits(12_000, CurrencyCode.PLN),
        date = LocalDate.of(2026, 4, 18),
        type = CostType.MATERIAL,
        category = CostCategory("Stolarka"),
        stageId = stageId,
        allocations = allocations,
    )

    @Test
    fun `DOM002-10 cost type covers required MVP types`() {
        val required = setOf(
            CostType.MATERIAL,
            CostType.LABOR,
            CostType.SERVICE,
            CostType.TRANSPORT,
            CostType.RENTAL,
            CostType.FORMALITIES,
            CostType.OTHER,
        )

        assertTrue(CostType.entries.containsAll(required))
        assertEquals(required.size, CostType.entries.size)
    }

    @Test
    fun `DOM002-12 cost target is typed across every level`() {
        val targets: List<CostTarget> = listOf(
            CostTarget.ProjectTarget(projectId),
            CostTarget.StageTarget(StageId("s-1")),
            CostTarget.FloorTarget(FloorId("f-1")),
            CostTarget.RoomTarget(RoomId("r-1")),
            CostTarget.BuildingElementTarget(BuildingElementId("e-1")),
        )

        // Every level of the product hierarchy can be addressed, and each keeps
        // its own type instead of collapsing into one opaque string.
        assertEquals(5, targets.size)
        assertEquals(5, targets.map { it::class }.toSet().size)

        // A floor and a room that happen to share a raw id are still different
        // targets, which a loose targetId String could never express.
        val floorTarget: Any = CostTarget.FloorTarget(FloorId("x-1"))
        val roomTarget: Any = CostTarget.RoomTarget(RoomId("x-1"))
        assertNotEquals(floorTarget, roomTarget)
    }

    @Test
    fun `DOM002-13 a cost can carry more than one allocation`() {
        val spreadOverTwoRooms = cost(
            allocations = listOf(
                CostAllocation.distributed(CostTarget.RoomTarget(RoomId("r-1")), 6_000),
                CostAllocation.distributed(CostTarget.RoomTarget(RoomId("r-2")), 4_000),
            ),
        )

        assertEquals(2, spreadOverTwoRooms.allocations.size)

        // Levels can be mixed on one cost.
        val mixedLevels = cost(
            allocations = listOf(
                CostAllocation.direct(CostTarget.BuildingElementTarget(BuildingElementId("e-1"))),
                CostAllocation.estimated(CostTarget.FloorTarget(FloorId("f-1"))),
                CostAllocation.distributed(CostTarget.StageTarget(StageId("s-1")), 2_500),
            ),
        )
        assertEquals(3, mixedLevels.allocations.size)

        // The same target twice would make the charge ambiguous.
        assertThrows(IllegalArgumentException::class.java) {
            cost(
                allocations = listOf(
                    CostAllocation.direct(CostTarget.RoomTarget(RoomId("r-1"))),
                    CostAllocation.estimated(CostTarget.RoomTarget(RoomId("r-1"))),
                ),
            )
        }

        // GUARANTEED NOW: shares cannot exceed 100% in total.
        assertThrows(IllegalArgumentException::class.java) {
            cost(
                allocations = listOf(
                    CostAllocation.distributed(CostTarget.RoomTarget(RoomId("r-1")), 7_000),
                    CostAllocation.distributed(CostTarget.RoomTarget(RoomId("r-2")), 4_000),
                ),
            )
        }

        // NOT GUARANTEED YET, DEFERRED TO STAGE-015: a partial split is accepted.
        // Requiring shares to sum to exactly 100%, and deciding how any
        // remainder is spread, belongs to the allocation feature stage.
        val partiallyAllocated = cost(
            allocations = listOf(
                CostAllocation.distributed(CostTarget.RoomTarget(RoomId("r-1")), 3_000),
            ),
        )
        assertEquals(1, partiallyAllocated.allocations.size)

        // An unallocated cost is a real state: money spent before anyone decided
        // what it belongs to.
        assertTrue(cost().allocations.isEmpty())
    }

    @Test
    fun `DOM002-14 direct distributed and estimated stay distinct`() {
        assertEquals(3, CostAllocationKind.entries.size)

        val target = CostTarget.RoomTarget(RoomId("r-1"))
        val direct = CostAllocation.direct(target)
        val estimated = CostAllocation.estimated(target)
        val distributed = CostAllocation.distributed(target, 5_000)

        assertEquals(CostAllocationKind.DIRECT, direct.kind)
        assertEquals(CostAllocationKind.ESTIMATED, estimated.kind)
        assertEquals(CostAllocationKind.DISTRIBUTED, distributed.kind)

        // Same target, same amount, different confidence - never equal.
        assertNotEquals(direct, estimated)
        assertNotEquals(direct, distributed)

        // A share only means something for a distributed allocation.
        assertEquals(null, direct.shareBasisPoints)
        assertEquals(5_000, distributed.shareBasisPoints)
        assertThrows(IllegalArgumentException::class.java) {
            CostAllocation(target, CostAllocationKind.DIRECT, shareBasisPoints = 5_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CostAllocation(target, CostAllocationKind.DISTRIBUTED, shareBasisPoints = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CostAllocation(target, CostAllocationKind.DISTRIBUTED, shareBasisPoints = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CostAllocation(target, CostAllocationKind.DISTRIBUTED, shareBasisPoints = 10_001)
        }
    }

    @Test
    fun `a cost uses Money and a calendar date`() {
        val recorded = cost(stageId = StageId("s-1"))

        assertEquals(1_200_000L, recorded.amount.minorUnits)
        assertEquals(CurrencyCode.PLN, recorded.amount.currency)
        assertEquals(LocalDate.of(2026, 4, 18), recorded.date)
        assertEquals(StageId("s-1"), recorded.stageId)

        // Optional free text is absent rather than an empty string.
        assertThrows(IllegalArgumentException::class.java) {
            recorded.copy(note = "   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CostCategory(" ")
        }
    }
}
