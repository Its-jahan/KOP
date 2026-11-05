package com.della.coveragenavigator

import della.core.GraphIndex
import della.core.LocationObservation
import della.core.LonLat
import della.core.NormalizeOptions
import della.core.OverpassResponse
import della.core.PlanMeta
import della.core.PlannerInput
import della.core.StartState
import della.core.ZoneDefinition
import della.core.buildManeuvers
import della.core.computeRequirements
import della.core.normalizeOverpass
import della.core.planCoverage
import della.core.stepStartDistances
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class NavigatorTest {
    private val mLon = 111_320 * cos(Math.toRadians(40.18))
    private fun ll(x: Double, y: Double) = LonLat(44.5 + x / mLon, 40.18 + y / 110_574)

    // A(0,0)-B(100,0)-C(200,0); B-D(100,100); C-E(200,100); D-E; plus an outside spur D-F(0,100)
    private val osm = """
        {"osm3s":{"timestamp_osm_base":"t"},"elements":[
        ${listOf(1 to (0.0 to 0.0), 2 to (100.0 to 0.0), 3 to (200.0 to 0.0), 4 to (100.0 to 100.0), 5 to (200.0 to 100.0), 6 to (0.0 to 100.0))
        .joinToString(",") { (id, xy) -> val p = ll(xy.first, xy.second); "{\"type\":\"node\",\"id\":$id,\"lat\":${p.lat},\"lon\":${p.lon}}" }},
        {"type":"way","id":101,"nodes":[1,2,3],"tags":{"highway":"residential","name":"Main"}},
        {"type":"way","id":102,"nodes":[2,4],"tags":{"highway":"residential","name":"West"}},
        {"type":"way","id":103,"nodes":[3,5],"tags":{"highway":"residential","name":"East"}},
        {"type":"way","id":104,"nodes":[4,5],"tags":{"highway":"residential","name":"North"}},
        {"type":"way","id":105,"nodes":[6,4],"tags":{"highway":"residential","name":"Spur"}}
        ]}
    """.trimIndent()

    private val g = normalizeOverpass(OverpassResponse.parse(osm), NormalizeOptions(fetchedAt = "x", providerId = "fixture"))
    private val gi = GraphIndex(g)
    // Zone covers the grid but not the spur west of D (x < 50 at y = 100).
    private val zone = ZoneDefinition.Polygon(listOf(ll(-30.0, -30.0), ll(230.0, -30.0), ll(230.0, 130.0), ll(60.0, 130.0), ll(60.0, 40.0), ll(-30.0, 40.0)))
    private val req = computeRequirements(gi, zone)
    private val plan = planCoverage(
        PlannerInput(
            gi, req.zoneInfo, req.requirements, emptyMap(), emptySet(), "STRICT_INSIDE",
            StartState("osm:w101:n1-n2:f", 0.0, "selected_start"),
            meta = PlanMeta("p1", g.contentHash, 1, 0, 1, "x"),
        ),
    )
    private val mans = buildManeuvers(gi, plan)
    private val starts = stepStartDistances(plan)

    private fun fix(x: Double, y: Double, bearing: Double?, speed: Double?, acc: Double = 5.0): LocationObservation {
        val p = ll(x, y)
        return LocationObservation("o", 0, null, p.lon, p.lat, acc, speed, null, bearing, null, null, null, "gps", 0.0)
    }

    @Test
    fun onRouteAnnouncesNextTurnWithDistance() {
        val nav = Navigator().compute(gi, plan, mans, starts, fix(50.0, 1.0, 90.0, 10.0), 500, 0)
        assertNotNull(nav)
        assertEquals("route", nav!!.mode)
        assertTrue("first maneuver should be a turn, was ${nav.type}", nav.type in setOf("left", "right", "turnaround"))
        assertTrue("distance ${nav.distanceMeters}", nav.distanceMeters in 30.0..200.0)
        assertTrue(nav.ahead.isNotEmpty())
    }

    @Test
    fun stationaryAtStartStillGetsInstructions() {
        val nav = Navigator().compute(gi, plan, mans, starts, fix(2.0, 3.0, null, 0.0), 500, 0)
        assertNotNull(nav)
        assertEquals("route", nav!!.mode)
    }

    @Test
    fun awayFromRouteGetsLegalApproachBackToTheRoute() {
        // On the spur, west of D, driving east towards D. The plan's start is A, far from here.
        val nav = Navigator().compute(gi, plan, mans, starts, fix(20.0, 100.0, 90.0, 8.0), 500, 0)
        assertNotNull(nav)
        assertEquals("approach", nav!!.mode)
        assertTrue("rejoin ${nav.rejoinMeters}", (nav.rejoinMeters ?: 0.0) > 150)
        assertTrue(nav.distanceMeters in 50.0..120.0) // first decision at D
        assertTrue(nav.ahead.isNotEmpty())
    }

    @Test
    fun offAnyRoadPointsToTheRoute() {
        val nav = Navigator().compute(gi, plan, mans, starts, fix(-150.0, 300.0, null, null), 500, 0)
        assertNotNull(nav)
        assertEquals("to_road", nav!!.mode)
        assertTrue(nav.headBearing!! in 90.0..270.0) // route lies to the south-east
    }

    @Test
    fun staleOrInaccurateFixGivesNoInstruction() {
        val n = Navigator()
        assertEquals(null, n.compute(gi, plan, mans, starts, fix(50.0, 0.0, 90.0, 10.0), 20_000, 0))
        assertEquals(null, n.compute(gi, plan, mans, starts, fix(50.0, 0.0, 90.0, 10.0, acc = 120.0), 500, 0))
    }

    // ---- lane guidance ----

    @Test
    fun laneHintsRightTurnMarksOnlyRightLanes() {
        val h = laneHints(listOf(listOf("left"), listOf("through"), listOf("through", "right")), "right")!!
        assertEquals(listOf(false, false, true), h.map { it.ok })
    }

    @Test
    fun laneHintsLeftTurnAndStraightOn() {
        assertEquals(listOf(true, true, false), laneHints(listOf(listOf("left"), listOf("left", "through"), listOf("through", "right")), "left")!!.map { it.ok })
        assertEquals(listOf(false, true, false), laneHints(listOf(listOf("left"), listOf("through"), listOf("right")), "continue")!!.map { it.ok })
    }

    @Test
    fun laneHintsNoAdviceWhenAllWorkNothingMatchesOrUnmapped() {
        assertNull(laneHints(listOf(listOf("through", "right"), listOf("through", "right")), "right"))
        assertNull(laneHints(listOf(listOf("left"), listOf("through")), "right"))
        assertNull(laneHints(null, "right"))
    }

    @Test
    fun navigatorAttachesLanesFromApproachingRoad() {
        // Approach road A(0,0)->B(150,0) has lanes; at B the route turns right (south) to C(150,-100).
        val pts = listOf(1 to (0.0 to 0.0), 2 to (150.0 to 0.0), 3 to (150.0 to -100.0), 4 to (300.0 to 0.0))
        val osm2 = """
            {"osm3s":{"timestamp_osm_base":"t"},"elements":[
            ${pts.joinToString(",") { (id, xy) -> val p = ll(xy.first, xy.second); "{\"type\":\"node\",\"id\":$id,\"lat\":${p.lat},\"lon\":${p.lon}}" }},
            {"type":"way","id":1,"nodes":[1,2],"tags":{"highway":"secondary","oneway":"yes","turn:lanes":"left|through|through;right"}},
            {"type":"way","id":2,"nodes":[2,3],"tags":{"highway":"secondary","oneway":"yes"}},
            {"type":"way","id":3,"nodes":[2,4],"tags":{"highway":"secondary","oneway":"yes"}}
            ]}
        """.trimIndent()
        val g2 = normalizeOverpass(OverpassResponse.parse(osm2), NormalizeOptions(fetchedAt = "x", providerId = "fixture"))
        val gi2 = GraphIndex(g2)
        val z2 = ZoneDefinition.Polygon(listOf(ll(-30.0, 30.0), ll(170.0, 30.0), ll(170.0, -130.0), ll(-30.0, -130.0)))
        val r2 = computeRequirements(gi2, z2)
        val p2 = planCoverage(
            PlannerInput(
                gi2, r2.zoneInfo, r2.requirements, emptyMap(), emptySet(), "STRICT_INSIDE",
                StartState("osm:w1:n1-n2:f", 0.0, "selected_start"),
                meta = PlanMeta("p2", g2.contentHash, 1, 0, 1, "x"),
            ),
        )
        assertEquals(listOf("osm:w1:n1-n2:f", "osm:w2:n2-n3:f"), p2.steps.map { it.edgeId })
        val n = Navigator().compute(gi2, p2, buildManeuvers(gi2, p2), stepStartDistances(p2), fix(80.0, 1.0, 90.0, 10.0), 500, 0)!!
        assertEquals("right", n.type)
        assertEquals(listOf(false, false, true), n.lanes!!.map { it.ok })
    }
}
