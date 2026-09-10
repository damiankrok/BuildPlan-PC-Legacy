package com.buildplan.app.analyzer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * AN024-API — the app talks to the analyzer through its service boundary and
 * not around it.
 *
 * The analyzer is a research prototype whose insides move: raster thresholds,
 * the skeleton solver, the glyph recogniser and every ARCHON selector are
 * expected to be rewritten. The boundary in `service/` is the promise that
 * survives that, and a boundary nobody is required to use is decoration.
 *
 * So the app may import the service, the platform seams it has to implement,
 * the value types the report is made of — and nothing else.
 */
class AnalyzerApiSurfaceTest {

    /**
     * Packages of `:analyzer` the application is allowed to name.
     *
     * `service` and `cache` are the boundary. `candidate`, `quantity`,
     * `fidelity`, `site`, `snapshot`, `source` and `validate` are the value
     * types a report is *made of* — a caller that reads a `Measured` has to
     * name `Measured` — and they are stable for that reason.
     */
    private val allowed = setOf(
        "com.buildplan.app.analyzer.service",
        "com.buildplan.app.analyzer.cache",
        "com.buildplan.app.analyzer.candidate",
        "com.buildplan.app.analyzer.quantity",
        "com.buildplan.app.analyzer.fidelity",
        "com.buildplan.app.analyzer.site",
        "com.buildplan.app.analyzer.snapshot",
        "com.buildplan.app.analyzer.source",
        "com.buildplan.app.analyzer.validate",
        // The one seam the platform has to implement itself: bytes to pixels.
        "com.buildplan.app.analyzer.raster",
        "com.buildplan.app.analyzer.asset",
        // STAGE-025. The verification overlay is a supported boundary in its own right: the
        // product asks a person the questions it raises and folds their decisions back through
        // it, so the app names `RootQuestion`, `VerificationSession` and the verified candidate
        // exactly as it names `Measured`. What stays out of reach is unchanged — the raster, the
        // solver and the reader that produced the report being verified.
        "com.buildplan.app.analyzer.verification",
    )

    /**
     * Packages that are the analyzer's own working, and will change without
     * notice. Naming them here rather than inferring the complement makes the
     * intent readable: these are the ones a new package should join unless
     * someone decides otherwise.
     */
    private val research = setOf(
        "com.buildplan.app.analyzer.plan",
        "com.buildplan.app.analyzer.roof",
        "com.buildplan.app.analyzer.text",
        "com.buildplan.app.analyzer.vertical",
        "com.buildplan.app.analyzer.visual",
        "com.buildplan.app.analyzer.site.archon",
        "com.buildplan.app.analyzer.evaluation",
    )

    /**
     * Fully qualified names the app itself declares.
     *
     * The app's own analyzer bindings live in `com.buildplan.app.analyzer` —
     * the same prefix as the module — because that is where the debug Lab has
     * always lived and splitting the convention would be worse than sharing
     * it. So "is this import crossing the boundary?" is answered by asking who
     * declares the class, not by reading its name.
     */
    private val topLevelDeclaration =
        Regex("""^(?:internal |public |private )?(?:data |sealed |value |annotation |abstract |open )*(?:class|object|interface|enum class|fun interface)\s+(\w+)""")

    private fun appDeclared(): Set<String> =
        listOf("src/main", "src/debug").map(::File).filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
                    val lines = file.readLines()
                    val pkg = lines.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim().orEmpty()
                    // Declarations at column zero only: a nested class is reached through its
                    // outer one and cannot be the target of a top-level import.
                    lines.mapNotNull { topLevelDeclaration.find(it)?.groupValues?.get(1) }.map { "$pkg.$it" } +
                        listOf("$pkg.${file.nameWithoutExtension}")
                }.toList()
            }
            .toSet()

    private fun productionImports(): List<Pair<String, String>> {
        val own = appDeclared()
        return File("src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .filter { it.startsWith("import com.buildplan.app.analyzer") }
                    .map { file.name to it.removePrefix("import ").trim() }
                    // A class the app declares is not a crossing, whatever package it sits in.
                    .filterNot { (_, imported) -> own.any { imported == it || imported.startsWith("$it.") } }
            }
            .toList()
    }

    @Test
    fun `production code imports only the analyzer's supported packages`() {
        val violations = productionImports().filterNot { (_, imported) ->
            allowed.any { imported.startsWith("$it.") }
        }
        if (violations.isNotEmpty()) {
            fail(violations.joinToString("\n") { (file, imported) -> "$file reaches past the boundary: $imported" })
        }
    }

    @Test
    fun `production code never names the analyzer's research internals`() {
        val violations = productionImports().filter { (_, imported) ->
            research.any { imported.startsWith("$it.") }
        }
        if (violations.isNotEmpty()) {
            fail(violations.joinToString("\n") { (file, imported) -> "$file depends on a moving part: $imported" })
        }
    }

    @Test
    fun `the app actually uses the boundary`() {
        // A guard over an unused API proves nothing. This is what makes the two tests above
        // statements about the product rather than about an empty set.
        val imports = productionImports().map { it.second }
        assertTrue(
            "production code should call the analyzer service",
            imports.any { it.startsWith("com.buildplan.app.analyzer.service.") },
        )
    }
}
