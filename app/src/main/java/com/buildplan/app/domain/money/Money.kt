package com.buildplan.app.domain.money

/**
 * An exact monetary amount.
 *
 * The truth of an amount is [minorUnits] — an integer count of the currency's
 * smallest unit (100 zl is stored as 10_000 grosze). Floating point is never
 * used for money anywhere in the domain: it cannot represent 0.10 exactly and
 * the errors accumulate across a construction budget.
 *
 * Two rules are enforced rather than documented:
 * - arithmetic across different currencies is refused, never silently coerced;
 * - overflow throws instead of wrapping around into a wrong amount.
 *
 * Money carries no formatting. Turning an amount into text is a UI concern and
 * lives outside the domain.
 */
data class Money(
    val minorUnits: Long,
    val currency: CurrencyCode,
) {

    val isZero: Boolean get() = minorUnits == 0L
    val isNegative: Boolean get() = minorUnits < 0L
    val isPositive: Boolean get() = minorUnits > 0L

    /** Adds two amounts of the same currency. Fails closed on mismatch or overflow. */
    operator fun plus(other: Money): Money {
        requireSameCurrency(other, "add")
        return Money(Math.addExact(minorUnits, other.minorUnits), currency)
    }

    /** Subtracts two amounts of the same currency. Fails closed on mismatch or overflow. */
    operator fun minus(other: Money): Money {
        requireSameCurrency(other, "subtract")
        return Money(Math.subtractExact(minorUnits, other.minorUnits), currency)
    }

    /** Scales an amount by a whole factor. Fails closed on overflow. */
    operator fun times(factor: Long): Money =
        Money(Math.multiplyExact(minorUnits, factor), currency)

    operator fun unaryMinus(): Money = Money(Math.negateExact(minorUnits), currency)

    private fun requireSameCurrency(other: Money, operation: String) {
        require(currency == other.currency) {
            "Cannot $operation amounts in different currencies: $currency and ${other.currency}"
        }
    }

    companion object {

        fun zero(currency: CurrencyCode): Money = Money(0L, currency)

        /** Builds an amount from the currency's smallest unit, e.g. grosze. */
        fun ofMinorUnits(minorUnits: Long, currency: CurrencyCode): Money =
            Money(minorUnits, currency)

        /**
         * Builds an amount from whole currency units, e.g. 100 zl.
         * Fails closed if the conversion would overflow.
         */
        fun ofMajorUnits(majorUnits: Long, currency: CurrencyCode): Money {
            var factor = 1L
            repeat(currency.minorUnitDigits) { factor = Math.multiplyExact(factor, 10L) }
            return Money(Math.multiplyExact(majorUnits, factor), currency)
        }

        /**
         * Sums amounts of a single [currency]. The currency is passed explicitly so
         * that summing an empty collection still yields a well-defined zero.
         */
        fun sum(amounts: Iterable<Money>, currency: CurrencyCode): Money =
            amounts.fold(zero(currency)) { acc, amount -> acc + amount }
    }
}
