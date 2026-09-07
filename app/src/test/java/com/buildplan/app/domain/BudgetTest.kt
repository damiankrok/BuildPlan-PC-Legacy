package com.buildplan.app.domain

import com.buildplan.app.domain.model.Budget
import com.buildplan.app.domain.model.BudgetId
import com.buildplan.app.domain.model.ProjectId
import com.buildplan.app.domain.money.CurrencyCode
import com.buildplan.app.domain.money.Money
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DOM002-15 - the budget contract. */
class BudgetTest {

    @Test
    fun `DOM002-15 budget uses Money and stores no derived spend`() {
        val budget = Budget(
            id = BudgetId("bg-1"),
            projectId = ProjectId("p-1"),
            amount = Money.ofMajorUnits(450_000, CurrencyCode.PLN),
        )

        assertEquals(Money::class.java, Budget::class.java.getDeclaredField("amount").type)
        assertEquals(45_000_000L, budget.amount.minorUnits)
        assertEquals(ProjectId("p-1"), budget.projectId)

        // A stored spend or remaining figure would be a second answer to a
        // question the recorded costs already answer, and the two would drift
        // apart the moment a cost is edited. Those values are derived later.
        val derived = listOf("spent", "remaining", "used", "percent", "overrun", "balance", "left")
        // Only real instance state counts. The Compose compiler plugin runs over
        // the whole module and adds a static synthetic field to every class.
        val fields = Budget::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name.lowercase() }

        derived.forEach { forbidden ->
            assertTrue(
                "Budget must not store a derived $forbidden value, found: $fields",
                fields.none { it.contains(forbidden) },
            )
        }

        // The budget carries only its identity, its project and the planned amount.
        assertEquals(setOf("id", "projectid", "amount"), fields.toSet())
    }
}
