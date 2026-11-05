// Port of web/tests/unit/campaign.test.ts (same 8x8 grid, same assertions).
package della.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class CampaignTest {
    companion object {
        private const val N = 8
        private val g: GraphSnapshot
        private val gi: GraphIndex
        private val zone: ZoneDefinition
        private val req: RequirementsResult
        private val base: Campaign
        private val p: CampaignPlan

        init {
            val o = FixtureLib.Osm()
            fun id(i: Int, j: Int) = (i * N + j + 1).toLong()
            for (i in 0 until N) for (j in 0 until N) o.node(id(i, j), j * 100.0, i * 100.0)
            var w = 1000L
            for (i in 0 until N) for (j in 0 until N - 1) o.way(w++, listOf(id(i, j), id(i, j + 1)), mapOf("highway" to "residential"))
            for (j in 0 until N) for (i in 0 until N - 1) o.way(w++, listOf(id(i, j), id(i + 1, j)), mapOf("highway" to "residential"))
            g = normalizeOverpass(OverpassResponse.parse(o.json()), NormalizeOptions(fetchedAt = "x", providerId = "fixture"))
            gi = GraphIndex(g)
            zone = ZoneDefinition.Polygon(FixtureLib.rect(-40.0, -40.0, (N - 1) * 100.0 + 40, (N - 1) * 100.0 + 40))
            req = computeRequirements(gi, zone)
            base = Campaign(
                campaignId = "c1", name = "t", createdAt = "x", graphId = g.graphId, contentHash = g.contentHash, zone = zone, policy = "STRICT_INSIDE",
                home = CampaignHome(LonLat(0.0, 0.0), "osm:w1000:n1-n2:f", 0.0),
                settings = CampaignSettings(0.5, 14.0), confirmed = emptyList(), history = emptyList(),
            )
            p = planCampaign(gi, req.zoneInfo, req.requirements, base, "p", "x")
        }
    }

    private fun closeTo(expected: Double, actual: Double, digits: Int = 3) =
        assertTrue(abs(expected - actual) < Math.pow(10.0, -digits.toDouble()) / 2, "expected $actual ≈ $expected")

    @Test
    fun `splits all plannable work into days within the budget`() {
        assertTrue(p.days.size > 2)
        val budget = 0.5 * 14 * 1000
        for (d in p.days) {
            if (d.steps.size > 1) assertTrue(d.totalMeters <= budget + 1, "day ${d.day} ${d.totalMeters}")
            assertTrue(d.targetIds.isNotEmpty())
        }
        val all = p.days.flatMap { it.targetIds }.toSet()
        assertEquals(p.fullPlan.coveredRequirementIds.toSet(), all)
        assertEquals(emptyList<String>(), p.unplannable)
    }

    @Test
    fun `day 1 starts at home, later days start with an approach from home`() {
        assertEquals(emptyList<PlanStep>(), p.days[0].approach)
        assertEquals(base.home.edgeId, p.days[0].steps[0].edgeId)
        assertTrue(p.days[1].approach.isNotEmpty())
        assertEquals(base.home.edgeId, p.days[1].approach[0].edgeId)
    }

    @Test
    fun `confirming a driven day removes it from the remaining plan, undo restores it`() {
        // simulate driving day 1 exactly
        val driven = LinkedHashMap<String, List<Interval>>()
        for (s in p.days[0].steps) driven[s.edgeId] = (driven[s.edgeId] ?: emptyList()) + Interval(s.fromOffsetMeters, s.toOffsetMeters)
        val done = newlyDriven(req.requirements, emptyMap(), driven)
        assertEquals(p.days[0].targetIds.size, done.size)
        val c2 = confirmDay(base, done, driven, "s1", "2026-11-05")
        val p2 = planCampaign(gi, req.zoneInfo, req.requirements, c2, "p2", "x")
        val remainingTargets = p2.days.flatMap { it.targetIds }.toSet()
        for (r in done) assertFalse(remainingTargets.contains(r.requirementId))
        val conf = c2.confirmed.toMap()
        for (r in done) assertTrue(requirementCovered(conf[r.edgeId] ?: emptyList(), r))
        assertTrue(p2.remainingMeters < p.remainingMeters)
        val c3 = undoLastDay(c2, req.requirements)
        assertEquals(0, c3.history.size)
        val p3 = planCampaign(gi, req.zoneInfo, req.requirements, c3, "p3", "x")
        closeTo(p.remainingMeters, p3.remainingMeters)
    }

    @Test
    fun `only what was confirmed counts - driving without confirming changes nothing`() {
        val p4 = planCampaign(gi, req.zoneInfo, req.requirements, base, "p4", "x")
        closeTo(p.remainingMeters, p4.remainingMeters)
    }

    @Test
    fun `day split matches the web reference implementation`() {
        // Produced by web/src/core/campaign.ts on the same grid (approach/steps/targets/total m/minutes).
        val web = "0/61/60/6819.2342/29 8/49/44/6816.6286/29 13/49/42/6952.5103/30 8/49/41/6814.6887/29 13/46/35/6012.7122/26 3/12/2/2294.9259/10"
        val kt = p.days.joinToString(" ") { d -> "${d.approach.size}/${d.steps.size}/${d.targetIds.size}/${String.format(java.util.Locale.ROOT, "%.4f", d.totalMeters)}/${d.minutes}" }
        assertEquals(web, kt)
        assertEquals("22449.884000", String.format(java.util.Locale.ROOT, "%.6f", p.remainingMeters))
    }

    @Test
    fun `a day plan is approach from home plus that day's steps and targets only`() {
        val rp = campaignDayPlan(p, 1, req.requirements, "d2", "x")
        val d = p.days[1]
        assertEquals(d.approach.size + d.steps.size, rp.steps.size)
        assertEquals(base.home.edgeId, rp.steps[0].edgeId)
        assertTrue(rp.steps.take(d.approach.size).all { it.kind == "approach" })
        assertEquals(d.targetIds, rp.coveredRequirementIds)
        assertEquals("complete", rp.status)
        assertTrue(rp.unreachable.isEmpty())
        assertTrue(rp.totals.newCoverageMeters > 0)
        assertTrue(rp.totals.approachMeters >= d.approachMeters - 1e-6)
        assertEquals(rp.steps.size, rp.legs.last().lastStep + 1)
    }

    @Test
    fun `campaign survives a JSON round trip`() {
        val driven = LinkedHashMap<String, List<Interval>>()
        for (s in p.days[0].steps) driven[s.edgeId] = (driven[s.edgeId] ?: emptyList()) + Interval(s.fromOffsetMeters, s.toOffsetMeters)
        val c2 = confirmDay(base, newlyDriven(req.requirements, emptyMap(), driven), driven, "s1", "2026-11-05")
        val back = CampaignJson.decode(ContractJson.parse(ContractJson.stringify(CampaignJson.encode(c2))))
        assertEquals(c2, back)
        val c3 = c2.copy(history = c2.history.map { it.copy(sessionId = null) })
        assertEquals(c3, CampaignJson.decode(ContractJson.parse(ContractJson.stringify(CampaignJson.encode(c3)))))
    }
}
