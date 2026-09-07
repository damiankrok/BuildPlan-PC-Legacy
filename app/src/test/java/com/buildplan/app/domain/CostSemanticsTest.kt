package com.buildplan.app.domain

import com.buildplan.app.domain.model.Cost
import com.buildplan.app.domain.model.CostAllocation
import com.buildplan.app.domain.model.CostId
import com.buildplan.app.domain.model.CostTarget
import com.buildplan.app.domain.model.CostType
import com.buildplan.app.domain.model.ProjectId
import com.buildplan.app.domain.model.RoomId
import com.buildplan.app.domain.model.Stage
import com.buildplan.app.domain.model.StageId
import com.buildplan.app.domain.money.CurrencyCode
import com.buildplan.app.domain.money.Money
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D3D002A-13..14 - what a cost's amount means, and what a stage does not do to it.
 */
class CostSemanticsTest {

    private val roofInvoice = Cost(
        id = CostId("c-dach"),
        projectId = ProjectId("p-1"),
        name = "Pokrycie dachowe",
        amount = Money.ofMajorUnits(1_000, CurrencyCode.PLN),
        date = LocalDate.of(2026, 5, 12),
        type = CostType.MATERIAL,
        stageId = StageId("s-dach"),
    )

    @Test
    fun `D3D002A-13 the amount is the gross figure paid, with no net or VAT beside it`() {
        // The amount is what was actually paid, in one field, in one currency.
        assertEquals(100_000L, roofInvoice.amount.minorUnits)
        assertEquals(CurrencyCode.PLN, roofInvoice.amount.currency)

        // Exactly one monetary field on a cost. A net and a gross sitting next to
        // each other could disagree, and nothing in the product resolves them yet.
        val monetaryFields = Cost::class.java.declaredFields
            .filter { it.type == Money::class.java }
            .map { it.name }
        assertEquals(listOf("amount"), monetaryFields)

        val taxLike = listOf("vat", "net", "gross", "tax", "brutto", "netto")
        val leaked = Cost::class.java.declaredFields
            .map { it.name.lowercase() }
            .filter { name -> taxLike.any { name.contains(it) } }
        assertTrue("Tax fields are deliberately absent for now, found: $leaked", leaked.isEmpty())
    }

    @Test
    fun `D3D002A-14 a stage classifies a cost without duplicating its amount`() {
        // The same 1000 PLN, classified by stage and charged to two rooms.
        val classifiedAndAllocated = roofInvoice.copy(
            allocations = listOf(
                CostAllocation.distributed(CostTarget.StageTarget(StageId("s-dach")), 5_000),
                CostAllocation.distributed(CostTarget.RoomTarget(RoomId("r-salon")), 5_000),
            ),
        )

        // Labels do not create money: the contribution to the project is the
        // amount, once, whatever it is classified as.
        assertEquals(roofInvoice.amount, classifiedAndAllocated.amount)
        assertEquals(
            Money.ofMajorUnits(1_000, CurrencyCode.PLN),
            Money.sum(listOf(classifiedAndAllocated).map { it.amount }, CurrencyCode.PLN),
        )
        assertEquals(
            roofInvoice.amount,
            Money.sum(listOf(roofInvoice.copy(stageId = null)).map { it.amount }, CurrencyCode.PLN),
        )

        // Nothing that classifies a cost carries an amount of its own, so no
        // relation can be summed into a second 1000 PLN.
        assertTrue(
            Stage::class.java.declaredFields.none { it.type == Money::class.java },
        )
        assertTrue(
            CostAllocation::class.java.declaredFields.none { it.type == Money::class.java },
        )
        assertTrue(
            CostTarget.StageTarget::class.java.declaredFields.none { it.type == Money::class.java },
        )
    }
}
