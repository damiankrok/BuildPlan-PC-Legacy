package com.buildplan.app.render.filament

import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.filamat.MaterialBuilder

/**
 * The one material the spike draws with: a neutral, untextured, double-sided
 * surface whose colour is a per-instance parameter.
 *
 * ## Why it is compiled on the device
 *
 * A Filament material is a compiled binary package, normally produced on a build
 * machine by `matc` and committed as a `.filamat` asset. This spike compiles it
 * at start-up with `filamat-android` instead, so that proving the renderer does
 * not also mean adding a downloaded native tool to the build and a binary blob
 * to the repository for a material that may not survive renderer selection.
 *
 * The cost is honest and bounded: `filamat-android` is a `debugImplementation`
 * dependency carrying a second native library, it never reaches a release build,
 * and productionising the renderer (STAGE-013) should replace this file with a
 * committed `.filamat` compiled by `matc`.
 *
 * ## Why lit rather than unlit
 *
 * An unlit material would paint every face the same colour, and a building drawn
 * that way is a silhouette: no wall reads as separate from the wall behind it.
 * Lit shading with flat per-face normals is what makes the geometry legible.
 * There is no texture, no environment map and no image-based lighting — the
 * ambient term is a single constant, set in [FilamentModelRenderer].
 */
internal object TechnicalMaterial {

    /** The colour parameter every [com.google.android.filament.MaterialInstance] sets. */
    const val BASE_COLOR_PARAMETER: String = "baseColor"

    private const val NAME = "buildplanTechnical"
    private const val LINE_NAME = "buildplanTechnicalLine"
    private const val GHOST_LINE_NAME = "buildplanGhostLine"

    private val SHADER = """
        void material(inout MaterialInputs material) {
            prepareMaterial(material);
            material.baseColor = materialParams.baseColor;
            material.metallic = 0.0;
            material.roughness = 0.75;
            material.reflectance = 0.2;
        }
    """.trimIndent()

    private val LINE_SHADER = """
        void material(inout MaterialInputs material) {
            prepareMaterial(material);
            material.baseColor = materialParams.baseColor;
        }
    """.trimIndent()

    /**
     * Compiles the material and uploads it to [engine].
     *
     * @throws IllegalStateException if the material fails to compile. This is
     *   deliberately fatal rather than caught: a spike that silently drew
     *   nothing would be reported as a renderer that cannot draw.
     */
    fun build(engine: Engine): Material = compile(engine, NAME) { builder ->
        builder
            .shading(MaterialBuilder.Shading.LIT)
            .blending(MaterialBuilder.BlendingMode.OPAQUE)
            // Double-sided with no culling: hiding the roof puts the camera
            // inside the building, where a single-sided wall is invisible.
            .doubleSided(true)
            .culling(MaterialBuilder.CullingMode.NONE)
            .material(SHADER)
    }

    /**
     * The material the edge overlay is drawn with: unlit, so a line is the
     * colour it was given rather than a colour the sun happened to leave on it.
     *
     * [depthTested] is the whole difference between the two kinds of line this
     * renderer draws, and it is not a tuning knob. Edges of geometry that is
     * *present* are depth tested, so the far side of a wall is hidden by the
     * near side and the model reads as solid. Edges of a layer that has been
     * *removed* are not, so the roof that was just taken off keeps hanging over
     * the house as a wireframe instead of disappearing and leaving the owner to
     * wonder whether this is even the same building.
     *
     * Both blend, so a removed layer can be drawn faint enough to stay behind
     * the layer being looked at.
     */
    fun buildLine(engine: Engine, depthTested: Boolean): Material =
        compile(engine, if (depthTested) LINE_NAME else GHOST_LINE_NAME) { builder ->
            builder
                .shading(MaterialBuilder.Shading.UNLIT)
                .blending(MaterialBuilder.BlendingMode.TRANSPARENT)
                .depthCulling(depthTested)
                // Lines never occlude anything: they are drawn over the model,
                // not part of it, and a line that wrote depth would punch a
                // one-pixel hole in whatever was drawn after it.
                .depthWrite(false)
                .doubleSided(true)
                .culling(MaterialBuilder.CullingMode.NONE)
                .material(LINE_SHADER)
        }

    private fun compile(
        engine: Engine,
        name: String,
        configure: (MaterialBuilder) -> MaterialBuilder,
    ): Material {
        MaterialBuilder.init()
        try {
            val compiled = configure(MaterialBuilder().name(name))
                .uniformParameter(MaterialBuilder.UniformType.FLOAT4, BASE_COLOR_PARAMETER)
                // No optimisation: this runs once at start-up on the device, and
                // the shaders are a handful of assignments. Optimising them would
                // only spend the user's start-up time in the SPIR-V toolchain.
                .optimization(MaterialBuilder.Optimization.NONE)
                .platform(MaterialBuilder.Platform.MOBILE)
                .targetApi(MaterialBuilder.TargetApi.OPENGL)
                .build()

            check(compiled.isValid) { "Filament could not compile the $name material" }

            val payload = compiled.buffer
            return Material.Builder()
                .payload(payload, payload.remaining())
                .build(engine)
        } finally {
            MaterialBuilder.shutdown()
        }
    }
}
