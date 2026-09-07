package com.buildplan.app.domain.units

/**
 * A physical measurement: a finite amount together with its unit.
 *
 * Unlike money, physical measurements are approximate by nature, so a Double is
 * an honest representation here. What is not tolerated is a non-finite one:
 * NaN and the infinities are rejected at construction, because they propagate
 * silently through every later calculation and turn one bad input into a
 * corrupt model.
 *
 * Sign is not constrained here — a floor elevation below ground level is
 * legitimately negative. Fields that cannot be negative enforce that themselves.
 */
data class Quantity(
    val amount: Double,
    val unit: UnitOfMeasure,
) {
    init {
        require(amount.isFinite()) {
            "Quantity amount must be finite, was $amount ${unit.name}"
        }
    }

    val dimension: MeasurementDimension get() = unit.dimension

    /** Requires this quantity to measure [expected], e.g. an area rather than a mass. */
    fun requireDimension(expected: MeasurementDimension, field: String): Quantity {
        require(dimension == expected) {
            "$field must be expressed in a $expected unit, but ${unit.name} measures $dimension"
        }
        return this
    }

    /** Requires a strictly positive amount, for values such as an area or a height. */
    fun requirePositive(field: String): Quantity {
        require(amount > 0.0) { "$field must be greater than zero, was $amount" }
        return this
    }
}
