package com.buildplan.app.ui.components

import androidx.annotation.StringRes
import com.buildplan.app.R
import com.buildplan.app.domain.model.BuildingElementKind

/**
 * The Polish word for each kind of building element, for the inspector.
 * A presentation fact kept out of the domain: the enum names what a thing
 * is, the string says it to a person.
 */
@get:StringRes
val BuildingElementKind.labelRes: Int
    get() = when (this) {
        BuildingElementKind.WALL -> R.string.element_kind_wall
        BuildingElementKind.SLAB -> R.string.element_kind_slab
        BuildingElementKind.ROOF -> R.string.element_kind_roof
        BuildingElementKind.DOOR -> R.string.element_kind_door
        BuildingElementKind.WINDOW -> R.string.element_kind_window
        BuildingElementKind.STAIRS -> R.string.element_kind_stairs
        BuildingElementKind.FOUNDATION -> R.string.element_kind_foundation
        BuildingElementKind.FACADE -> R.string.element_kind_facade
        BuildingElementKind.OTHER -> R.string.element_kind_other
    }
