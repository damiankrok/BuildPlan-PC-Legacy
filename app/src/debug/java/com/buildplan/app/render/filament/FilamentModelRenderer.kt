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
 * ids survive, having been worked out by the domain's own selection queries and
 * looked up through the geometry layer's `primitivesOf` bridge. The renderer's
 * whole contribution is to add and remove the matching entities — no second rule
 * about roofs or storeys lives here.
 *
 * Nor does it decide where the camera is: it draws whatever [pose] it was handed
 * this frame.
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
    private val lineMaterial: Material = TechnicalMaterial.buildLine(engine, depthTested = true)
    private val ghostLineMaterial: Material =
        TechnicalMaterial.buildLine(engine, depthTested = false)
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

    /**
     * The colour each mesh returns to when it stops being selected, as linear
     * RGBA.
     *
     * Stored rather than recomputed as "the neutral one", because glass is not
     * neutral and not opaque: a pane that came back the colour of a wall after
     * being tapped would quietly turn every window back into masonry, and one
     * that came back opaque would turn it into a parapet.
     */
    private val baseColorByEntity = HashMap<Int, FloatArray>()

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

    /** One mesh's edges, and the two ways they can be drawn. */
    private class Outline(
        val entity: Int,
        /** Depth-tested and bright: the element is here. */
        val present: MaterialInstance,
        /** Drawn through everything and faint: the element has been taken away. */
        val removed: MaterialInstance,
    ) {
        var showingRemoved: Boolean = false
    }

    private val skybox: Skybox
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

    private var selectedElementId: BuildingElementId? = null

    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)

    init {
        skybox = Skybox.Builder()
            .color(BACKGROUND[0], BACKGROUND[1], BACKGROUND[2], 1.0f)
            .build(engine)
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
            .irradiance(1, floatArrayOf(1.0f, 1.0f, 1.06f))
            .intensity(AMBIENT_LUX)
            .build(engine)
        scene.indirectLight = indirectLight

        sunEntity = entityManager.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.98f, 0.95f)
            .intensity(SUN_LUX)
            .direction(-0.45f, -0.82f, -0.36f)
            .castShadows(false)
            .build(engine, sunEntity)
        scene.addEntity(sunEntity)

        meshes.forEach(::uploadMesh)
        grid?.let(::uploadGrid)

        camera.setExposure(APERTURE, SHUTTER_SPEED, SENSITIVITY)

        view.scene = scene
        view.camera = camera

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
        val baseColor = when (role) {
            VisualSurfaceRole.OPAQUE_STUDY -> NEUTRAL
            VisualSurfaceRole.GLASS_STUDY -> GLASS
        }
        val instance = when (role) {
            VisualSurfaceRole.OPAQUE_STUDY -> material
            VisualSurfaceRole.GLASS_STUDY -> glassMaterial
        }.createInstance()
        instance.setParameter(
            TechnicalMaterial.BASE_COLOR_PARAMETER,
            baseColor[0],
            baseColor[1],
            baseColor[2],
            baseColor[3],
        )
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
            .castShadows(false)
            .receiveShadows(false)
            .culling(true)
            .build(engine, entity)

        vertexBuffers += vertexBuffer
        indexBuffers += indexBuffer
        materialInstances += instance
        renderableEntities += entity
        elementIdByEntity[entity] = mesh.elementId
        entitiesByElementId.getOrPut(mesh.elementId) { ArrayList() } += entity
        instanceByEntity[entity] = instance
        baseColorByEntity[entity] = baseColor

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

        val present = lineMaterial.createInstance()
        present.setParameter(
            TechnicalMaterial.BASE_COLOR_PARAMETER,
            EDGE[0], EDGE[1], EDGE[2], EDGE[3],
        )
        val removed = ghostLineMaterial.createInstance()
        removed.setParameter(
            TechnicalMaterial.BASE_COLOR_PARAMETER,
            GHOST_EDGE[0], GHOST_EDGE[1], GHOST_EDGE[2], GHOST_EDGE[3],
        )

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
            .material(0, present)
            .castShadows(false)
            .receiveShadows(false)
            .culling(true)
            .build(engine, entity)

        indexBuffers += edgeBuffer
        materialInstances += present
        materialInstances += removed
        val outline = Outline(entity = entity, present = present, removed = removed)
        outlines += outline
        outlinesByElementId.getOrPut(mesh.elementId) { ArrayList() } += outline
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
            grid.minorIndices to GRID_MINOR,
            grid.majorIndices to GRID_MAJOR,
        ).forEach { (lineIndices, color) ->
            if (lineIndices.isEmpty()) return@forEach

            val indexBuffer = IndexBuffer.Builder()
                .indexCount(lineIndices.size)
                .bufferType(IndexBuffer.Builder.IndexType.UINT)
                .build(engine)
            indexBuffer.setBuffer(engine, lineIndices.toDirectBuffer())
            indexBuffers += indexBuffer

            val instance = lineMaterial.createInstance()
            instance.setParameter(
                TechnicalMaterial.BASE_COLOR_PARAMETER,
                color[0], color[1], color[2], color[3],
            )
            materialInstances += instance

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
     * Draws the elements in [visibleElementIds] as solid, and the elements in
     * [removedElementIds] as their outline alone.
     *
     * ## Why a removed element is still drawn
     *
     * Because taking the roof off has to leave the same house standing. Drawing
     * nothing where a layer used to be gives the owner two pictures with no
     * visible relationship between them, and the honest question that follows is
     * whether the second one is even the same model. Keeping the removed layer
     * as a wireframe answers it in the picture itself: the roof is still there,
     * over the same footprint, at the same pitch — it has been made transparent,
     * not swapped out.
     *
     * It is not a second opinion about what is visible. Both sets are handed in,
     * both come from the one selection the domain made, and this method's whole
     * contribution is which of two ways to draw each id.
     *
     * ## Why nothing is rebuilt
     *
     * The meshes were uploaded once and stay on the GPU. Toggling the roof adds
     * and removes entities and swaps a material instance; no geometry is
     * regenerated, so the shape an owner reviewed cannot change as a side effect
     * of looking at it from a different state.
     */
    fun setVisibleElements(
        visibleElementIds: Set<BuildingElementId>,
        removedElementIds: Set<BuildingElementId> = emptySet(),
    ) {
        if (destroyed) return
        entitiesByElementId.forEach { (elementId, entities) ->
            val shouldShow = elementId in visibleElementIds
            entities.forEach { entity -> setInScene(entity, shouldShow) }
        }
        outlinesByElementId.forEach { (elementId, elementOutlines) ->
            val present = elementId in visibleElementIds
            val removed = !present && elementId in removedElementIds
            elementOutlines.forEach { outline ->
                if (outline.showingRemoved != removed) {
                    outline.showingRemoved = removed
                    renderableManager.setMaterialInstanceAt(
                        renderableManager.getInstance(outline.entity),
                        0,
                        if (removed) outline.removed else outline.present,
                    )
                }
                setInScene(outline.entity, present || removed)
            }
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
            val base = baseColorByEntity[entity] ?: NEUTRAL
            val color = if (elementIdByEntity[entity] == elementId) {
                val alpha = if (base[3] < 1.0f) GlassPresentation.SELECTED_ALPHA else 1.0f
                floatArrayOf(SELECTED[0], SELECTED[1], SELECTED[2], alpha)
            } else {
                base
            }
            instance.setParameter(
                TechnicalMaterial.BASE_COLOR_PARAMETER,
                color[0],
                color[1],
                color[2],
                color[3],
            )
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

        materialInstances.forEach(engine::destroyMaterialInstance)
        materialInstances.clear()
        vertexBuffers.forEach(engine::destroyVertexBuffer)
        vertexBuffers.clear()
        indexBuffers.forEach(engine::destroyIndexBuffer)
        indexBuffers.clear()

        engine.destroyMaterial(material)
        engine.destroyMaterial(glassMaterial)
        engine.destroyMaterial(lineMaterial)
        engine.destroyMaterial(ghostLineMaterial)

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
        }
    }

    private fun applyCamera() {
        val currentPose = pose ?: return
        if (viewportWidth <= 0 || viewportHeight <= 0) return

        camera.setProjection(
            OrbitCameraState.FIELD_OF_VIEW_DEGREES,
            viewportWidth.toDouble() / viewportHeight.toDouble(),
            currentPose.near,
            currentPose.far,
            Camera.Fov.VERTICAL,
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

        /**
         * Filament works in linear light, so these are linear values, not the
         * sRGB numbers from the app theme. The background is a very dark
         * neutral rather than black, so an unlit silhouette still separates from
         * it; the surfaces are a light neutral grey with no hue of their own.
         */
        val BACKGROUND = floatArrayOf(0.010f, 0.011f, 0.013f)
        val NEUTRAL = floatArrayOf(0.55f, 0.57f, 0.60f, 1.0f)
        val SELECTED = floatArrayOf(0.13f, 0.46f, 0.74f)

        /**
         * Glass: a light, faintly cool sheet at the opacity [GlassPresentation]
         * fixes, blended over whatever stands behind it.
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
         * The edge overlay, as premultiplied linear RGBA.
         *
         * Brighter than any surface can be lit, so an outline always separates
         * from the face it bounds rather than disappearing into whichever wall
         * happens to be catching the sun. The removed-layer lines are the same
         * hue at about a third of the strength — clearly readable against both
         * the background and a lit wall, because the whole job of a removed
         * layer is to still be seen, but far enough below the present lines that
         * no reviewer could mistake one for the other.
         */
        val EDGE = floatArrayOf(0.86f, 0.90f, 0.96f, 0.90f)
        val GHOST_EDGE = floatArrayOf(0.34f, 0.39f, 0.48f, 0.55f)

        /**
         * The reference plane, well below every line the model draws.
         *
         * Quieter than the faintest removed-layer line on purpose: the grid has
         * to be readable as a ruled plane and must never be mistaken for part of
         * the building. The emphasised lines are roughly twice the fine ones,
         * which is enough to count by and not enough to notice on its own.
         */
        val GRID_MINOR = floatArrayOf(0.10f, 0.12f, 0.15f, 0.42f)
        val GRID_MAJOR = floatArrayOf(0.19f, 0.22f, 0.28f, 0.60f)

        /**
         * How far behind itself a lit surface is pushed so its own outline wins
         * the depth test. Positive is away from the camera in Filament's
         * convention, and one unit of each term is the smallest offset that is
         * reliable across drivers without the line detaching visibly.
         */
        const val SURFACE_DEPTH_OFFSET = 1.0f

        /**
         * Lighting in Filament's physical units, balanced against the camera
         * exposure below.
         *
         * These are far below real daylight on purpose. A technical model wants
         * a legible mid-grey with a visible difference between a lit and an
         * unlit face; exposing it like an actual sunlit building clips every
         * surface to white and throws away exactly the shading that makes one
         * wall read as separate from the next. The ambient term is a fifth of
         * the key, which is enough to keep north-facing walls off black.
         */
        const val SUN_LUX = 45_000.0f
        const val AMBIENT_LUX = 6_000.0f

        const val APERTURE = 16.0f
        const val SHUTTER_SPEED = 1.0f / 125.0f
        const val SENSITIVITY = 100.0f

        const val POSITION_STRIDE_BYTES = 12
        const val TANGENT_STRIDE_BYTES = 16
    }
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
