// Independent plan validator (port of web/src/core/validator.ts). It re-derives
// legality from the graph and must not trust any planner bookkeeping.
package della.core

import kotlin.math.abs
import kotlin.math.max

data class ValidationResult(val ok: Boolean, val errors: List<String>)

class ValidationContext(
    val zoneInfo: List<EdgeZoneInfo>,
    val policy: String,
    val requirements: List<RequiredInterval>,
    val observed: Map<String, List<Interval>>,
    val excluded: Set<String>,
    val startPrefix: Prefix? = null,
)

fun validatePlan(gi: GraphIndex, plan: RoutePlan, ctx: ValidationContext): ValidationResult {
    val errors = ArrayList<String>()
    val eps = 0.01
    if (plan.graphHash != gi.g.contentHash) errors.add("graph_hash_mismatch")
    val steps = plan.steps
    if (steps.isEmpty()) {
        if (plan.coveredRequirementIds.isNotEmpty()) errors.add("empty_plan_claims_coverage")
        return ValidationResult(errors.isEmpty(), errors)
    }
    val reqById = HashMap<String, RequiredInterval>()
    for (r in ctx.requirements) reqById[r.requirementId] = r
    val blockedEdges = HashSet<String>()
    for (id in ctx.excluded) {
        val r = reqById[id]
        if (r != null) blockedEdges.add(r.edgeId)
    }
    if (steps[0].edgeId != plan.startState.edgeId) errors.add("first_step_not_start_edge")
    if (abs(steps[0].fromOffsetMeters - plan.startState.offsetMeters) > eps) errors.add("first_step_not_start_offset")

    var prefix: Prefix? = null
    var outside = 0.0
    val planned = HashMap<String, List<Interval>>()
    for (k in steps.indices) {
        val s = steps[k]
        val ei = gi.idx[s.edgeId]
        if (ei == null) {
            errors.add("step_${k}_unknown_edge")
            continue
        }
        val e = gi.edges[ei]
        if (e.eligibility != "eligible") errors.add("step_${k}_ineligible_edge:${e.eligibility}")
        if (blockedEdges.contains(e.edgeId) && k > 0) errors.add("step_${k}_user_blocked_edge")
        if (s.fromOffsetMeters < -eps || s.toOffsetMeters > e.lengthMeters + eps || s.toOffsetMeters < s.fromOffsetMeters - eps) {
            errors.add("step_${k}_bad_offsets")
        }
        if (k > 0 && abs(s.fromOffsetMeters) > eps) errors.add("step_${k}_not_from_edge_start")
        if (k < steps.size - 1 && abs(s.toOffsetMeters - e.lengthMeters) > eps) errors.add("step_${k}_not_to_edge_end")
        val zi = ctx.zoneInfo[ei]
        val out = max(0.0, s.toOffsetMeters - s.fromOffsetMeters - coveredWithin(zi.inside, s.fromOffsetMeters, s.toOffsetMeters))
        outside += out
        if (ctx.policy == BoundaryPolicy.STRICT_INSIDE && zi.outsideMeters > STRICT_OUTSIDE_TOL_M) errors.add("step_${k}_outside_zone_under_strict")
        if (k == 0) {
            val sp = ctx.startPrefix
            prefix = if (sp != null && sp.isNotEmpty() && sp[sp.size - 1] == ei) sp else intArrayOf(ei)
        } else if (prefix != null) {
            val prev = prefix[prefix.size - 1]
            val pe = gi.edges[prev]
            if (pe.toNodeId != e.fromNodeId) errors.add("step_${k}_disconnected")
            val a = pe.geometry[pe.geometry.size - 1]
            val b = e.geometry[0]
            if (haversine(a, b) > 0.5) errors.add("step_${k}_geometry_gap")
            val uturn = gi.isUturn(prev, ei)
            if (uturn && !gi.deadEnd[prev]) errors.add("step_${k}_illegal_uturn")
            if (uturn && s.turnaroundAtStart.isNullOrEmpty()) errors.add("step_${k}_unflagged_uturn")
            val node = gi.nodes[pe.toNodeId]
            if (!uturn && (node == null || node.passage != "free")) errors.add("step_${k}_through_barrier")
            val np = gi.step(prefix, ei)
            if (np == null) errors.add("step_${k}_turn_restriction_violated")
            prefix = np ?: intArrayOf(ei)
        }
        planned[e.edgeId] = addInterval(planned[e.edgeId] ?: emptyList(), Interval(s.fromOffsetMeters, s.toOffsetMeters))
    }
    if (ctx.policy == BoundaryPolicy.STRICT_INSIDE && outside > STRICT_OUTSIDE_TOL_M * steps.size + eps) errors.add("strict_outside_travel")
    if (abs(outside - plan.totals.outsideMeters) > 0.5) errors.add("outside_total_mismatch")
    for (id in plan.coveredRequirementIds) {
        val r = reqById[id]
        if (r == null) {
            errors.add("claimed_unknown_requirement:$id")
            continue
        }
        if (ctx.excluded.contains(id)) errors.add("claimed_excluded_requirement:$id")
        val u = (ctx.observed[r.edgeId] ?: emptyList()) + (planned[r.edgeId] ?: emptyList())
        if (!requirementCovered(unionIntervals(u), r)) errors.add("claimed_but_not_traversed:$id")
    }
    val claimed = plan.coveredRequirementIds.toHashSet()
    val unreach = plan.unreachable.map { it.requirementId }.toHashSet()
    for (r in ctx.requirements) {
        if (requirementCovered(ctx.observed[r.edgeId] ?: emptyList(), r)) continue
        if (!claimed.contains(r.requirementId) && !unreach.contains(r.requirementId)) errors.add("requirement_unaccounted:${r.requirementId}")
    }
    if (plan.status == "complete" && plan.unreachable.any { it.cause == "not_reachable_from_start" || it.cause == "budget_exceeded" }) {
        errors.add("complete_status_with_unplanned_remainder")
    }
    return ValidationResult(errors.isEmpty(), errors)
}
