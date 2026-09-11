package com.buildplan.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import com.buildplan.app.analyzer.candidate.ProjectAnalysisCandidate
import com.buildplan.app.analyzer.preview.CandidateGeometry
import com.buildplan.app.domain.model.BuildingElementKind
import com.buildplan.app.geometry.*
import kotlin.math.*

/** Local orthographic 3D preview with drag orbit; no native renderer or reference data. */
@Composable
internal fun AutomaticHouseCanvas(candidate: ProjectAnalysisCandidate, modifier: Modifier = Modifier) {
    val model = remember(candidate) { CandidateGeometry.of(candidate) } ?: return
    val bounds = model.geometry.bounds ?: return
    var yaw by remember(candidate) { mutableDoubleStateOf(0.6) }
    var pitch by remember(candidate) { mutableDoubleStateOf(0.35) }
    val bitmapHolder = remember { arrayOfNulls<Bitmap>(1) }
    DisposableEffect(Unit) { onDispose { bitmapHolder[0]?.recycle() } }
    data class Face(val vertices: List<ModelPoint>, val color: Color)
    val faces = remember(model) {
        val elements = model.building.elements.associateBy { it.id }
        buildList {
            fun face(vertices: List<ModelPoint>, color: Color) { add(Face(vertices,color)) }
            fun prism(outline: List<PlanPoint>, bottom: Double, top: Double, color: Color) {
                face(outline.map { ModelPoint(it.x,top,it.z) },color)
                outline.indices.forEach { i -> val a=outline[i];val b=outline[(i+1)%outline.size]
                    face(listOf(ModelPoint(a.x,bottom,a.z),ModelPoint(b.x,bottom,b.z),ModelPoint(b.x,top,b.z),ModelPoint(a.x,top,a.z)),color)
                }
            }
            model.geometry.primitives.forEach { primitive ->
                val roof=elements[primitive.elementId]?.kind==BuildingElementKind.ROOF
                val color=if(roof) Color(0xff747c84) else Color(0xffeeeae1)
                when(primitive) {
                    is WallGeometry -> prism(primitive.footprint(),primitive.baseElevation,primitive.topElevation,color)
                    is SlabGeometry -> prism(primitive.outline,primitive.elevation,primitive.topElevation,color)
                    is RoofFacetGeometry -> face(primitive.vertices,color)
                    is GablePanelGeometry -> face(primitive.vertices,color)
                    is OpeningPanelGeometry -> face(primitive.vertices,Color(0xff7493a8))
                }
            }
        }
    }
    Canvas(modifier.pointerInput(candidate) {
        detectDragGestures { change, drag -> change.consume(); yaw += drag.x * 0.008; pitch=(pitch-drag.y*0.006).coerceIn(-0.1,1.35) }
    }) {
        val ratio = min(1.0, 1024.0 / max(size.width,size.height))
        val rasterWidth = max(1, (size.width * ratio).roundToInt())
        val rasterHeight = max(1, (size.height * ratio).roundToInt())
        val center=bounds.center
        val scale=min(size.width,size.height)*0.78 / sqrt(bounds.sizeX.pow(2)+bounds.sizeY.pow(2)+bounds.sizeZ.pow(2)).coerceAtLeast(1.0)
        fun rotated(p:ModelPoint):Triple<Double,Double,Double> {
            val x=p.x-center.x;val y=p.y-center.y;val z=p.z-center.z
            val horizontal=x*cos(yaw)-z*sin(yaw);val depth=x*sin(yaw)+z*cos(yaw)
            return Triple(horizontal,y*cos(pitch)-depth*sin(pitch),depth*cos(pitch)+y*sin(pitch))
        }
        val projected = faces.map { face ->
            val vertices = face.vertices.map { p ->
                val v=rotated(p)
                RasterVertex((size.width/2+v.first*scale)*ratio,(size.height/2-v.second*scale)*ratio,v.third)
            }
            // Gentle orientation shading makes the solid shell readable without wire overlays.
            val a=face.vertices[0]; val b=face.vertices[1]; val c=face.vertices[2]
            val nx=(b.y-a.y)*(c.z-a.z)-(b.z-a.z)*(c.y-a.y)
            val ny=(b.z-a.z)*(c.x-a.x)-(b.x-a.x)*(c.z-a.z)
            val nz=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x)
            val length=sqrt(nx*nx+ny*ny+nz*nz).coerceAtLeast(1e-9)
            val light=(0.82+0.18*abs((0.3*nx+0.8*ny+0.5*nz)/length)).coerceIn(0.0,1.0).toFloat()
            RasterFace(vertices,Color(face.color.red*light,face.color.green*light,face.color.blue*light).toArgb())
        }
        val pixels=DepthRasterizer.render(rasterWidth,rasterHeight,projected,0xffe1e2df.toInt())
        var bitmap=bitmapHolder[0]
        if(bitmap==null || bitmap.width!=rasterWidth || bitmap.height!=rasterHeight) {
            bitmap?.recycle()
            bitmap=Bitmap.createBitmap(rasterWidth,rasterHeight,Bitmap.Config.ARGB_8888)
            bitmapHolder[0]=bitmap
        }
        bitmap.setPixels(pixels,0,rasterWidth,0,0,rasterWidth,rasterHeight)
        drawImage(bitmap.asImageBitmap(),dstSize=IntSize(size.width.toInt(),size.height.toInt()))
    }
}
