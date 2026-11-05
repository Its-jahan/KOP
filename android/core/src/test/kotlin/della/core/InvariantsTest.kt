// Property/invariant tests (port of web/tests/unit/invariants.test.ts plus Android-specific cases).
package della.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class InvariantsTest {
    private fun osmJson(nodes: List<Triple<Long, Pair<Double, Double>, Map<String, String>?>>, ways: List<Triple<Long, List<Long>, Map<String, String>>>, rels: List<JsonElement>): JsonObject {
        val els = ArrayList<JsonElement>()
        for ((id, xy, tags) in nodes) {
            val p = FixtureLib.ll(xy.first, xy.second)
            val m = linkedMapOf<String, JsonElement>("type" to JsonPrimitive("node"), "id" to JsonPrimitive(id), "lat" to ContractJson.num(p.lat), "lon" to ContractJson.num(p.lon))
            if (tags != null) m["tags"] = JsonObject(tags.mapValues { JsonPrimitive(it.value) })
            els.add(JsonObject(m))
        }
        for ((id, ns, tags) in ways) els.add(ContractJson.obj("type" to JsonPrimitive("way"), "id" to JsonPrimitive(id), "nodes" to JsonArray(ns.map { JsonPrimitive(it) }), "tags" to JsonObject(tags.mapValues { JsonPrimitive(it.value) })))
        els.addAll(rels)
        return ContractJson.obj("osm3s" to ContractJson.obj("timestamp_osm_base" to JsonPrimitive("fixture")), "elements" to JsonArray(els))
    }

    private fun randomGrid(seed: Int, n: Int = 6): Pair<GraphIndex, Double> {
        val r = FixtureLib.mulberry32(seed)
        fun id(i: Int, j: Int) = (i * n + j + 1).toLong()
        val nodes = ArrayList<Triple<Long, Pair<Double, Double>, Map<String, String>?>>()
        for (i in 0 until n) for (j in 0 until n) {
            val x = j * 90 + (r() - 0.5) * 20
            val y = i * 90 + (r() - 0.5) * 20
            nodes.add(Triple(id(i, j), x to y, if (r() < 0.05) mapOf("barrier" to "bollard") else null))
        }
        var w = 1000L
        val ways = ArrayList<Triple<Long, List<Long>, Map<String, String>>>()
        fun add(a: Long, b: Long) {
            if (r() < 0.12) return
            val tags = LinkedHashMap<String, String>()
            tags["highway"] = if (r() < 0.1) "track" else "residential"
            val x = r()
            if (x < 0.25) tags["oneway"] = "yes" else if (x < 0.3) tags["oneway"] = "-1"
            if (r() < 0.05) tags["access"] = "private"
            ways.add(Triple(w++, listOf(a, b), tags))
        }
        for (i in 0 until n) for (j in 0 until n - 1) add(id(i, j), id(i, j + 1))
        for (j in 0 until n) for (i in 0 until n - 1) add(id(i, j), id(i + 1, j))
        val rels = ArrayList<JsonElement>()
        var rel = 1L
        for (k in 0 until 6) {
            if (ways.size <= 2) break
            val a = ways[(r() * ways.size).toInt()]
            val b = ways.firstOrNull { it !== a && (it.second[0] == a.second[1] || it.second[1] == a.second[1]) } ?: continue
            rels.add(
                ContractJson.obj(
                    "type" to JsonPrimitive("relation"), "id" to JsonPrimitive(rel++),
                    "members" to JsonArray(listOf(
                        ContractJson.obj("type" to JsonPrimitive("way"), "ref" to JsonPrimitive(a.first), "role" to JsonPrimitive("from")),
                        ContractJson.obj("type" to JsonPrimitive("node"), "ref" to JsonPrimitive(a.second[1]), "role" to JsonPrimitive("via")),
                        ContractJson.obj("type" to JsonPrimitive("way"), "ref" to JsonPrimitive(b.first), "role" to JsonPrimitive("to")),
                    )),
                    "tags" to ContractJson.obj("type" to JsonPrimitive("restriction"), "restriction" to JsonPrimitive(if (r() < 0.7) "no_left_turn" else "only_straight_on")),
                ),
            )
        }
        val g = normalizeOverpass(OverpassResponse.parse(osmJson(nodes, ways, rels)), NormalizeOptions(fetchedAt = "x", dataState = "synthetic_test_fixture", providerId = "fixture"))
        return GraphIndex(g) to (n - 1) * 90.0
    }

    @TestFactory
    fun plannerInvariantsOnRandomGraphs(): List<DynamicTest> = (1..12).flatMap { seed ->
        listOf(BoundaryPolicy.STRICT_INSIDE, BoundaryPolicy.ALLOW_NECESSARY_CONNECTORS).map { policy ->
            DynamicTest.dynamicTest("seed $seed $policy") {
                val (gi, size) = randomGrid(seed)
                val zone = ZoneDefinition.Polygon(FixtureLib.rect(size * 0.15, size * 0.1, size * 0.85, size * 0.9))
                val req = computeRequirements(gi, zone)
                val allow: (Int) -> Boolean = { i -> edgeTraversable(gi, req.zoneInfo, policy, emptySet(), i) }
                val startIdx = gi.edges.indices.firstOrNull { allow(it) && req.zoneInfo[it].insideMeters > 0 } ?: return@dynamicTest
                fun mk() = planCoverage(
                    PlannerInput(
                        gi = gi, zoneInfo = req.zoneInfo, requirements = req.requirements, observed = emptyMap(), excluded = emptySet(), policy = policy,
                        start = StartState(gi.edges[startIdx].edgeId, 0.0, "selected_start"), meta = PlanMeta("p", gi.g.contentHash, 1, 0, 1, "x"),
                    ),
                )
                val plan = mk()
                val v = validatePlan(gi, plan, ValidationContext(req.zoneInfo, policy, req.requirements, emptyMap(), emptySet()))
                assertEquals(emptyList<String>(), v.errors)
                assertEquals(plan.steps.map { it.edgeId }, mk().steps.map { it.edgeId }, "deterministic")
                if (policy == BoundaryPolicy.STRICT_INSIDE) for (s in plan.steps) assertTrue(s.outsideMeters <= STRICT_OUTSIDE_TOL_M)
                val all = req.requirements.map { it.requirementId }.toSet()
                val claimed = plan.coveredRequirementIds.toSet()
                for (u in plan.unreachable) assertFalse(claimed.contains(u.requirementId))
                assertEquals(all, claimed + plan.unreachable.map { it.requirementId })
                for (s in plan.steps) assertEquals("eligible", gi.edges[gi.idx[s.edgeId]!!].eligibility)
            }
        }
    }

    @Test
    fun blockedStartEdgeCanStillBeLeftButIsNeverClaimed() {
        val (gi, size) = randomGrid(3)
        val zone = ZoneDefinition.Polygon(FixtureLib.rect(-30.0, -30.0, size + 30, size + 30))
        val req = computeRequirements(gi, zone)
        val pol = BoundaryPolicy.STRICT_INSIDE
        val allow: (Int) -> Boolean = { i -> edgeTraversable(gi, req.zoneInfo, pol, emptySet(), i) }
        val start = gi.edges.indices.first { i -> allow(i) && req.requirements.any { it.edgeId == gi.edges[i].edgeId } }
        val blocked = req.requirements.filter { it.edgeId == gi.edges[start].edgeId }.map { it.requirementId }.toSet()
        val plan = planCoverage(
            PlannerInput(gi, req.zoneInfo, req.requirements, emptyMap(), blocked, pol, StartState(gi.edges[start].edgeId, 1.0, "gps_position"), startPrefix = intArrayOf(start), meta = PlanMeta("p", gi.g.contentHash, 1, 0, 2, "x")),
        )
        assertNotEquals("failed", plan.status)
        assertEquals(gi.edges[start].edgeId, plan.steps[0].edgeId)
        for (id in blocked) assertFalse(plan.coveredRequirementIds.contains(id))
        for (s in plan.steps.drop(1)) assertNotEquals(gi.edges[start].edgeId, s.edgeId, "blocked edge only used to leave")
        val v = validatePlan(gi, plan, ValidationContext(req.zoneInfo, pol, req.requirements, emptyMap(), blocked, intArrayOf(start)))
        assertEquals(emptyList<String>(), v.errors)
    }

    @Test
    fun unionMonotoneIdempotentBounded() {
        val r = FixtureLib.mulberry32(42)
        var u: List<Interval> = emptyList()
        var prev = 0.0
        repeat(500) {
            val a = r() * 300
            val b = a + r() * 50
            u = addInterval(u, Interval(a, minOf(300.0, b)))
            val len = totalLength(u)
            assertTrue(len >= prev - 1e-9 && len <= 300 + 1e-9)
            prev = len
            assertEquals(u, unionIntervals(u + u))
        }
    }

    @Test
    fun oppositeDirectionsNeverAliasAndReplaysAreIdempotent() {
        val o = FixtureLib.Osm().node(1, 0.0, 0.0).node(2, 250.0, 0.0).way(1, listOf(1, 2), mapOf("highway" to "residential")).json()
        val g = normalizeOverpass(OverpassResponse.parse(o), NormalizeOptions(fetchedAt = "x", providerId = "fixture"))
        val gi = GraphIndex(g)
        var st = initialMatcherState()
        val events = ArrayList<SessionEvent>()
        var seq = 0L
        for (ob in FixtureLib.track(listOf(0.0 to 0.0, 250.0 to 0.0), "o", 12.0, 6.0, noise = 3.0, seed = 7)) {
            val res = processObservation(gi, st, ob, ob.timestamp)
            st = res.state
            for (t in res.traversals) { seq++; events.add(SessionEvent("e$seq", seq, "s", EventType.TRAVERSAL_CONFIRMED, ob.timestamp, SCHEMA_VERSION, ContractJson.obj("traversal" to ContractJson.encodeTraversal(t)))) }
        }
        val ctx = ApplyContext("s", g.contentHash, g.directedEdges.associate { it.edgeId to it.lengthMeters })
        val p1 = applyEvents(emptyProgress(), events, ctx)
        val p2 = applyEvents(applyEvents(emptyProgress(), events, ctx), events, ctx)
        assertEquals(p1.union.toList(), p2.union.toList())
        assertNull(p1.union["osm:w1:n1-n2:b"])
        assertTrue(totalLength(p1.union["osm:w1:n1-n2:f"] ?: emptyList()) > 200)
        val dup = events[0].copy(eventId = "dup", seq = seq + 1)
        val p3 = applyEvents(emptyProgress(), events + dup, ctx)
        assertEquals(totalLength(p1.union["osm:w1:n1-n2:f"]!!), totalLength(p3.union["osm:w1:n1-n2:f"]!!), 1e-6)
        val req = computeRequirements(gi, ZoneDefinition.Polygon(FixtureLib.rect(-20.0, -20.0, 270.0, 20.0)))
        val block = SessionEvent("b", seq + 2, "s", EventType.REQUIREMENT_EXCLUDED, 0, SCHEMA_VERSION, ContractJson.obj("requirementIds" to ContractJson.strs(listOf("osm:w1:n1-n2:b#0")), "reason" to JsonPrimitive("blocked")))
        val p4 = applyEvents(emptyProgress(), events + block, ctx)
        val (m, statuses) = computeMetrics(req.requirements, p4)
        assertEquals("blocked", statuses["osm:w1:n1-n2:b#0"])
        assertTrue(m.confirmedMeters <= m.totalRequiredMeters + 1e-6)
        assertEquals(m.totalRequiredMeters, m.confirmedMeters + m.remainingMeters + m.blockedMeters + m.unreachableMeters + m.needsReviewMeters, 1e-6)
        val foreign = events[0].copy(eventId = "f", sessionId = "other", seq = 999)
        val p5 = applyEvents(emptyProgress(), listOf(foreign), ctx)
        assertTrue(p5.union.isEmpty())
        assertEquals("foreign_session", p5.rejectedEvents[0].second)
    }

    @Test
    fun revisionMismatchCannotOverwrite() {
        val ctx = PlanningContext("a", 1, 3, 2, 5)
        assertFalse(decidePlan(ctx, PlanResultMeta("b", 1, 3, 5, "complete")).accept)
        assertFalse(decidePlan(ctx, PlanResultMeta("a", 1, 2, 5, "complete")).accept)
        assertTrue(decidePlan(ctx, PlanResultMeta("a", 1, 3, 4, "partial")).reconcile == true)
    }

    @Test
    fun zoneGeometry() {
        val bow = ZoneDefinition.Polygon(listOf(LonLat(44.5, 40.18), LonLat(44.502, 40.182), LonLat(44.502, 40.18), LonLat(44.5, 40.182)))
        assertTrue(validateZone(bow).errors.map { it.code }.contains("self_intersection"))
        assertFalse(validateZone(ZoneDefinition.Circle(LonLat(44.5, 40.18), 5.0)).ok)
        val c = circleApproximation(LonLat(44.5, 40.18), 500.0, 1.0)
        assertTrue(c.toleranceMeters <= 1.0001)
        val v = validateZone(ZoneDefinition.Circle(LonLat(44.5, 40.18), 500.0, c))
        assertTrue(v.ok)
        assertTrue(v.areaSqMeters > Math.PI * 500 * 500 * 0.995 && v.areaSqMeters < Math.PI * 500 * 500)
    }

    @Test
    fun corruptOrTruncatedEventLogKeepsValidPrefix() {
        val good = (1..5).map { k -> ContractJson.stringify(ContractJson.encodeEvent(SessionEvent("s:$k", k.toLong(), "s", EventType.PAUSED, k.toLong(), SCHEMA_VERSION, JsonObject(emptyMap())))) }
        val torn = good.take(3) + good[3].take(good[3].length / 2) + good[4]
        val r = recoverEventLog("s", StorageImage(null, torn))
        assertEquals(3L, r.lastValidSeq)
        assertEquals(listOf("corrupt_record", "after_corruption"), r.discarded.map { it.reason })
    }
}
