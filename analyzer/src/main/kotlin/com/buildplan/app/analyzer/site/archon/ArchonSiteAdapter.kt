package com.buildplan.app.analyzer.site.archon

import com.buildplan.app.analyzer.asset.AssetManifest
import com.buildplan.app.analyzer.asset.AssetRecord
import com.buildplan.app.analyzer.asset.AssetRole
import com.buildplan.app.analyzer.fidelity.FactFidelity
import com.buildplan.app.analyzer.fidelity.MeasureUnit
import com.buildplan.app.analyzer.fidelity.Measured
import com.buildplan.app.analyzer.fidelity.Provenance
import com.buildplan.app.analyzer.site.ConstructionFact
import com.buildplan.app.analyzer.site.PublishedFloor
import com.buildplan.app.analyzer.site.PublishedRoofFamily
import com.buildplan.app.analyzer.site.PublishedRoom
import com.buildplan.app.analyzer.site.PublishedScalar
import com.buildplan.app.analyzer.site.RelatedPage
import com.buildplan.app.analyzer.site.RelatedPageRole
import com.buildplan.app.analyzer.site.RoomKind
import com.buildplan.app.analyzer.site.ScalarKey
import com.buildplan.app.analyzer.site.SiteAdapter
import com.buildplan.app.analyzer.site.SourcePackage
import com.buildplan.app.analyzer.source.FetchException
import com.buildplan.app.analyzer.source.FetchedResource
import com.buildplan.app.analyzer.source.ResourceFetcher
import com.buildplan.app.analyzer.source.SourceIdentity
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Reads an ARCHON+ project page.
 *
 * The page's structure, as observed on the live site and reproduced in the
 * test fixture:
 *
 * - scalars in `.product-data__item[data-resource]` blocks, each with a
 *   `.product-data__title` and a `.product-data__value`;
 * - the construction block (`data-resource="construction"`) as `label: text`
 *   lines, one of which names the roof and its pitch and one the knee wall;
 * - one `table.table-striped` per storey: `thead th` carries the storey name,
 *   the usable-area total and the parenthesised floor-area total; each
 *   `tbody tr` a numbered room with `td[title="powierzchnia użytkowa"]` and
 *   `td[title="powierzchnia podłogi"]`;
 * - images on `assets.archon.pl/images/products/<key>/…` with alt text naming
 *   the view; plan images carry `data-floor-pom-img` pointing at the
 *   area-annotated variant;
 * - a link to `…-koszt-budowy-7`, whose `#dane-do-kalkulacji` block lists the
 *   aggregate quantities the cost estimate was computed from.
 *
 * Every number keeps its raw label and a DOM locator as provenance. The
 * adapter never reads pixels and never invents a value the page lacks.
 */
class ArchonSiteAdapter : SiteAdapter {

    override fun read(identity: SourceIdentity, page: FetchedResource, fetcher: ResourceFetcher): SourcePackage {
        val html = page.bodyAsText()
        val document = Jsoup.parse(html, page.finalUrl)
        val pageUrl = page.finalUrl

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.title().substringBefore(" Dane projektu").removePrefix("Projekt domu ").trim()

        val scalars = readScalars(document, pageUrl).toMutableList()
        val construction = readConstruction(document)
        construction.forEach { fact ->
            constructionScalars(fact, pageUrl).forEach { scalar -> scalars += scalar }
        }
        val floors = readFloors(document, pageUrl)
        val assets = readAssets(document, identity, pageUrl)
        val relatedPages = mutableListOf<RelatedPage>()

        costPageUrl(document, identity)?.let { costUrl ->
            val cost = try {
                fetcher.fetch(costUrl)
            } catch (e: FetchException) {
                null
            }
            if (cost != null) {
                relatedPages += RelatedPage(RelatedPageRole.COST_CALCULATION, cost.finalUrl, cost.statusCode, cost.retrievedAtEpochMillis)
                if (cost.isSuccess) {
                    scalars += readCostBenchmarks(Jsoup.parse(cost.bodyAsText(), cost.finalUrl), cost.finalUrl)
                }
            }
        }

        return SourcePackage(
            identity = identity,
            title = title,
            retrievedAtEpochMillis = page.retrievedAtEpochMillis,
            scalars = scalars,
            construction = construction,
            floors = floors,
            assets = AssetManifest(assets + relatedPages.map { related ->
                AssetRecord(
                    role = AssetRole.COST_CALCULATION_PAGE,
                    url = related.url,
                    sourcePageUrl = pageUrl,
                    locator = "a[href*=koszt-budowy]",
                    altText = null,
                    declaredMediaType = "text/html",
                )
            }),
            relatedPages = relatedPages,
            siteTags = readSiteTags(html),
        )
    }

    // ------------------------------------------------------------------ scalars

    private fun readScalars(document: Document, pageUrl: String): List<PublishedScalar> =
        document.select(".product-data__item[data-resource]").mapNotNull { item ->
            val resource = item.attr("data-resource")
            val label = item.selectFirst(".product-data__title")?.text()?.let(ArchonNumbers::clean) ?: return@mapNotNull null
            val valueElement = item.selectFirst(".product-data__value") ?: return@mapNotNull null
            val rawValue = ArchonNumbers.clean(valueElement.text())
            if (rawValue.isBlank()) return@mapNotNull null
            val locator = ".product-data__item[data-resource=$resource] .product-data__value"
            scalarFor(resource, label, rawValue, Provenance(pageUrl, locator, "HTML text of the published parameter"))
        }.flatten()

    private fun scalarFor(resource: String, label: String, rawValue: String, provenance: Provenance): List<PublishedScalar> {
        val key = when {
            resource.startsWith("powierzchnia-netto-domu") -> ScalarKey.HOUSE_NET_AREA
            resource == "powierzchnia-garazu" -> ScalarKey.GARAGE_AREA
            resource == "powierzchnia-kotlowni" -> ScalarKey.BOILER_ROOM_AREA
            resource == "powierzchnia-strychu" -> ScalarKey.ATTIC_STORAGE_AREA
            resource.startsWith("powierzchnia-uzytkowa") -> ScalarKey.USABLE_AREA
            resource == "powierzchnia-zabudowy" -> ScalarKey.FOOTPRINT_AREA
            resource == "powierzchnia-podlog" -> ScalarKey.FLOOR_AREA_TOTAL
            resource == "powierzchnia-calkowita" -> ScalarKey.GROSS_AREA_TOTAL
            resource == "kubatura" -> ScalarKey.VOLUME
            resource == "powierzchnia-dachu" -> ScalarKey.ROOF_AREA
            resource == "wysokosc-budynku" -> ScalarKey.BUILDING_HEIGHT
            resource == "minimalne-wymiary-dzialki" -> return plotScalars(label, rawValue, provenance)
            else -> ScalarKey.OTHER
        }
        val unit = when (key) {
            ScalarKey.VOLUME -> MeasureUnit.CUBIC_METER
            ScalarKey.BUILDING_HEIGHT -> MeasureUnit.METER
            ScalarKey.OTHER -> return listOf(
                PublishedScalar(key, label, rawValue, Measured.missing(MeasureUnit.RATIO, "unclassified published parameter '$label'")),
            )
            else -> MeasureUnit.SQUARE_METER
        }
        val value = ArchonNumbers.first(rawValue)
            ?: return listOf(PublishedScalar(key, label, rawValue, Measured.missing(unit, "published value '$rawValue' is not a number")))
        return listOf(PublishedScalar(key, label, rawValue, Measured.exact(value, unit, provenance, uncertainty = roundingOf(rawValue))))
    }

    private fun plotScalars(label: String, rawValue: String, provenance: Provenance): List<PublishedScalar> {
        val (w, d) = ArchonNumbers.pair(rawValue) ?: return listOf(
            PublishedScalar(ScalarKey.MIN_PLOT_WIDTH, label, rawValue, Measured.missing(MeasureUnit.METER, "plot dimensions '$rawValue' unparsable")),
        )
        return listOf(
            PublishedScalar(ScalarKey.MIN_PLOT_WIDTH, label, rawValue, Measured.exact(w, MeasureUnit.METER, provenance, 0.005)),
            PublishedScalar(ScalarKey.MIN_PLOT_DEPTH, label, rawValue, Measured.exact(d, MeasureUnit.METER, provenance, 0.005)),
        )
    }

    /** Half of the last printed decimal place: `131,16` -> 0.005, `24,1` -> 0.05. */
    private fun roundingOf(rawValue: String): Double {
        val token = Regex("""\d+(?:[.,](\d+))?""").find(rawValue) ?: return 0.5
        val decimals = token.groupValues[1].length
        return 0.5 * Math.pow(10.0, -decimals.toDouble())
    }

    // ------------------------------------------------------------- construction

    /**
     * The construction lines follow the `construction` header as their own
     * `.product-data__item`s, each a `.product-data__title` holding
     * `<strong>label:</strong> text`. Read wherever they sit, keyed on that
     * shape, so a reshuffled page still yields them.
     */
    private fun readConstruction(document: Document): List<ConstructionFact> =
        document.select(".product-data__title")
            .asSequence()
            .mapNotNull { title ->
                val strong = title.selectFirst("strong") ?: return@mapNotNull null
                val label = ArchonNumbers.clean(strong.text()).trimEnd(':').trim()
                val text = ArchonNumbers.clean(title.ownText()).trimStart(':').trim()
                if (label.isBlank() || text.isBlank() || label.length > 60 || text.length > 400) null else ConstructionFact(label, text)
            }
            .distinct()
            .toList()

    private fun constructionScalars(fact: ConstructionFact, pageUrl: String): List<PublishedScalar> {
        val label = fact.label.lowercase()
        val provenance = Provenance(pageUrl, ".product-data__item[data-resource=construction] '${fact.label}:'", "HTML text of the construction line")
        return buildList {
            if (label.startsWith("dach")) {
                ArchonNumbers.pitchDegrees(fact.text)?.let {
                    add(PublishedScalar(ScalarKey.ROOF_PITCH, fact.label, fact.text, Measured.exact(it, MeasureUnit.DEGREE, provenance, 0.5)))
                }
            }
            if (label.contains("kolankow")) {
                ArchonNumbers.lengthMeters(fact.text)?.let {
                    add(PublishedScalar(ScalarKey.KNEE_WALL_HEIGHT, fact.label, fact.text, Measured.exact(it, MeasureUnit.METER, provenance, 0.005)))
                }
            }
        }
    }

    // ------------------------------------------------------------------ floors

    private fun readFloors(document: Document, pageUrl: String): List<PublishedFloor> =
        document.select("table.table-striped").mapIndexedNotNull { index, table ->
            val header = table.selectFirst("thead tr") ?: return@mapIndexedNotNull null
            val cells = header.select("th")
            if (cells.size < 2) return@mapIndexedNotNull null
            val name = ArchonNumbers.clean(cells[0].text())
            if (name.isBlank()) return@mapIndexedNotNull null
            val locator = "table.table-striped:nth-of-type(${index + 1})"
            val usable = cells.getOrNull(1)?.text()?.let(ArchonNumbers::first)
            val floorArea = cells.getOrNull(2)?.text()?.let(ArchonNumbers::first)
            val rooms = table.select("tbody tr").mapIndexedNotNull { rowIndex, row ->
                readRoom(row, "$locator tbody tr:nth-child(${rowIndex + 1})", pageUrl)
            }
            if (rooms.isEmpty()) return@mapIndexedNotNull null
            PublishedFloor(
                name = name,
                printedIndex = index,
                usableAreaTotal = usable?.let { Measured.exact(it, MeasureUnit.SQUARE_METER, Provenance(pageUrl, "$locator thead th:nth-child(2)", "printed storey total"), 0.005) }
                    ?: Measured.missing(MeasureUnit.SQUARE_METER, "storey '$name' prints no usable-area total"),
                floorAreaTotal = floorArea?.let { Measured.exact(it, MeasureUnit.SQUARE_METER, Provenance(pageUrl, "$locator thead th:nth-child(3)", "printed storey floor-area total"), 0.005) }
                    ?: Measured.missing(MeasureUnit.SQUARE_METER, "storey '$name' prints no floor-area total"),
                rooms = rooms,
            )
        }

    private fun readRoom(row: Element, locator: String, pageUrl: String): PublishedRoom? {
        val cells = row.select("td")
        if (cells.size < 2) return null
        val label = ArchonNumbers.clean(cells[0].text())
        if (label.isBlank()) return null
        val ordinalMatch = Regex("""^(\d{1,2})\.\s*(.+)$""").find(label)
        val ordinal = ordinalMatch?.groupValues?.get(1)?.toIntOrNull()
        val name = ordinalMatch?.groupValues?.get(2)?.trim() ?: label
        val usableCell = row.selectFirst("td[title=powierzchnia użytkowa]") ?: cells[1]
        val floorCell = row.selectFirst("td[title=powierzchnia podłogi]") ?: cells.getOrNull(2)
        val usable = ArchonNumbers.first(usableCell.text())
            ?.let { Measured.exact(it, MeasureUnit.SQUARE_METER, Provenance(pageUrl, "$locator td[title=powierzchnia użytkowa]", "printed room usable area"), 0.005) }
            ?: Measured.missing(MeasureUnit.SQUARE_METER, "room '$name' prints no usable area")
        val floorArea = floorCell?.text()?.let(ArchonNumbers::first)
            ?.let { Measured.exact(it, MeasureUnit.SQUARE_METER, Provenance(pageUrl, "$locator td[title=powierzchnia podłogi]", "printed room floor area (parenthesised)"), 0.005) }
            ?: Measured.missing(MeasureUnit.SQUARE_METER, "room '$name' prints no floor area; only usable area is published")
        return PublishedRoom(ordinal, name, usable, floorArea, roomKind(name))
    }

    /** The site's Polish room vocabulary mapped onto [RoomKind]. Unknown names stay OTHER. */
    private fun roomKind(name: String): RoomKind {
        val n = name.lowercase()
        return when {
            n.startsWith("gara") -> RoomKind.GARAGE
            n.startsWith("schod") -> RoomKind.STAIRS
            n.startsWith("wiatro") -> RoomKind.VESTIBULE
            n.startsWith("hol") || n.startsWith("koryta") || n.startsWith("komunik") -> RoomKind.HALL
            n.startsWith("kuchn") -> RoomKind.KITCHEN
            n.startsWith("salon") || n.startsWith("pokój dzienny") || n.startsWith("pokoj dzienny") -> RoomKind.LIVING
            n.startsWith("pok") || n.startsWith("sypial") || n.startsWith("gabinet") -> RoomKind.BEDROOM
            n.startsWith("łazien") || n.startsWith("lazien") || n.startsWith("wc") || n.startsWith("toalet") -> RoomKind.BATHROOM
            n.startsWith("kotłow") || n.startsWith("kotlow") -> RoomKind.BOILER
            n.startsWith("praln") -> RoomKind.LAUNDRY
            n.startsWith("garder") -> RoomKind.WARDROBE
            n.startsWith("spiż") || n.startsWith("spiz") -> RoomKind.PANTRY
            n.startsWith("schow") || n.startsWith("skład") || n.startsWith("sklad") || n.startsWith("pom. gosp") -> RoomKind.STORAGE
            n.startsWith("strych") -> RoomKind.ATTIC_STORAGE
            else -> RoomKind.OTHER
        }
    }

    // ------------------------------------------------------------------ assets

    private fun readAssets(document: Document, identity: SourceIdentity, pageUrl: String): List<AssetRecord> {
        val seen = LinkedHashMap<String, AssetRecord>()
        fun add(record: AssetRecord) {
            val existing = seen[record.url]
            // A URL seen twice keeps its first locator but takes a more specific role if one arrives.
            if (existing == null || (existing.role == AssetRole.UNKNOWN_RELEVANT_IMAGE && record.role != AssetRole.UNKNOWN_RELEVANT_IMAGE)) {
                seen[record.url] = record
            }
        }
        var planCounter = 0
        document.select("img[src*=/images/products/]").forEach { img ->
            val src = img.absUrl("src").takeIf { it.isNotBlank() } ?: return@forEach
            if (!src.contains("/${identity.projectKey}/")) return@forEach
            val alt = img.attr("alt").takeIf { it.isNotBlank() }
            val role = roleFor(alt, src, img)
            val locator = "img[src$=${src.substringAfterLast('/')}]"
            val floorIndex = when (role) {
                AssetRole.PLAN_GROUND -> 0
                AssetRole.PLAN_UPPER -> 1
                AssetRole.PLAN_FLOOR_N -> (planCounter++) + 2
                else -> null
            }
            add(AssetRecord(role, src, pageUrl, locator, alt, guessMediaType(src), floorIndex = floorIndex))
            val areasVariant = img.attr("data-floor-pom-img").takeIf { it.isNotBlank() }
            if (areasVariant != null && role.isPlan) {
                val areasRole = when (role) {
                    AssetRole.PLAN_GROUND -> AssetRole.PLAN_GROUND_WITH_AREAS
                    AssetRole.PLAN_UPPER -> AssetRole.PLAN_UPPER_WITH_AREAS
                    else -> AssetRole.UNKNOWN_RELEVANT_IMAGE
                }
                add(AssetRecord(areasRole, areasVariant, pageUrl, "$locator[data-floor-pom-img]", alt, guessMediaType(areasVariant), floorIndex = floorIndex))
            }
        }
        return seen.values.toList()
    }

    private fun roleFor(alt: String?, src: String, img: Element): AssetRole {
        val a = (alt ?: "").lowercase()
        val s = src.substringAfterLast('/').lowercase()
        val modal = img.attr("data-images-modal-link").lowercase()
        return when {
            a.contains("rzut parteru") || s.startsWith("rzut-parteru") -> if (s.contains("z-powierzchniami")) AssetRole.PLAN_GROUND_WITH_AREAS else AssetRole.PLAN_GROUND
            a.contains("rzut poddasza") || a.contains("rzut piętra") || a.contains("rzut pietra") || s.startsWith("rzut-poddasza") || s.startsWith("rzut-pietra") ->
                if (s.contains("z-powierzchniami")) AssetRole.PLAN_UPPER_WITH_AREAS else AssetRole.PLAN_UPPER
            a.contains("rzut") -> AssetRole.PLAN_FLOOR_N
            a.contains("przekroj") || a.contains("przekrój") || s.startsWith("przekroj") -> AssetRole.SECTION
            a.startsWith("elewacja frontowa") || s.startsWith("elewacja-frontowa") -> AssetRole.ELEVATION_FRONT
            a.startsWith("elewacja ogrodowa") || s.startsWith("elewacja-ogrodowa") -> AssetRole.ELEVATION_REAR
            a.startsWith("elewacja boczna") || s.startsWith("elewacja-boczna") ->
                if (modal.endsWith("2") || s.endsWith("__265.jpg")) AssetRole.ELEVATION_LEFT else AssetRole.ELEVATION_RIGHT
            a.contains("sytuacja") || s.startsWith("sytuacja") -> AssetRole.SITE_PLAN
            a.contains("widok 1") || s.startsWith("widok-1") -> AssetRole.HERO_RENDER
            a.contains("widok") || s.startsWith("widok-") -> AssetRole.UNKNOWN_RELEVANT_IMAGE
            else -> AssetRole.UNKNOWN_RELEVANT_IMAGE
        }
    }

    private fun guessMediaType(url: String): String? = when (url.substringAfterLast('.').lowercase().substringBefore('?')) {
        "gif" -> "image/gif"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> null
    }

    // --------------------------------------------------------------- cost page

    private fun costPageUrl(document: Document, identity: SourceIdentity): String? {
        val linked = document.select("a[href*=koszt-budowy]")
            .map { it.absUrl("href").substringBefore('#') }
            .firstOrNull { it.contains(identity.projectKey) }
        return linked
    }

    private fun readCostBenchmarks(document: Document, costUrl: String): List<PublishedScalar> {
        val block = document.selectFirst("#dane-do-kalkulacji") ?: return emptyList()
        return block.select(".cost-table").mapNotNull { row ->
            val paragraphs = row.select("p")
            if (paragraphs.size < 2) return@mapNotNull null
            val label = ArchonNumbers.clean(paragraphs[0].text())
            val rawValue = ArchonNumbers.clean(paragraphs[1].text())
            val key = benchmarkKey(label) ?: return@mapNotNull null
            val unit = if (key == ScalarKey.ROOF_TIMBER_VOLUME) MeasureUnit.CUBIC_METER else MeasureUnit.SQUARE_METER
            val value = ArchonNumbers.first(rawValue) ?: return@mapNotNull null
            PublishedScalar(
                key = key,
                rawLabel = label,
                rawValue = rawValue,
                measured = Measured.exact(
                    value,
                    unit,
                    Provenance(costUrl, "#dane-do-kalkulacji .cost-table '$label'", "HTML text of the cost-calculation input"),
                    roundingOf(rawValue),
                ),
            )
        }
    }

    private fun benchmarkKey(label: String): ScalarKey? {
        val l = label.lowercase()
        return when {
            l.contains("fundamentow") -> ScalarKey.FOUNDATION_WALL_AREA
            l.contains("ścian zewnętrznych") || l.contains("scian zewnetrznych") -> ScalarKey.EXTERNAL_WALL_AREA
            l.contains("wewnętrznych nośnych") || l.contains("wewnetrznych nosnych") -> ScalarKey.INTERNAL_LOAD_BEARING_WALL_AREA
            l.contains("działowych parter") || l.contains("dzialowych parter") -> ScalarKey.PARTITION_WALL_AREA_GROUND
            l.contains("działowych poddasze") || l.contains("dzialowych poddasze") || l.contains("działowych piętro") -> ScalarKey.PARTITION_WALL_AREA_UPPER
            l.contains("podłóg i schodów") || l.contains("podlog i schodow") -> ScalarKey.FLOORS_AND_STAIRS_AREA
            l.contains("stolarki zewnętrznej") || l.contains("stolarki zewnetrznej") -> ScalarKey.EXTERIOR_JOINERY_AREA
            l.contains("elewacji do ocieplenia") -> ScalarKey.FACADE_INSULATION_AREA
            l.contains("powierzchnia dachu") -> ScalarKey.ROOF_AREA
            l.contains("więźb") || l.contains("wiezb") -> ScalarKey.ROOF_TIMBER_VOLUME
            else -> null
        }
    }

    // --------------------------------------------------------------- site tags

    private val dataLayer = Regex("""dataLayer\.push\((\{"pageType":"Produkt"[^)]*\})\)""")
    private val tagPair = Regex(""""([A-Za-z]+)":"([^"]*)"""")

    private fun readSiteTags(html: String): Map<String, String> {
        val json = dataLayer.find(html)?.groupValues?.get(1) ?: return emptyMap()
        return tagPair.findAll(json).associate { it.groupValues[1] to it.groupValues[2] }
    }

    companion object {
        /** Maps the site's roof vocabulary onto a family the roof solver understands. */
        fun roofFamily(text: String?): PublishedRoofFamily {
            val t = text?.lowercase() ?: return PublishedRoofFamily.UNKNOWN
            return when {
                t.contains("dwuspadow") -> PublishedRoofFamily.GABLE
                t.contains("czterospadow") -> PublishedRoofFamily.HIP
                t.contains("wielospadow") -> PublishedRoofFamily.MULTI_HIP
                t.contains("kopertow") -> PublishedRoofFamily.HIP
                t.contains("płask") || t.contains("plask") -> PublishedRoofFamily.FLAT
                t.contains("pulpitow") -> PublishedRoofFamily.MONO_PITCH
                else -> PublishedRoofFamily.UNKNOWN
            }
        }
    }
}
