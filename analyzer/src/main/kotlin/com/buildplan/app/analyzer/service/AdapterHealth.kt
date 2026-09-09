package com.buildplan.app.analyzer.service

import com.buildplan.app.analyzer.asset.RetrievalState
import com.buildplan.app.analyzer.site.RelatedPageRole
import com.buildplan.app.analyzer.site.ScalarKey
import com.buildplan.app.analyzer.site.SourcePackage

/**
 * The facts a site adapter must still be able to find for its reading to mean
 * anything.
 *
 * A page whose markup has moved on does not fail loudly — jsoup selectors
 * that match nothing return nothing, and an adapter that shrugs produces a
 * tidy, almost empty package that looks like a house with no rooms. Naming
 * each signal and checking it is what turns that silence into a statement.
 */
enum class AdapterSignal(val required: Boolean, val what: String) {
    TITLE(required = false, what = "project title"),
    SCALARS(required = true, what = "published parameter blocks"),
    ROOM_TABLES(required = true, what = "per-storey room tables"),
    PLAN_ASSETS(required = true, what = "floor plan drawings"),
    COST_PAGE(required = false, what = "cost calculation page"),
    SITE_TAGS(required = false, what = "structured page tags"),
}

enum class AdapterCheckState { PRESENT, DEGRADED, ABSENT }

data class AdapterCheck(
    val signal: AdapterSignal,
    val state: AdapterCheckState,
    /** What was actually found, in words a reader can check against the page. */
    val evidence: String,
)

/** How badly the page has drifted from what the adapter knows how to read. */
enum class AdapterSeverity { HEALTHY, DEGRADED, BROKEN }

/**
 * The verdict on one reading of one page.
 *
 * [BROKEN] means a *required* signal is gone: the adapter can no longer claim
 * to have read this page, and the service says so rather than handing back a
 * near-empty candidate that would pass for a very small house.
 */
data class AdapterHealth(
    val adapterId: String,
    val adapterVersion: String,
    val checks: List<AdapterCheck>,
) {
    val severity: AdapterSeverity
        get() = when {
            checks.any { it.signal.required && it.state == AdapterCheckState.ABSENT } -> AdapterSeverity.BROKEN
            checks.any { it.state != AdapterCheckState.PRESENT } -> AdapterSeverity.DEGRADED
            else -> AdapterSeverity.HEALTHY
        }

    val absent: List<AdapterCheck> get() = checks.filter { it.state == AdapterCheckState.ABSENT }
    val degraded: List<AdapterCheck> get() = checks.filter { it.state == AdapterCheckState.DEGRADED }

    fun check(signal: AdapterSignal): AdapterCheck? = checks.firstOrNull { it.signal == signal }

    companion object {

        /** The health of a package the adapter has just read. Pure: no network, no pixels. */
        fun of(adapterId: String, adapterVersion: String, source: SourcePackage?): AdapterHealth {
            if (source == null) {
                return AdapterHealth(
                    adapterId,
                    adapterVersion,
                    AdapterSignal.entries.map { AdapterCheck(it, AdapterCheckState.ABSENT, "page was never read") },
                )
            }
            val numeric = source.scalars.count { it.measured.value != null }
            val rooms = source.floors.sumOf { it.rooms.size }
            val plans = source.assets.plans
            val decodedPlans = plans.count { it.retrieval == RetrievalState.DECODED }
            val costPage = source.relatedPages.firstOrNull { it.role == RelatedPageRole.COST_CALCULATION }

            return AdapterHealth(
                adapterId,
                adapterVersion,
                listOf(
                    AdapterCheck(
                        AdapterSignal.TITLE,
                        if (source.title.isNotBlank()) AdapterCheckState.PRESENT else AdapterCheckState.ABSENT,
                        if (source.title.isNotBlank()) "\"${source.title}\"" else "no title element matched",
                    ),
                    AdapterCheck(
                        AdapterSignal.SCALARS,
                        when {
                            numeric == 0 -> AdapterCheckState.ABSENT
                            // The footprint area is the plan's only source-stated calibration anchor.
                            // Without it every traced length rests on a weaker anchor, so the reading
                            // is degraded even when a dozen other numbers came through.
                            source.scalar(ScalarKey.FOOTPRINT_AREA)?.measured?.value == null -> AdapterCheckState.DEGRADED
                            numeric < MIN_HEALTHY_SCALARS -> AdapterCheckState.DEGRADED
                            else -> AdapterCheckState.PRESENT
                        },
                        "$numeric numeric parameters, footprint area " +
                            (source.scalar(ScalarKey.FOOTPRINT_AREA)?.rawValue ?: "absent"),
                    ),
                    AdapterCheck(
                        AdapterSignal.ROOM_TABLES,
                        when {
                            rooms == 0 -> AdapterCheckState.ABSENT
                            source.floors.any { it.rooms.isEmpty() } -> AdapterCheckState.DEGRADED
                            else -> AdapterCheckState.PRESENT
                        },
                        "${source.floors.size} storey table(s), $rooms room row(s)",
                    ),
                    AdapterCheck(
                        AdapterSignal.PLAN_ASSETS,
                        when {
                            plans.isEmpty() -> AdapterCheckState.ABSENT
                            decodedPlans == 0 -> AdapterCheckState.DEGRADED
                            decodedPlans < plans.size -> AdapterCheckState.DEGRADED
                            else -> AdapterCheckState.PRESENT
                        },
                        "${plans.size} plan drawing(s) referenced, $decodedPlans decoded",
                    ),
                    AdapterCheck(
                        AdapterSignal.COST_PAGE,
                        when {
                            costPage == null -> AdapterCheckState.ABSENT
                            costPage.statusCode !in 200..299 -> AdapterCheckState.DEGRADED
                            else -> AdapterCheckState.PRESENT
                        },
                        costPage?.let { "HTTP ${it.statusCode} from ${it.url}" } ?: "no cost page link matched",
                    ),
                    AdapterCheck(
                        AdapterSignal.SITE_TAGS,
                        if (source.siteTags.isEmpty()) AdapterCheckState.ABSENT else AdapterCheckState.PRESENT,
                        "${source.siteTags.size} tag(s)",
                    ),
                ),
            )
        }

        /**
         * Below this many published numbers the page is not the parameter table
         * the adapter was written against. Chosen from the shape of the markup —
         * a project page prints well over a dozen — not from any one project's
         * count, so it stays a structural check rather than a benchmark fit.
         */
        private const val MIN_HEALTHY_SCALARS = 6
    }
}
