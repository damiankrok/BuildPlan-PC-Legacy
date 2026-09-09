package com.buildplan.app.analyzer.site

import com.buildplan.app.analyzer.asset.AssetManifest
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.source.SourceIdentity

/**
 * Everything a site adapter extracted from a project page, before any
 * geometry exists: identity, published scalars, room tables, construction
 * facts, related public pages and the assets it discovered.
 *
 * A [SourcePackage] contains no geometry and no interpretation. It is the
 * boundary between "what the page says" and "what the analyzer makes of it",
 * and it is serialised into the snapshot as-is so a later run can be compared
 * against the page as it was on the day.
 */
data class SourcePackage(
    val identity: SourceIdentity,
    val title: String,
    val retrievedAtEpochMillis: Long,
    /** Published scalars keyed by a stable [ScalarKey]; every one keeps its raw label. */
    val scalars: List<PublishedScalar>,
    /** Published construction facts as label/value text pairs, plus the numbers parsed out of them. */
    val construction: List<ConstructionFact>,
    /** Floor room tables in the page's order (lowest storey first as printed). */
    val floors: List<PublishedFloor>,
    /** Every asset the adapter recognised, with its role. */
    val assets: AssetManifest,
    /** Public subpages the adapter found linked and read, such as the cost calculation page. */
    val relatedPages: List<RelatedPage>,
    /** Structured tags the site publishes about the project, verbatim. */
    val siteTags: Map<String, String>,
) {
    fun scalar(key: ScalarKey): PublishedScalar? = scalars.firstOrNull { it.key == key }
}

/** A stable vocabulary for the scalars ARCHON-like pages publish. Unknown labels keep [OTHER] with the raw label. */
enum class ScalarKey {
    HOUSE_NET_AREA,
    GARAGE_AREA,
    BOILER_ROOM_AREA,
    ATTIC_STORAGE_AREA,
    USABLE_AREA,
    FOOTPRINT_AREA,
    FLOOR_AREA_TOTAL,
    GROSS_AREA_TOTAL,
    VOLUME,
    ROOF_AREA,
    BUILDING_HEIGHT,
    MIN_PLOT_WIDTH,
    MIN_PLOT_DEPTH,
    KNEE_WALL_HEIGHT,
    ROOF_PITCH,
    FOUNDATION_WALL_AREA,
    EXTERNAL_WALL_AREA,
    INTERNAL_LOAD_BEARING_WALL_AREA,
    PARTITION_WALL_AREA_GROUND,
    PARTITION_WALL_AREA_UPPER,
    FLOORS_AND_STAIRS_AREA,
    EXTERIOR_JOINERY_AREA,
    FACADE_INSULATION_AREA,
    ROOF_TIMBER_VOLUME,
    OTHER,
}

/** One published number with the exact label it was printed under. */
data class PublishedScalar(
    val key: ScalarKey,
    val rawLabel: String,
    val rawValue: String,
    val measured: Measured,
)

/** One line of the construction block, e.g. `dach: dwuspadowy, nachylenie 40 st.` */
data class ConstructionFact(
    val label: String,
    val text: String,
)

/** The roof family the site names, mapped from its Polish vocabulary. */
enum class PublishedRoofFamily { GABLE, HIP, MULTI_HIP, FLAT, MONO_PITCH, UNKNOWN }

data class PublishedFloor(
    val name: String,
    /** Position as printed: 0 for the first table, 1 for the second. Semantic order is decided later. */
    val printedIndex: Int,
    val usableAreaTotal: Measured,
    val floorAreaTotal: Measured,
    val rooms: List<PublishedRoom>,
)

data class PublishedRoom(
    /** The printed ordinal, when the label carries one ("4. Salon + Jadalnia" -> 4). */
    val ordinal: Int?,
    val name: String,
    val usableArea: Measured,
    /** The parenthesised floor area when printed; otherwise MISSING. */
    val floorArea: Measured,
    /** What the site's name for the room says it is, for structural cues; [RoomKind.OTHER] when it says nothing. */
    val kind: RoomKind = RoomKind.OTHER,
)

/**
 * The function a published room name declares. Assigned by the site adapter
 * from the site's own vocabulary; the analyzer core never reads names.
 */
enum class RoomKind { GARAGE, STAIRS, VESTIBULE, HALL, KITCHEN, LIVING, BEDROOM, BATHROOM, BOILER, LAUNDRY, WARDROBE, PANTRY, STORAGE, ATTIC_STORAGE, OTHER }

data class RelatedPage(
    val role: RelatedPageRole,
    val url: String,
    val statusCode: Int,
    val retrievedAtEpochMillis: Long,
)

enum class RelatedPageRole { COST_CALCULATION }
