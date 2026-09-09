package com.buildplan.app.analyzer.text

import com.buildplan.app.analyzer.raster.PixelBox
import com.buildplan.app.analyzer.raster.RasterImage

/**
 * Why a run of glyphs on a drawing was, or was not, turned into characters.
 *
 * The distinction that matters is between "there is no text here" and "there
 * is text here that this raster cannot carry". The second is a fact about the
 * *source*, and reporting it is worth more than a guess: it tells the reader
 * the drawing does dimension its openings, and that a larger raster would
 * answer the question the analyzer is asking them.
 */
enum class TextLegibility {
    /** Characters were recognised, with [TextObservation.confidence] saying how well. */
    READ,

    /** A text run was located, but its glyphs are too few pixels tall to recognise without guessing. */
    TOO_SMALL_TO_READ,

    /**
     * Big enough, looked at, and refused: at least one glyph did not clear the
     * score or the margin over its runner-up.
     *
     * A distinct answer from [TOO_SMALL_TO_READ] because it is a different
     * fact about the source — the raster *can* carry this text and this
     * particular run still could not be read — and a distinct answer from
     * [READ] because a number with one uncertain digit is not a number.
     */
    AMBIGUOUS,

    /** Glyphs are large enough, but no recogniser is wired in to read them. */
    NO_RECOGNISER,
}

/** Which way a run of characters reads on the sheet. */
enum class TextOrientation {
    HORIZONTAL,

    /** Rotated a quarter turn — how every drawing sets the dimensions up its own side. */
    VERTICAL,
}

/**
 * One run of characters located on a drawing, read or not.
 *
 * [text] is null unless [legibility] is [TextLegibility.READ]. Nothing
 * downstream may substitute a value for a null: an observation that was
 * located but not read is evidence of a *label*, never of a number.
 */
data class TextObservation(
    val sourceAsset: String,
    val bounds: PixelBox,
    val text: String?,
    /** 0..1, and 0 whenever [text] is null. */
    val confidence: Double,
    val method: String,
    val legibility: TextLegibility,
    val glyphCount: Int,
    val glyphHeightPx: Int,
    val orientation: TextOrientation = TextOrientation.HORIZONTAL,
) {
    init {
        require(text == null || legibility == TextLegibility.READ) { "text is only allowed on a READ observation" }
        require(confidence in 0.0..1.0) { "confidence out of range: $confidence" }
    }
}

/**
 * The seam between the analyzer and whatever reads characters off a drawing.
 *
 * The core owns this contract and nothing else: no font, no OCR engine, no
 * platform bitmap type. An implementation may live in an adapter with its own
 * dependencies as long as it returns these observations, and the core stays a
 * pure function of pixels either way.
 */
interface DrawingTextExtractor {
    /** Text runs in [region] of [image], or in the whole image when [region] is null. */
    fun extract(assetUrl: String, image: RasterImage, region: PixelBox? = null): List<TextObservation>

    companion object {
        /**
         * Below this glyph height no recognition is attempted, at any confidence.
         *
         * At ten pixels a stroke is two pixels wide and survives the GIF
         * quantisation and JPEG ringing these drawings are published through.
         * Below it strokes drop out — the published section prints its floor
         * levels at roughly four pixels a glyph, where a "5" and a "6" differ
         * by a pixel that is not reliably there. A recogniser aimed at that
         * would return confident digits that are not in the drawing, which is
         * worse than the MISSING it would replace, so the floor is enforced
         * here rather than left to each implementation's threshold.
         */
        const val MIN_LEGIBLE_GLYPH_HEIGHT_PX = 10
    }
}
