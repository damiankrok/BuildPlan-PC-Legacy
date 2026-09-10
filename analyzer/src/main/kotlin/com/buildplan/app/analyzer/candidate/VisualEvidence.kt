package com.buildplan.app.analyzer.candidate

import com.buildplan.app.analyzer.asset.AssetRole
import com.buildplan.app.analyzer.fidelity.FactFidelity

/**
 * What kind of picture an observation was read from.
 *
 * An orthographic elevation is a drawing: horizontal positions along it are
 * proportional to positions along the facade, and vertical ones to heights.
 * A perspective render is a photograph of a model: it says what is *there*
 * and roughly where, and it says nothing metric. The two are kept apart at
 * the type level so that a later consumer cannot average a render's guess
 * into an elevation's measurement.
 */
enum class VisualViewpoint { ORTHOGRAPHIC_ELEVATION, PERSPECTIVE_RENDER }

/**
 * The things a deterministic raster pass can point at in an elevation or a
 * render. Deliberately generic: a portal, a band or a stack is a *shape in
 * the picture*, never a named feature of any particular house.
 */
enum class VisualObservationKind {
    /** The building's silhouette against the sky: its bounding box in the picture. */
    BUILDING_SILHOUETTE,

    /** One straight piece of the top edge of the silhouette. */
    ROOFLINE_SEGMENT,

    /** The highest point of the roofline, when it is a point rather than a flat top. */
    ROOF_APEX,

    /** A horizontal line where the dark roof meets the wall below it. */
    EAVE_LINE,

    /** The roof's flat top edge, when the elevation shows one. */
    RIDGE_LINE,

    /** The roofline reads as a gable: two slopes meeting at an apex. */
    GABLE_READ,

    /** The roofline reads as a hip: a flat top between two slopes. */
    HIP_READ,

    /** A dark or glazed rectangle in the wall that is not on the ground. */
    OPENING_RECTANGLE,

    /** A dark or glazed rectangle standing on the ground, door-shaped. */
    DOOR_RECTANGLE,

    /** A wide, low, dark rectangle standing on the ground, gate-shaped. */
    GARAGE_GATE_RECTANGLE,

    /** A wide dark mass on the ground beside the main wall: a lower secondary body. */
    DARK_MASS,

    /** A long horizontal luma edge across the wall that is neither eave nor ground. */
    HORIZONTAL_BAND,

    /** A patch of warm, saturated cladding inside the silhouette. */
    CLADDING_PATCH,

    /** A light border round a recessed, clad patch under the gable: portal-like. */
    FRAME_OR_PORTAL,

    /** A pale, wide, low, translucent strip above a band at mid height: railing-like. */
    RAILING_STRIP,

    /** A narrow spike above the roofline: a stack. */
    ROOF_STACK,

    /** A small rectangle inside the roof region: a rooflight. */
    ROOFLIGHT_PATCH,

    /** The roof region shows regular horizontal courses: a tile-like covering. */
    ROOF_COVER_TEXTURE,

    /** The region of the picture the roof occupies. */
    ROOF_REGION,
}

/**
 * A rectangle in the picture, as fractions of the picture's width and height
 * (0 at the left / top, 1 at the right / bottom).
 *
 * Normalised so that two assets of different pixel sizes compare, and so that
 * nothing here is a pixel count that would tempt a metre out of it.
 */
data class NormalizedBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) { "NormalizedBox must be finite" }
        require(left <= right && top <= bottom) { "NormalizedBox min must not exceed max: $this" }
    }

    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centreX: Double get() = (left + right) / 2
    val centreY: Double get() = (top + bottom) / 2

    /** This box as fractions of [outer] rather than of the picture. */
    fun relativeTo(outer: NormalizedBox): NormalizedBox {
        val w = outer.width.takeIf { it > 1e-9 } ?: 1.0
        val h = outer.height.takeIf { it > 1e-9 } ?: 1.0
        return NormalizedBox((left - outer.left) / w, (top - outer.top) / h, (right - outer.left) / w, (bottom - outer.top) / h)
    }
}

/**
 * One thing seen in one picture.
 *
 * Every observation carries where it is, how it was found, how sure the pass
 * is and what fidelity the reading has. The fidelity is never stronger than
 * [FactFidelity.SOURCE_DERIVED] for an elevation and never stronger than
 * [FactFidelity.TRACE_UNCERTAIN] for a render — a picture is evidence about
 * composition, and a number read off it is a ratio, not a length.
 *
 * @property rejectedAlternatives readings that fitted almost as well and
 *   were not chosen, when the choice was material; a reviewer should see
 *   what the pass decided against.
 */
data class VisualObservation(
    val kind: VisualObservationKind,
    val bounds: NormalizedBox,
    /** 0..1. How well the shape fitted the rule that found it. */
    val confidence: Double,
    val method: String,
    val fidelity: FactFidelity,
    val note: String = "",
    val rejectedAlternatives: List<String> = emptyList(),
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be in 0..1, was $confidence" }
    }
}

/**
 * Everything one downloaded picture contributed.
 *
 * The role comes from the page's own markup — its alt text, its position —
 * never from the pixels. Where the page did not say what a picture shows,
 * the role stays what the adapter gave it and the viewpoint is decided from
 * the role alone.
 */
data class VisualAssetEvidence(
    val assetUrl: String,
    val role: AssetRole,
    val viewpoint: VisualViewpoint,
    val widthPx: Int,
    val heightPx: Int,
    val observations: List<VisualObservation>,
    /** 0..1. How far the pass trusts this picture at all: a clean silhouette scores high, a cluttered render low. */
    val confidence: Double,
    val fidelity: FactFidelity,
    val notes: List<String> = emptyList(),
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be in 0..1, was $confidence" }
    }

    fun of(kind: VisualObservationKind): List<VisualObservation> = observations.filter { it.kind == kind }

    /** The building's silhouette box, when the picture yielded one. */
    val silhouette: NormalizedBox? get() = of(VisualObservationKind.BUILDING_SILHOUETTE).firstOrNull()?.bounds
}

/** Which way a facade of the candidate faces, in the plan's own frame (Z down the page). */
enum class FacadeSide { NORTH, EAST, SOUTH, WEST }

/**
 * Which facade of the candidate an elevation asset shows.
 *
 * Decided from the candidate alone — the side with the entrance door is the
 * front, the opposite side the rear — and left open when the candidate does
 * not settle it. An elevation the adapter calls "side" could show either of
 * the two remaining facades; the two readings are both listed and neither is
 * chosen, so a count compared under the wrong one cannot become a conflict.
 */
data class FacadeAssignment(
    val role: AssetRole,
    val assetUrl: String,
    /** Every facade this asset might show, best first. Exactly one when settled. */
    val sides: List<FacadeSide>,
    val confidence: Double,
    val reason: String,
) {
    val isSettled: Boolean get() = sides.size == 1
}

/** What kind of disagreement a picture has with the candidate. */
enum class VisualConflictKind {
    OPENING_COUNT_CONFLICT,
    OPENING_POSITION_CONFLICT,
    ROOF_SILHOUETTE_CONFLICT,
    MASSING_CONFLICT,
    FACADE_FEATURE_MISSING,
    GARAGE_RELATION_CONFLICT,
    VISUAL_EVIDENCE_UNCORROBORATED,
}

enum class VisualConflictSeverity { LOW, MEDIUM, HIGH }

/**
 * A picture and the candidate disagree, and here is by how much.
 *
 * A conflict names the candidate region it is about, the picture it came
 * from and the observation inside it, so that a verification screen can put
 * the two side by side. It never edits the candidate: what to do about it is
 * a question, and the recommended action is a suggestion in the reader's
 * language, not a rule.
 */
data class VisualConflict(
    val id: String,
    val kind: VisualConflictKind,
    val severity: VisualConflictSeverity,
    /** Candidate ids the conflict is about: opening ids, a floor id, `roof`, `roof-mass-1`. */
    val subjectIds: List<String>,
    val facade: FacadeSide?,
    val assetUrl: String,
    /** Index into the asset's observation list, or -1 when the conflict is about a count rather than one shape. */
    val observationIndex: Int,
    val candidateReading: String,
    val sourceReading: String,
    /** Why this matters, in the analyzer's English. */
    val impact: String,
    /** What a person could do about it, in Polish. */
    val recommendedAction: String,
    val confidence: Double,
)

/** The generic kinds of facade feature a picture may propose. */
enum class AppearanceKind {
    FACADE_FRAME,
    HORIZONTAL_BAND,
    BALCONY,
    RAILING,
    EAVES_FASCIA,
    ROOF_STACK,
    ROOFLIGHT,
    ROOF_COVER_HINT,
    GARAGE_PORTAL,
    CLADDING,
}

/** Where on the candidate an appearance feature would sit, without claiming a metre. */
data class AffectedRegion(
    val facade: FacadeSide?,
    val floorId: String?,
    /** Fractions of the facade's own box (0..1 left to right, 0..1 top to bottom), when an elevation gave them. */
    val facadeFraction: NormalizedBox?,
)

/** One picture, one observation, in support of an appearance feature. */
data class EvidenceRef(val assetUrl: String, val observationIndex: Int, val viewpoint: VisualViewpoint)

/**
 * A presentation feature the pictures suggest and the candidate does not
 * carry: a frame, a band, a railing, a stack, a covering.
 *
 * Not a building element. Nothing here enters the canonical model; the
 * verification stage offers it as a choice, and what becomes of an accepted
 * one is a later decision. The parameters are ratios of the facade, never
 * metres — a render cannot supply a width, and this type refuses to pretend.
 */
data class AppearanceCandidate(
    val featureId: String,
    val kind: AppearanceKind,
    val sourceEvidence: List<EvidenceRef>,
    val affectedRegion: AffectedRegion,
    val confidence: Double,
    val fidelity: FactFidelity,
    /** Named ratios: `heightFraction`, `widthFraction`, `topFraction`, `thicknessFraction`, `period`. */
    val presentationParameters: Map<String, Double>,
    val note: String,
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be in 0..1, was $confidence" }
    }
}

/**
 * Everything the pictures contributed to one candidate: what was seen,
 * which facade each elevation shows, where the pictures disagree with the
 * traced geometry, and what they propose on top of it.
 */
data class VisualEvidence(
    val assets: List<VisualAssetEvidence>,
    val facades: List<FacadeAssignment>,
    val conflicts: List<VisualConflict>,
    val appearance: List<AppearanceCandidate>,
    val notes: List<String>,
) {
    fun asset(url: String): VisualAssetEvidence? = assets.firstOrNull { it.assetUrl == url }

    companion object {
        val NONE = VisualEvidence(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}
