package com.buildplan.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSectionTest {

    @Test
    fun everySectionHasAUniqueRoute() {
        val routes = AppSection.entries.map { it.route }

        assertEquals(routes.size, routes.toSet().size)
        assertTrue(routes.none { it.isBlank() })
    }

    @Test
    fun fromRouteResolvesEverySection() {
        AppSection.entries.forEach { section ->
            assertEquals(section, AppSection.fromRoute(section.route))
        }
    }

    @Test
    fun fromRouteFallsBackToStartForUnknownRoutes() {
        assertEquals(AppSection.Start, AppSection.fromRoute(null))
        assertEquals(AppSection.Start, AppSection.fromRoute("not-a-route"))
    }

    @Test
    fun theDrawerListsEverySectionExactlyOnce() {
        val grouped = AppSectionGroup.entries.flatMap { AppSection.inGroup(it) }
        val listed = grouped + AppSection.ungrouped

        assertEquals(AppSection.entries.size, listed.size)
        assertEquals(AppSection.entries.toSet(), listed.toSet())
        AppSectionGroup.entries.forEach { group ->
            assertTrue("Group $group lists nothing", AppSection.inGroup(group).isNotEmpty())
        }
        // Settings stand alone at the foot; the start screen leads the first group.
        assertEquals(listOf(AppSection.Settings), AppSection.ungrouped)
        assertEquals(AppSection.Start, AppSection.inGroup(AppSectionGroup.Project).first())
    }
}
