package com.buildplan.app.analyzer.asset

/**
 * What an asset is *for*. Assigned by the site adapter from the page's own
 * markup (alt text, data attributes, section), never from image content.
 */
enum class AssetRole {
    HERO_RENDER,
    PLAN_GROUND,
    PLAN_UPPER,
    PLAN_FLOOR_N,
    /** The plan variant with room areas printed inside rooms, when the site serves one. */
    PLAN_GROUND_WITH_AREAS,
    PLAN_UPPER_WITH_AREAS,
    SECTION,
    ELEVATION_FRONT,
    ELEVATION_REAR,
    ELEVATION_LEFT,
    ELEVATION_RIGHT,
    SITE_PLAN,
    COST_CALCULATION_PAGE,
    UNKNOWN_RELEVANT_IMAGE,
    ;

    val isPlan: Boolean
        get() = this == PLAN_GROUND || this == PLAN_UPPER || this == PLAN_FLOOR_N ||
            this == PLAN_GROUND_WITH_AREAS || this == PLAN_UPPER_WITH_AREAS

    val isElevation: Boolean
        get() = this == ELEVATION_FRONT || this == ELEVATION_REAR || this == ELEVATION_LEFT || this == ELEVATION_RIGHT
}

enum class RetrievalState { DISCOVERED, DOWNLOADED, DECODED, FAILED, SKIPPED }

/**
 * One asset the page refers to, from discovery through download and decode.
 *
 * Content never enters the manifest — only where it lives, what it is, how
 * big it decoded to and its hash. Third-party drawings stay in analysis
 * storage outside the repository and are referenced by URL and checksum.
 */
data class AssetRecord(
    val role: AssetRole,
    val url: String,
    val sourcePageUrl: String,
    /** Where on the page it was found: a CSS-ish locator or a description. */
    val locator: String,
    val altText: String?,
    val declaredMediaType: String? = null,
    val retrieval: RetrievalState = RetrievalState.DISCOVERED,
    val byteCount: Long? = null,
    val sha256: String? = null,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    /** Path in app-private analysis storage when downloaded, else null. Never a repository path. */
    val storagePath: String? = null,
    val failure: String? = null,
    /** For floor plans: which printed floor table (0-based) the plan belongs to, when the page says so. */
    val floorIndex: Int? = null,
)

/** The ordered list of assets, with helpers the analyzers use. Order is discovery order and is deterministic. */
data class AssetManifest(val assets: List<AssetRecord>) {

    fun withRole(role: AssetRole): List<AssetRecord> = assets.filter { it.role == role }

    fun firstWithRole(role: AssetRole): AssetRecord? = assets.firstOrNull { it.role == role }

    val plans: List<AssetRecord> get() = assets.filter { it.role.isPlan }

    val elevations: List<AssetRecord> get() = assets.filter { it.role.isElevation }

    fun replace(url: String, transform: (AssetRecord) -> AssetRecord): AssetManifest =
        AssetManifest(assets.map { if (it.url == url) transform(it) else it })

    companion object {
        val EMPTY = AssetManifest(emptyList())
    }
}
