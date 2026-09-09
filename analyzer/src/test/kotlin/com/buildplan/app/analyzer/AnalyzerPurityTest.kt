package com.buildplan.app.analyzer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * AN023-PURITY — the analyzer core stays generic and platform-free.
 *
 * Three promises, each cheap to break by accident:
 *
 * 1. **No Android, no UI, no renderer.** The module is pure JVM; this test
 *    makes the intent explicit in the sources as well as in the build.
 * 2. **No project-specific knowledge.** No evaluation project's name, slug,
 *    key, room name spelled as a constant, or coordinate lives in the core.
 *    Expected values are test truth in `evaluation/`, never inference.
 * 3. **No remote generative AI.** No LLM or vision API client, no embedding
 *    store: the analyzer is deterministic parsing and geometry.
 */
class AnalyzerPurityTest {

    private val forbiddenImportPrefixes = listOf(
        "android.", "androidx.", "com.google.android.",
        "com.buildplan.app.ui.", "com.buildplan.app.render.", "com.buildplan.app.domain.", "com.buildplan.app.geometry.",
        "kotlinx.serialization.", "com.google.gson.", "com.squareup.moshi.",
        "retrofit2.", "okhttp3.", "io.ktor.",
        "com.openai.", "com.anthropic.", "com.google.ai.", "com.google.genai.", "dev.langchain4j.",
    )

    /** Project-specific tokens that must not appear in the core. Lower-case substring match. */
    private val forbiddenTokens = listOf(
        "marcowk", "marcówk", "wiazowc", "wiązowc", "m2fa281446a8ca", "md6a1fa1493ad8",
        "131.16", "150.83", "150.57", "246.7", "8.27", "8.49",
        "openai", "anthropic", "gemini", "gpt-", "claude",
    )

    private fun coreSources(): List<File> {
        val root = File("src/main/kotlin/com/buildplan/app/analyzer")
        assertTrue("analyzer core sources exist at ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `core imports no platform, UI, renderer, domain or AI client types`() {
        val violations = coreSources().flatMap { file ->
            file.readLines().filter { it.startsWith("import ") }.map { it.removePrefix("import ").trim() }
                .mapNotNull { imported -> forbiddenImportPrefixes.firstOrNull { imported.startsWith(it) }?.let { "${file.name} imports $imported" } }
        }
        if (violations.isNotEmpty()) fail(violations.joinToString("\n"))
    }

    @Test
    fun `core names no evaluation project and no generative AI service`() {
        val violations = coreSources().flatMap { file ->
            val text = file.readText().lowercase()
            forbiddenTokens.filter { text.contains(it) }.map { "${file.name} contains '$it'" }
        }
        if (violations.isNotEmpty()) fail(violations.joinToString("\n"))
    }

    /**
     * Coroutines are the service boundary's vocabulary, not the analyzer's.
     *
     * The pipeline stays a plain synchronous computation over bytes — which is
     * why it can be run from a JVM test, a debug harness or a `Dispatchers.IO`
     * coroutine without three notions of what cancellation means. `service/`
     * bridges structured concurrency onto the one cooperative signal the core
     * exposes, and that bridge is allowed to be in exactly one place.
     */
    @Test
    fun `structured concurrency lives only at the service boundary`() {
        val violations = coreSources()
            .filter { !it.path.replace('\\', '/').contains("/service/") }
            .flatMap { file ->
                file.readLines().filter { it.startsWith("import kotlinx.coroutines") }.map { "${file.name}: $it" }
            }
        if (violations.isNotEmpty()) fail(violations.joinToString("\n"))
    }

    @Test
    fun `site vocabulary lives only in the site adapter`() {
        // Polish room-name keywords are site vocabulary; the generic layers work on RoomKind and numbers.
        val polishKeywords = listOf("kuchn", "salon", "łazien", "garaż", "kotłow", "wiatro")
        val violations = coreSources()
            .filter { !it.path.replace('\\', '/').contains("/site/archon/") && !it.path.replace('\\', '/').contains("/validate/") }
            .flatMap { file ->
                val text = file.readText().lowercase()
                polishKeywords.filter { text.contains("\"$it") || text.contains("startswith(\"$it") }.map { "${file.name} matches '$it'" }
            }
        if (violations.isNotEmpty()) fail(violations.joinToString("\n"))
    }
}
