package com.buildplan.app.analyzer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * AN024-RELEASE — what STAGE-024 let into the shipped app, and what it did not.
 *
 * Moving `:analyzer` onto the release path is the point of the stage, and it
 * is also the moment three things could ride along unnoticed: the debug Lab,
 * the evaluation fixtures, and the expected values of the two benchmark
 * houses. The first would put evidence tooling in front of an owner; the
 * second and third would put a specific house's numbers inside a general
 * analyzer, which is the failure `AnalyzerPurityTest` exists to prevent and
 * this one keeps out of the app module too.
 *
 * Source-level rather than APK-level on purpose: this runs on every build,
 * where an APK check runs when someone remembers. The packaged artefact is
 * checked separately, by hand, against the release APK.
 */
class ReleaseBoundaryTest {

    private val appRoot = File("src")

    private fun sources(vararg sourceSets: String): List<File> = sourceSets
        .map { File(appRoot, it) }
        .filter { it.isDirectory }
        .flatMap { it.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList() }

    @Test
    fun `the production source set exists and is what is under test`() {
        assertTrue("app sources are where this test expects them: ${appRoot.absolutePath}", File(appRoot, "main").isDirectory)
        assertTrue("the debug source set still exists", File(appRoot, "debug").isDirectory)
    }

    @Test
    fun `production code never reaches into the debug Analyzer Lab or the renderer spike`() {
        val forbidden = listOf(
            "com.buildplan.app.analyzer.lab",
            "com.buildplan.app.render.filament",
            "com.buildplan.app.reference",
            "com.buildplan.app.geometry.demo",
        )
        val violations = sources("main").flatMap { file ->
            file.readLines()
                .filter { it.startsWith("import ") }
                .map { it.removePrefix("import ").trim() }
                .mapNotNull { imported -> forbidden.firstOrNull { imported.startsWith(it) }?.let { "${file.name} imports $imported" } }
        }
        if (violations.isNotEmpty()) fail(violations.joinToString("\n"))
    }

    @Test
    fun `no evaluation harness or benchmark project key reaches production code`() {
        // Unambiguous tokens only, checked across the whole production source set. A project key
        // and an evidence-directory variable cannot turn up in prose by accident: either is a
        // benchmark leaking into the shipped app.
        val forbiddenAnywhere = listOf(
            "m2fa281446a8ca", "md6a1fa1493ad8",
            "BUILDPLAN_ANALYZER_EVIDENCE_DIR", "BUILDPLAN_ANALYZER_LIVE",
            "EvaluationProjects", "EvidenceHarness",
        )
        val violations = sources("main").flatMap { file ->
            val text = file.readText()
            forbiddenAnywhere.filter { text.contains(it) }.map { "${file.name} contains '$it'" }
        }
        if (violations.isNotEmpty()) fail(violations.joinToString("\n"))
    }

    @Test
    fun `the analyzer's production wiring knows nothing about any particular house`() {
        // The stricter check, over the code that actually drives the analyzer. Elsewhere in the
        // app a comment may legitimately name the reference project — the debug reference model
        // is a real thing and it is discussed in prose. Here it may not: a general analyzer that
        // carries one house's names or numbers has stopped being general, and this is the file
        // where that would first appear as a convenience.
        val forbiddenTokens = listOf(
            "marcowk", "marcówk", "wiazowc", "wiązowc",
            "131.16", "150.83", "150.57", "246.7", "8.27", "8.49",
            "archon.pl/projekty-domow",
        )
        val wiring = sources("main").filter { file ->
            val path = file.path.replace('\\', '/')
            path.contains("/com/buildplan/app/analyzer/") || file.name.startsWith("ProjectImport") || file.name.startsWith("Verification")
        }
        assertTrue("this test must actually have files to check", wiring.isNotEmpty())
        val violations = wiring.flatMap { file ->
            val text = file.readText().lowercase()
            forbiddenTokens.filter { text.contains(it.lowercase()) }.map { "${file.name} contains '$it'" }
        }
        if (violations.isNotEmpty()) fail(violations.joinToString("\n"))
    }

    /**
     * The rule the whole stage rests on: a candidate is a proposal, and a
     * proposal that can write itself into the model is not a proposal.
     *
     * Checked as a dependency rather than as an intention, because the way
     * this gets broken is not a decision — it is one convenient call added to
     * a screen that already has the data in front of it. If the analyzer's
     * production path cannot name a domain entity, it cannot construct one,
     * and it certainly cannot cost one.
     */
    @Test
    fun `the analyzer's production path cannot reach the canonical model or the budget`() {
        val wiring = sources("main").filter { file ->
            val path = file.path.replace('\\', '/')
            path.contains("/com/buildplan/app/analyzer/") || file.name.startsWith("ProjectImport") || file.name.startsWith("Verification")
        }
        assertTrue("this test must actually have files to check", wiring.isNotEmpty())
        val violations = wiring.flatMap { file ->
            file.readLines()
                .filter { it.startsWith("import ") }
                .map { it.removePrefix("import ").trim() }
                // STAGE-025A authorizes an actual release preview. This one pure adapter
                // creates a temporary cand-* Building; it cannot write or promote it.
                .filter { !file.path.replace('\\', '/').endsWith("/analyzer/preview/CandidateGeometry.kt") && (it.startsWith("com.buildplan.app.domain") || it.startsWith("com.buildplan.app.geometry")) }
                .map { "${file.name} can reach the canonical model: $it" }
        }
        if (violations.isNotEmpty()) fail(violations.joinToString("\n"))
    }

    @Test
    fun `release preview is a read-only candidate adapter with isolated identifiers`() {
        val adapter = File("src/main/java/com/buildplan/app/analyzer/preview/CandidateGeometry.kt").readText()
        assertTrue(adapter.contains("BuildingId(\"cand-building\")"))
        assertTrue(adapter.contains("BuildingElementId(\"cand-\$id\")"))
        val forbidden = listOf("Repository", "CostEngine", "Budget", "SharedPreferences", "File(", "android.content", "reference.")
        forbidden.forEach { assertTrue("Preview must not persist/promote: $it", !adapter.contains(it)) }
    }

    @Test
    fun `the Lab activity is declared only in the debug manifest`() {
        val main = File("src/main/AndroidManifest.xml").readText()
        val debug = File("src/debug/AndroidManifest.xml").readText()
        assertTrue("the Lab must not be in the release manifest", !main.contains("AnalyzerLabActivity"))
        assertTrue("the Lab is still reachable in debug", debug.contains("AnalyzerLabActivity"))
        assertTrue(
            "a release build must have exactly one launcher entry",
            main.split("android.intent.category.LAUNCHER").size - 1 == 1,
        )
    }

    @Test
    fun `the release manifest asks for INTERNET and nothing more`() {
        val main = File("src/main/AndroidManifest.xml").readText()
        assertTrue("project import needs network access", main.contains("android.permission.INTERNET"))

        val permissions = Regex("""android:name="(android\.permission\.[A-Z_]+)"""").findAll(main).map { it.groupValues[1] }.toSet()
        // Nothing here writes outside app-private storage, reads a contact or knows where the
        // device is, and each of those would be a question an owner is entitled to ask about.
        val expected = setOf("android.permission.INTERNET")
        if (permissions != expected) fail("release permissions are $permissions, expected $expected")

        val debug = File("src/debug/AndroidManifest.xml").readText()
        assertTrue("INTERNET is declared once, in main", !debug.contains("android.permission.INTERNET"))
    }

    @Test
    fun `the Lab's strings stay in the debug resources`() {
        val main = File("src/main/res/values/strings.xml").readText()
        assertTrue(!main.contains("lab_"), "no Lab string may reach the release resources")
        val debug = File("src/debug/res/values/strings.xml").readText()
        assertTrue(debug.contains("lab_launcher_label"))
    }

    @Test
    fun `the analyzer is a production dependency and the Lab is not`() {
        val build = File("build.gradle.kts").readText()
        assertTrue(
            "the analyzer module must be on the release path",
            build.contains("implementation(project(\":analyzer\"))"),
        )
        assertTrue(
            "and must not also be declared debug-only",
            !build.contains("debugImplementation(project(\":analyzer\"))"),
        )
        assertTrue(
            "the renderer spike stays debug-only",
            build.contains("debugImplementation(libs.filament.android)") &&
                !build.contains("\n    implementation(libs.filament.android)"),
        )
    }

    private fun assertTrue(condition: Boolean, message: String) = assertTrue(message, condition)
}
