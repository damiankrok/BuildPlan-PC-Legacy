package com.buildplan.app.domain

import com.buildplan.app.domain.model.Budget
import com.buildplan.app.domain.model.Building
import com.buildplan.app.domain.model.BuildingElement
import com.buildplan.app.domain.model.Cost
import com.buildplan.app.domain.model.CostAllocation
import com.buildplan.app.domain.model.Floor
import com.buildplan.app.domain.model.Project
import com.buildplan.app.domain.model.Room
import com.buildplan.app.domain.model.Stage
import com.buildplan.app.domain.money.Money
import com.buildplan.app.domain.units.Quantity
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * DOM002-16 - the domain stays free of framework dependencies.
 *
 * This is checked rather than trusted because the leak is easy to make and
 * expensive to undo: one persistence annotation or one Android type in an
 * entity and the model can no longer be reasoned about, or tested, on its own.
 */
class DomainPurityTest {

    private val forbiddenImportPrefixes = listOf(
        // Android platform and UI
        "android.",
        "androidx.",
        "com.google.android.",
        // The app UI layer
        "com.buildplan.app.ui.",
        // The geometry layer: it reads the domain, never the other way round,
        // so that a building can exist and be costed before it has a shape.
        "com.buildplan.app.geometry.",
        // Persistence and serialization
        "kotlinx.serialization.",
        "javax.persistence.",
        "jakarta.persistence.",
        "com.google.gson.",
        "com.squareup.moshi.",
        // Networking
        "retrofit2.",
        "okhttp3.",
        "io.ktor.",
        // Dependency injection
        "dagger.",
        "javax.inject.",
        "org.koin.",
    )

    @Test
    fun `DOM002-16 domain sources import no framework or persistence types`() {
        val domainDir = locateDomainSources()
        val sources = domainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        assertTrue(
            "Expected to find domain sources under $domainDir",
            sources.size >= 10,
        )

        val violations = mutableListOf<String>()
        sources.forEach { file ->
            file.readLines().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                if (!line.startsWith("import ")) return@forEachIndexed

                val imported = line.removePrefix("import ").substringBefore(" as ").trim()
                forbiddenImportPrefixes
                    .filter { imported.startsWith(it) }
                    .forEach { violations += "${file.name}:${index + 1} imports $imported" }
            }
        }

        if (violations.isNotEmpty()) {
            fail("Domain must not depend on framework or persistence types:\n" + violations.joinToString("\n"))
        }
    }

    @Test
    fun `DOM002-16 domain types expose no framework types in their fields`() {
        val domainTypes = listOf(
            Project::class.java,
            Building::class.java,
            Floor::class.java,
            Room::class.java,
            BuildingElement::class.java,
            Stage::class.java,
            Cost::class.java,
            CostAllocation::class.java,
            Budget::class.java,
            Money::class.java,
            Quantity::class.java,
        )

        val violations = domainTypes.flatMap { type ->
            type.declaredFields.mapNotNull { field ->
                val fieldTypeName = field.type.name
                val leaks = forbiddenImportPrefixes.any { fieldTypeName.startsWith(it) }
                if (leaks) "${type.simpleName}.${field.name} is $fieldTypeName" else null
            } + type.annotations.mapNotNull { annotation ->
                val annotationName = annotation.annotationClass.java.name
                val leaks = forbiddenImportPrefixes.any { annotationName.startsWith(it) }
                if (leaks) "${type.simpleName} is annotated with $annotationName" else null
            }
        }

        if (violations.isNotEmpty()) {
            fail("Framework types leaked into the domain:\n" + violations.joinToString("\n"))
        }
    }

    /** Walks up from the working directory so the test does not depend on where Gradle runs it. */
    private fun locateDomainSources(): File {
        var directory: File? = File("").absoluteFile
        val relativePaths = listOf(
            "src/main/java/com/buildplan/app/domain",
            "app/src/main/java/com/buildplan/app/domain",
        )

        while (directory != null) {
            relativePaths
                .map { File(directory, it) }
                .firstOrNull { it.isDirectory }
                ?.let { return it }
            directory = directory.parentFile
        }

        throw AssertionError(
            "Could not locate the domain sources starting from ${File("").absolutePath}",
        )
    }
}
