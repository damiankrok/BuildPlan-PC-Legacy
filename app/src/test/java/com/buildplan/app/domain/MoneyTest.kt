package com.buildplan.app.domain

import com.buildplan.app.domain.money.CurrencyCode
import com.buildplan.app.domain.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** DOM002-03..06 — the money contract. */
class MoneyTest {

    @Test
    fun `DOM002-03 money stores integer minor units`() {
        val hundredZloty = Money.ofMajorUnits(100, CurrencyCode.PLN)

        assertEquals(10_000L, hundredZloty.minorUnits)
        assertEquals(CurrencyCode.PLN, hundredZloty.currency)

        // The stored truth is a Long, never a floating point type.
        val minorUnitsType = Money::class.java.getDeclaredField("minorUnits").type
        assertEquals(java.lang.Long.TYPE, minorUnitsType)
    }

    @Test
    fun `DOM002-04 same currency addition is exact`() {
        // 0.10 + 0.20 is the classic case a Double gets wrong.
        val tenGrosze = Money.ofMinorUnits(10, CurrencyCode.PLN)
        val twentyGrosze = Money.ofMinorUnits(20, CurrencyCode.PLN)

        assertEquals(Money.ofMinorUnits(30, CurrencyCode.PLN), tenGrosze + twentyGrosze)

        // And it stays exact when repeated, where Double drift would show.
        val hundredTimes = Money.sum(List(100) { tenGrosze }, CurrencyCode.PLN)
        assertEquals(Money.ofMinorUnits(1_000, CurrencyCode.PLN), hundredTimes)

        assertEquals(
            Money.ofMinorUnits(10, CurrencyCode.PLN),
            twentyGrosze - tenGrosze,
        )
        assertTrue((tenGrosze - tenGrosze).isZero)
    }

    @Test
    fun `DOM002-05 mixed currency arithmetic refuses`() {
        val zloty = Money.ofMajorUnits(100, CurrencyCode.PLN)
        val euro = Money.ofMajorUnits(100, CurrencyCode.EUR)

        assertThrows(IllegalArgumentException::class.java) { zloty + euro }
        assertThrows(IllegalArgumentException::class.java) { zloty - euro }

        // Summing a mixed collection is refused for the same reason.
        assertThrows(IllegalArgumentException::class.java) {
            Money.sum(listOf(zloty, euro), CurrencyCode.PLN)
        }
    }

    @Test
    fun `DOM002-06 overflow fails closed`() {
        val max = Money.ofMinorUnits(Long.MAX_VALUE, CurrencyCode.PLN)
        val one = Money.ofMinorUnits(1, CurrencyCode.PLN)

        // Wrapping around would silently turn the largest amount into a negative one.
        assertThrows(ArithmeticException::class.java) { max + one }
        assertThrows(ArithmeticException::class.java) { max * 2 }
        assertThrows(ArithmeticException::class.java) {
            Money.ofMinorUnits(Long.MIN_VALUE, CurrencyCode.PLN) - one
        }
        assertThrows(ArithmeticException::class.java) {
            Money.ofMajorUnits(Long.MAX_VALUE, CurrencyCode.PLN)
        }
    }
}
