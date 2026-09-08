package com.buildplan.app.render.filament

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.buildplan.app.R
import com.buildplan.app.domain.model.BuildingElement
import com.buildplan.app.domain.model.BuildingElementId
import com.buildplan.app.domain.model.BuildingVisibility
import com.buildplan.app.domain.model.FloorId
import com.buildplan.app.geometry.LocalBounds
import com.buildplan.app.ui.workspace.MotionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Which of the four debug views of the building is on screen.
 *
 * The enum carries no rule of its own: it turns straight into a
 * [BuildingVisibility], and the domain decides from there what survives. Adding
 * "hide the roof" logic here would be the second answer to a question the domain
 * already answers, and the one the user saw would be whichever the renderer
 * happened to consult.
 *
 * It is told which storey counts as the upper one rather than knowing, so that
 * the same four views mean the same thing on whichever model is loaded.
 */
internal enum class SpikeVisibility(val labelRes: Int) {
    EVERYTHING(R.string.model_spike_visibility_all),
    ROOF_HIDDEN(R.string.model_spike_visibility_no_roof),
    UPPER_FLOOR_HIDDEN(R.string.model_spike_visibility_no_upper_floor),
    ROOF_AND_UPPER_FLOOR_HIDDEN(R.string.model_spike_visibility_no_roof_no_upper_floor),
    ;

    fun toBuildingVisibility(atticId: FloorId): BuildingVisibility = when (this) {
        EVERYTHING -> BuildingVisibility.EVERYTHING
        ROOF_HIDDEN -> BuildingVisibility(roofHidden = true)
        UPPER_FLOOR_HIDDEN -> BuildingVisibility(hiddenFloorIds = setOf(atticId))
        ROOF_AND_UPPER_FLOOR_HIDDEN -> BuildingVisibility(
            hiddenFloorIds = setOf(atticId),
            roofHidden = true,
        )
    }
}

/**
 * One loaded model and everything the canvas and the chrome say about it to
 * each other: what is shown, from where, in which presentation, and what is
 * picked.
 *
 * The canvas draws this; the tool rail and the inspector read and change it.
 * Neither knows the other exists. The meshes are baked once here, so the
 * shape the owner reviews is fixed the moment the model is chosen and no
 * control can alter it — a control changes which entities are in the scene
 * and how the camera looks at them, never a vertex.
 *
 * Nothing but the camera is persisted, and the camera only because the user
 * aimed it by hand.
 */
@Stable
internal class ModelScene(
    val model: DebugModel,
    val bounds: LocalBounds,
    val cameraState: OrbitCameraState,
    private val scope: CoroutineScope,
) {
    private var cameraTravel: Job? = null

    /**
     * The primitives' meshes plus the frames round the facade panes: baked
     * from the panes the model's presentation names, carrying the panes' own
     * ids, and uploaded beside the primitives' meshes.
     */
    val meshes: List<BuildingRenderMesh> =
        model.geometry.primitives.toRenderMeshes() + model.geometry.openingFrameMeshes(model.openingFrames)

    /**
     * The roof coverings, laid once over the facets the model's presentation
     * names. They carry the roof's own id, so the visibility path below needs
     * no word about them.
     */
    val roofCovers: List<RoofCoverMesh> = model.geometry.roofCoverMeshes(model.roofCover)

    /** The ruled plane under the model, sized to this model's extent. */
    val grid: PresentationGrid = PresentationGrid.under(bounds)

    val elementsById: Map<BuildingElementId, BuildingElement> = model.building.elements.associateBy { it.id }

    /**
     * The named view the camera was last put in, or null once a layer change
     * has moved the scene away from it. A preset is a camera *and* a
     * visibility; when the visibility is changed on its own the picture no
     * longer matches the name, and a marker left on the name would lie.
     */
    var preset: ModelViewPreset? by mutableStateOf(ModelViewPreset.FULL_AXON)
        private set
    var visibility: SpikeVisibility by mutableStateOf(ModelViewPreset.FULL_AXON.visibility)
        private set
    var style: RenderStyle by mutableStateOf(RenderStyle.DEFAULT)
        private set
    var selected: BuildingElementId? by mutableStateOf(null)
        private set

    /**
     * Where on the canvas the last pick landed, in canvas pixels — a fact for
     * the chrome, so the inspector can grow towards the thing that was
     * touched. Not a scene fact: nothing in the renderer reads it.
     */
    var selectionAnchor: Offset? by mutableStateOf(null)
        private set

    /** The renderer currently drawing this scene, while its surface is on screen. */
    var renderer: FilamentModelRenderer? by mutableStateOf(null)

    /** Whether the renderer has drawn a real frame, so the cover may lift. */
    var modelShown: Boolean by mutableStateOf(false)

    /**
     * The single decision path: the domain says which elements survive, the
     * model's presentation profile narrows that to what the view is meant to
     * expose, the geometry layer's one bridge says which shapes draw them, and
     * their element ids are what the renderer shows.
     */
    val visibleElementIds: Set<BuildingElementId> by derivedStateOf {
        model.visibleElementIds(visibility.toBuildingVisibility(model.atticId))
    }

    /** The picked element, when the id resolves to one. */
    val selectedElement: BuildingElement? get() = selected?.let { elementsById[it] }

    fun applyPreset(option: ModelViewPreset, motion: MotionPolicy) {
        preset = option
        visibility = option.visibility
        travelTo(option.framing(bounds, model.focusBounds(option.focus, bounds)), motion)
    }

    fun showVisibility(option: SpikeVisibility) {
        visibility = option
        if (preset?.visibility != option) preset = null
    }

    fun showStyle(option: RenderStyle) {
        style = option
    }

    fun resetCamera(motion: MotionPolicy) {
        travelTo(OrbitCameraState.frame(bounds), motion)
    }

    /**
     * Takes the camera to [target] along a path rather than in a cut, so the
     * eye keeps hold of which house this is while the view changes. The end
     * state is exactly the framing asked for — a preset stays reproducible —
     * and a finger on the canvas takes over at once; see [interruptCamera].
     */
    private fun travelTo(target: OrbitCameraState.Framing, motion: MotionPolicy) {
        cameraTravel?.cancel()
        if (motion.reduced) {
            cameraState.apply(target)
            pushPose()
            return
        }
        val start = cameraState.framing()
        val share = OrbitCameraState.magnitude(start, target)
        cameraTravel = scope.launch {
            val progress = Animatable(0f)
            progress.animateTo(1f, motion.travel(share)) {
                cameraState.apply(OrbitCameraState.between(start, target, value))
                pushPose()
            }
        }
    }

    /** The user has the camera: whatever path it was on ends where it is. */
    fun interruptCamera() {
        cameraTravel?.cancel()
        cameraTravel = null
    }

    /**
     * The camera has been moved by hand, so the picture no longer matches any
     * named view: the preset marker is cleared for the same reason a layer
     * change clears it. The visibility stays — that half of the preset is
     * still true.
     */
    fun cameraMovedByHand() {
        preset = null
    }

    fun select(elementId: BuildingElementId?, anchor: Offset? = null) {
        selected = elementId
        selectionAnchor = if (elementId == null) null else anchor
    }

    fun clearSelection() = select(null)

    /** Hands the camera's current pose to the renderer, outside composition. */
    fun pushPose() {
        renderer?.pose = cameraState.pose()
    }
}

/**
 * The scene for [model], or null when the model has no geometry to draw.
 * Rebuilt only when the model changes; the camera survives recreation on
 * its own.
 */
@Composable
internal fun rememberModelScene(model: DebugModel): ModelScene? {
    val bounds = remember(model) { model.geometry.bounds } ?: return null
    val cameraState = rememberOrbitCameraState(bounds)
    val scope = rememberCoroutineScope()
    return remember(model, cameraState) { ModelScene(model, bounds, cameraState, scope) }
}
