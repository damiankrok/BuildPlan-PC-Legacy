package com.buildplan.app.ui.navigation

import androidx.annotation.StringRes
import com.buildplan.app.R

/**
 * The fixed set of top-level sections of the product.
 *
 * Routes use stable English slugs; every user-facing label is a Polish string
 * resource. This is a navigation fact, not a data model — no domain entities
 * have been designed yet.
 */
enum class AppSection(
    val route: String,
    @get:StringRes val labelRes: Int,
    @get:StringRes val summaryRes: Int,
) {
    Dashboard("dashboard", R.string.section_dashboard, R.string.section_dashboard_summary),
    Model("model", R.string.section_model, R.string.section_model_summary),
    Timeline("timeline", R.string.section_timeline, R.string.section_timeline_summary),
    Costs("costs", R.string.section_costs, R.string.section_costs_summary),
    Documents("documents", R.string.section_documents, R.string.section_documents_summary),
    Statistics("statistics", R.string.section_statistics, R.string.section_statistics_summary),
    Budget("budget", R.string.section_budget, R.string.section_budget_summary),
    Market("market", R.string.section_market, R.string.section_market_summary),
    Settings("settings", R.string.section_settings, R.string.section_settings_summary),
    ;

    companion object {
        /** Section shown when the app starts. */
        val Start: AppSection = Dashboard

        /** Resolves a navigation route back to its section, falling back to [Start]. */
        fun fromRoute(route: String?): AppSection =
            entries.firstOrNull { it.route == route } ?: Start
    }
}
