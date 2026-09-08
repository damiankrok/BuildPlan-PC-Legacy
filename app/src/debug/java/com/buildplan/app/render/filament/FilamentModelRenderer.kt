package com.buildplan.app.render.filament

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.reference.visual.VisualSurfaceRole
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndexBuffer
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.MathUtils
import com.google.android.filament.RenderableManager
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The spike's Filament renderer: it owns one [Engine] and every GPU resource
 * hanging off it, for exactly as long as one [SurfaceView] is on screen.
 *
 * ## Ownership
 *
 * There is no singleton and no static engine. One instance is created when the
 * viewport enters composition and [destroy]ed when it leaves, so an Activity
 * recreation produces a second, independent renderer and the first one has
 * already released its surface, swap chain and engine. A shared engine would
 * make that recreation a leak instead of a lifecycle.
 *
 * ## What it does not decide
 *
 * It does not decide what is visible. [setVisibleElements] is told which element
 * ids survive, having been worked out by the domain's own selection queries,
 * narrowed by the model's presentation profile and looked up through the
 * geometry layer's `primitivesOf` bridge. The renderer's whole contribution is
 * to add and remove the matching entities — no second rule about roofs, frames
 * or storeys lives here, and nothing is drawn for an element that is not in
 * the set: a removed layer is absent, not ghosted.
 *
 * Nor does it decide where the camera is: it draws whatever [pose] it was handed
 * this frame. Nor what the model looks like beyond a [RenderStyle], which is
 * colours, light and passes over geometry that was uploaded once.
 */
internal class FilamentModelRenderer(
    private val surfaceView: SurfaceView,
    meshes: List<BuildingRenderMesh>,
    /**
     * The reference plane drawn under the model, or null to draw none.
     *
     * Handed in rather than derived here, because it is a fact about the model's
     * extent and this class is not allowed to have an opinion about the model.
     * It is deliberately kept out of every id map: it belongs to no element, so
     * it never hides, never tints and never answers a pick.
     */
    grid: PresentationGrid? = null,
    /**
     * Which elements read as glass beyond what their shape already says.
     *
     * Reference presentation metadata, keyed by element id and owned by the
     * model being drawn — see
     * [com.buildplan.app.reference.visual.MarcowkiVisualPresentation]. The
     * renderer consults it once, at upload, and never infers a material from
     * the element's kind.
     */
    private val surfaceRoles: Map<BuildingElementId, VisualSurfaceRole> = emptyMap(),
    initialStyle: RenderStyle = RenderStyle.DEFAULT,
    /**
     * The roof coverings, one batch per covered facet, already laid.
     *
     * Each batch carries its roof's element id and is uploaded as exactly
     * one renderable — never one per tile — so it sits in the same id maps as
     * the roof's own planes and goes in and out of the scene with them. The
     * renderer does not know what a tile is; it knows a mesh with an id.
     */
    roofCovers: List<RoofCoverMesh> = emptyList(),
) : Choreographer.FrameCallback {

    init {
        // Loads libfilament-jni.so. Idempotent, and must precede Engine.create().
        Filament.init()
    }

    private val engine: Engine = Engine.create()
    private val renderer = engine.createRenderer()
    private val scene = engine.createScene()
    private val view = engine.createView()

    private val entityManager: EntityManager = EntityManager.get()
    private val renderableManager: RenderableManager = engine.renderableManager
    private val cameraEntity: Int = entityManager.create()
    private val camera: Camera = engine.createCamera(cameraEntity)

    private val material: Material = TechnicalMaterial.build(engine)
    private val glassMaterial: Material = TechnicalMaterial.buildGlass(engine)
    private val lineMaterial: Material = TechnicalMaterial.buildLine(engine)
    private val materialInstances = ArrayList<MaterialInstance>()
    private val vertexBuffers = ArrayList<VertexBuffer>()
    private val indexBuffers = ArrayList<IndexBuffer>()

    /** Every surface entity, in mesh order. */
    private val renderableEntities = ArrayList<Int>()

    /** The renderer-to-domain direction of the join, used to answer a pick. */
    private val elementIdByEntity = HashMap<Int, BuildingElementId>()

    /** The domain-to-renderer direction, used to show and hide. */
    private val entitiesByElementId = LinkedHashMap<BuildingElementId, MutableList<Int>>()

    /** The material instance of each mesh, so one element can be tinted when selected. */
    private val instanceByEntity = HashMap<Int, MaterialInstance>()

    /** Which surface entities are glass, whose colour no style changes. */
    private val glassEntities = HashSet<Int>()

    /** Which surface entities are a roof covering, coloured by the style's cover value. */
    private val roofCoverEntities = HashSet<Int>()

    /**
     * The edge overlay of every mesh, keyed the same way as the surfaces.
     *
     * A parallel structure rather than a second list of meshes, because an
     * element's outline and its surfaces are two ways of drawing *the same*
     * shape: they are added and removed together, they answer to the same id,
     * and there is exactly one of these per surface entity.
     */
    private val outlines = ArrayList<Outline>()

    /** Every mesh's outline, by the element it belongs to. */
    private val outlinesByElementId = LinkedHashMap<BuildingElementId, MutableList<Outline>>()

    /** The reference plane's entities, if one was built. Never in any id map. */
    private val gridEntities = ArrayList<Int>()

    /** The fine and the emphasised grid lines' instances, recoloured per style. */
    private var gridMinorInstance: MaterialInstance? = null
    private var gridMajorInstance: MaterialInstance? = null

    /** One mesh's edges: depth-tested, so the far side of a wall stays hidden. */
    private class Outline(val entity: Int, val instance: MaterialInstance)

    private var skybox: Skybox
    private val indirectLight: IndirectLight
    private val sunEntity: Int

    private val choreographer: Choreographer = Choreographer.getInstance()
    private val pickHandler = Handler(Looper.getMainLooper())

    private var swapChain: SwapChain? = null
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var running = false
    private var destroyed = false

    /** Where to look from, replaced by the host whenever a gesture moves the camera. */
    var pose: OrbitPose? = null

    /**
     * Called once, on the main thread, when the surface shows the model: the
     * shader programs the scene needs have been compiled and a frame has been
     * rendered with them. Until then the surface is undefined or empty —
     * black, on most devices, for as long as the driver takes to compile —
     * and the host covers it; this is what lets it uncover.
     *
     * Filament compiles a material's programs lazily, on its driver thread,
     * the first time a frame needs them, and draws nothing with a program
     * that is not ready. So "a frame was rendered" is not "the model is on
     * screen": the first frames after start-up are empty. The materials are
     * therefore asked to compile the variants this scene uses up front, and
     * readiness is the last of those compilations *and* a rendered frame.
     */
    var onReady: (() -> Unit)? = null
    private var frameRendered = false
    private var pendingCompilations = 0
    private var readySignalled = false

    private var selectedElementId: BuildingElementId? = null

    /** The presentation currently applied. See [setStyle]. */
    var style: RenderStyle = initialStyle
        private set

    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)

    init {
        skybox = buildSkybox(style)
        scene.skybox = skybox

        // A constant ambient term given directly as the single band-0 spherical
        // harmonic. There is no environment map, no KTX and no IBL asset: this
        // is one number per channel, and its only job is to keep the faces the
        // sun does not reach from going black.
        //
        // The coefficients are normalised and the lux goes into intensity(),
        // because intensity() *multiplies* them and defaults to 30 000. Passing
        // a lux-scale coefficient here instead scales the ambient by that
        // default and washes the whole model out to white — at which point
        // changing the sun does nothing, because the sun is eight orders of
        // magnitude below the ambient.
        indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(1.0f, 1.0f, 1.02f))
            .intensity(AMBIENT_LUX)
            .build(engine)
        scene.indirectLight = indirectLight

        // The key light casts shadows; whether they are rendered is the
        // style's decision, made per frame through the view.
        sunEntity = entityManager.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.99f, 0.97f)
            .intensity(SUN_LUX)
            .direction(-0.45f, -0.82f, -0.36f)
            .castShadows(true)
            .shadowOptions(
                LightManager.ShadowOptions().apply {
                    mapSize = SHADOW_MAP_SIZE
                    // The whole house is a dozen metres across: one cascade at
                    // this map size resolves a stair tread.
                    shadowCascades = 1
                },
            )
            .build(engine, sunEntity)
        scene.addEntity(sunEntity)

        meshes.forEach(::uploadMesh)
        roofCovers.forEach(::uploadRoofCover)
        grid?.let(::uploadGrid)

        // The variants this scene draws with: one directional light, with
        // shadows. Every other user variant — dynamic lights, fog, skinning,
        // screen-space reflections — is never used and never compiled.
        val materials = listOf(material, glassMaterial, lineMaterial)
        pendingCompilations = materials.size
        materials.forEach { compiled ->
            compiled.compile(Material.CompilerPriorityQueue.HIGH, SCENE_VARIANTS, pickHandler) {
                pendingCompilations--
                signalReadyIfSo()
            }
        }

        camera.setExposure(APERTURE, SHUTTER_SPEED, SENSITIVITY)

        view.scene = scene
        view.camera = camera
        // Edges are one pixel wide, and the difference between a drawing and a
        // pile of stairsteps is whether those pixels are resolved.
        view.antiAliasing = View.AntiAliasing.FXAA
        view.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply {
            enabled = true
            sampleCount = MSAA_SAMPLES
        }
        applyStyle(style)

        uiHelper.renderCallback = SurfaceCallback()
        uiHelper.attachTo(surfaceView)
    }

    // --- Geometry upload -----------------------------------------------------

    /**
     * Uploads one mesh as one renderable entity.
     *
     * Entities are created but deliberately *not* added to the scene here: what
     * is on screen is decided by [setVisibleElements], and an entity added at
     * upload time would be visible for one frame regardless of the current
     * visibility.
     */
    private fun uploadMesh(mesh: BuildingRenderMesh) {
        val vertexBuffer = VertexBuffer.Builder()
            .bufferCount(2)
            .vertexCount(mesh.vertexCount)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION,
                0,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                POSITION_STRIDE_BYTES,
            )
            .attribute(
                VertexBuffer.VertexAttribute.TANGENTS,
                1,
                VertexBuffer.AttributeType.FLOAT4,
                0,
                TANGENT_STRIDE_BYTES,
            )
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, mesh.positions.toDirectBuffer())
        vertexBuffer.setBufferAt(engine, 1, tangentFrames(mesh.normals))

        val indexBuffer = IndexBuffer.Builder()
            .indexCount(mesh.indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        indexBuffer.setBuffer(engine, mesh.indices.toDirectBuffer())

        // Glass or fabric is decided once, here, from the primitive type and
        // the reference presentation — never from the element's kind.
        val role = surfaceRoleOf(mesh, surfaceRoles)
        val glass = role == VisualSurfaceRole.GLASS_STUDY
        val instance = (if (glass) glassMaterial else material).createInstance()
        instance.setColor(if (glass) GLASS else style.surface)
        // Pushes the surface away from the camera by a hair, so that the edge
        // drawn along it wins the depth test instead of tying with it. Without
        // this the two are at exactly the same depth and the outline breaks into
        // a dashed line that reshuffles itself every time the camera moves.
        instance.setPolygonOffset(SURFACE_DEPTH_OFFSET, SURFACE_DEPTH_OFFSET)

        val entity = entityManager.create()
        RenderableManager.Builder(1)
            .boundingBox(Box(mesh.boundsCenter, mesh.boundsHalfExtent))
            .geometry(
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer,
                indexBuffer,
                0,
                mesh.indices.size,
            )
            .material(0, instance)
            // A pane neither throws a shadow nor takes one: a sheet that
            // shadowed the room behind it would read as a wall again.
            .castShadows(!glass)
            .receiveShadows(!glass)
            .culling(true)
            .build(engine, entity)

        vertexBuffers += vertexBuffer
        indexBuffers += indexBuffer
        materialInstances += instance
        renderableEntities += entity
        elementIdByEntity[entity] = mesh.elementId
        entitiesByElementId.getOrPut(mesh.elementId) { ArrayList() } += entity
        instanceByEntity[entity] = instance
        if (glass) glassEntities += entity

        uploadOutline(mesh, vertexBuffer)
    }

    /**
     * Uploads the same mesh a second time as a line list, sharing its vertex
     * buffer.
     *
     * Sharing is the point: the outline is not an approximation of the surface
     * drawn beside it, it is the surface's own corners. A separately generated
     * wireframe would drift from the geometry it outlines the first time either
     * changed, and drift by less than a pixel is exactly what looks like a
     * rendering bug rather than a design.
     */
    private fun uploadOutline(mesh: BuildingRenderMesh, vertexBuffer: VertexBuffer) {
        if (mesh.edgeCount == 0) return

        val edgeBuffer = IndexBuffer.Builder()
            .indexCount(mesh.edgeIndices.size)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        edgeBuffer.setBuffer(engine, mesh.edgeIndices.toDirectBuffer())

        val instance = lineMaterial.createInstance()
        instance.setColor(style.edge)

        val entity = entityManager.create()
        RenderableManager.Builder(1)
            .boundingBox(Box(mesh.boundsCenter, mesh.boundsHalfExtent))
            .geometry(
                0,
                RenderableManager.PrimitiveType.LINES,
                vertexBuffer,
                edgeBuffer,
                0,
                mesh.edgeIndices.size,
            )
            .material(0, instance)
            .castShadows(false)
            .receiveShadows(false)
            .culling(true)
            .build(engine, entity)

        indexBuffers += edgeBuffer
        materialInstances += instance
        val outline = Outline(entity = entity, instance = instance)
        outlines += outline
        outlinesByElementId.getOrPut(mesh.elementId) { ArrayList() } += outline
    }

    /**
     * Uploads one facet's covering as one renderable.
     *
     * One entity for the whole batch, whatever its tile count: the covering
     * is registered under its roof's id exactly as a roof plane is, so
     * [setVisibleElements] and [pick] treat it as the roof without a line of
     * their own about it. No outline is uploaded — a covering reads from its
     * relief, and two thousand outlined quads would be the wireframe
     * STAGE-013E removed, put back.
     *
     * The third buffer is the tile signal — phase and position along the
     * tile — carried as `UV0`. No material reads it yet; it is the seam a
     * later vertex-shader animation attaches to without the geometry, the
     * entity count or the id maps changing.
     */
    private fun uploadRoofCover(cover: RoofCoverMesh) {
        val vertexBuffer = VertexBuffer.Builder()
            .bufferCount(3)
            .vertexCount(cover.vertexCount)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION,
                0,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                POSITION_STRIDE_BYTES,
            )
            .attribute(
                VertexBuffer.VertexAttribute.TANGENTS,
                1,
                VertexBuffer.AttributeType.FLOAT4,
                0,
                TANGENT_STRIDE_BYTES,
            )
            .attribute(
                VertexBuffer.VertexAttribute.UV0,
                2,
                VertexBuffer.AttributeType.FLOAT2,
                0,
                SIGNAL_STRIDE_BYTES,
            )
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, cover.positions.toDirectBuffer())
        vertexBuffer.setBufferAt(engine, 1, tangentFrames(cover.normals))
        vertexBuffer.setBufferAt(engine, 2, cover.signals.toDirectBuffer())

        val indexBuffer = IndexBuffer.Builder()
            .indexCount(cover.indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        indexBuffer.setBuffer(engine, cover.indices.toDirectBuffer())

        val instance = material.createInstance()
        instance.setColor(style.roofCover)

        val entity = entityManager.create()
        RenderableManager.Builder(1)
            .boundingBox(Box(cover.boundsCenter, cover.boundsHalfExtent))
            .geometry(
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer,
                indexBuffer,
                0,
                cover.indices.size,
            )
            .material(0, instance)
            // The relief is the point: each course shadows the one below.
            .castShadows(true)
            .receiveShadows(true)
            .culling(true)
            .build(engine, entity)

        vertexBuffers += vertexBuffer
        indexBuffers += indexBuffer
        materialInstances += instance
        renderableEntities += entity
        elementIdByEntity[entity] = cover.elementId
        entitiesByElementId.getOrPut(cover.elementId) { ArrayList() } += entity
        instanceByEntity[entity] = instance
        roofCoverEntities += entity
    }

    /**
     * Uploads the reference plane as two line lists sharing one vertex buffer.
     *
     * Added to the scene here rather than in [setVisibleElements], and never
     * removed from it: it is not part of the model, so no visibility state has
     * anything to say about it. Depth-tested, so the building stands on it
     * instead of being drawn through by it, and unlit, so it stays the faint
     * grey it was given wherever the sun happens to be.
     */
    private fun uploadGrid(grid: PresentationGrid) {
        if (grid.lineCount == 0) return

        val vertexBuffer = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(grid.positions.size / 3)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION,
                0,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                POSITION_STRIDE_BYTES,
            )
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, grid.positions.toDirectBuffer())
        vertexBuffers += vertexBuffer

        listOf(
            Triple(grid.minorIndices, style.gridMinor, true),
            Triple(grid.majorIndices, style.gridMajor, false),
        ).forEach { (lineIndices, color, minor) ->
            if (lineIndices.isEmpty()) return@forEach

            val indexBuffer = IndexBuffer.Builder()
                .indexCount(lineIndices.size)
                .bufferType(IndexBuffer.Builder.IndexType.UINT)
                .build(engine)
            indexBuffer.setBuffer(engine, lineIndices.toDirectBuffer())
            indexBuffers += indexBuffer

            val instance = lineMaterial.createInstance()
            instance.setColor(color)
            materialInstances += instance
            if (minor) gridMinorInstance = instance else gridMajorInstance = instance

            val entity = entityManager.create()
            RenderableManager.Builder(1)
                .boundingBox(Box(grid.boundsCenter, grid.boundsHalfExtent))
                .geometry(
                    0,
                    RenderableManager.PrimitiveType.LINES,
                    vertexBuffer,
                    indexBuffer,
                    0,
                    lineIndices.size,
                )
                .material(0, instance)
                .castShadows(false)
                .receiveShadows(false)
                .culling(true)
                .build(engine, entity)

            gridEntities += entity
            scene.addEntity(entity)
        }
    }

    // --- Visibility ----------------------------------------------------------

    /**
     * Draws exactly the elements in [visibleElementIds]: their surfaces and
     * their feature edges, and nothing for any other element.
     *
     * ## Why nothing is drawn for a removed layer
     *
     * STAGE-013B kept a removed layer as a wireframe drawn through everything,
     * to say "same house, layer lifted". The owner's verdict was that without
     * the roof there was still a roof: a house-shaped cage over the attic that
     * was supposed to be exposed. Continuity is held where it belongs — every
     * state is a subset of one canonical model, which the tests prove — and
     * not by drawing the thing that was asked to go away.
     *
     * It is not a second opinion about what is visible. The set is handed in,
     * it comes from the one selection the domain and the presentation profile
     * made, and this method's whole contribution is adding and removing
     * entities.
     *
     * ## Why nothing is rebuilt
     *
     * The meshes were uploaded once and stay on the GPU. Toggling the roof adds
     * and removes entities; no geometry is regenerated, so the shape an owner
     * reviewed cannot change as a side effect of looking at it from a different
     * state.
     */
    fun setVisibleElements(visibleElementIds: Set<BuildingElementId>) {
        if (destroyed) return
        entitiesByElementId.forEach { (elementId, entities) ->
            val shouldShow = elementId in visibleElementIds
            entities.forEach { entity -> setInScene(entity, shouldShow) }
        }
        outlinesByElementId.forEach { (elementId, elementOutlines) ->
            val shouldShow = elementId in visibleElementIds
            elementOutlines.forEach { outline -> setInScene(outline.entity, shouldShow) }
        }
    }

    private fun setInScene(entity: Int, shouldBeInScene: Boolean) {
        val inScene = scene.hasEntity(entity)
        if (shouldBeInScene && !inScene) {
            scene.addEntity(entity)
        } else if (!shouldBeInScene && inScene) {
            scene.removeEntity(entity)
        }
    }

    // --- Style ---------------------------------------------------------------

    /**
     * Switches the presentation: backdrop, surface and line colours, shadows
     * and ambient occlusion. Every entity, buffer and id map is untouched — a
     * style is how the one model is lit and inked, never which model.
     */
    fun setStyle(newStyle: RenderStyle) {
        if (destroyed || newStyle == style) return
        style = newStyle
        applyStyle(newStyle)
    }

    private fun applyStyle(applied: RenderStyle) {
        val previous = skybox
        skybox = buildSkybox(applied)
        scene.skybox = skybox
        engine.destroySkybox(previous)

        renderableEntities.forEach { entity ->
            if (entity in glassEntities) return@forEach
            val tinted = elementIdByEntity[entity] == selectedElementId
            instanceByEntity[entity]?.setColor(if (tinted) SELECTED else baseColorOf(entity, applied))
        }
        outlines.forEach { it.instance.setColor(applied.edge) }
        gridMinorInstance?.setColor(applied.gridMinor)
        gridMajorInstance?.setColor(applied.gridMajor)

        view.setShadowingEnabled(applied.shadows)
        view.ambientOcclusionOptions = View.AmbientOcclusionOptions().apply {
            enabled = applied.ambientOcclusion
            // A room-scale radius: the occlusion is the corner where a wall
            // meets a floor, not the whole facade darkening.
            radius = AO_RADIUS_METERS
            intensity = AO_INTENSITY
            power = AO_POWER
            quality = View.QualityLevel.HIGH
            lowPassFilter = View.QualityLevel.HIGH
            upsampling = View.QualityLevel.HIGH
        }
    }

    private fun buildSkybox(applied: RenderStyle): Skybox = Skybox.Builder()
        .color(applied.background[0], applied.background[1], applied.background[2], 1.0f)
        .build(engine)

    /**
     * The colour an unselected opaque entity returns to under [applied]: the
     * covering's value for a roof covering, the surface value for everything
     * else. The one place the renderer tells the two apart, and it does so by
     * which upload registered the entity — never by the element's kind.
     */
    private fun baseColorOf(entity: Int, applied: RenderStyle): FloatArray =
        if (entity in roofCoverEntities) applied.roofCover else applied.surface

    /**
     * Tints every mesh of [elementId], and returns every other mesh to its own
     * base colour.
     *
     * A selected pane keeps being a pane: it takes the selection hue at a
     * somewhat higher opacity, not full opacity, because a balustrade that went
     * solid when tapped would answer "what is this" with the wrong picture.
     */
    fun setSelectedElement(elementId: BuildingElementId?) {
        if (destroyed || elementId == selectedElementId) return
        selectedElementId = elementId
        renderableEntities.forEach { entity ->
            val instance = instanceByEntity[entity] ?: return@forEach
            val glass = entity in glassEntities
            val color = when {
                elementIdByEntity[entity] != elementId -> if (glass) GLASS else baseColorOf(entity, style)
                glass -> floatArrayOf(SELECTED[0], SELECTED[1], SELECTED[2], GlassPresentation.SELECTED_ALPHA)
                else -> SELECTED
            }
            instance.setColor(color)
        }
    }

    // --- Picking -------------------------------------------------------------

    /**
     * Resolves the element under a tap, using Filament's own picking pass.
     *
     * [x] and [y] are viewport pixels with the origin at the bottom left, which
     * is Filament's convention and not the one the touch event arrives in — the
     * host flips it. A tap on the background resolves to entity 0, which is in
     * no map, so the result is null rather than a nearest guess.
     */
    fun pick(x: Int, y: Int, onResult: (BuildingElementId?) -> Unit) {
        if (destroyed || !uiHelper.isReadyToRender) {
            onResult(null)
            return
        }
        view.pick(x, y, pickHandler) { result ->
            onResult(elementIdByEntity[result.renderable])
        }
    }

    // --- Lifecycle -----------------------------------------------------------

    fun resume() {
        if (destroyed || running) return
        running = true
        choreographer.postFrameCallback(this)
    }

    fun pause() {
        if (!running) return
        running = false
        choreographer.removeFrameCallback(this)
    }

    /**
     * Releases everything, in the order Filament requires: entities and their
     * buffers first, then the scene-level resources, then the engine.
     *
     * [UiHelper.detach] is called first so the swap chain is destroyed while the
     * engine is still alive, and the whole method is idempotent because a
     * recreated Activity can dispose a viewport that has already gone.
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        pause()

        uiHelper.detach()

        (renderableEntities + outlines.map { it.entity } + gridEntities).forEach { entity ->
            scene.removeEntity(entity)
            engine.destroyEntity(entity)
            entityManager.destroy(entity)
        }
        renderableEntities.clear()
        outlines.clear()
        gridEntities.clear()
        outlinesByElementId.clear()
        elementIdByEntity.clear()
        entitiesByElementId.clear()
        instanceByEntity.clear()
        glassEntities.clear()
        roofCoverEntities.clear()
        gridMinorInstance = null
        gridMajorInstance = null

        materialInstances.forEach(engine::destroyMaterialInstance)
        materialInstances.clear()
        vertexBuffers.forEach(engine::destroyVertexBuffer)
        vertexBuffers.clear()
        indexBuffers.forEach(engine::destroyIndexBuffer)
        indexBuffers.clear()

        engine.destroyMaterial(material)
        engine.destroyMaterial(glassMaterial)
        engine.destroyMaterial(lineMaterial)

        scene.removeEntity(sunEntity)
        engine.destroyEntity(sunEntity)
        entityManager.destroy(sunEntity)

        scene.skybox = null
        scene.indirectLight = null
        engine.destroySkybox(skybox)
        engine.destroyIndirectLight(indirectLight)

        engine.destroyCameraComponent(cameraEntity)
        entityManager.destroy(cameraEntity)

        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyRenderer(renderer)

        engine.destroy()
    }

    // --- Frame loop ----------------------------------------------------------

    override fun doFrame(frameTimeNanos: Long) {
        if (destroyed || !running) return
        choreographer.postFrameCallback(this)

        if (!uiHelper.isReadyToRender) return
        val currentSwapChain = swapChain ?: return
        applyCamera()

        if (renderer.beginFrame(currentSwapChain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
            if (!frameRendered) {
                frameRendered = true
                signalReadyIfSo()
            }
        }
    }

    private fun signalReadyIfSo() {
        if (destroyed || readySignalled || !frameRendered || pendingCompilations > 0) return
        readySignalled = true
        onReady?.invoke()
    }

    private fun applyCamera() {
        val currentPose = pose ?: return
        if (viewportWidth <= 0 || viewportHeight <= 0) return

        // The field of view spans the viewport's shorter side. A preset frames
        // the model so that it fills that angle; on a tall phone viewport the
        // shorter side is the width, and a vertical angle there would leave
        // the house wider than the screen and cropped at both ends.
        val aspect = viewportWidth.toDouble() / viewportHeight.toDouble()
        camera.setProjection(
            OrbitCameraState.FIELD_OF_VIEW_DEGREES,
            aspect,
            currentPose.near,
            currentPose.far,
            if (aspect < 1.0) Camera.Fov.HORIZONTAL else Camera.Fov.VERTICAL,
        )
        camera.lookAt(
            currentPose.eyeX, currentPose.eyeY, currentPose.eyeZ,
            currentPose.targetX, currentPose.targetY, currentPose.targetZ,
            0.0, 1.0, 0.0,
        )
    }

    private inner class SurfaceCallback : UiHelper.RendererCallback {

        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let(engine::destroySwapChain)
            swapChain = engine.createSwapChain(surface)
        }

        override fun onDetachedFromSurface() {
            swapChain?.let { chain ->
                engine.destroySwapChain(chain)
                // The swap chain is destroyed on the driver thread; waiting here
                // is what makes it safe to hand the surface back to the system.
                engine.flushAndWait()
                swapChain = null
            }
        }

        override fun onResized(width: Int, height: Int) {
            viewportWidth = width
            viewportHeight = height
            view.viewport = Viewport(0, 0, width, height)
        }
    }

    private companion object {

        /** The selection tint, linear RGB; the one hue the study has. */
        val SELECTED = floatArrayOf(0.13f, 0.46f, 0.74f, 1.0f)

        /** The user variants the scene's materials are compiled for up front. */
        val SCENE_VARIANTS: Int =
            Material.UserVariantFilterBit.DIRECTIONAL_LIGHTING or Material.UserVariantFilterBit.SHADOW_RECEIVER

        /**
         * Glass: a neutral sheet at the opacity [GlassPresentation] fixes,
         * blended over whatever stands behind it, in every style.
         *
         * STAGE-013B drew glazing as an opaque dark panel, which read as a hole
         * from outside and as a wall from the balcony. Transparency is the
         * one exception the study makes to a single opaque colour, and it is
         * made because a railing that cannot be seen through is a parapet.
         */
        val GLASS = floatArrayOf(
            GlassPresentation.COLOR[0],
            GlassPresentation.COLOR[1],
            GlassPresentation.COLOR[2],
            GlassPresentation.ALPHA,
        )

        /**
         * How far behind itself a lit surface is pushed so its own outline wins
         * the depth test. Positive is away from the camera in Filament's
         * convention, and one unit of each term is the smallest offset that is
         * reliable across drivers without the line detaching visibly.
         */
        const val SURFACE_DEPTH_OFFSET = 1.0f

        /**
         * Lighting in Filament's physical units, balanced against the camera
         * exposure below so that a lit off-white face lands just under white
         * and a face in shadow stays a readable mid-grey.
         *
         * Far below real daylight on purpose. A study model wants a visible
         * difference between a lit, an unlit and a shadowed face; exposing it
         * like a sunlit building clips every surface to white and throws away
         * exactly the shading that makes one wall read as separate from the
         * next. The ambient is about a third of the key, which is what keeps
         * the shadow side legible without flattening the sun.
         */
        const val SUN_LUX = 90_000.0f
        const val AMBIENT_LUX = 28_000.0f

        const val APERTURE = 16.0f
        const val SHUTTER_SPEED = 1.0f / 125.0f
        const val SENSITIVITY = 100.0f

        /** Shadow map resolution: enough to resolve one stair tread on a house-sized model. */
        const val SHADOW_MAP_SIZE = 2048

        const val MSAA_SAMPLES = 4

        const val AO_RADIUS_METERS = 0.45f
        const val AO_INTENSITY = 0.9f
        const val AO_POWER = 1.2f

        const val POSITION_STRIDE_BYTES = 12
        const val TANGENT_STRIDE_BYTES = 16
        const val SIGNAL_STRIDE_BYTES = 8
    }
}

private fun MaterialInstance.setColor(color: FloatArray) {
    setParameter(TechnicalMaterial.BASE_COLOR_PARAMETER, color[0], color[1], color[2], color[3])
}

/**
 * Packs per-vertex normals into the tangent-frame quaternions Filament's lit
 * shading reads.
 *
 * Filament does not take a raw normal attribute: it takes a quaternion encoding
 * the whole tangent frame. The spike has no textures and therefore no meaningful
 * tangent direction, so any tangent perpendicular to the normal will do — the
 * reference axis is only swapped when the normal is close to it, which is what
 * keeps the cross product from collapsing on horizontal faces.
 */
private fun tangentFrames(normals: FloatArray): FloatBuffer {
    val vertexCount = normals.size / 3
    val packed = FloatArray(vertexCount * 4)
    val quaternion = FloatArray(4)

    for (vertex in 0 until vertexCount) {
        val normalX = normals[vertex * 3]
        val normalY = normals[vertex * 3 + 1]
        val normalZ = normals[vertex * 3 + 2]

        val referenceIsUp = abs(normalY) < 0.9f
        val referenceX = if (referenceIsUp) 0.0f else 1.0f
        val referenceY = if (referenceIsUp) 1.0f else 0.0f

        // tangent = normalize(reference x normal)
        var tangentX = referenceY * normalZ
        var tangentY = -referenceX * normalZ
        var tangentZ = referenceX * normalY - referenceY * normalX
        val length = sqrt(
            tangentX * tangentX + tangentY * tangentY + tangentZ * tangentZ,
        )
        if (length > 0f) {
            tangentX /= length
            tangentY /= length
            tangentZ /= length
        }

        // bitangent = normal x tangent
        val bitangentX = normalY * tangentZ - normalZ * tangentY
        val bitangentY = normalZ * tangentX - normalX * tangentZ
        val bitangentZ = normalX * tangentY - normalY * tangentX

        MathUtils.packTangentFrame(
            tangentX, tangentY, tangentZ,
            bitangentX, bitangentY, bitangentZ,
            normalX, normalY, normalZ,
            quaternion,
        )

        packed[vertex * 4] = quaternion[0]
        packed[vertex * 4 + 1] = quaternion[1]
        packed[vertex * 4 + 2] = quaternion[2]
        packed[vertex * 4 + 3] = quaternion[3]
    }

    return packed.toDirectBuffer()
}

/** Filament reads from native memory, so every buffer handed to it must be direct. */
private fun FloatArray.toDirectBuffer(): FloatBuffer {
    val buffer = ByteBuffer.allocateDirect(size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    buffer.put(this)
    buffer.rewind()
    return buffer
}

private fun IntArray.toDirectBuffer(): IntBuffer {
    val buffer = ByteBuffer.allocateDirect(size * Int.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asIntBuffer()
    buffer.put(this)
    buffer.rewind()
    return buffer
}
