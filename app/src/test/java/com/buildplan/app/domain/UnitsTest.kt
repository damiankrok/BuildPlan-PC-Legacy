package com.buildplan.app.domain

import com.buildplan.app.domain.model.Floor
import com.buildplan.app.domain.model.FloorId
import com.buildplan.app.domain.model.Room
import com.buildplan.app.domain.model.RoomId
import com.buildplan.app.domain.units.MeasurementDimension
import com.buildplan.app.domain.units.Quantity
import com.buildplan.app.domain.units.UnitOfMeasure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** DOM002-07 and DOM002-11 - the unit contract. */
class UnitsTest {

    @Test
    fun `DOM002-07 physical values reject NaN and infinities`() {
        val broken = listOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
        )

        broken.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                Quantity(value, UnitOfMeasure.SQUARE_METER)
            }
        }

        // Finite values, including negative ones, are accepted at this level.
        assertEquals(18.5, Quantity(18.5, UnitOfMeasure.SQUARE_METER).amount, 0.0)
        assertEquals(-2.8, Quantity(-2.8, UnitOfMeasure.METER).amount, 0.0)

        // Fields that carry meaning constrain further: an area must be an area,
        // and it must be positive.
        assertThrows(IllegalArgumentException::class.java) {
            Room(RoomId("r-1"), "Salon", area = Quantity(20.0, UnitOfMeasure.KILOGRAM))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Room(RoomId("r-1"), "Salon", area = Quantity(0.0, UnitOfMeasure.SQUARE_METER))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Floor(FloorId("f-1"), "Parter", order = 0, height = Quantity(-2.5, UnitOfMeasure.METER))
        }

        // A basement sits below ground, so elevation may legitimately be negative.
        val basement = Floor(
            id = FloorId("f-0"),
            name = "Piwnica",
            order = 0,
            elevation = Quantity(-2.8, UnitOfMeasure.METER),
            height = Quantity(2.4, UnitOfMeasure.METER),
        )
        assertEquals(-2.8, basement.elevation?.amount)
    }

    @Test
    fun `DOM002-11 unit of measure covers required construction units`() {
        val required = setOf(
            UnitOfMeasure.MILLIMETER,
            UnitOfMeasure.CENTIMETER,
            UnitOfMeasure.METER,
            UnitOfMeasure.SQUARE_METER,
            UnitOfMeasure.CUBIC_METER,
            UnitOfMeasure.PIECE,
            UnitOfMeasure.KILOGRAM,
            UnitOfMeasure.TONNE,
            UnitOfMeasure.LITER,
        )

        assertTrue(UnitOfMeasure.entries.containsAll(required))

        // Units are enumerated, not free text, and each says what it measures.
        assertEquals(MeasurementDimension.AREA, UnitOfMeasure.SQUARE_METER.dimension)
        assertEquals(MeasurementDimension.LENGTH, UnitOfMeasure.METER.dimension)
        assertEquals(MeasurementDimension.VOLUME, UnitOfMeasure.CUBIC_METER.dimension)
        assertEquals(MeasurementDimension.MASS, UnitOfMeasure.TONNE.dimension)
        assertEquals(MeasurementDimension.COUNT, UnitOfMeasure.PIECE.dimension)
    }
}
