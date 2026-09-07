package com.buildplan.app.reference

import com.buildplan.app.domain.model.Building
import com.buildplan.app.domain.model.BuildingId
import com.buildplan.app.domain.model.Floor
import com.buildplan.app.domain.model.FloorId
import com.buildplan.app.domain.model.Project
import com.buildplan.app.domain.model.ProjectId
import com.buildplan.app.domain.model.Room
import com.buildplan.app.domain.model.RoomId
import com.buildplan.app.domain.units.Quantity
import com.buildplan.app.domain.units.UnitOfMeasure

/**
 * Where a reference dataset came from.
 *
 * Provenance lives here and not on [Project], because a source URL is a fact
 * about how a development fixture was assembled, not a property of a user's
 * construction project. Putting it in the domain would make every real project
 * carry a field only this fixture can fill.
 */
data class ReferenceProjectSource(
    val title: String,
    val url: String,
    /** Public scalar facts stated by the source, kept out of the canonical model. */
    val statedFacts: ReferenceProjectFacts,
    /** Why the encoded numbers are the ones they are. */
    val notes: List<String>,
)

/**
 * Public scalar facts the source page states about the house.
 *
 * These are deliberately *not* pushed into the domain: the model has no concept
 * of "usable area excluding the garage", of a roof surface or of a ridge height,
 * and inventing those fields to hold a fixture's trivia would expand the domain
 * for a debug artefact. They sit here so a later stage can read them without
 * going back to the web page.
 */
data class ReferenceProjectFacts(
    val houseArea: Quantity,
    val garageArea: Quantity,
    val boilerRoomArea: Quantity,
    val roofArea: Quantity,
    val buildingHeight: Quantity,
    val roofShape: String,
    val roofPitchDegrees: Double,
)

/**
 * One deterministic, house-shaped [Project] for development and renderer work.
 *
 * It exists so that the coming building model has a stable semantic input that
 * is shaped like a real two-storey house — two storeys, eighteen rooms, real
 * areas — without anyone first having to build a parser, a network layer or a
 * persistence layer to obtain one.
 *
 * **Debug source set on purpose.** This is development scaffolding, not product
 * content: it lives in `src/debug` so the release build neither compiles nor
 * ships it, and so it can never be mistaken for a user's own project.
 *
 * **No geometry.** The source states room names, room areas and floor totals.
 * It does not state wall coordinates, room polygons, adjacency, openings, stair
 * geometry or floor elevations, so none of those are represented here — an
 * invented plan that looked authoritative would be worse than no plan at all.
 * [Building.elements] is therefore empty; the geometry contract belongs to
 * STAGE-011.
 *
 * **One area per room.** The source shows an alternate value in parentheses for
 * some rooms and totals. Only the primary stated area is encoded: a second area
 * concept is not part of the accepted domain contract, and guessing at its
 * meaning would put an unverified number into the model.
 */
object MarcowkiReferenceProject {

    val source: ReferenceProjectSource = ReferenceProjectSource(
        title = "ARCHON+ — Dom w marcówkach (GE)",
        url = "https://www.archon.pl/projekty-domow/projekt-dom-w-marcowkach-ge-m2fa281446a8ca",
        statedFacts = ReferenceProjectFacts(
            houseArea = squareMeters(129.04),
            garageArea = squareMeters(24.10),
            boilerRoomArea = squareMeters(5.80),
            roofArea = squareMeters(150.57),
            buildingHeight = Quantity(8.27, UnitOfMeasure.METER),
            roofShape = "dwuspadowy",
            roofPitchDegrees = 40.0,
        ),
        notes = listOf(
            "Only the primary stated area of each room is encoded. The source " +
                "also shows alternate values in parentheses for some rooms and " +
                "floor totals; their semantics are not part of the domain contract.",
            "No wall coordinates, room polygons, openings or stair geometry are " +
                "stated by the source, so none are represented.",
            "Public factual data only: no images, drawings or descriptions from " +
                "the source are copied into this repository.",
        ),
    )

    val projectId: ProjectId = ProjectId(PROJECT_KEY)
    val buildingId: BuildingId = BuildingId("$PROJECT_KEY-building")

    val groundFloorId: FloorId = floorId("parter")
    val atticId: FloorId = floorId("poddasze")

    /** Stated total of the ground floor, for cross-checking the encoded rooms. */
    val groundFloorStatedArea: Quantity = squareMeters(96.50)

    /** Stated total of the attic storey, for cross-checking the encoded rooms. */
    val atticStatedArea: Quantity = squareMeters(62.44)

    /**
     * Parter — nine rooms, listed in the order the source lists them.
     *
     * Room ids are derived from the storey and a stable slug rather than from
     * the name alone, because names repeat: this house has one "Pokój" here and
     * three more upstairs.
     */
    val groundFloor: Floor = Floor(
        id = groundFloorId,
        name = "Parter",
        order = 0,
        rooms = listOf(
            room(groundFloorId, "wiatrolap", "Wiatrołap", 3.70),
            room(groundFloorId, "hol", "Hol", 9.18),
            room(groundFloorId, "kuchnia", "Kuchnia", 9.63),
            room(groundFloorId, "salon-jadalnia", "Salon + Jadalnia", 29.52),
            room(groundFloorId, "spizarnia", "Spiżarnia", 1.44),
            room(groundFloorId, "lazienka", "Łazienka", 3.95),
            room(groundFloorId, "pokoj", "Pokój", 9.18),
            room(groundFloorId, "kotlownia", "Kotłownia", 5.80),
            room(groundFloorId, "garaz", "Garaż", 24.10),
        ),
    )

    /** Poddasze — nine rooms, listed in the order the source lists them. */
    val attic: Floor = Floor(
        id = atticId,
        name = "Poddasze",
        order = 1,
        rooms = listOf(
            room(atticId, "korytarz", "Korytarz", 6.17),
            room(atticId, "pokoj-1", "Pokój", 10.25),
            room(atticId, "garderoba-1", "Garderoba", 5.19),
            room(atticId, "lazienka", "Łazienka", 6.40),
            room(atticId, "pralnia", "Pralnia", 5.59),
            room(atticId, "pokoj-2", "Pokój", 12.57),
            room(atticId, "pokoj-3", "Pokój", 9.02),
            room(atticId, "garderoba-2", "Garderoba", 1.62),
            room(atticId, "schody", "Schody", 5.63),
        ),
    )

    /**
     * The reference project. Built on demand so that a caller experimenting with
     * a copy cannot mutate what the next caller sees.
     */
    fun build(): Project = Project(
        id = projectId,
        name = "Dom w marcówkach (GE)",
        building = Building(
            id = buildingId,
            floors = listOf(groundFloor, attic),
            // Empty on purpose: the source states no element geometry or
            // topology, and STAGE-011 owns that contract.
            elements = emptyList(),
        ),
    )
}

private const val PROJECT_KEY = "ref-archon-marcowki-ge"

private fun floorId(slug: String): FloorId = FloorId("$PROJECT_KEY-$slug")

private fun room(floor: FloorId, slug: String, name: String, areaM2: Double): Room = Room(
    id = RoomId("${floor.value}-$slug"),
    name = name,
    area = squareMeters(areaM2),
)

private fun squareMeters(value: Double): Quantity = Quantity(value, UnitOfMeasure.SQUARE_METER)
