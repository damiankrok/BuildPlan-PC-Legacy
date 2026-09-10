package com.buildplan.app.analyzer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * AN025-BOUNDARY — what the verification stage is allowed to do, checked in
 * the sources rather than promised in prose.
 *
 * Verification is the first stage where a person's answer becomes part of the
 * data, and that makes three things easy to get wrong by convenience rather
 * than by decision: writing the verified candidate into the domain, pricing it,
 * and minting `USER_CONFIRMED` somewhere other than a decision. Each has a test
 * here.
 */
class VerificationBoundaryTest {

    private fun sources(vararg roots: String): List<File> = roots
        .map { File("src/$it") }
        .filter { it.isDirectory }
        .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }

    private fun verificationUi(): List<File> = sources("main", "debug", "release")
        .filter { it.name.startsWith("Verification") }

    @Test
    fun `the verification UI exists in both variants and is what is under test`() {
        val files = verificationUi().map { it.name }
        assertTrue("the workspace is shipped code: $files", files.contains("VerificationWorkspace.kt"))
        assertTrue("its state holder is shipped code: $files", files.contains("VerificationUiState.kt"))
        // Two halves of the canvas: the renderer is debug-only, so a release build has to draw
        // something else, and the seam is what keeps Filament out of the shipped app.
        assertTrue("the debug canvas exists", File("src/debug/java/com/buildplan/app/ui/screens/VerificationCanvas.kt").isFile)
        assertTrue("the release canvas exists", File("src/release/java/com/buildplan/app/ui/screens/VerificationCanvas.kt").isFile)
    }

    @Test
    fun `the verification UI cannot reach the canonical model, the budget or the renderer`() {
        // A verified candidate is still a candidate. The way it stops being one is not a decision
        // somebody makes — it is one convenient call added to a screen that already holds the
        // data. If the screen cannot name a domain entity it cannot construct one, and it
        // certainly cannot cost one.
        val forbidden = listOf(
            "com.buildplan.app.domain",
            "com.buildplan.app.geometry",
            "com.buildplan.app.reference",
        )
        val violations = sources("main").filter { it.name.startsWith("Verification") }.flatMap { file ->
            file.readLines().filter { it.startsWith("import ") }.map { it.removePrefix("import ").trim() }
                .mapNotNull { imported -> forbidden.firstOrNull { imported.startsWith(it) }?.let { "${file.name} reaches $imported" } }
        }
        if (violations.isNotEmpty()) fail(violations.joinToString("\n"))

        // The release canvas must not name the renderer either; the debug one is where it lives.
        val releaseCanvas = File("src/release/java/com/buildplan/app/ui/screens/VerificationCanvas.kt").readText()
        assertTrue("the release canvas must not name Filament", !releaseCanvas.contains("render.filament"))
    }

    @Test
    fun `only a decision can mint USER_CONFIRMED`() {
        // The analyzer has its own test that a run never emits it. This is the other half: no
        // screen, view model or canvas may write that fidelity onto a value either, because a
        // quantity marked confirmed by the UI would be confirmed by nobody.
        val offenders = sources("main", "debug", "release")
            .filter { file -> file.readText().contains("USER_CONFIRMED") }
            .map { it.path }
        if (offenders.isNotEmpty()) {
            fail("USER_CONFIRMED is set by the verification engine from a decision, never by the app: $offenders")
        }
    }

    @Test
    fun `no benchmark project or reference model reaches the verification path`() {
        val forbiddenTokens = listOf("marcowk", "marcówk", "wiazowc", "wiązowc", "m2fa281446a8ca", "md6a1fa1493ad8", "com.buildplan.app.evaluation")
        val violations = verificationUi().flatMap { file ->
            val text = file.readText().lowercase()
            forbiddenTokens.filter { text.contains(it.lowercase()) }.map { "${file.name} contains '$it'" }
        }
        if (violations.isNotEmpty()) fail(violations.joinToString("\n"))
    }

    @Test
    fun `the workspace speaks Polish through resources and never through a literal`() {
        // The project's rule: labels are Polish and live in strings.xml, names in code are
        // English. A hard-coded Polish sentence in a composable is the way that erodes.
        val polish = Regex("""\"[^\"]*[ąćęłńóśźżĄĆĘŁŃÓŚŹŻ][^\"]*\"""")
        val offenders = verificationUi().flatMap { file ->
            file.readLines().withIndex()
                .filterNot { (_, line) -> line.trimStart().startsWith("//") || line.trimStart().startsWith("*") }
                .filter { (_, line) -> polish.containsMatchIn(line) }
                .map { (i, line) -> "${file.name}:${i + 1} ${line.trim()}" }
        }
        if (offenders.isNotEmpty()) fail("Polish text belongs in strings.xml:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `every verification string the workspace asks for exists`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val plurals = File("src/main/res/values/plurals.xml").readText()
        val used = verificationUi().flatMap { file ->
            Regex("""R\.(string|plurals)\.(verify_[A-Za-z0-9_]+)""").findAll(file.readText())
                .map { it.groupValues[1] to it.groupValues[2] }
                .toList()
        }.distinct()
        assertTrue("the workspace must actually use resources", used.size >= 20)
        val missing = used.filterNot { (kind, name) ->
            val body = if (kind == "string") strings else plurals
            body.contains("name=\"$name\"")
        }
        if (missing.isNotEmpty()) fail("missing resources: ${missing.map { it.second }}")
    }
}
