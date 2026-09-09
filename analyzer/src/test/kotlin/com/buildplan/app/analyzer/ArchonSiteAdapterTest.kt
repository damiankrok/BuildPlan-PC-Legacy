package com.buildplan.app.analyzer

import com.buildplan.app.analyzer.asset.AssetRole
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.site.PublishedRoofFamily
import com.buildplan.app.analyzer.site.ScalarKey
import com.buildplan.app.analyzer.site.archon.ArchonNumbers
import com.buildplan.app.analyzer.site.archon.ArchonSiteAdapter
import com.buildplan.app.analyzer.source.SourceIdentity
import com.buildplan.app.analyzer.source.SupportedSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AN023-03 — the ARCHON adapter extracts published facts with provenance and never invents. */
class ArchonSiteAdapterTest {

    private val fetcher = FakeFetcher().apply {
        serve(ArchonFixture.PAGE_URL, ArchonFixture.page)
        serve(ArchonFixture.COST_URL, ArchonFixture.costPage)
    }
    private val identity = SourceIdentity(SupportedSite.ARCHON, ArchonFixture.KEY, ArchonFixture.PAGE_URL)
    private val pkg = ArchonSiteAdapter().read(identity, fetcher.fetch(ArchonFixture.PAGE_URL), fetcher)

    @Test
    fun `title and site tags come from the page`() {
        assertEquals("Dom testowy (X)", pkg.title)
        assertEquals("dwuspadowy", pkg.siteTags["projectRoof"])
        assertEquals(PublishedRoofFamily.GABLE, ArchonSiteAdapter.roofFamily(pkg.siteTags["projectRoof"]))
    }

    @Test
    fun `published scalars are exact with a DOM locator`() {
        val footprint = pkg.scalar(ScalarKey.FOOTPRINT_AREA)!!
        assertEquals(80.5, footprint.measured.value!!, 1e-9)
        assertEquals(FactFidelity.SOURCE_EXACT, footprint.measured.fidelity)
        assertEquals(ArchonFixture.PAGE_URL, footprint.measured.provenance.sourceUrl)
        assertTrue(footprint.measured.provenance.locator.contains("powierzchnia-zabudowy"))
        assertEquals(0.005, footprint.measured.uncertainty!!, 1e-12)

        assertEquals(8.1, pkg.scalar(ScalarKey.BUILDING_HEIGHT)!!.measured.value!!, 1e-9)
        assertEquals(500.5, pkg.scalar(ScalarKey.VOLUME)!!.measured.value!!, 1e-9)
        assertEquals(17.5, pkg.scalar(ScalarKey.MIN_PLOT_WIDTH)!!.measured.value!!, 1e-9)
        assertEquals(20.05, pkg.scalar(ScalarKey.MIN_PLOT_DEPTH)!!.measured.value!!, 1e-9)
    }

    @Test
    fun `construction lines yield pitch and knee wall with their raw text`() {
        val pitch = pkg.scalar(ScalarKey.ROOF_PITCH)!!
        assertEquals(42.0, pitch.measured.value!!, 1e-9)
        assertTrue(pitch.rawValue.contains("dwuspadowy"))
        val knee = pkg.scalar(ScalarKey.KNEE_WALL_HEIGHT)!!
        assertEquals(1.20, knee.measured.value!!, 1e-9)
        assertTrue(pkg.construction.any { it.label == "ściany" && it.text.contains("25 cm") })
    }

    @Test
    fun `floor tables keep names, ordinals, usable and floor areas`() {
        assertEquals(2, pkg.floors.size)
        val ground = pkg.floors[0]
        assertEquals("PARTER", ground.name)
        assertEquals(60.0, ground.usableAreaTotal.value!!, 1e-9)
        assertEquals(61.2, ground.floorAreaTotal.value!!, 1e-9)
        assertEquals(listOf(1, 2, 3, 4), ground.rooms.map { it.ordinal })
        assertEquals("Salon + Jadalnia", ground.rooms[1].name)
        assertEquals(30.0, ground.rooms[1].usableArea.value!!, 1e-9)
        assertEquals(FactFidelity.MISSING, ground.rooms[1].floorArea.fidelity)
        assertEquals(10.4, ground.rooms[2].floorArea.value!!, 1e-9)

        val attic = pkg.floors[1]
        assertEquals("PODDASZE", attic.name)
        assertEquals(26.0, attic.rooms[0].floorArea.value!!, 1e-9)
    }

    @Test
    fun `assets are role-tagged from markup, filtered to this project, and include area variants`() {
        val roles = pkg.assets.assets.map { it.role }
        assertTrue(AssetRole.HERO_RENDER in roles)
        assertTrue(AssetRole.PLAN_GROUND in roles)
        assertTrue(AssetRole.PLAN_UPPER in roles)
        assertTrue(AssetRole.PLAN_GROUND_WITH_AREAS in roles)
        assertTrue(AssetRole.PLAN_UPPER_WITH_AREAS in roles)
        assertTrue(AssetRole.SECTION in roles)
        assertTrue(AssetRole.SITE_PLAN in roles)
        assertEquals(1, pkg.assets.withRole(AssetRole.ELEVATION_FRONT).size)
        assertEquals(1, pkg.assets.withRole(AssetRole.ELEVATION_REAR).size)
        assertEquals(1, pkg.assets.withRole(AssetRole.ELEVATION_LEFT).size)
        assertEquals(1, pkg.assets.withRole(AssetRole.ELEVATION_RIGHT).size)
        assertTrue(pkg.assets.assets.none { it.url.contains("ffffffffffffff") })
        assertEquals(0, pkg.assets.firstWithRole(AssetRole.PLAN_GROUND)!!.floorIndex)
        assertEquals(1, pkg.assets.firstWithRole(AssetRole.PLAN_UPPER)!!.floorIndex)
        assertNotNull(pkg.assets.firstWithRole(AssetRole.COST_CALCULATION_PAGE))
    }

    @Test
    fun `the cost page is discovered from the link and its benchmarks read with cost-page provenance`() {
        assertEquals(listOf(ArchonFixture.PAGE_URL, ArchonFixture.COST_URL), fetcher.requested)
        val external = pkg.scalar(ScalarKey.EXTERNAL_WALL_AREA)!!
        assertEquals(100.5, external.measured.value!!, 1e-9)
        assertEquals(ArchonFixture.COST_URL, external.measured.provenance.sourceUrl)
        assertEquals(30.0, pkg.scalar(ScalarKey.FOUNDATION_WALL_AREA)!!.measured.value!!, 1e-9)
        assertEquals(50.0, pkg.scalar(ScalarKey.PARTITION_WALL_AREA_UPPER)!!.measured.value!!, 1e-9)
        assertEquals(25.0, pkg.scalar(ScalarKey.EXTERIOR_JOINERY_AREA)!!.measured.value!!, 1e-9)
        assertEquals(5.0, pkg.scalar(ScalarKey.ROOF_TIMBER_VOLUME)!!.measured.value!!, 1e-9)
        // Roof area appears on both pages; both readings are kept, each with its own provenance.
        assertEquals(2, pkg.scalars.count { it.key == ScalarKey.ROOF_AREA })
    }

    @Test
    fun `a missing cost page leaves the package intact`() {
        val lonely = FakeFetcher().apply {
            serve(ArchonFixture.PAGE_URL, ArchonFixture.page)
            fail(ArchonFixture.COST_URL, "offline")
        }
        val pkg2 = ArchonSiteAdapter().read(identity, lonely.fetch(ArchonFixture.PAGE_URL), lonely)
        assertNull(pkg2.scalar(ScalarKey.EXTERNAL_WALL_AREA))
        assertTrue(pkg2.relatedPages.isEmpty())
        assertEquals(2, pkg2.floors.size)
    }

    @Test
    fun `number parsing is strict about ambiguity`() {
        assertEquals(131.16, ArchonNumbers.single("131,16 m2")!!, 1e-9)
        assertNull(ArchonNumbers.single("19,05 x 20,6 m"))
        assertEquals(19.05 to 20.6, ArchonNumbers.pair("19,05 x 20,6 m"))
        assertEquals(40.0, ArchonNumbers.pitchDegrees("dwuspadowy, nachylenie 40 st. , więźba")!!, 1e-9)
        assertEquals(38.0, ArchonNumbers.pitchDegrees("czterospadowy, nachylenie 38°")!!, 1e-9)
        assertEquals(0.98, ArchonNumbers.lengthMeters("98 cm")!!, 1e-9)
        assertEquals(1.3, ArchonNumbers.lengthMeters("1,3 m")!!, 1e-9)
        assertNull(ArchonNumbers.lengthMeters("brak"))
        assertEquals(listOf(25.0, 20.0), ArchonNumbers.all("pustak ceramiczny 25 cm, styropian 20 cm, tynk"))
    }
}
