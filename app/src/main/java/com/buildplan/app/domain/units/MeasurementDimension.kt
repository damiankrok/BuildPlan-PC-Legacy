package com.buildplan.app.domain.units

/**
 * What a [UnitOfMeasure] measures.
 *
 * The dimension exists so the model can refuse nonsense such as a room area
 * expressed in kilograms. It is deliberately not a physical unit system: no
 * conversion between units is implemented at this stage.
 */
enum class MeasurementDimension {
    LENGTH,
    AREA,
    VOLUME,
    MASS,
    COUNT,
}
