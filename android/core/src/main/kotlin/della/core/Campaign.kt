// Multi-day coverage campaign (port of web/src/core/campaign.ts): one large zone driven over
// many days from a fixed home point.
//
// The whole remaining area is planned once from home (same legal planner), then the route is
// cut into consecutive daily chunks that fit the daily time budget including the drive from
// home to the chunk and back. After each day the user confirms what was actually driven (from
// GPS); confirmed coverage is removed and the remaining days are re-planned.
package della.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

data class CampaignSettings(
    val hoursPerDay: Double,
    /** Effective average speed while covering (stops, turns, lights). */
    val speedKmh: Double,
)

val DEFAULT_CAMPAIGN_SETTINGS = CampaignSettings(hoursPerDay = 4.0, speedKmh = 14.0)

data class CampaignDay(
    /** 1-based day number within the current plan. */
    val day: Int,
    /** Drive from home to where this day's coverage starts (not counted as new coverage). */
    val approach: List<PlanStep>,
    /** This day's part of the coverage route. */
    val steps: List<PlanStep>,
    val targetIds: List<String>,
    val coverMeters: Double,
    val approachMeters: Double,
    /** Estimated drive back home (straight-line x 1.4; informative). */
    val returnMeters: Double,
    val totalMeters: Double,
    val minutes: Long,
    /** Bounding box for map preview. */
    val bbox: GeographicExtent,
)

data class CampaignPlan(
    val days: List<CampaignDay>,
    val remainingMeters: Double,
    val unplannable: List<String>,
    val fullPlan: RoutePlan,
)

data class CampaignHome(val requested: LonLat, val edgeId: String, val offsetMeters: Double)

data class CampaignHistoryEntry(val date: String, val sessionId: String?, val confirmedMeters: Double, val requirementIds: List<String>)

data class Campaign(
    val campaignId: String,
    val name: String,
    val createdAt: String,
    val graphId: String,
    val contentHash: String,
    val zone: ZoneDefinition,
    val policy: String,
    val home: CampaignHome,
    val settings: CampaignSettings,
    /** Confirmed coverage (only what the user confirmed), per directed edge, in insertion order. */
    val confirmed: List<Pair<String, List<Interval>>>,
    val history: List<CampaignHistoryEntry>,
    /** Optional explicit street list: only requirements on these streets are part of the campaign. */
    val streetSet: StreetSet? = null,
)

private fun stepLen(s: PlanStep) = s.toOffsetMeters - s.fromOffsetMeters

private fun endPoint(gi: GraphIndex, s: PlanStep): LonLat {
    val i = gi.idx[s.edgeId]!!
    return pointAtOffset(gi.edges[i].geometry, gi.cum[i], s.toOffsetMeters)
}

private fun mk(gi: GraphIndex, edge: Int, from: Double, to: Double) =
    PlanStep(gi.edges[edge].edgeId, from, to, "approach", emptyList(), 0.0, null)

/** Legal path from home to the start of `target` (any eligible road; may leave the zone). */
private fun approachSteps(gi: GraphIndex, homeEdge: Int, homeOffset: Double, target: PlanStep): List<PlanStep> {
    val ti = gi.idx[target.edgeId]!!
    if (ti == homeEdge && target.fromOffsetMeters >= homeOffset) {
        return if (target.fromOffsetMeters - homeOffset > 0.5) listOf(mk(gi, homeEdge, homeOffset, target.fromOffsetMeters)) else emptyList()
    }
    val allow: (Int) -> Boolean = { i -> gi.edges[i].eligibility == "eligible" }
    val r = legalShortestPath(gi, intArrayOf(homeEdge), { e -> e == ti }, allow)
    val path = r.path ?: return emptyList()
    val out = ArrayList<PlanStep>()
    out.add(mk(gi, homeEdge, homeOffset, gi.length(homeEdge)))
    path.forEachIndexed { k, p ->
        val last = k == path.size - 1
        out.add(mk(gi, p.edge, 0.0, if (last) target.fromOffsetMeters else gi.length(p.edge)).copy(turnaroundAtStart = if (p.t.uturn) p.t.turnaround else null))
    }
    return out.filter { stepLen(it) > 0.01 || it.edgeId == target.edgeId }
}

/** Cut a whole-area plan into daily chunks within the budget. */
fun splitIntoDays(gi: GraphIndex, plan: RoutePlan, homeEdgeId: String, homeOffset: Double, settings: CampaignSettings): List<CampaignDay> {
    val budget = settings.hoursPerDay * settings.speedKmh * 1000
    val homeEdge = gi.idx[homeEdgeId]!!
    val homePt = pointAtOffset(gi.edges[homeEdge].geometry, gi.cum[homeEdge], homeOffset)
    val days = ArrayList<CampaignDay>()
    var i = 0
    val all = plan.steps
    while (i < all.size) {
        val approach = if (days.isEmpty()) emptyList() else approachSteps(gi, homeEdge, homeOffset, all[i])
        var approachMeters = 0.0
        for (s in approach) approachMeters += stepLen(s)
        val steps = ArrayList<PlanStep>()
        var cover = 0.0
        var j = i
        while (j < all.size) {
            val s = all[j]
            val ret = haversine(endPoint(gi, s), homePt) * 1.4
            if (steps.isNotEmpty() && approachMeters + cover + stepLen(s) + ret > budget) break
            steps.add(s)
            cover += stepLen(s)
            j++
        }
        // Do not end a day on steps that cover nothing new (they belong to the next day's drive).
        while (steps.size > 1 && steps[steps.size - 1].newlyCoveredRequirementIds.isEmpty()) {
            steps.removeAt(steps.size - 1)
            j--
            cover -= stepLen(all[j])
        }
        val last = steps[steps.size - 1]
        val returnMeters = haversine(endPoint(gi, last), homePt) * 1.4
        var w = Double.POSITIVE_INFINITY
        var so = Double.POSITIVE_INFINITY
        var e = Double.NEGATIVE_INFINITY
        var n = Double.NEGATIVE_INFINITY
        for (s in steps) {
            for (p in gi.edges[gi.idx[s.edgeId]!!].geometry) {
                w = min(w, p.lon); e = max(e, p.lon); so = min(so, p.lat); n = max(n, p.lat)
            }
        }
        var total = 0.0
        for (s in approach) total += stepLen(s)
        for (s in steps) total += stepLen(s)
        total += returnMeters
        days.add(
            CampaignDay(
                day = days.size + 1,
                approach = approach,
                steps = steps,
                targetIds = steps.flatMap { it.newlyCoveredRequirementIds },
                coverMeters = cover,
                approachMeters = approachMeters,
                returnMeters = returnMeters,
                totalMeters = total,
                minutes = jsRound((total / 1000 / settings.speedKmh) * 60).roundToLong(),
                bbox = GeographicExtent(w, so, e, n),
            ),
        )
        i = j
    }
    // Drop trailing days that cover nothing (pure transit at the end of the plan).
    while (days.isNotEmpty() && days[days.size - 1].targetIds.isEmpty()) days.removeAt(days.size - 1)
    return days
}

/** Plan all remaining work from home and split it into days. */
fun planCampaign(
    gi: GraphIndex,
    zoneInfo: List<EdgeZoneInfo>,
    requirements: List<RequiredInterval>,
    c: Campaign,
    planId: String,
    createdAt: String,
    timeBudgetMs: Long = 30_000,
    shouldCancel: (() -> Boolean)? = null,
): CampaignPlan {
    val confirmed = LinkedHashMap<String, List<Interval>>()
    for ((k, v) in c.confirmed) confirmed[k] = v
    val rev = c.history.size.toLong()
    val fullPlan = planCoverage(
        PlannerInput(
            gi = gi, zoneInfo = zoneInfo, requirements = requirements, observed = confirmed, excluded = emptySet(), policy = c.policy,
            start = StartState(c.home.edgeId, c.home.offsetMeters, "selected_start"),
            timeBudgetMs = timeBudgetMs, shouldCancel = shouldCancel,
            meta = PlanMeta(planId, c.contentHash, 1, rev, rev + 1, createdAt),
        ),
    )
    val days = splitIntoDays(gi, fullPlan, c.home.edgeId, c.home.offsetMeters, c.settings)
    var remaining = 0.0
    for (r in requirements) {
        if (r.status == RequirementStatus.NEEDS_REVIEW) continue
        if (!requirementCovered(confirmed[r.edgeId] ?: emptyList(), r)) remaining += r.lengthMeters
    }
    return CampaignPlan(days, remaining, fullPlan.unreachable.map { it.requirementId }, fullPlan)
}

/**
 * One day as a drivable route plan (web App.tsx dayPlan): approach from home + that day's
 * steps; covered requirements are exactly that day's targets.
 */
fun campaignDayPlan(plan: CampaignPlan, dayIndex: Int, requirements: List<RequiredInterval>, planId: String, createdAt: String): RoutePlan {
    val d = plan.days[dayIndex]
    val steps = d.approach.map { it.copy(kind = "approach") } + d.steps
    return plan.fullPlan.copy(
        planId = planId, steps = steps, legs = buildLegs(steps), coveredRequirementIds = d.targetIds, unreachable = emptyList(),
        status = "complete", createdAt = createdAt, totals = computeTotals(steps, requirements, d.targetIds, emptyMap()),
    )
}

/** Requirements newly covered by `driven` (union from a day's session) beyond what is already confirmed. */
fun newlyDriven(requirements: List<RequiredInterval>, confirmed: Map<String, List<Interval>>, driven: Map<String, List<Interval>>): List<RequiredInterval> {
    val out = ArrayList<RequiredInterval>()
    for (r in requirements) {
        val before = confirmed[r.edgeId] ?: emptyList()
        if (requirementCovered(before, r)) continue
        val merged = (before + (driven[r.edgeId] ?: emptyList())).fold(emptyList<Interval>()) { u, iv -> addInterval(u, iv) }
        if (requirementCovered(merged, r)) out.add(r)
    }
    return out
}

/** Apply a user-confirmed day: add the driven intervals of the confirmed requirements. */
fun confirmDay(c: Campaign, confirmedReqs: List<RequiredInterval>, driven: Map<String, List<Interval>>, sessionId: String?, date: String): Campaign {
    val conf = LinkedHashMap<String, List<Interval>>()
    for ((k, v) in c.confirmed) conf[k] = v.toList()
    var meters = 0.0
    for (r in confirmedReqs) {
        var u = conf[r.edgeId] ?: emptyList()
        for (iv in driven[r.edgeId] ?: emptyList()) {
            val lo = max(iv.a, r.startOffsetMeters)
            val hi = min(iv.b, r.endOffsetMeters)
            if (hi > lo) u = addInterval(u, Interval(lo, hi))
        }
        conf[r.edgeId] = u
        meters += r.lengthMeters
    }
    return c.copy(
        confirmed = conf.entries.map { it.key to it.value },
        history = c.history + CampaignHistoryEntry(date, sessionId, meters, confirmedReqs.map { it.requirementId }),
    )
}

/** Undo the last confirmed day (in case of a mistake). */
fun undoLastDay(c: Campaign, requirements: List<RequiredInterval>): Campaign {
    if (c.history.isEmpty()) return c
    val removed = LinkedHashSet(c.history[c.history.size - 1].requirementIds)
    val reqById = HashMap<String, RequiredInterval>()
    for (r in requirements) reqById[r.requirementId] = r
    val conf = LinkedHashMap<String, List<Interval>>()
    for ((k, v) in c.confirmed) conf[k] = v.toList()
    for (id in removed) {
        val r = reqById[id] ?: continue
        val u = conf[r.edgeId] ?: emptyList()
        val cut = ArrayList<Interval>()
        for (iv in u) {
            if (iv.b <= r.startOffsetMeters || iv.a >= r.endOffsetMeters) cut.add(iv)
            else {
                if (iv.a < r.startOffsetMeters) cut.add(Interval(iv.a, r.startOffsetMeters))
                if (iv.b > r.endOffsetMeters) cut.add(Interval(r.endOffsetMeters, iv.b))
            }
        }
        conf[r.edgeId] = cut
    }
    return c.copy(confirmed = conf.entries.map { it.key to it.value }, history = c.history.dropLast(1))
}

/** Persistence codec for one campaign (same field names as the web `Campaign` object). */
object CampaignJson {
    fun encode(c: Campaign): JsonObject = with(ContractJson) {
        obj(
            "campaignId" to JsonPrimitive(c.campaignId), "name" to JsonPrimitive(c.name), "createdAt" to JsonPrimitive(c.createdAt),
            "graphId" to JsonPrimitive(c.graphId), "contentHash" to JsonPrimitive(c.contentHash),
            "zone" to encodeZoneDefinition(c.zone), "policy" to JsonPrimitive(c.policy),
            "home" to obj("requested" to ll(c.home.requested), "edgeId" to JsonPrimitive(c.home.edgeId), "offsetMeters" to num(c.home.offsetMeters)),
            "settings" to obj("hoursPerDay" to num(c.settings.hoursPerDay), "speedKmh" to num(c.settings.speedKmh)),
            "confirmed" to JsonArray(c.confirmed.map { (k, v) -> JsonArray(listOf(JsonPrimitive(k), JsonArray(v.map { JsonArray(listOf(num(it.a), num(it.b))) }))) }),
            "history" to JsonArray(
                c.history.map {
                    obj("date" to JsonPrimitive(it.date), "sessionId" to str(it.sessionId), "confirmedMeters" to num(it.confirmedMeters), "requirementIds" to strs(it.requirementIds))
                },
            ),
        ).let { o -> if (c.streetSet == null) o else JsonObject(o + ("streetSet" to StreetSetJson.encode(c.streetSet))) }
    }

    fun decode(el: JsonElement): Campaign = with(ContractJson) {
        val o = el.o()
        val h = o.req("home").o()
        val st = o.req("settings").o()
        Campaign(
            campaignId = o.s("campaignId"), name = o.s("name"), createdAt = o.s("createdAt"), graphId = o.s("graphId"), contentHash = o.s("contentHash"),
            zone = decodeZoneDefinition(o.req("zone")), policy = o.s("policy"),
            home = CampaignHome(h.ll("requested"), h.s("edgeId"), h.d("offsetMeters")),
            settings = CampaignSettings(st.d("hoursPerDay"), st.d("speedKmh")),
            confirmed = o.req("confirmed").a().map { pair ->
                val p = pair.a()
                (p[0] as JsonPrimitive).content to p[1].a().map { iv ->
                    val x = iv.a()
                    Interval((x[0] as JsonPrimitive).content.toDouble(), (x[1] as JsonPrimitive).content.toDouble())
                }
            },
            history = o.req("history").a().map {
                val e = it.o()
                CampaignHistoryEntry(e.s("date"), if (e["sessionId"] == null || e["sessionId"] is JsonNull) null else e.s("sessionId"), e.d("confirmedMeters"), e.ss("requirementIds"))
            },
            streetSet = o["streetSet"]?.takeIf { it !is JsonNull }?.let { StreetSetJson.parse(it, "street-set") },
        )
    }
}
