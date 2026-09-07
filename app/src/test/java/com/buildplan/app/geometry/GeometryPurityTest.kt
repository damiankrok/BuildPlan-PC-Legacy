package com.buildplan.app.geometry

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * GEO011-07, GEO011-18 — the geometry package stays a plain, unopinionated
 * model layer.
 *
 * Two separate promises are checked here, because both are easy to break by
 * accident and expensive to undo:
 *
 * 1. **No framework.** No Android, no Compose, no renderer, no persistence.
 *    Geometry must stay testable on a bare JVM and reusable by whatever
 *    rendering technology is eventually chosen — a choice that has not been made
 *    and must not be pre-empted by an import.
 * 2. **No duplicated semantics.** Geometry joins the domain by
 *    `BuildingElementId` alone. The moment a primitive also knows its floor, its
 *    rooms, its kind or whether it is hidden, the product has two answers to
 *    each of those questions and no rule for which one wins.
 */
class GeometryPurityTest {

    private val forbiddenImportPrefixes = listOf(
        // Android platform and UI
        "android.",
        "androidx.",
        "com.google.android.",
        // The app UI layer
        "com.buildplan.app.ui.",
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

    /**
     * The only domain types geometry is allowed to name.
     *
     * [com.buildplan.app.domain.model.Building] and
     * [com.buildplan.app.domain.model.BuildingElement] appear only so that
     * geometry can be *checked against* and *filtered by* an already-made
     * semantic decision. Everything else — room ids, floor ids, scope, kind,
     * visibility — would be a second copy of an answer the domain already owns.
     */
    private val allowedDomainImports = setOf(
        "com.buildplan.app.domain.model.Building",
        "com.buildplan.app.domain.model.BuildingElement",
        "com.buildplan.app.domain.model.BuildingElementId",
    )

    @Test
    fun `GEO011-18 geometry sources import no framework, renderer or persistence types`() {
        val sources = geometrySources()
        assertTrue("Expected to find geometry sources", sources.size >= 4)

        val violations = sources.flatMap { file ->
            file.importedTypes().mapNotNull { imported ->
                val prefix = forbiddenImportPrefixes.firstOrNull { imported.startsWith(it) }
                if (prefix == null) null else "${file.name} imports $imported"
            }
        }

        if (violations.isNotEmpty()) {
            fail("Geometry must not depend on framework or persistence types:\n" + violations.joinToString("\n"))
        }
    }

    @Test
    fun `GEO011-07 geometry names no domain type beyond the element id join`() {
        val violations = geometrySources().flatMap { file ->
            file.importedTypes()
                .filter { it.startsWith("com.buildplan.app.domain.") }
                .filterNot { it in allowedDomainImports }
                .map { "${file.name} imports $it" }
        }

        if (violations.isNotEmpty()) {
            fail(
                "Geometry must join the domain by BuildingElementId only, not re-declare its " +
                    "semantics:\n" + violations.joinToString("\n"),
            )
        }
    }

    @Test
    fun `GEO011-07 geometry primitives store no room, floor, kind or visibility state`() {
        val primitiveTypes = listOf(
            WallGeometry::class.java,
            SlabGeometry::class.java,
            RoofFacetGeometry::class.java,
            GablePanelGeometry::class.java,
            BuildingGeometry::class.java,
            LocalBounds::class.java,
            PlanPoint::class.java,
            ModelPoint::class.java,
        )

        val forbiddenFieldTypes = listOf(
            "com.buildplan.app.domain.model.RoomId",
            "com.buildplan.app.domain.model.FloorId",
            "com.buildplan.app.domain.model.BuildingElementKind",
            "com.buildplan.app.domain.model.BuildingElementScope",
            "com.buildplan.app.domain.model.BuildingVisibility",
        )

        val violations = primitiveTypes.flatMap { type ->
            type.declaredFields.mapNotNull { field ->
                val fieldTypeName = field.type.name
                val leaks = fieldTypeName in forbiddenFieldTypes ||
                    forbiddenImportPrefixes.any { fieldTypeName.startsWith(it) }
                if (leaks) "${type.simpleName}.${field.name} is $fieldTypeName" else null
            }
        }

        if (violations.isNotEmpty()) {
            fail("Semantic or renderer state leaked into geometry:\n" + violations.joinToString("\n"))
        }
    }

    private fun File.importedTypes(): List<String> = readLines()
        .map { it.trim() }
        .filter { it.startsWith("import ") }
        .map { it.removePrefix("import ").substringBefore(" as ").trim() }

    /** Walks up from the working directory so the test does not depend on where Gradle runs it. */
    private fun geometrySources(): List<File> {
        var directory: File? = File("").absoluteFile
        val relativePaths = listOf(
            "src/main/java/com/buildplan/app/geometry",
            "app/src/main/java/com/buildplan/app/geometry",
        )

        while (directory != null) {
            relativePaths
                .map { File(directory, it) }
                .firstOrNull { it.isDirectory }
                ?.let { root ->
                    return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
                }
            directory = directory.parentFile
        }

        throw AssertionError(
            "Could not locate the geometry sources starting from ${File("").absolutePath}",
        )
    }
}
