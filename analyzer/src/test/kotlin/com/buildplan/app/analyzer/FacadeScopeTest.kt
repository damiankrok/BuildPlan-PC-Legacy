package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.quantity.FacadeScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AN023C-FACADE — the envelope, itemised by the choices a cost page makes
 * without printing them.
 *
 * One published number under "facade" can mean six different measurements of
 * the same wall, spanning half its value: over the openings or net of them,
 * an unheated garage in or out, a mass under its own roof in or out. Before
 * these scopes were named the benchmark read as a third of the building
 * missing; the scopes are what turn that into a question about definitions.
 */
class FacadeScopeTest {

    private fun m(v: Double) = Measured(v, MeasureUnit.SQUARE_METER, FactFidelity.SOURCE_DERIVED, Provenance.derived("test"))

    private fun scope(gross: Double, openings: Double, garage: Double, secondary: Double) = FacadeScope(
        exteriorStructuralWall = m(gross * 0.4),
        finishGross = m(gross),
        finishNet = m(gross - openings),
        openingDeduction = m(openings),
        gableFace = m(0.0),
        garageExterior = m(garage),
        secondaryMassExterior = m(secondary),
        plinth = Measured.missing(MeasureUnit.SQUARE_METER, "terrain assumed"),
    )

    @Test
    fun `every scope is enumerated, smallest first, and named`() {
        val s = scope(gross = 300.0, openings = 60.0, garage = 30.0, secondary = 20.0)
        val values = s.scopeValues
        assertEquals(values.sorted(), values)
        assertTrue(values.contains(300.0))
        assertTrue("net", values.contains(240.0))
        assertTrue("net less garage", values.contains(210.0))
        assertTrue("net less garage and secondary", values.contains(190.0))
        assertTrue("each scope is named", s.scopes.all { it.first.isNotBlank() })
    }

    @Test
    fun `a building with no garage and no secondary mass has only the two readings`() {
        val s = scope(gross = 300.0, openings = 60.0, garage = 0.0, secondary = 0.0)
        assertEquals(listOf(240.0, 300.0), s.scopeValues)
    }

    @Test
    fun `the plausible range spans every scope`() {
        val s = scope(gross = 300.0, openings = 60.0, garage = 30.0, secondary = 20.0)
        assertEquals(190.0, s.plausibleRange.start, 1e-9)
        assertEquals(300.0, s.plausibleRange.endInclusive, 1e-9)
    }

    @Test
    fun `an unmeasurable plinth stays missing rather than becoming zero`() {
        val s = scope(gross = 300.0, openings = 60.0, garage = 30.0, secondary = 0.0)
        assertEquals(null, s.plinth.value)
        assertEquals(FactFidelity.MISSING, s.plinth.fidelity)
    }

    @Test
    fun `a figure that only one scope fits names that scope`() {
        // Two scopes, 60 m2 apart: at this tolerance only one can fit, and naming it says
        // something the source did not — that the page priced the wall net of its openings.
        val s = scope(gross = 300.0, openings = 60.0, garage = 0.0, secondary = 0.0)
        val a = s.attribute(sourceValue = 245.0, tolerance = 0.15)!!
        assertEquals("net of the openings", a.label)
        assertTrue(a.isDecided)
    }

    @Test
    fun `a figure two scopes fit is reported as undecided, not as the nearer one`() {
        // With a garage and a secondary mass the six scopes run 190, 210, 220, 240, 270, 300 —
        // spaced closer than a 15 % tolerance is wide. 224 sits 1.8 % from one and 7.1 % from
        // the next; both are legitimate readings of the same envelope, and nothing in the source
        // says which the page priced, so naming the nearer one invents a definition.
        val s = scope(gross = 300.0, openings = 60.0, garage = 30.0, secondary = 20.0)
        val a = s.attribute(sourceValue = 224.0, tolerance = 0.15)!!
        assertTrue("two scopes fit; the attribution must not be presented as settled", !a.isDecided)
        assertEquals("net of the openings, less a secondary mass", a.label)
        assertEquals("net of the openings, less the garage", a.alternative)
    }

    @Test
    fun `an undecided attribution still reports the measured agreement`() {
        // The ambiguity is about the label, not the number: the geometry agreed to what it agreed
        // to, and downgrading that would hide a real measurement behind a naming problem.
        val s = scope(gross = 300.0, openings = 60.0, garage = 30.0, secondary = 20.0)
        val a = s.attribute(sourceValue = 224.0, tolerance = 0.15)!!
        assertEquals(0.0179, a.relative, 1e-3)
    }

    @Test
    fun `structural masonry is never one of the facade scopes`() {
        // Masonry excludes the openings by construction and is short of whatever envelope the
        // raster left unresolved; it is a different quantity and must not be offered as a facade.
        val s = scope(gross = 300.0, openings = 60.0, garage = 30.0, secondary = 20.0)
        assertTrue(s.scopeValues.none { it == s.exteriorStructuralWall.value })
    }
}
