// Optional: runs only with -PrealOsm=<Overpass JSON>. Mirrors web/scripts/smoke-real.ts
// (circle zone, main-component snap, both policies) and prints fingerprints that can be
// compared with the web reference implementation on the same file.
package della.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

class RealDataSmokeTest {
    @Test
    fun realOverpassExtract() {
        val path = System.getProperty("real.osm") ?: ""
        assumeTrue(path.isNotEmpty() && File(path).exists(), "no real OSM extract given")
        val t0 = System.nanoTime()
        val g = normalizeOverpass(OverpassResponse.parse(File(path).readText()), NormalizeOptions(fetchedAt = "1970-01-01T00:00:00.000Z"))
        val t1 = System.nanoTime()
        val gi = GraphIndex(g)
        println("REAL graph ${g.graphId} hash=${g.contentHash} nodes=${g.nodes.size} edges=${g.directedEdges.size} restrictions=${g.turnRestrictions.size} normalizeMs=${(t1 - t0) / 1_000_000}")
        assertTrue(verifyGraphHash(g))
        for (radius in listOf(300.0, 600.0)) {
            val zone = ZoneDefinition.Circle(LonLat(44.5100, 40.1840), radius)
            for (policy in listOf(BoundaryPolicy.STRICT_INSIDE, BoundaryPolicy.ALLOW_NECESSARY_CONNECTORS)) {
                val req = computeRequirements(gi, zone)
                val allow: (Int) -> Boolean = { i -> edgeTraversable(gi, req.zoneInfo, policy, emptySet(), i) }
                val snap = snapStart(gi, LonLat(44.5100, 40.1840), allow, 150.0, mainComponentPredicate(gi, edgeScc(gi, allow)))!!
                val t2 = System.nanoTime()
                val plan = planCoverage(
                    PlannerInput(
                        gi = gi, zoneInfo = req.zoneInfo, requirements = req.requirements, observed = emptyMap(), excluded = emptySet(), policy = policy,
                        start = StartState(snap.edgeId, snap.offsetMeters, "selected_start"), timeBudgetMs = 1_000_000_000L,
                        meta = PlanMeta("p1", g.contentHash, 1, 0, 1, "x"),
                    ),
                )
                val t3 = System.nanoTime()
                val v = validatePlan(gi, plan, ValidationContext(req.zoneInfo, policy, req.requirements, emptyMap(), emptySet()))
                val causes = plan.unreachable.groupingBy { it.cause }.eachCount().toSortedMap()
                val seqHash = sha256Hex(plan.steps.joinToString(",") { it.edgeId })
                println(
                    "REAL r=$radius $policy reqs=${req.requirements.size} snap=${snap.edgeId}@${"%.3f".format(snap.offsetMeters)} status=${plan.status} steps=${plan.steps.size} " +
                        "covered=${plan.coveredRequirementIds.size} unreach=$causes total=${Math.round(plan.totals.totalMeters)} repeat=${Math.round(plan.totals.repeatMeters)} " +
                        "outside=${Math.round(plan.totals.outsideMeters)} planMs=${(t3 - t2) / 1_000_000} iters=${plan.limits.iterationsUsed} valid=${v.ok} seqSha=${seqHash.take(16)}",
                )
                assertEquals(emptyList<String>(), v.errors.take(5))
            }
        }
    }
}
