package com.buildplan.app.render.filament

import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.reference.visual.VisualSurfaceRole

/**
 * The renderer's one exception to a single opaque surface colour, stated as
 * plain numbers so that a JVM test can hold them to what they promise.
 *
 * Nothing here touches Filament. The material that uses these values is
 * compiled on the device by [TechnicalMaterial.buildGlass]; this object is what
 * the material is *for*, which is the part a test can read.
 */
internal object GlassPresentation {

    /**
     * Opacity of a pane. Inside the range a technical drawing can use: high
     * enough that the sheet is seen at all against a dark background, low
     * enough that the balcony floor behind a balustrade and the room behind a
     * glazing stay legible through it. Nearly opaque glass would be the
     * parapet the owner already rejected.
     */
    const val ALPHA: Float = 0.38f

    /**
     * Linear, unpremultiplied RGB of a pane: a light neutral with a slight
     * coolness so it separates from the warmer grey of the fabric. Monochrome
     * to the eye — there is no hue here anyone would call a colour.
     */
    val COLOR: FloatArray = floatArrayOf(0.62f, 0.70f, 0.80f)

    /** The opacity a glass pane keeps while it is selected. Selection tints; it does not solidify. */
    const val SELECTED_ALPHA: Float = 0.55f
}

/**
 * Which presentation a baked mesh gets: the one the geometry implies, unless
 * the reference presentation names its element as glass.
 *
 * Two inputs, one answer, no Filament. A pane is glass because it is the fill
 * of an opening — that is the primitive type, and the renderer is allowed to
 * know it. A balustrade is glass because the reference model's presentation
 * says so by id. Neither answer comes from
 * [com.buildplan.app.domain.model.BuildingElementKind], and nothing here
 * reads it.
 */
internal fun surfaceRoleOf(
    mesh: BuildingRenderMesh,
    roles: Map<BuildingElementId, VisualSurfaceRole>,
): VisualSurfaceRole = when {
    mesh.style == MeshStyle.GLAZING -> VisualSurfaceRole.GLASS_STUDY
    else -> roles[mesh.elementId] ?: VisualSurfaceRole.OPAQUE_STUDY
}
