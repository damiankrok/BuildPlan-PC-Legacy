package com.buildplan.app.analyzer.plan

import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.site.PublishedRoom
import com.buildplan.app.analyzer.site.RoomKind
import kotlin.math.abs
import kotlin.math.ln

/**
 * Global assignment of published rooms to disjoint plan regions or connected groups.
 * Area is constrained by its source semantics (floor versus usable attic area).
 * Gate, stair and circulation cues affect costs. A bounded beam retains competing
 * assignments; unresolved ties and runner-up information are kept internally.
 * No architecture answer is consumed by this matcher.
 */
object RoomMatcher {

    /** A room that also fitted the regions a match took, kept so the choice can be questioned. */
    data class Alternative(val roomIndex: Int, val roomName: String, val publishedArea: Double, val relativeError: Double, val why: String)

    data class Match(
        val regionIndices: List<Int>,
        val roomIndices: List<Int>,
        val fidelity: FactFidelity,
        val note: String,
        /** Rooms that fit these regions nearly as well, best first; empty when the fit was unique. */
        val alternatives: List<Alternative> = emptyList(),
        /** Which signals decided this match, in the order they were applied. */
        val signals: List<String> = emptyList(),
    )

    data class Result(val matches: List<Match>, val unmatchedRegions: List<Int>, val unmatchedRooms: List<Int>, val ambiguities: List<String>)

    private data class Target(val index: Int, val area: Double, val oneSided: Boolean)

    /**
     * What a region is next to on the plan, independent of any room name.
     *
     * [CIRCULATION_HUB] is the structural half of "this is the hall": the
     * region the most other regions open onto. A house has one or two of them
     * per storey and they are the circulation, whatever the table calls them.
     * Like every cue it is a preference and never a veto — a hall and a
     * bedroom of the same published area stay ambiguous unless a cue picks
     * one, and the ambiguity is reported either way.
     */
    enum class RegionCue { GATE, STAIR, CIRCULATION_HUB }

    /** Regions that the most others open onto, as a cue map to merge into the caller's. */
    fun circulationCues(regionCount: Int, adjacency: List<Pair<Int, Int>>, minDegree: Int = 3): Map<Int, Set<RegionCue>> {
        if (regionCount == 0) return emptyMap()
        val degree = IntArray(regionCount)
        adjacency.forEach { (a, b) ->
            if (a in 0 until regionCount) degree[a]++
            if (b in 0 until regionCount) degree[b]++
        }
        val best = degree.max()
        if (best < minDegree) return emptyMap()
        return degree.indices.filter { degree[it] == best }.associateWith { setOf(RegionCue.CIRCULATION_HUB) }
    }

    fun match(
        regionAreasM2: List<Double>,
        /** Pairs of region indices that touch across a sealed gap (a virtual line, not a wall). */
        adjacency: List<Pair<Int, Int>>,
        rooms: List<PublishedRoom>,
        attic: Boolean,
        /** Structural cues per region index: a gate-width exterior opening, a stair flight. */
        cues: Map<Int, Set<RegionCue>> = emptyMap(),
        tolerance: Double = 0.12,
        /** For attic rooms compared by usable area: how much larger a region may be (slope strip). */
        atticOneSidedSlack: Double = 0.45,
    ): Result {
        val targets = rooms.mapIndexedNotNull { i, room ->
            val floorArea = room.floorArea.value
            val usable = room.usableArea.value
            when {
                floorArea != null -> Target(i, floorArea, false)
                usable != null -> Target(i, usable, attic)
                else -> null
            }
        }
        val matches = mutableListOf<Match>()
        val usedRooms = mutableSetOf<Int>()
        val usedRegions = mutableSetOf<Int>()
        val ambiguities = mutableListOf<String>()
        val neighbours = HashMap<Int, MutableSet<Int>>()
        adjacency.forEach { (a, b) ->
            neighbours.getOrPut(a) { mutableSetOf() } += b
            neighbours.getOrPut(b) { mutableSetOf() } += a
        }

        fun distance(area: Double, t: Target): Double? {
            if (t.oneSided) {
                val ratio = area / t.area
                return if (ratio >= 1 - tolerance && ratio <= 1 + atticOneSidedSlack) abs(ln(ratio)) else null
            }
            val d = abs(ln(area / t.area))
            return if (d <= -ln(1 - tolerance)) d else null
        }
        fun strong(d: Double) = d <= -ln(1 - 0.05)
        fun hasCue(regions: List<Int>, cue: RegionCue) = regions.any { cue in cues[it].orEmpty() }
        val stairCueExists = cues.values.any { RegionCue.STAIR in it }

        /**
         * A cue is a preference, never a veto on its own: when a garage room has
         * any candidate beside a gate-width opening it takes one of those, and a
         * stair room takes a region with a flight in it when the plan shows one.
         * A wide terrace glazing also passes for a gate, so a gate region is
         * still open to other rooms.
         */
        val hubCueExists = cues.values.any { RegionCue.CIRCULATION_HUB in it }
        fun cueFor(t: Target): RegionCue? = when (rooms[t.index].kind) {
            RoomKind.GARAGE -> RegionCue.GATE
            RoomKind.STAIRS -> if (stairCueExists) RegionCue.STAIR else null
            RoomKind.HALL, RoomKind.VESTIBULE -> if (hubCueExists) RegionCue.CIRCULATION_HUB else null
            else -> null
        }

        // Global bounded assignment over the room/region-group cost matrix. A region
        // can be used once across the complete floor, so an early room cannot greedily
        // steal the only feasible region of another room.
        data class Choice(val target:Target,val regions:List<Int>,val error:Double,val cost:Double)
        data class Assignment(val choices:List<Choice>,val used:Set<Int>,val cost:Double,val missed:Int)
        val allRegions=regionAreasM2.indices.toList()
        val sets=allRegions.map { listOf(it) }+connectedGroups(allRegions,neighbours)
        if(sets.size>=4096) ambiguities+="Region-group enumeration reached its finite 4096-state cap; some alternatives were not evaluated."
        val options=targets.associate { t -> t.index to sets.mapNotNull { set ->
            val error=distance(set.sumOf { regionAreasM2[it] },t) ?: return@mapNotNull null
            val cue=cueFor(t)
            val cueCost=if(cue==null) 0.0 else if(hasCue(set,cue)) -CUE_BONUS else CUE_PENALTY
            Choice(t,set,error,error+GROUP_PENALTY*(set.size-1)+cueCost)
        }.sortedWith(compareBy<Choice> { it.cost }.thenBy { it.regions.joinToString(",") }).take(32) }
        val order=targets.sortedWith(compareBy<Target> { options[it.index]!!.size }.thenBy { it.index })
        var beam=listOf(Assignment(emptyList(),emptySet(),0.0,0))
        val comparator=compareBy<Assignment> { it.missed }.thenBy { it.cost }.thenBy { a->a.choices.joinToString(";") { "${it.target.index}:${it.regions}" } }
        order.forEach { t ->
            beam=beam.flatMap { state ->
                listOf(state.copy(missed=state.missed+1))+options.getValue(t.index).filter { choice -> choice.regions.none { it in state.used } }.map { choice ->
                    Assignment(state.choices+choice,state.used+choice.regions,state.cost+choice.cost,state.missed)
                }
            }.sortedWith(comparator).take(128)
        }
        val best=beam.firstOrNull()
        val runnerUp=beam.drop(1).firstOrNull()
        best?.choices?.forEach { choice ->
            val t=choice.target; val set=choice.regions
            val area=set.sumOf { regionAreasM2[it] }
            val alternatives=targets.filter { it.index!=t.index }.mapNotNull { other -> distance(area,other)?.let { d ->
                Alternative(other.index,rooms[other.index].name,other.area,area/other.area-1,"area fits; global assignment also considers competing regions and topology cues") to d
            } }.sortedBy { it.second }.take(3).map { it.first }
            val alternativeAssignment=runnerUp?.takeIf { it.missed==best.missed && it.cost-best.cost<0.02 }
            val changed=alternativeAssignment!=null && alternativeAssignment.choices.none { it.target.index==t.index && it.regions==set }
            val cue=cueFor(t)
            val settledByCue=cue!=null && hasCue(set,cue) && alternatives.all { cueFor(targets.first { target->target.index==it.roomIndex })!=cue }
            if(changed && !settledByCue) ambiguities+="Globalne przypisanie pomieszczenia ${rooms[t.index].name} ma zbliżony wariant alternatywny; wybór jest deterministyczny, ale niepewny."
            matches+=Match(set,listOf(t.index),if(set.size==1 && strong(choice.error) && (!changed || settledByCue)) FactFidelity.SOURCE_TRACED else FactFidelity.TRACE_UNCERTAIN,
                "global floor assignment; area residual ${area/t.area-1}; cost ${choice.cost}; runner-up margin ${runnerUp?.let { it.cost-best.cost }}",alternatives,
                buildList { add("published area residual ${area/t.area-1}"); add("global exclusive region assignment, beam <=128 and <=32 choices per room")
                    if(set.size>1) add("${set.size} regions joined across wall-line gaps")
                    if(cue!=null) add(if(hasCue(set,cue)) "$cue cue on the region" else "no $cue cue") })
            usedRooms+=t.index; usedRegions+=set
        }

        // Pass 3: open-plan unions for regions still unmatched, largest first.
        val regionOrder = regionAreasM2.indices.sortedByDescending { regionAreasM2[it] }
        for (r in regionOrder) {
            if (r in usedRegions) continue
            val area = regionAreasM2[r]
            val remaining = targets.filter { it.index !in usedRooms }
            val union = bestSubset(area, remaining.map { it.index to it.area }, tolerance) ?: continue
            if (union.size < 2) continue
            matches += Match(listOf(r), union, FactFidelity.TRACE_UNCERTAIN, "region ${"%.2f".format(java.util.Locale.ROOT, area)} m2 spans rooms ${union.joinToString { rooms[it].name }} drawn without walls between them")
            ambiguities += "Region ${r + 1} (${"%.2f".format(java.util.Locale.ROOT, area)} m2) obejmuje pomieszczenia ${union.joinToString { rooms[it].name }} bez ścian między nimi; podział jest założeniem."
            usedRooms += union
            usedRegions += r
        }

        return Result(
            matches = matches.sortedBy { it.regionIndices.first() },
            unmatchedRegions = regionAreasM2.indices.filter { it !in usedRegions },
            unmatchedRooms = rooms.indices.filter { it !in usedRooms },
            ambiguities = ambiguities,
        )
    }

    /** A cue found where a room's kind expects one is worth this much off the cost; missing, this much on. */
    private const val CUE_BONUS = 0.03
    // Within the admissible 12% area band, a drawn structural cue should beat a
    // slightly smaller area residual. It still cannot admit an out-of-band room.
    private const val CUE_PENALTY = 0.15

    /** A group must fit this much better (in log-ratio) than a single to be preferred over it. */
    private const val GROUP_PENALTY = 0.02

    /** Regions a room may be joined from; six covers a living room split by several wall lines. */
    private const val MAX_GROUP = 6

    /** Every connected set of two to [MAX_GROUP] free regions, connected through gap adjacency. */
    private fun connectedGroups(free: List<Int>, neighbours: Map<Int, Set<Int>>): List<List<Int>> {
        val seen=linkedSetOf<List<Int>>()
        val pending=ArrayDeque<List<Int>>()
        free.sorted().forEach { val group=listOf(it); seen+=group; pending.add(group) }
        while(pending.isNotEmpty() && seen.size<4096) {
            val group=pending.removeFirst()
            if(group.size>=MAX_GROUP) continue
            val frontier=group.flatMap { neighbours[it].orEmpty() }.filter { it in free && it !in group }.distinct().sorted()
            for(next in frontier) {
                if(seen.size>=4096) break
                val expanded=(group+next).sorted()
                if(seen.add(expanded)) pending.add(expanded)
            }
        }
        return seen.filter { it.size>=2 }
    }

    /** The subset of rooms (by index) whose areas sum closest to [target] within [tolerance]; up to 4 rooms. */
    private fun bestSubset(target: Double, rooms: List<Pair<Int, Double>>, tolerance: Double): List<Int>? {
        var best: List<Int>? = null
        var bestError = Double.MAX_VALUE
        val n = minOf(rooms.size, 14)
        if (n == 0) return null
        for (mask in 1 until (1 shl n)) {
            if (Integer.bitCount(mask) > 4) continue
            var sum = 0.0
            for (i in 0 until n) if (mask and (1 shl i) != 0) sum += rooms[i].second
            val error = abs(sum - target) / target
            if (error < bestError) {
                bestError = error
                best = (0 until n).filter { mask and (1 shl it) != 0 }.map { rooms[it].first }
            }
        }
        return if (bestError <= tolerance) best else null
    }
}
