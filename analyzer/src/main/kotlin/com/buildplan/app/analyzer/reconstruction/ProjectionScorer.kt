package com.buildplan.app.analyzer.reconstruction

import com.buildplan.app.analyzer.candidate.*
import kotlin.math.*

internal data class CameraFit(val yaw:Double,val pitch:Double,val distanceFactor:Double,val silhouetteIoU:Double,val edgeResidual:Double,val rooflineResidual:Double) {
    val score:Double get()=(0.55*silhouetteIoU+0.30*(1-edgeResidual)+0.15*(1-rooflineResidual)).coerceIn(0.0,1.0)
}
internal class ProjectionBudget(val maximum:Int) {
    var used:Int=0; private set
    fun take():Boolean { if(used>=maximum) return false; used++; return true }
}

/** Local software projection of the resolved surfaces. No texture or RGB objective. */
internal object ProjectionScorer {
    fun polygons(c:ProjectAnalysisCandidate):List<List<Pt3>> = buildList {
        val resolved=c.resolvedGeometry?.surfaces.orEmpty().filter { it.exterior && it.kind!=ResolvedSurfaceKind.ROOM_WALL_FACE }
        if(resolved.isNotEmpty()) addAll(resolved.map { it.vertices }) else c.facadeEnvelopes.forEach { f ->
            add(listOf(Pt3(f.segment.a.x,f.baseLevel.value ?: 0.0,f.segment.a.z),Pt3(f.segment.b.x,f.baseLevel.value ?: 0.0,f.segment.b.z))+f.topProfile.reversed())
        }
        if(resolved.none { it.kind==ResolvedSurfaceKind.ROOF }) c.roof?.let { r -> addAll(r.facets.map { it.vertices }); addAll(r.secondaryMasses.flatMap { it.facets }.map { it.vertices }) }
    }
    fun raster(polygons:List<List<Pt3>>,size:Int,yaw:Double,pitch:Double,distanceFactor:Double,targetAspect:Double?=null):BooleanArray {
        if(polygons.isEmpty()) return BooleanArray(size*size)
        val all=polygons.flatten()
        val cx=(all.minOf { it.x }+all.maxOf { it.x })/2
        val cy=(all.minOf { it.y }+all.maxOf { it.y })/2
        val cz=(all.minOf { it.z }+all.maxOf { it.z })/2
        val span=max(max(all.maxOf { it.x }-all.minOf { it.x },all.maxOf { it.z }-all.minOf { it.z }),all.maxOf { it.y }-all.minOf { it.y }).coerceAtLeast(1.0)
        val projected=polygons.map { polygon->polygon.map { p ->
            val x=p.x-cx; val y=p.y-cy; val z=p.z-cz
            val u=x*cos(yaw)-z*sin(yaw); val depth=x*sin(yaw)+z*cos(yaw)
            val v=y*cos(pitch)-depth*sin(pitch)
            val forward=depth*cos(pitch)+y*sin(pitch)
            val scale=if(distanceFactor==0.0) 1.0 else span*distanceFactor/(span*distanceFactor-forward).coerceAtLeast(span*0.1)
            Pt(u*scale,-v*scale)
        } }
        val points=projected.flatten(); val b=Box.around(points)
        val aspect=b.width/b.depth.coerceAtLeast(1e-6)
        val sx=if(targetAspect==null) 1.0 else min(1.0,aspect/targetAspect)
        val sy=if(targetAspect==null) 1.0 else min(1.0,targetAspect/aspect.coerceAtLeast(1e-6))
        val normalized=projected.map { p -> Polygon(p.map { Pt(((it.x-b.minX)/b.width.coerceAtLeast(1e-6)*sx+(1-sx)/2)*size,
            ((it.z-b.minZ)/b.depth.coerceAtLeast(1e-6)*sy+1-sy)*size) }) }
        val bits=BooleanArray(size*size)
        normalized.forEach { polygon ->
            val bb=polygon.bounds
            for(y in floor(bb.minZ).toInt().coerceIn(0,size-1)..ceil(bb.maxZ).toInt().coerceIn(0,size-1)) {
                val row=y+0.5
                val intersections=polygon.edges.mapNotNull { e ->
                    if((e.a.z>row)==(e.b.z>row)) null else e.a.x+(row-e.a.z)*(e.b.x-e.a.x)/(e.b.z-e.a.z)
                }.sorted()
                intersections.chunked(2).filter { it.size==2 }.forEach { pair ->
                    val from=ceil(pair[0]-0.5).toInt().coerceIn(0,size); val until=ceil(pair[1]-0.5).toInt().coerceIn(0,size)
                    for(x in from until until) bits[y*size+x]=true
                }
            }
        }
        return bits
    }
    fun iou(a:BooleanArray,b:BooleanArray,excluded:BooleanArray=BooleanArray(a.size)):Double {
        require(a.size==b.size && excluded.size==a.size)
        var intersection=0; var union=0
        for(i in a.indices) if(!excluded[i]) { if(a[i] && b[i]) intersection++; if(a[i] || b[i]) union++ }
        return if(union==0) 0.0 else intersection.toDouble()/union
    }
    private fun edges(mask:BooleanArray,n:Int)=BooleanArray(mask.size) { i ->
        val x=i%n; val y=i/n
        mask[i] && (x==0 || y==0 || x==n-1 || y==n-1 || !mask[i-1] || !mask[i+1] || !mask[i-n] || !mask[i+n])
    }
    /** Two-pass octile distance transform, bounded linear work per raster. */
    private fun distance(mask:BooleanArray,n:Int):DoubleArray {
        val d=DoubleArray(mask.size) { if(mask[it]) 0.0 else n.toDouble()*2 }
        for(y in 0 until n) for(x in 0 until n) { val i=y*n+x
            if(x>0) d[i]=min(d[i],d[i-1]+1); if(y>0) d[i]=min(d[i],d[i-n]+1)
            if(x>0 && y>0) d[i]=min(d[i],d[i-n-1]+sqrt(2.0)); if(x<n-1 && y>0) d[i]=min(d[i],d[i-n+1]+sqrt(2.0)) }
        for(y in n-1 downTo 0) for(x in n-1 downTo 0) { val i=y*n+x
            if(x<n-1) d[i]=min(d[i],d[i+1]+1); if(y<n-1) d[i]=min(d[i],d[i+n]+1)
            if(x<n-1 && y<n-1) d[i]=min(d[i],d[i+n+1]+sqrt(2.0)); if(x>0 && y<n-1) d[i]=min(d[i],d[i+n-1]+sqrt(2.0)) }
        return d
    }
    fun residuals(source:BooleanArray,candidate:BooleanArray,n:Int):Pair<Double,Double> {
        val a=edges(source,n); val b=edges(candidate,n); val da=distance(a,n); val db=distance(b,n)
        var error=0.0; var count=0
        for(i in a.indices) { if(a[i]) { error+=db[i]; count++ }; if(b[i]) { error+=da[i]; count++ } }
        val edge=if(count==0) 1.0 else (error/count/n).coerceIn(0.0,1.0)
        val roof=(0 until n).map { x ->
            val ay=(0 until n).firstOrNull { source[it*n+x] } ?: n
            val by=(0 until n).firstOrNull { candidate[it*n+x] } ?: n
            abs(ay-by).toDouble()/n
        }.average()
        return edge to roof
    }
    fun fit(c:ProjectAnalysisCandidate,asset:VisualAssetEvidence,budget:ProjectionBudget,fixed:CameraFit?=null):CameraFit? {
        val mask=asset.structuralMask ?: return null
        val source=mask.decode(); val excluded=mask.decode(true); val polygons=polygons(c)
        val assignment=c.visual.facades.firstOrNull { it.assetUrl==asset.assetUrl && it.isSettled }
        val yaws=if(assignment!=null) listOf(when(assignment.sides.single()) { FacadeSide.SOUTH->0.0; FacadeSide.WEST->-PI/2; FacadeSide.NORTH->PI; FacadeSide.EAST->PI/2 }) else (0 until 12).map { it*PI/6 }
        val pitches=if(assignment!=null) listOf(0.0) else listOf(0.08,0.25,0.45)
        val distances=if(assignment!=null) listOf(0.0) else listOf(2.0,3.5,6.0)
        var best:CameraFit?=null
        fun evaluate(yaw:Double,pitch:Double,distance:Double) {
            if(!budget.take()) return
            val aspect=asset.silhouette?.let { it.width*asset.widthPx/(it.height*asset.heightPx).coerceAtLeast(1e-6) }
            val raster=raster(polygons,mask.size,yaw,pitch,distance,aspect)
            val (edge,roof)=residuals(source,raster,mask.size)
            val fit=CameraFit(yaw,pitch,distance,iou(source,raster,excluded),edge,roof)
            if(best==null || fit.score>best!!.score) best=fit
        }
        if(fixed!=null) { evaluate(fixed.yaw,fixed.pitch,fixed.distanceFactor); return best }
        yaws.forEach { yaw -> pitches.forEach { pitch -> distances.forEach { evaluate(yaw,pitch,it) } } }
        if(assignment==null) best?.let { coarse -> listOf(-PI/18,0.0,PI/18).forEach { offset -> listOf(-0.06,0.0,0.06).forEach { evaluate(coarse.yaw+offset,(coarse.pitch+it).coerceAtLeast(0.0),coarse.distanceFactor) } } }
        return best
    }
}
