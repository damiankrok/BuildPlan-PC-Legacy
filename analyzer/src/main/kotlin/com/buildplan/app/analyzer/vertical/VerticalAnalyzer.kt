package com.buildplan.app.analyzer.vertical

import com.buildplan.app.analyzer.candidate.LevelsCandidate
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import kotlin.math.tan

/**
 * The vertical skeleton of the building from what the page states and what
 * the roof solver derived — never from the section image, whose levels are
 * printed text this stage cannot read.
 *
 * Chain, for a house with an attic storey:
 *
 * ```
 * ridge      = building height − terrain offset          (height: SOURCE_EXACT; offset: assumption)
 * eave       = ridge − skeleton rise                      (rise from the roof solver)
 * attic floor= eave − knee wall − wall thickness·tan(pitch)
 * ground clear height = attic floor − slab thickness      (slab: assumption)
 * ```
 *
 * Every assumed number is a [FactFidelity.DISPLAY_ASSUMPTION] with its
 * reason, and each is also a clarification question downstream. When the
 * chain cannot close (no height, no pitch) the levels stay MISSING rather
 * than becoming typical values.
 */
object VerticalAnalyzer {

    data class Assumptions(
        /** How far the terrain at the entrance lies below the ground-floor level. */
        val terrainBelowGroundFloorM: Double = 0.30,
        /** Thickness of the floor structure between the ground storey ceiling and the attic floor. */
        val upperSlabThicknessM: Double = 0.30,
        /** The flat ceiling of an attic room above its floor, where the roof allows one. */
        val atticFlatCeilingM: Double = 2.60,
        /** Clear height used for a storey the chain cannot reach. */
        val fallbackClearHeightM: Double = 2.70,
        /** A chain implying a ground storey below this is rejected as implausible (no habitable room is lower). */
        val minPlausibleClearHeightM: Double = 2.30,
        /** ... and above this as well (a single storey is never this tall in a catalogue house). */
        val maxPlausibleClearHeightM: Double = 4.20,
    )

    data class Input(
        val buildingHeight: Measured?,
        val kneeWall: Measured?,
        val pitchDegrees: Measured?,
        /** Horizontal sweep of the roof skeleton at the ridge, from the roof solver; null when no roof. */
        val roofRiseM: Double?,
        /** How far the roof outline stands beyond the top storey's walls, traced from the plans; null when unknown. */
        val roofOverhang: Measured?,
        val exteriorWallThickness: Measured?,
        val storeyCount: Int,
        val assumptions: Assumptions = Assumptions(),
    )

    fun analyse(input: Input): LevelsCandidate {
        val a = input.assumptions
        val notes = mutableListOf<String>()
        val terrain = Measured.assumed(-a.terrainBelowGroundFloorM, MeasureUnit.METER, "terrain at the entrance assumed ${a.terrainBelowGroundFloorM} m below the ground floor; the section prints the level but this stage reads no text")
        val ground = Measured(0.0, MeasureUnit.METER, FactFidelity.SOURCE_DERIVED, com.buildplan.app.analyzer.fidelity.Provenance.derived("local datum: finished ground floor = 0.00"))
        val height = input.buildingHeight
        val pitch = input.pitchDegrees
        val knee = input.kneeWall
        val rise = input.roofRiseM

        val ridge: Measured = if (height?.value != null) {
            Measured.derived(height.value + terrain.requireValue(), MeasureUnit.METER, "building height above terrain + terrain level", listOf(height, terrain))
        } else Measured.missing(MeasureUnit.METER, "no published building height")

        val eave: Measured = if (ridge.value != null && rise != null && pitch?.value != null) {
            Measured.derived(ridge.value - rise * tan(Math.toRadians(pitch.value)), MeasureUnit.METER, "ridge − skeleton rise × tan(pitch)", listOf(ridge, pitch))
        } else Measured.missing(MeasureUnit.METER, "eave needs a ridge, a pitch and a roof outline")

        val tanPitch = pitch?.value?.let { tan(Math.toRadians(it)) } ?: 0.0
        val wallDrop = (input.exteriorWallThickness?.value ?: 0.0) * tanPitch
        // The eave sits on the roof outline. Where the outline stands beyond the wall (an eaves
        // overhang), the roof plane at the wall's outer face is higher by overhang × tan(pitch).
        val overhangRise = (input.roofOverhang?.value ?: 0.0) * tanPitch
        val slab = Measured.assumed(a.upperSlabThicknessM, MeasureUnit.METER, "floor structure between storeys assumed ${a.upperSlabThicknessM} m; the section dimensions it but this stage reads no text")
        val chainedUpperFloor: Measured = when {
            input.storeyCount < 2 -> Measured.missing(MeasureUnit.METER, "single-storey house: no upper floor")
            eave.value != null && knee?.value != null -> Measured.derived(
                eave.value + overhangRise - knee.value - wallDrop, MeasureUnit.METER,
                "eave + overhang × tan(pitch) − knee wall − wall thickness × tan(pitch)", listOfNotNull(eave, input.roofOverhang, knee, input.exteriorWallThickness),
                note = "knee wall taken at the inner wall face; the roof plane rises ${"%.2f".format(java.util.Locale.ROOT, overhangRise)} m over the overhang and drops ${"%.2f".format(java.util.Locale.ROOT, wallDrop)} m across the wall",
            )
            else -> Measured.missing(MeasureUnit.METER, "upper floor level needs an eave level and a knee wall height")
        }
        // Plausibility gate: a chain that leaves the ground storey lower than any habitable room
        // (or absurdly tall) means the skeleton rise does not describe this roof (a wing junction
        // peaking above the main ridge, a dormer, a mansard). Then the level is an assumption with
        // the conflict on record, not a derived number that looks measured.
        val impliedClear = chainedUpperFloor.value?.let { it - slab.requireValue() }
        val chainImplausible = impliedClear != null && (impliedClear < a.minPlausibleClearHeightM || impliedClear > a.maxPlausibleClearHeightM)
        val upperFloor: Measured = if (chainImplausible) {
            notes += "Vertical chain rejected: it implied a ground storey clear height of ${"%.2f".format(java.util.Locale.ROOT, impliedClear)} m (plausible ${a.minPlausibleClearHeightM}–${a.maxPlausibleClearHeightM} m); the skeleton rise ${"%.2f".format(java.util.Locale.ROOT, rise ?: 0.0)} m probably overstates the main ridge."
            Measured.assumed(
                a.fallbackClearHeightM + slab.requireValue(), MeasureUnit.METER,
                "upper floor assumed at fallback storey height + slab because the published-height chain implied an implausible ${"%.2f".format(java.util.Locale.ROOT, impliedClear)} m ground storey; the section prints the level but this stage reads no text",
            )
        } else chainedUpperFloor
        val groundClear: Measured = when {
            chainImplausible -> Measured.assumed(a.fallbackClearHeightM, MeasureUnit.METER, "storey height assumed because the vertical chain from published height, pitch and roof outline gave an implausible ${"%.2f".format(java.util.Locale.ROOT, impliedClear)} m")
            upperFloor.value != null -> Measured.derived(upperFloor.value - slab.requireValue(), MeasureUnit.METER, "upper floor − slab thickness", listOf(upperFloor, slab))
            eave.value != null && input.storeyCount < 2 -> Measured.derived(eave.value - wallDrop, MeasureUnit.METER, "eave − wall drop for a single storey", listOf(eave))
            else -> Measured.assumed(a.fallbackClearHeightM, MeasureUnit.METER, "storey height assumed because the vertical chain could not be closed from published facts")
        }
        val upperClear: Measured = when {
            input.storeyCount < 2 -> Measured.missing(MeasureUnit.METER, "no upper storey")
            ridge.value != null && upperFloor.value != null -> Measured.derived(ridge.value - upperFloor.value, MeasureUnit.METER, "ridge − upper floor (to the roof, not to a ceiling)", listOf(ridge, upperFloor))
            else -> Measured.missing(MeasureUnit.METER, "upper storey height needs ridge and upper floor")
        }
        val flatCeiling: Measured = if (input.storeyCount >= 2) {
            Measured.assumed(a.atticFlatCeilingM, MeasureUnit.METER, "flat attic ceiling assumed ${a.atticFlatCeilingM} m above the attic floor where the roof is higher; the section dimensions it but this stage reads no text")
        } else Measured.missing(MeasureUnit.METER, "no attic")

        if (upperFloor.value != null) notes += "Upper floor level derived at ${"%.2f".format(java.util.Locale.ROOT, upperFloor.value)} m from published height, knee wall and pitch with an assumed terrain offset; a section reading would replace it."
        if (rise == null) notes += "No roof outline: eave and ridge could not be placed."

        return LevelsCandidate(
            terrain = terrain,
            groundFloor = ground,
            upperFloor = upperFloor,
            groundClearHeight = groundClear,
            upperClearHeight = upperClear,
            upperSlabThickness = slab,
            kneeWall = knee ?: Measured.missing(MeasureUnit.METER, "no published knee wall"),
            eave = eave,
            ridge = ridge,
            buildingHeight = height ?: Measured.missing(MeasureUnit.METER, "no published building height"),
            atticFlatCeilingHeight = flatCeiling,
            notes = notes,
        )
    }
}
