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

    private val SHADER = """
        void material(inout MaterialInputs material) {
            prepareMaterial(material);
            material.baseColor = materialParams.baseColor;
            material.metallic = 0.0;
            material.roughness = 0.75;
            material.reflectance = 0.2;
        }
    """.trimIndent()

    /**
     * Compiles the material and uploads it to [engine].
     *
     * @throws IllegalStateException if the material fails to compile. This is
     *   deliberately fatal rather than caught: a spike that silently drew
     *   nothing would be reported as a renderer that cannot draw.
     */
    fun build(engine: Engine): Material {
        MaterialBuilder.init()
        try {
            val compiled = MaterialBuilder()
                .name(NAME)
                .shading(MaterialBuilder.Shading.LIT)
                .blending(MaterialBuilder.BlendingMode.OPAQUE)
                // Double-sided with no culling: hiding the roof puts the camera
                // inside the building, where a single-sided wall is invisible.
                .doubleSided(true)
                .culling(MaterialBuilder.CullingMode.NONE)
                .uniformParameter(MaterialBuilder.UniformType.FLOAT4, BASE_COLOR_PARAMETER)
                .material(SHADER)
                // No optimisation: this runs once at start-up on the device, and
                // the shader is four assignments. Optimising it would only spend
                // the user's start-up time in the SPIR-V toolchain.
                .optimization(MaterialBuilder.Optimization.NONE)
                .platform(MaterialBuilder.Platform.MOBILE)
                .targetApi(MaterialBuilder.TargetApi.OPENGL)
                .build()

            check(compiled.isValid) { "Filament could not compile the $NAME material" }

            val payload = compiled.buffer
            return Material.Builder()
                .payload(payload, payload.remaining())
                .build(engine)
        } finally {
            MaterialBuilder.shutdown()
        }
    }
}
