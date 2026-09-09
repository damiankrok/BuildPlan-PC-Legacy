package com.buildplan.app.analyzer.plan

import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.site.PublishedRoom
import com.buildplan.app.analyzer.site.RoomKind
import kotlin.math.abs
import kotlin.math.ln

/**
 * Associates enclosed plan regions with the storey's published room table.
 *
 * The only signal available without reading the plan's printed room numbers
 * is area, so the matcher is explicit about what area can and cannot decide.
 * The plan is deliberately *over-segmented* — every wall line is extended
 * across the gaps in it — so a room is usually one region and sometimes a
 * few regions joined across those virtual lines, and only an open plan that
 * the source draws with no wall line at all ends up as one region shared by
 * several rooms.
 *
 * - a region is matched to a room when exactly one unmatched room's published
 *   area is within [tolerance] of the region's, and no second room is close
 *   enough to be confused with it;
 * - an unmatched room is then tried as a *connected* group of unmatched
 *   regions — connected across sealed gaps, never across a wall — whose
 *   areas sum to the room's;
 * - a large region that still matches no single room is tried as the union
 *   of several rooms drawn without walls between them; the rooms are then
 *   reported as sharing that region and flagged uncertain;
 * - two rooms with the same published area (a hall and a bedroom both at
 *   9.18 m2) are an ambiguity: assigned by table order and flagged, with a
 *   question raised.
 *
 * Attic rooms are compared against their published *floor* area when the
 * site prints one, because their usable area excludes the strip under the
 * slope and is not the polygon the plan draws. When only usable area exists
 * the comparison is one-sided: a region may be larger, never smaller.
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

        fun preferred(t: Target, candidates: List<List<Int>>): List<List<Int>> {
            val cue = cueFor(t) ?: return candidates
            val cued = candidates.filter { hasCue(it, cue) }
            return if (cued.isEmpty()) candidates else cued
        }

        // One room at a time, and rooms that structural evidence can *place* go first.
        //
        // Ordering by area alone lets a room matched on nothing but its number take the very
        // region another room can prove it belongs to: on a storey with a 7.78 m2 wardrobe and a
        // 7.05 m2 hall, the wardrobe was settled first and took the region every other room opens
        // onto — leaving the hall, which that region demonstrably is, with nothing. A cue is still
        // never a veto and never overrides an area that does not fit; it only decides who chooses
        // first among rooms that all fit.
        val gateCueExists = cues.values.any { RegionCue.GATE in it }

        // Pass 0: mutually unique singles. A region that fits exactly one room, when that room
        // fits exactly one region, is settled before any larger room can absorb it into a group.
        var settled = true
        while (settled) {
            settled = false
            val freeRegions = regionAreasM2.indices.filter { it !in usedRegions }
            val freeTargets = targets.filter { it.index !in usedRooms }
            for (t in freeTargets) {
                val fits = freeRegions.filter { distance(regionAreasM2[it], t) != null }
                if (fits.size != 1) continue
                val r = fits.single()
                val roomsForRegion = freeTargets.filter { distance(regionAreasM2[r], it) != null }
                if (roomsForRegion.size != 1) continue
                val error = distance(regionAreasM2[r], t)!!
                val fidelity = if (strong(error)) FactFidelity.SOURCE_TRACED else FactFidelity.TRACE_UNCERTAIN
                matches += Match(
                    listOf(r), listOf(t.index), fidelity,
                    "region ${r + 1}: ${"%.2f".format(java.util.Locale.ROOT, regionAreasM2[r])} m2 vs published ${"%.2f".format(java.util.Locale.ROOT, t.area)} m2 (${"%.1f".format(java.util.Locale.ROOT, (regionAreasM2[r] / t.area - 1) * 100)} %); unique fit",
                    signals = listOf(
                        "published area residual ${"%.1f".format(java.util.Locale.ROOT, (regionAreasM2[r] / t.area - 1) * 100)} %",
                        "mutually unique: this room fits only this region and this region fits only this room",
                    ),
                )
                usedRooms += t.index
                usedRegions += r
                settled = true
                break
            }
        }

        // One room at a time, largest first: the best fit among single free regions and connected
        // groups of free regions, so a room split by a wall-line gap is not lost to a loose single
        // match on one of its halves. A garage goes first when the plan shows a gate.
        //
        // Two alternatives were tried against both houses and both reverted. Letting rooms with a
        // structural cue choose first cost Project A a bathroom, and choosing the globally
        // cheapest (room, region) pair each round recovered one room on B while losing a kitchen
        // and two bedrooms on A. Both are still greedy — they only move the bias — and neither
        // earned the regression, so the ordering stays as it is and the ambiguity B's attic
        // really has is reported as ambiguity rather than reshuffled.
        val roomOrder = targets.sortedWith(
            compareByDescending<Target> { gateCueExists && rooms[it.index].kind == RoomKind.GARAGE }.thenByDescending { it.area },
        )
        for (t in roomOrder) {
            if (t.index in usedRooms) continue
            val free = regionAreasM2.indices.filter { it !in usedRegions }
            val singles = free.map { listOf(it) }
            val groups = connectedGroups(free, neighbours)
            val fitting = (singles + groups).mapNotNull { set -> distance(set.sumOf { regionAreasM2[it] }, t)?.let { set to it } }
            if (fitting.isEmpty()) continue
            // Singles win ties against groups; groups need a clearly better fit to be chosen.
            val ranked = preferred(t, fitting.map { it.first }).map { set -> set to fitting.first { it.first == set }.second }
                .sortedWith(compareBy({ it.second + if (it.first.size > 1) GROUP_PENALTY else 0.0 }, { it.first.size }))
            val (set, error) = ranked.first()
            val area = set.sumOf { regionAreasM2[it] }

            // Every other unmatched room these same regions would also fit. Reported whether or not
            // one was close enough to call the match ambiguous, because a reader checking a room
            // needs to see what else it could have been, not only that something else could.
            val alternatives = targets
                .filter { o -> o.index != t.index && o.index !in usedRooms }
                .mapNotNull { o -> distance(area, o)?.let { d -> o to d } }
                .sortedBy { it.second }
                .take(3)
                .map { (o, _) ->
                    val cue = cueFor(o)
                    Alternative(
                        o.index, rooms[o.index].name, o.area, area / o.area - 1,
                        buildString {
                            append("area fits within tolerance")
                            if (cue != null && !hasCue(set, cue)) append("; but these regions carry no $cue cue, which this kind of room expects")
                        },
                    )
                }
            val signals = buildList {
                add("published area residual ${"%.1f".format(java.util.Locale.ROOT, (area / t.area - 1) * 100)} %")
                if (set.size > 1) add("${set.size} regions joined across wall-line gaps")
                cueFor(t)?.let { c -> if (hasCue(set, c)) add("$c cue on the region") else add("no $c cue, which this kind of room expects") }
            }
            // Ambiguity: another unmatched room of almost the same area would fit these regions as well.
            val rival = targets.firstOrNull { o -> o.index != t.index && o.index !in usedRooms && abs(o.area - t.area) / t.area < 0.06 && distance(area, o) != null }
            val label = if (set.size == 1) "region ${set.first() + 1}" else "regions ${set.joinToString { "${it + 1}" }} joined across wall-line gaps"
            val polishLabel = if (set.size == 1) "regionu ${set.first() + 1}" else "regionów ${set.joinToString { "${it + 1}" }}"
            if (rival != null) {
                // A cue the rival lacks and this room has is exactly the second signal that is
                // allowed to settle an area tie; without one the two stay indistinguishable.
                val cue = cueFor(t)
                val settledByCue = cue != null && hasCue(set, cue) && cueFor(rival) != cue
                matches += Match(
                    set, listOf(t.index),
                    if (settledByCue) FactFidelity.SOURCE_TRACED else FactFidelity.TRACE_UNCERTAIN,
                    "$label: ${"%.2f".format(java.util.Locale.ROOT, area)} m2 fits ${rooms[t.index].name} and ${rooms[rival.index].name} (~${"%.2f".format(java.util.Locale.ROOT, t.area)} m2); " +
                        if (settledByCue) "settled by the $cue cue" else "assigned by table order",
                    alternatives, signals,
                )
                if (!settledByCue) {
                    ambiguities += "Pomieszczenia ${rooms[t.index].name} i ${rooms[rival.index].name} mają zbliżoną powierzchnię (${"%.2f".format(java.util.Locale.ROOT, t.area)} m2); przypisanie $polishLabel jest niepewne."
                }
            } else {
                val fidelity = if (set.size == 1 && strong(error)) FactFidelity.SOURCE_TRACED else FactFidelity.TRACE_UNCERTAIN
                matches += Match(
                    set, listOf(t.index), fidelity,
                    "$label: ${"%.2f".format(java.util.Locale.ROOT, area)} m2 vs published ${"%.2f".format(java.util.Locale.ROOT, t.area)} m2 (${"%.1f".format(java.util.Locale.ROOT, (area / t.area - 1) * 100)} %)",
                    alternatives, signals,
                )
            }
            usedRooms += t.index
            usedRegions += set
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
    private const val CUE_PENALTY = 0.02

    /** A group must fit this much better (in log-ratio) than a single to be preferred over it. */
    private const val GROUP_PENALTY = 0.02

    /** Regions a room may be joined from; six covers a living room split by several wall lines. */
    private const val MAX_GROUP = 6

    /** Every connected set of two to [MAX_GROUP] free regions, connected through gap adjacency. */
    private fun connectedGroups(free: List<Int>, neighbours: Map<Int, Set<Int>>): List<List<Int>> {
        val out = LinkedHashSet<List<Int>>()
        fun grow(group: List<Int>) {
            if (group.size >= 2) out += group.sorted()
            if (group.size >= MAX_GROUP) return
            val frontier = group.flatMap { neighbours[it].orEmpty() }.filter { it in free && it !in group }.distinct()
            for (next in frontier) grow(group + next)
        }
        free.forEach { grow(listOf(it)) }
        return out.toList()
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
