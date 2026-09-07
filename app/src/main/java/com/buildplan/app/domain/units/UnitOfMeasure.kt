package com.buildplan.app.domain.units

/**
 * Units a construction quantity can be expressed in.
 *
 * Units are never stored as free-form strings: "m2", "m^2" and "sqm" would all
 * mean the same thing to a person and nothing to the model.
 *
 * Conversion between units is intentionally not implemented here. The goal of
 * this stage is stable semantics, not a units library.
 */
enum class UnitOfMeasure(val dimension: MeasurementDimension) {
    MILLIMETER(MeasurementDimension.LENGTH),
    CENTIMETER(MeasurementDimension.LENGTH),
    METER(MeasurementDimension.LENGTH),
    SQUARE_METER(MeasurementDimension.AREA),
    CUBIC_METER(MeasurementDimension.VOLUME),
    LITER(MeasurementDimension.VOLUME),
    PIECE(MeasurementDimension.COUNT),
    KILOGRAM(MeasurementDimension.MASS),
    TONNE(MeasurementDimension.MASS),
}
