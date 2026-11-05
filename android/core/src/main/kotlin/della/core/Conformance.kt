// Conformance runner for /contract/fixtures (port of web/src/core/conformance.ts).
// Produces the same JSON shape the reference runner produces so outputs can be compared
// key-by-key against `expected` (see CONFORMANCE.md).
package della.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

object Conformance {
    private fun r2(x: Double) = jsRound(x * 100) / 100
    private fun r1(x: Double) = jsRound(x * 10) / 10
    private fun num(d: Double) = ContractJson.num(d)
    private fun strs(l: List<String>) = ContractJson.strs(l)

    private fun parseStart(e: JsonElement?): Pair<String, Double>? {
        val o = e as? JsonObject ?: return null
        return (o["edgeId"] as JsonPrimitive).content to (o["offsetMeters"] as JsonPrimitive).doubleOrNull!!
    }

    private fun mergedConfig(o: JsonObject?): MatcherConfig {
        var c = DEFAULT_MATCHER_CONFIG
        if (o == null) return c
        fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
        d("maxAccuracyM")?.let { c = c.copy(maxAccuracyM = it) }
        d("maxFixAgeMs")?.let { c = c.copy(maxFixAgeMs = it) }
        d("maxGapMs")?.let { c = c.copy(maxGapMs = it) }
        d("maxSpeedMps")?.let { c = c.copy(maxSpeedMps = it) }
        d("searchRadiusMinM")?.let { c = c.copy(searchRadiusMinM = it) }
        d("searchRadiusMaxM")?.let { c = c.copy(searchRadiusMaxM = it) }
        d("minAdvanceM")?.let { c = c.copy(minAdvanceM = it) }
        d("commitMinM")?.let { c = c.copy(commitMinM = it) }
        d("ambiguityMarginM")?.let { c = c.copy(ambiguityMarginM = it) }
        d("maxJunctionPathM")?.let { c = c.copy(maxJunctionPathM = it) }
        d("minMovingSpeedMps")?.let { c = c.copy(minMovingSpeedMps = it) }
        d("headingMinSpeedMps")?.let { c = c.copy(headingMinSpeedMps = it) }
        d("headingToleranceDeg")?.let { c = c.copy(headingToleranceDeg = it) }
        return c
    }

    /** Run a `kind: graph` fixture input. */
    fun runFixture(input: JsonObject): JsonObject {
        val g = normalizeOverpass(
            OverpassResponse.parse(input["osm"] as JsonObject),
            NormalizeOptions(fetchedAt = "1970-01-01T00:00:00.000Z", dataState = "synthetic_test_fixture", providerId = "fixture"),
        )
        val gi = GraphIndex(g)
        val zone = ContractJson.decodeZoneDefinition(input["zone"]!!)
        val req = computeRequirements(gi, zone)
        val graph = ContractJson.obj(
            "contentHash" to JsonPrimitive(g.contentHash),
            "graphId" to JsonPrimitive(g.graphId),
            "nodeCount" to JsonPrimitive(g.nodes.size),
            "edgeCount" to JsonPrimitive(g.directedEdges.size),
            "restrictionCount" to JsonPrimitive(g.turnRestrictions.size),
            "edgeIds" to strs(g.directedEdges.map { it.edgeId }),
            "eligibility" to JsonObject(g.directedEdges.associate { it.edgeId to JsonPrimitive(it.eligibility) }),
            "eligibilityReason" to JsonObject(g.directedEdges.filter { truthy(it.eligibilityReason) }.associate { it.edgeId to JsonPrimitive(it.eligibilityReason!!) }),
            "turnLanes" to JsonObject(g.directedEdges.filter { it.turnLanes != null }.associate { e -> e.edgeId to JsonPrimitive(e.turnLanes!!.joinToString("|") { it.joinToString(";") }) }),
            "nodePassage" to JsonObject(g.nodes.filter { it.passage != "free" }.associate { it.nodeId to JsonPrimitive(it.passage) }),
            "restrictions" to JsonArray(
                g.turnRestrictions.map { r ->
                    ContractJson.obj(
                        "id" to JsonPrimitive(r.restrictionId),
                        "kind" to JsonPrimitive(r.kind),
                        "from" to JsonPrimitive(r.fromEdgeId),
                        "via" to (r.viaNodeId?.let { JsonPrimitive(it) } ?: strs(r.viaEdgeIds)),
                        "to" to JsonPrimitive(r.toEdgeId),
                    )
                },
            ),
        )
        val requirements = JsonArray(
            req.requirements.map { r ->
                ContractJson.obj(
                    "requirementId" to JsonPrimitive(r.requirementId),
                    "edgeId" to JsonPrimitive(r.edgeId),
                    "start" to num(r2(r.startOffsetMeters)),
                    "end" to num(r2(r.endOffsetMeters)),
                    "status" to JsonPrimitive(r.status),
                )
            },
        )
        val policies = (input["policies"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()
        val start = parseStart(input["start"])
        val returnToStart = (input["returnToStart"] as? JsonPrimitive)?.booleanOrNull ?: false
        val maxIter = ((input["plannerLimits"] as? JsonObject)?.get("maxIterations") as? JsonPrimitive)?.doubleOrNull?.toLong()
        val wantManeuvers = (input["maneuvers"] as? JsonPrimitive)?.booleanOrNull ?: false
        val plans = LinkedHashMap<String, JsonElement>()
        val planObjs = HashMap<String, RoutePlan>()
        for (policy in policies) {
            if (start == null) continue
            val plan = planCoverage(
                PlannerInput(
                    gi = gi, zoneInfo = req.zoneInfo, requirements = req.requirements, observed = emptyMap(), excluded = emptySet(), policy = policy,
                    start = StartState(start.first, start.second, "selected_start"), returnToStart = returnToStart,
                    maxIterations = maxIter, timeBudgetMs = 1_000_000_000L,
                    meta = PlanMeta("fixture-$policy", g.contentHash, 1, 0, 1, "1970-01-01T00:00:00.000Z"),
                ),
            )
            planObjs[policy] = plan
            val v = validatePlan(gi, plan, ValidationContext(req.zoneInfo, policy, req.requirements, emptyMap(), emptySet()))
            val m = linkedMapOf<String, JsonElement>(
                "status" to JsonPrimitive(plan.status),
                "edgeSequence" to strs(plan.steps.map { it.edgeId }),
                "stepKinds" to strs(plan.steps.map { it.kind }),
                "turnarounds" to strs(plan.steps.mapIndexedNotNull { i, s -> if (!s.turnaroundAtStart.isNullOrEmpty()) "$i:${s.turnaroundAtStart}" else null }),
                "coveredRequirementIds" to strs(plan.coveredRequirementIds.sorted()),
                "unreachable" to strs(plan.unreachable.map { "${it.requirementId}:${it.cause}" }),
                "totals" to ContractJson.obj(
                    "totalMeters" to num(r1(plan.totals.totalMeters)),
                    "newCoverageMeters" to num(r1(plan.totals.newCoverageMeters)),
                    "repeatMeters" to num(r1(plan.totals.repeatMeters)),
                    "outsideMeters" to num(r1(plan.totals.outsideMeters)),
                    "approachMeters" to num(r1(plan.totals.approachMeters)),
                ),
                "valid" to JsonPrimitive(v.ok),
                "validationErrors" to strs(v.errors),
            )
            if (wantManeuvers) {
                m["maneuvers"] = strs(buildManeuvers(gi, plan).map { mm -> mm.type + (if (mm.turnaroundVerified == null) "" else if (mm.turnaroundVerified) ":verified" else ":unverified") })
            }
            plans[policy] = JsonObject(m)
        }
        val paths = LinkedHashMap<String, JsonElement>()
        for (q in (input["pathQueries"] as? JsonArray) ?: JsonArray(emptyList())) {
            val qo = q as JsonObject
            val allow: (Int) -> Boolean = { i -> gi.edges[i].eligibility == "eligible" }
            val from = (qo["fromPrefix"] as JsonArray).map { gi.idx[(it as JsonPrimitive).content]!! }.toIntArray()
            val target = gi.idx[(qo["toEdgeId"] as JsonPrimitive).content]!!
            val r = legalShortestPath(gi, from, { e -> e == target }, allow)
            paths[(qo["name"] as JsonPrimitive).content] = r.path?.let { pth -> strs(pth.map { gi.edges[it.edge].edgeId }) } ?: JsonNull
        }
        val tracks = LinkedHashMap<String, JsonElement>()
        for (trEl in (input["tracks"] as? JsonArray) ?: JsonArray(emptyList())) {
            val tr = trEl as JsonObject
            val name = (tr["name"] as JsonPrimitive).content
            val nowMs = (tr["nowMs"] as? JsonPrimitive)?.doubleOrNull?.toLong()
            val guidancePolicy = (tr["guidancePolicy"] as? JsonPrimitive)?.content
            val cfg = mergedConfig(tr["matcherConfig"] as? JsonObject)
            val observations = (tr["observations"] as JsonArray).map { ContractJson.decodeObservation(it) }
            var st = initialMatcherState()
            val union = LinkedHashMap<String, List<Interval>>()
            val traversalIds = ArrayList<String>()
            val traversalIdSet = HashSet<String>()
            val qualities = ArrayList<String>()
            val gaps = ArrayList<String>()
            val ignored = ArrayList<String>()
            var plan: RoutePlan? = if (guidancePolicy != null) planObjs[guidancePolicy] else null
            var guidance: GuidanceState? = plan?.let { initialGuidance(it) }
            var deviationAt: Int? = null
            var replan: JsonElement = JsonNull
            observations.forEachIndexed { k, o ->
                val res = processObservation(gi, st, o, nowMs ?: o.timestamp, cfg)
                st = res.state
                qualities.add(res.quality)
                res.gap?.let { gaps.add("$k:${it.reason}") }
                res.ignored?.let { ignored.add("$k:$it") }
                for (t in res.traversals) {
                    if (traversalIdSet.contains(t.traversalId)) continue
                    traversalIdSet.add(t.traversalId)
                    traversalIds.add(t.traversalId)
                    union[t.edgeId] = addInterval(union[t.edgeId] ?: emptyList(), Interval(t.startOffsetMeters, t.endOffsetMeters))
                }
                val pl = plan
                val gd = guidance
                if (pl != null && gd != null && deviationAt == null) {
                    val (ns, event) = updateGuidance(gi, pl, gd, res.matched, res.quality == GpsQuality.RELIABLE)
                    guidance = ns
                    val matched = res.matched
                    if (event == GuidanceEvent.DEVIATION && matched != null) {
                        deviationAt = k
                        val mi = gi.idx[matched.edgeId]!!
                        val newPlan = planCoverage(
                            PlannerInput(
                                gi = gi, zoneInfo = req.zoneInfo, requirements = req.requirements, observed = LinkedHashMap(union), excluded = emptySet(),
                                policy = guidancePolicy!!, start = StartState(matched.edgeId, matched.offset, "gps_position"), startPrefix = intArrayOf(mi),
                                timeBudgetMs = 1_000_000_000L,
                                meta = PlanMeta("replan", g.contentHash, 1, k.toLong(), 2, "1970-01-01T00:00:00.000Z"),
                            ),
                        )
                        val v = validatePlan(gi, newPlan, ValidationContext(req.zoneInfo, guidancePolicy, req.requirements, LinkedHashMap(union), emptySet(), intArrayOf(mi)))
                        replan = ContractJson.obj(
                            "status" to JsonPrimitive(newPlan.status),
                            "firstEdge" to (newPlan.steps.firstOrNull()?.edgeId?.let { JsonPrimitive(it) } ?: JsonNull),
                            "edgeSequence" to strs(newPlan.steps.map { it.edgeId }),
                            "coveredRequirementIds" to strs(newPlan.coveredRequirementIds.sorted()),
                            "valid" to JsonPrimitive(v.ok),
                            "validationErrors" to strs(v.errors),
                        )
                        plan = newPlan
                        guidance = initialGuidance(newPlan)
                    }
                }
            }
            tracks[name] = ContractJson.obj(
                "unionByEdge" to JsonObject(
                    union.entries.sortedWith { a, b -> a.key.compareTo(b.key) }.associate { (k, v) ->
                        k to JsonArray(v.map { JsonArray(listOf(num(r2(it.a)), num(r2(it.b)))) })
                    },
                ),
                "traversalCount" to JsonPrimitive(traversalIds.size),
                "qualities" to strs(qualities),
                "gaps" to strs(gaps),
                "ignored" to strs(ignored),
                "deviationAt" to (deviationAt?.let { JsonPrimitive(it) } ?: JsonNull),
                "replan" to replan,
            )
        }
        return ContractJson.obj(
            "graph" to graph,
            "requirements" to requirements,
            "plans" to JsonObject(plans),
            "paths" to JsonObject(paths),
            "tracks" to JsonObject(tracks),
        )
    }
}
