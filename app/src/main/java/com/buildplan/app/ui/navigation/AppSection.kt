package com.buildplan.app.ui.navigation

import androidx.annotation.StringRes
import com.buildplan.app.R

/**
 * How the drawer groups the sections: the project itself, its money, and the
 * material around it. Settings stand alone at the foot of the drawer and have
 * no group.
 *
 * A navigation fact, not a data model: it says where a destination is listed,
 * never what it contains.
 */
enum class AppSectionGroup(@get:StringRes val labelRes: Int) {
    Project(R.string.nav_group_project),
    Finance(R.string.nav_group_finance),
    Resources(R.string.nav_group_resources),
}

/**
 * The fixed set of top-level sections of the product.
 *
 * Routes use stable English slugs; every user-facing label is a Polish string
 * resource. This is a navigation fact, not a data model — no domain entities
 * have been designed yet.
 *
 * [Home] is the workspace: the house itself, with the timeline under it and
 * the model's tools at its edge. There is no separate model section — the
 * house is not content reached from the start screen, it is the start
 * screen — so the drawer is the launcher for everything that is not the
 * house.
 */
enum class AppSection(
    val route: String,
    @get:StringRes val labelRes: Int,
    @get:StringRes val summaryRes: Int,
    /** Where the drawer lists this section; null for the one that stands alone. */
    val group: AppSectionGroup?,
) {
    Home("home", R.string.section_home, R.string.section_home_summary, AppSectionGroup.Project),
    Timeline("timeline", R.string.section_timeline, R.string.section_timeline_summary, AppSectionGroup.Project),
    Costs("costs", R.string.section_costs, R.string.section_costs_summary, AppSectionGroup.Finance),
    Budget("budget", R.string.section_budget, R.string.section_budget_summary, AppSectionGroup.Finance),
    Statistics("statistics", R.string.section_statistics, R.string.section_statistics_summary, AppSectionGroup.Finance),
    Documents("documents", R.string.section_documents, R.string.section_documents_summary, AppSectionGroup.Resources),
    Market("market", R.string.section_market, R.string.section_market_summary, AppSectionGroup.Resources),
    Settings("settings", R.string.section_settings, R.string.section_settings_summary, null),
    ;

    companion object {
        /** Section shown when the app starts. */
        val Start: AppSection = Home

        /** Resolves a navigation route back to its section, falling back to [Start]. */
        fun fromRoute(route: String?): AppSection =
            entries.firstOrNull { it.route == route } ?: Start

        /** The sections of one drawer group, in drawer order. */
        fun inGroup(group: AppSectionGroup): List<AppSection> = entries.filter { it.group == group }

        /** The sections listed at the foot of the drawer, outside every group. */
        val ungrouped: List<AppSection> get() = entries.filter { it.group == null }
    }
}
