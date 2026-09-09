package com.buildplan.app.analyzer.evaluation

/**
 * The two evaluation projects of STAGE-023A and the public benchmarks the
 * coordinator observed for them.
 *
 * **Test truth only.** These values validate analyzer output; nothing in
 * `src/main` reads them, and `AnalyzerPurityTest` checks that no project
 * name, key or coordinate from here appears in the analyzer core. The live
 * page is re-read on every evaluation run and its values win over the
 * numbers below whenever they differ.
 */
object EvaluationProjects {

    data class Project(
        val label: String,
        val ownerUrl: String,
        val projectKey: String,
        val expectedCanonicalUrl: String,
        /** Coordinator-observed page/cost-page values, for the reconciliation table. Verified live. */
        val observed: Map<String, Double>,
    )

    val A = Project(
        label = "A",
        ownerUrl = "https://www.archon.pl/projekty-domow/projekt-dom-w-marcowkach-ge-m2fa281446a8ca",
        projectKey = "m2fa281446a8ca",
        expectedCanonicalUrl = "https://www.archon.pl/projekty-domow/projekt-dom-w-marcowkach-ge-m2fa281446a8ca",
        observed = mapOf(
            "footprint" to 131.16,
            "roof" to 150.57,
            "height" to 8.27,
            "foundationWalls" to 42.70,
            "externalWalls" to 136.00,
            "internalLoadBearing" to 25.90,
            "partitionsGround" to 50.90,
            "partitionsUpper" to 79.60,
            "floorsAndStairs" to 170.62,
            "joinery" to 41.53,
            "facade" to 225.90,
        ),
    )

    val B = Project(
        label = "B",
        ownerUrl = "https://www.archon.pl/projekty-domow/projekt-dom-pod-wiazowcem-n-ver-2-md6a1fa1493ad8to",
        projectKey = "md6a1fa1493ad8",
        expectedCanonicalUrl = "https://www.archon.pl/projekty-domow/projekt-dom-pod-wiazowcem-n-ver-2-md6a1fa1493ad8",
        observed = mapOf(
            "footprint" to 150.83,
            "roof" to 246.70,
            "height" to 8.49,
            "foundationWalls" to 57.60,
            "externalWalls" to 142.30,
            "internalLoadBearing" to 57.00,
            "partitionsGround" to 22.60,
            "partitionsUpper" to 83.00,
            "floorsAndStairs" to 226.88,
            "joinery" to 41.24,
            "facade" to 171.60,
        ),
    )

    val all = listOf(A, B)
}
