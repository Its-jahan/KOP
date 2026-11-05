// Session state derivation from an append-only event log (port of web/src/core/session.ts).
// Replay is deterministic and idempotent: duplicate events (same eventId) and duplicate
// traversals (same traversalId) never double-credit progress.
package della.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.max
import kotlin.math.min

data class RecordedGap(val reason: String, val fromT: Long?, val toT: Long)

class DerivedProgress(
    val union: LinkedHashMap<String, List<Interval>> = LinkedHashMap(),
    val traversals: MutableList<MatchedTraversal> = ArrayList(),
    val exclusions: LinkedHashMap<String, ExclusionRecord> = LinkedHashMap(),
    var plan: RoutePlan? = null,
    var status: String = "ready",
    var lastSeq: Long = 0,
    var progressRevision: Long = 0,
    var planningRevision: Long = 0,
    val gaps: MutableList<JsonObject> = ArrayList(),
    val appliedEventIds: HashSet<String> = HashSet(),
    val rejectedEvents: MutableList<Pair<String, String>> = ArrayList(),
)

fun emptyProgress(): DerivedProgress = DerivedProgress()

class ApplyContext(val sessionId: String, val graphHash: String, val edgeLengths: Map<String, Double>)

/** Apply events in sequence order. Mutates and returns `p`. */
fun applyEvents(p: DerivedProgress, events: List<SessionEvent>, ctx: ApplyContext): DerivedProgress {
    val sorted = events.sortedWith { a, b -> a.seq.compareTo(b.seq) }
    val seenTraversal = p.traversals.map { it.traversalId }.toHashSet()
    for (ev in sorted) {
        if (p.appliedEventIds.contains(ev.eventId)) continue
        if (ev.sessionId != ctx.sessionId) {
            p.rejectedEvents.add(ev.eventId to "foreign_session")
            continue
        }
        if (ev.seq <= p.lastSeq) {
            p.rejectedEvents.add(ev.eventId to "non_monotonic_seq")
            continue
        }
        p.appliedEventIds.add(ev.eventId)
        p.lastSeq = ev.seq
        when (ev.type) {
            EventType.TRAVERSAL_CONFIRMED -> {
                val t = try {
                    ev.payload["traversal"]?.let { ContractJson.decodeTraversal(it) }
                } catch (e: IllegalArgumentException) {
                    null
                }
                val len = t?.let { ctx.edgeLengths[it.edgeId] }
                if (t == null || len == null || !(t.startOffsetMeters >= -0.01) || !(t.endOffsetMeters <= len + 0.01) || !(t.endOffsetMeters > t.startOffsetMeters)) {
                    p.rejectedEvents.add(ev.eventId to "invalid_traversal")
                } else if (!seenTraversal.contains(t.traversalId)) {
                    seenTraversal.add(t.traversalId)
                    p.traversals.add(t)
                    p.union[t.edgeId] = addInterval(p.union[t.edgeId] ?: emptyList(), Interval(max(0.0, t.startOffsetMeters), min(len, t.endOffsetMeters)))
                    p.progressRevision = ev.seq
                }
            }
            EventType.PLAN_ACCEPTED -> {
                val plan = try {
                    ev.payload["plan"]?.let { ContractJson.decodePlan(it) }
                } catch (e: IllegalArgumentException) {
                    null
                }
                if (plan != null && plan.graphHash == ctx.graphHash) {
                    p.plan = plan
                    p.planningRevision = plan.planningRevision
                } else p.rejectedEvents.add(ev.eventId to "plan_graph_mismatch")
            }
            EventType.REQUIREMENT_EXCLUDED -> {
                val ids = ContractJson.payloadStrings(ev.payload, "requirementIds") ?: emptyList()
                for (id in ids) {
                    p.exclusions[id] = ExclusionRecord(
                        requirementId = id,
                        reason = ContractJson.payloadString(ev.payload, "reason") ?: "blocked",
                        note = ContractJson.payloadString(ev.payload, "note"),
                        at = ev.timestamp,
                        active = true,
                    )
                }
                p.progressRevision = ev.seq
            }
            EventType.REQUIREMENT_RESTORED -> {
                for (id in ContractJson.payloadStrings(ev.payload, "requirementIds") ?: emptyList()) {
                    p.exclusions[id]?.active = false
                }
                p.progressRevision = ev.seq
            }
            EventType.GAP_RECORDED -> p.gaps.add(ev.payload)
            EventType.PAUSED -> p.status = "paused"
            EventType.RESUMED, EventType.START_CONFIRMED -> p.status = "active"
            EventType.ENDED -> p.status = "ended"
            else -> {}
        }
    }
    return p
}

fun activeExclusionIds(p: DerivedProgress): Set<String> =
    p.exclusions.values.filter { it.active }.map { it.requirementId }.toCollection(LinkedHashSet())

fun requirementStatus(r: RequiredInterval, union: List<Interval>, excluded: Boolean, unreachableIds: Set<String>): String {
    if (excluded) return RequirementStatus.BLOCKED
    if (requirementCovered(union, r, COVERAGE_TOLERANCE_M)) return RequirementStatus.COVERED
    val cov = coveredWithin(union, r.startOffsetMeters, r.endOffsetMeters)
    if (cov > 0.5) return RequirementStatus.PARTIAL
    if (r.status == RequirementStatus.NEEDS_REVIEW) return RequirementStatus.NEEDS_REVIEW
    if (unreachableIds.contains(r.requirementId)) return RequirementStatus.UNREACHABLE
    return RequirementStatus.UNVISITED
}

data class CoverageMetrics(
    val totalRequiredMeters: Double,
    val confirmedMeters: Double,
    val remainingMeters: Double,
    val blockedMeters: Double,
    val unreachableMeters: Double,
    val needsReviewMeters: Double,
    val counts: Map<String, Int>,
    val percentConfirmed: Double,
)

fun computeMetrics(requirements: List<RequiredInterval>, p: DerivedProgress): Pair<CoverageMetrics, LinkedHashMap<String, String>> {
    val excluded = activeExclusionIds(p)
    val unreachable = (p.plan?.unreachable ?: emptyList())
        .filter { it.cause == "outside_policy" || it.cause == "not_reachable_from_start" }
        .map { it.requirementId }.toHashSet()
    val counts = LinkedHashMap<String, Int>()
    for (s in RequirementStatus.ALL) counts[s] = 0
    val statuses = LinkedHashMap<String, String>()
    var total = 0.0
    var confirmed = 0.0
    var remaining = 0.0
    var blocked = 0.0
    var unr = 0.0
    var nr = 0.0
    for (r in requirements) {
        val u = p.union[r.edgeId] ?: emptyList()
        val st = requirementStatus(r, u, excluded.contains(r.requirementId), unreachable)
        statuses[r.requirementId] = st
        counts[st] = (counts[st] ?: 0) + 1
        total += r.lengthMeters
        val cov = min(r.lengthMeters, coveredWithin(u, r.startOffsetMeters, r.endOffsetMeters))
        confirmed += cov
        val unc = r.lengthMeters - cov
        when (st) {
            RequirementStatus.BLOCKED -> blocked += unc
            RequirementStatus.UNREACHABLE -> unr += unc
            RequirementStatus.NEEDS_REVIEW -> nr += unc
            else -> remaining += unc
        }
    }
    return CoverageMetrics(
        totalRequiredMeters = total,
        confirmedMeters = confirmed,
        remainingMeters = remaining,
        blockedMeters = blocked,
        unreachableMeters = unr,
        needsReviewMeters = nr,
        counts = counts,
        percentConfirmed = if (total > 0) (100 * confirmed) / total else 0.0,
    ) to statuses
}

/** Static session fields (everything not derived from the event log). */
class SessionBase(
    val sessionId: String,
    val graphId: String,
    val contentHash: String,
    val zone: Zone,
    val vehicleProfile: String,
    val boundaryPolicy: String,
    val returnToStart: Boolean,
    val startSelection: StartSelection,
    val createdAt: String,
)

/** Build the canonical CoverageSession document for export/persistence. */
fun sessionDocument(base: SessionBase, requirements: List<RequiredInterval>, p: DerivedProgress): CoverageSession {
    val (_, statuses) = computeMetrics(requirements, p)
    return CoverageSession(
        schemaVersion = SCHEMA_VERSION,
        sessionId = base.sessionId,
        graphId = base.graphId,
        contentHash = base.contentHash,
        zone = base.zone,
        vehicleProfile = base.vehicleProfile,
        boundaryPolicy = base.boundaryPolicy,
        returnToStart = base.returnToStart,
        startSelection = base.startSelection,
        createdAt = base.createdAt,
        status = p.status,
        planningRevision = p.planningRevision,
        progressRevision = p.progressRevision,
        requiredIntervals = requirements.map { it.copy(status = statuses[it.requirementId] ?: it.status) },
        confirmedTraversals = p.traversals.toList(),
        exclusions = p.exclusions.values.map { it.copy() },
        routePlan = p.plan,
        eventLogPosition = p.lastSeq,
    )
}

/** Gap payload helper. */
fun gapPayload(reason: String, fromT: Long?, toT: Long): JsonObject = ContractJson.obj(
    "reason" to JsonPrimitive(reason),
    "fromT" to ContractJson.longOrNull(fromT),
    "toT" to JsonPrimitive(toT),
)

internal fun JsonObject.numberOrNull(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
