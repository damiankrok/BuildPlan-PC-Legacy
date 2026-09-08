package com.buildplan.app.render.filament

import com.buildplan.app.R

/**
 * The two technical presentations the spike can draw the same geometry in.
 *
 * ## Why a style and not a second model
 *
 * A style is colours, light and which passes run. It owns no geometry, no
 * visibility and no semantics: switching it changes the material parameters on
 * entities that were uploaded once and never touches which of them are in the
 * scene. That is the whole contract — the owner compares two looks of one
 * house, never two houses.
 *
 * ## The two candidates
 *
 * [CLAY] is the architectural study model: a light neutral backdrop, off-white
 * matte surfaces, a key light with shadows, ambient occlusion in the corners and
 * dark restrained feature edges. Depth comes from light rather than from lines.
 *
 * [LINE_STUDY] is the hidden-line hybrid the spike grew out of: the same
 * neutral surfaces on a dark backdrop, no shadows, and the feature edges
 * carrying the depth on their own. Kept as the comparison and as the fallback
 * on a device whose driver makes shadows or occlusion unreliable.
 *
 * Every value is linear RGB, as Filament reads it, and monochrome by intent:
 * there is no facade colour, no roof colour and no material library here.
 */
internal enum class RenderStyle(
    val labelRes: Int,
    /** Skybox colour behind the model. */
    val background: FloatArray,
    /** Base colour of every opaque surface. */
    val surface: FloatArray,
    /**
     * Base colour of a roof covering: the same neutral a step darker, so the
     * tiles read as a different material from the walls and still as one
     * study. The value is the *only* thing that differs — see
     * [FilamentModelRenderer] — and it is monochrome like everything here.
     */
    val roofCover: FloatArray,
    /** Premultiplied colour of the feature-edge overlay. */
    val edge: FloatArray,
    /** The fine and the emphasised grid lines, premultiplied. */
    val gridMinor: FloatArray,
    val gridMajor: FloatArray,
    val shadows: Boolean,
    val ambientOcclusion: Boolean,
) {

    CLAY(
        labelRes = R.string.model_style_clay,
        background = floatArrayOf(0.58f, 0.58f, 0.57f),
        surface = floatArrayOf(0.74f, 0.74f, 0.73f, 1.0f),
        roofCover = floatArrayOf(0.62f, 0.62f, 0.61f, 1.0f),
        edge = floatArrayOf(0.06f, 0.065f, 0.07f, 0.72f),
        gridMinor = floatArrayOf(0.44f, 0.44f, 0.43f, 0.40f),
        gridMajor = floatArrayOf(0.34f, 0.34f, 0.33f, 0.55f),
        shadows = true,
        ambientOcclusion = true,
    ),

    LINE_STUDY(
        labelRes = R.string.model_style_lines,
        background = floatArrayOf(0.010f, 0.011f, 0.013f),
        surface = floatArrayOf(0.55f, 0.56f, 0.58f, 1.0f),
        roofCover = floatArrayOf(0.46f, 0.47f, 0.49f, 1.0f),
        edge = floatArrayOf(0.90f, 0.91f, 0.93f, 0.90f),
        gridMinor = floatArrayOf(0.11f, 0.12f, 0.13f, 0.42f),
        gridMajor = floatArrayOf(0.20f, 0.21f, 0.23f, 0.60f),
        shadows = false,
        ambientOcclusion = false,
    ),
    ;

    companion object {
        /** What the viewport opens on. */
        val DEFAULT: RenderStyle = CLAY
    }
}
