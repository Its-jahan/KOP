// Port of web/tests/unit/streets.test.ts (same 4x4 grid, same assertions).
package della.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

class StreetsTest {
    companion object {
        private const val N = 4
        private val g: GraphSnapshot
        private val gi: GraphIndex
        private val zone: ZoneDefinition.Polygon
        private val req: RequirementsResult
        private val lines: List<List<LonLat>>

        init {
            val o = FixtureLib.Osm()
            fun id(i: Int, j: Int) = (i * N + j + 1).toLong()
            for (i in 0 until N) for (j in 0 until N) o.node(id(i, j), j * 100.0, i * 100.0)
            var w = 1000L
            for (i in 0 until N) for (j in 0 until N - 1) o.way(w++, listOf(id(i, j), id(i, j + 1)), mapOf("highway" to "residential"))
            for (j in 0 until N) for (i in 0 until N - 1) o.way(w++, listOf(id(i, j), id(i + 1, j)), mapOf("highway" to "residential"))
            g = normalizeOverpass(OverpassResponse.parse(o.json()), NormalizeOptions(fetchedAt = "x", providerId = "fixture"))
            gi = GraphIndex(g)
            zone = ZoneDefinition.Polygon(FixtureLib.rect(-40.0, -40.0, 340.0, 340.0))
            req = computeRequirements(gi, zone)
            lines = listOf(listOf(FixtureLib.ll(0.0, 3.0), FixtureLib.ll(300.0, 3.0)), listOf(FixtureLib.ll(-3.0, 0.0), FixtureLib.ll(-3.0, 300.0)))
        }

        private fun total(a: List<RequiredInterval>) = a.sumOf { it.lengthMeters }
    }

    @Test
    fun keepsOnlyRequirementsAlongListedStreets() {
        val f = filterRequirementsByStreets(gi, req.requirements, lines)
        assertTrue(abs(total(f) - 1200) < 5, "total ${total(f)}")
        val o = FixtureLib.ll(0.0, 0.0)
        for (r in f) {
            val e = gi.edges[gi.idx[r.edgeId]!!]
            assertTrue(e.geometry.all { abs(it.lat - o.lat) < 1e-6 || abs(it.lon - o.lon) < 1e-6 })
        }
    }

    @Test
    fun ignoresCrossingStreets() {
        val f = filterRequirementsByStreets(gi, req.requirements, listOf(listOf(FixtureLib.ll(0.0, 100.0), FixtureLib.ll(300.0, 100.0))))
        assertTrue(abs(total(f) - 600) < 5, "total ${total(f)}")
    }

    @Test
    fun parsesOurFormatAndGeoJson() {
        val a = StreetSetJson.parse(StreetSetJson.encode(StreetSet("x", "x", "x", zone.coordinates, lines)), "f")
        assertEquals(2, a.lines.size)
        assertEquals(zone.coordinates, a.ring)
        val ring = (zone.coordinates + zone.coordinates[0]).joinToString(",") { "[${it.lon},${it.lat}]" }
        val ml = lines.joinToString(",") { l -> "[" + l.joinToString(",") { "[${it.lon},${it.lat}]" } + "]" }
        val b = StreetSetJson.parse(
            ContractJson.parse("""{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$ring]]}},{"type":"Feature","geometry":{"type":"MultiLineString","coordinates":[$ml]}}]}"""),
            "f",
        )
        assertEquals(zone.coordinates.size, b.ring.size)
        assertEquals(2, b.lines.size)
        assertThrows<IllegalArgumentException> { StreetSetJson.parse(ContractJson.parse("""{"foo":1}"""), "f") }
    }

    @Test
    fun campaignWithStreetSetPlansOnlyThoseStreets() {
        val f = filterRequirementsByStreets(gi, req.requirements, lines)
        val o = FixtureLib.ll(0.0, 0.0)
        val start = gi.edges.first { it.geometry[0] == o && it.geometry[1].lat == o.lat }
        val c = Campaign(
            campaignId = "c", name = "c", createdAt = "x", graphId = g.graphId, contentHash = g.contentHash, zone = zone, policy = "STRICT_INSIDE",
            home = CampaignHome(o, start.edgeId, 0.0), settings = CampaignSettings(4.0, 14.0), confirmed = emptyList(), history = emptyList(),
            streetSet = StreetSet("s", "s", "s", zone.coordinates, lines),
        )
        val p = planCampaign(gi, req.zoneInfo, f, c, "p", "x")
        assertTrue(abs(p.remainingMeters - 1200) < 5)
        assertEquals(emptyList<String>(), p.unplannable)
        assertEquals(f.size, p.days.flatMap { it.targetIds }.toSet().size)
        val round = CampaignJson.decode(CampaignJson.encode(c))
        assertEquals(c.streetSet, round.streetSet)
    }
}
