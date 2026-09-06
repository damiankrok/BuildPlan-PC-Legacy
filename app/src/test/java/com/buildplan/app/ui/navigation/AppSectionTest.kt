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
}
