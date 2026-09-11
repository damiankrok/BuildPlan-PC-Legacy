package com.buildplan.app.analyzer.reconstruction

import com.buildplan.app.analyzer.candidate.*
import com.buildplan.app.analyzer.reconstruction.internal.PlanarTopology

internal object StairTopologySolver {
    fun polygon(b:Box)=Polygon(listOf(Pt(b.minX,b.minZ),Pt(b.maxX,b.minZ),Pt(b.maxX,b.maxZ),Pt(b.minX,b.maxZ)))
    fun resolve(c:ProjectAnalysisCandidate):List<StairTopologyCandidate> = c.stairs.map { s ->
        val diagnostics=mutableListOf<String>()
        val flights=s.flights.map(::polygon).filter(PlanarTopology::valid)
        val shaft=c.room(s.roomId.orEmpty())?.polygon ?: polygon(s.zone)
        val from=c.floor(s.fromFloorId ?: s.floorId); val to=c.floor(s.toFloorId.orEmpty())
        if(to==null) diagnostics+="No corroborated floor transition"
        if(flights.isEmpty()) diagnostics+="No drawn flights recovered"
        if(!s.direction.startsWith("up-")) diagnostics+="Ascent direction unresolved; no arbitrary up/down geometry accepted"
        val inner=flights.flatMap { PlanarTopology.inset(it,0.05) }
        val collisions=c.walls.filter { it.floorId==s.floorId && !it.touchesOutside }.filter { w ->
            val d=w.centreline.b-w.centreline.a
            val len=w.centreline.length
            if(len<0.01) false else {
                val n=Pt(-d.z,d.x)*((w.thickness.value ?: 0.12)/2/len)
                val wall=Polygon(listOf(w.centreline.a+n,w.centreline.b+n,w.centreline.b-n,w.centreline.a-n))
                inner.any { PlanarTopology.intersection(it,wall).sumOf { p->p.area }>0.02 }
            }
        }
        if(collisions.isNotEmpty()) diagnostics+="Flight/wall collision: ${collisions.joinToString { it.id }}"
        val supported=from!=null && to!=null && to.order==from.order+1 && flights.isNotEmpty() && collisions.isEmpty()
        val openings=if(supported) flights.flatMap { p -> to!!.footprint?.let { PlanarTopology.intersection(p,it) }.orEmpty() } else emptyList()
        var landings=listOf(shaft)
        flights.forEach { p -> landings=landings.flatMap { PlanarTopology.difference(it,p) } }
        StairTopologyCandidate(s.id,from?.id,to?.id,shaft,flights,landings,openings,s.direction,supported,diagnostics)
    }
}
