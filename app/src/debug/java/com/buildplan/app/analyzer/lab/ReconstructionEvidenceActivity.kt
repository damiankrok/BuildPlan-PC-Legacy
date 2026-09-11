package com.buildplan.app.analyzer.lab

import android.os.Bundle
import android.util.Log
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import com.buildplan.app.analyzer.snapshot.SnapshotCodec
import com.buildplan.app.render.filament.DebugModel
import com.buildplan.app.render.filament.FilamentCanvas
import com.buildplan.app.render.filament.OrbitCameraState
import com.buildplan.app.render.filament.SceneModel
import com.buildplan.app.render.filament.SpikeVisibility
import com.buildplan.app.render.filament.rememberModelScene
import com.buildplan.app.ui.theme.BuildPlanTheme
import com.buildplan.app.ui.components.AutomaticHouseCanvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Debug-only replay of real analyzer snapshots through the product preview adapter. */
class ReconstructionEvidenceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val candidate = requireNotNull(SnapshotCodec.read(File(filesDir, "evidence-snapshot.json").readText()).candidate)
        if (intent.getBooleanExtra("software", false)) {
            setContent { BuildPlanTheme { AutomaticHouseCanvas(candidate, Modifier.fillMaxSize()) } }
            return
        }
        val baseline = requireNotNull(BaselineCandidatePreview.of(candidate))
        val model: SceneModel = if (intent.getBooleanExtra("reference", false)) DebugModel.MARCOWKI
            else if (intent.getBooleanExtra("baselineAdapter", false)) baseline else requireNotNull(CandidatePreview.of(candidate))
        val view = intent.getIntExtra("view", 0).coerceIn(0, 7)
        val captureAll = intent.getBooleanExtra("captureAll", false)
        setContent {
            BuildPlanTheme {
                val scene = rememberModelScene(model)
                if (scene != null) {
                    LaunchedEffect(scene, scene.renderer) {
                        if (scene.renderer != null) {
                            var readyAttempts = 0
                            while (scene.renderer?.readyForEvidence != true && readyAttempts++ < 120) delay(1000)
                            check(scene.renderer?.readyForEvidence == true) { "Renderer shaders did not become ready" }
                            val views = if (captureAll) (0..7).toList() else listOf(view)
                            for (index in views) {
                                val yaw = listOf(0.0, 180.0, 90.0, 270.0, 35.0, 215.0, 0.0, 35.0)[index]
                                val pitch = if (index == 6) 85.0 else if (index >= 4) 25.0 else 0.0
                                val comparisonBounds = if (intent.getBooleanExtra("fixedCamera", false)) requireNotNull(baseline.geometry.bounds) else scene.bounds
                                val frame = OrbitCameraState.framing(comparisonBounds, yaw, pitch, 1.15).copy(
                                    focusX = (comparisonBounds.center.x - scene.bounds.center.x).toFloat(),
                                    focusY = (comparisonBounds.center.y - scene.bounds.center.y).toFloat(),
                                    focusZ = (comparisonBounds.center.z - scene.bounds.center.z).toFloat())
                                scene.cameraState.apply(frame)
                                scene.showVisibility(if (index == 7) SpikeVisibility.ROOF_HIDDEN else SpikeVisibility.EVERYTHING)
                                scene.pushPose()
                                delay(4000)
                                val name = "${if (model == DebugModel.MARCOWKI) "reference" else "candidate"}-$index"
                                File(filesDir, "$name-camera.txt").writeText("$frame\nprimitives=${model.geometry.primitives.size}")
                                val surface = findSurface(window.decorView)
                                if (surface != null && surface.width > 0 && surface.height > 0) {
                                    val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
                                    var result = PixelCopy.ERROR_SOURCE_NO_DATA
                                    var attempts = 0
                                    while (result != PixelCopy.SUCCESS && attempts++ < 20) {
                                        result = suspendCancellableCoroutine { continuation ->
                                            if (!surface.holder.surface.isValid) continuation.resume(PixelCopy.ERROR_SOURCE_INVALID)
                                            else try {
                                                PixelCopy.request(surface, bitmap, { code -> if (continuation.isActive) continuation.resume(code) }, Handler(Looper.getMainLooper()))
                                            } catch (_: IllegalArgumentException) { if (continuation.isActive) continuation.resume(PixelCopy.ERROR_SOURCE_INVALID) }
                                        }
                                        if (result != PixelCopy.SUCCESS) delay(2000)
                                    }
                                    if (result == PixelCopy.SUCCESS) File(filesDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                    bitmap.recycle()
                                    Log.i("ReconstructionEvidence", "$name capture=$result frame=$frame")
                                }
                            }
                            Log.i("ReconstructionEvidence", "COMPLETE")
                        }
                    }
                    FilamentCanvas(scene)
                }
            }
        }
    }

    private fun findSurface(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findSurface(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
