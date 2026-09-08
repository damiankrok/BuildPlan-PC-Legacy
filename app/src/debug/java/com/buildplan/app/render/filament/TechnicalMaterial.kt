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
    private const val GLASS_NAME = "buildplanTechnicalGlass"
    private const val LINE_NAME = "buildplanTechnicalLine"

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
     * Glass: the same lit, untextured surface, blended over what is behind it.
     *
     * Filament's transparent blending expects a premultiplied colour, so the
     * alpha is applied here rather than left to the caller. A little smoother
     * and more reflective than fabric, which is what gives a pane its faint
     * sheen against the dark background; no refraction, no environment — a
     * study pane, not a rendered one.
     */
    private val GLASS_SHADER = """
        void material(inout MaterialInputs material) {
            prepareMaterial(material);
            material.baseColor = materialParams.baseColor;
            material.baseColor.rgb *= material.baseColor.a;
            material.metallic = 0.0;
            material.roughness = 0.35;
            material.reflectance = 0.4;
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
     * The one material that is not opaque: neutral technical glass.
     *
     * Transparent blending, so a balustrade shows the balcony behind it and a
     * gable glazing shows the room. Double-sided and unculled like the fabric,
     * because a pane is one sheet seen from both sides. It does not write
     * depth: a two-centimetre sheet that wrote depth would cut the outline of
     * whatever stands behind it, and the depth-tested edge overlay would then
     * break along the pane's silhouette. Filament sorts blended surfaces back
     * to front on its own.
     *
     * A tap passes through it. Filament's picking pass renders only opaque
     * surfaces — verified on the device with depth writing both off and on —
     * so a blended sheet cannot answer a pick, and the element behind the
     * glass answers instead. That is the deliberate reading of "technical
     * glass": it is looked through, and tapped through.
     *
     * Not a refractive, reflective or textured glass, on purpose: the study
     * has one surface colour and one exception to it, and the exception is
     * transparency alone. See [GlassPresentation] for the values.
     */
    fun buildGlass(engine: Engine): Material = compile(engine, GLASS_NAME) { builder ->
        builder
            .shading(MaterialBuilder.Shading.LIT)
            .blending(MaterialBuilder.BlendingMode.TRANSPARENT)
            .depthWrite(false)
            .doubleSided(true)
            .culling(MaterialBuilder.CullingMode.NONE)
            .material(GLASS_SHADER)
    }

    /**
     * The material the edge overlay is drawn with: unlit, so a line is the
     * colour it was given rather than a colour the sun happened to leave on it.
     *
     * Always depth tested. The far side of a wall is hidden by the near side
     * and the model reads as solid; no line is ever drawn through an opaque
     * surface. STAGE-013B had a second, untested line material for ghosting a
     * removed layer, and the owner's verdict on it was that a removed roof was
     * still a roof — so there is one line material and it obeys depth.
     *
     * Blended, so the ink can sit at a chosen weight over the surface.
     */
    fun buildLine(engine: Engine): Material =
        compile(engine, LINE_NAME) { builder ->
            builder
                .shading(MaterialBuilder.Shading.UNLIT)
                .blending(MaterialBuilder.BlendingMode.TRANSPARENT)
                .depthCulling(true)
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
