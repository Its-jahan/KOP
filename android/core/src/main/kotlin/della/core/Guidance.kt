// Plan cursor tracking and persistent-deviation detection with hysteresis
// (port of web/src/core/guidance.ts).
package della.core

data class GuidanceConfig(
    /** Planned distance ahead of the current step that still counts as on-route. */
    val lookaheadMeters: Double = 120.0,
    val lookaheadSteps: Int = 12,
    val offRouteFixes: Int = 3,
    val offRouteDistanceM: Double = 25.0,
    val offsetSlackM: Double = 10.0,
)

val DEFAULT_GUIDANCE_CONFIG = GuidanceConfig()

data class GuidanceState(val planId: String, val cursor: Int, val offRouteCount: Int)

object GuidanceEvent {
    const val ON_ROUTE = "on_route"
    const val OFF_ROUTE_PENDING = "off_route_pending"
    const val DEVIATION = "deviation"
    const val FINISHED = "finished"
    const val NO_FIX = "no_fix"
}

fun initialGuidance(plan: RoutePlan): GuidanceState = GuidanceState(plan.planId, 0, 0)

fun updateGuidance(
    gi: GraphIndex,
    plan: RoutePlan,
    st: GuidanceState,
    matched: MatchedPosition?,
    reliable: Boolean,
    cfg: GuidanceConfig = DEFAULT_GUIDANCE_CONFIG,
): Pair<GuidanceState, String> {
    if (matched == null || !reliable) return st to GuidanceEvent.NO_FIX
    val steps = plan.steps
    // Window: current step, the next step, and further steps starting within lookaheadMeters.
    var end = minOf(steps.size - 1, st.cursor + 1)
    var acc = 0.0
    var k = st.cursor
    while (k < steps.size - 1 && k < st.cursor + cfg.lookaheadSteps) {
        acc += steps[k].toOffsetMeters - steps[k].fromOffsetMeters
        if (acc > cfg.lookaheadMeters) break
        end = maxOf(end, k + 1)
        k++
    }
    for (j in st.cursor..end) {
        val s = steps[j]
        if (s.edgeId == matched.edgeId && matched.offset >= s.fromOffsetMeters - cfg.offsetSlackM && matched.offset <= s.toOffsetMeters + cfg.offsetSlackM) {
            val finished = j == steps.size - 1 && matched.offset >= s.toOffsetMeters - cfg.offsetSlackM
            return st.copy(cursor = j, offRouteCount = 0) to (if (finished) GuidanceEvent.FINISHED else GuidanceEvent.ON_ROUTE)
        }
    }
    // Not on a window edge. Wrong direction on the same physical road is always off-route.
    val mi = gi.idx[matched.edgeId]
    var wrongDirection = false
    var minDist = Double.POSITIVE_INFINITY
    for (j in st.cursor..end) {
        val si = gi.idx[steps[j].edgeId]!!
        if (mi != null && gi.edges[si].physicalSegmentId == gi.edges[mi].physicalSegmentId) wrongDirection = true
        val g = gi.edges[si].geometry
        val d = projectOnPolyline(g, cumulativeLengths(g), matched.point).distance
        if (d < minDist) minDist = d
    }
    if (!matched.committed) return st to GuidanceEvent.OFF_ROUTE_PENDING
    if (!wrongDirection && minDist <= cfg.offRouteDistanceM) return st to GuidanceEvent.OFF_ROUTE_PENDING
    val count = st.offRouteCount + 1
    if (count >= cfg.offRouteFixes) return st.copy(offRouteCount = count) to GuidanceEvent.DEVIATION
    return st.copy(offRouteCount = count) to GuidanceEvent.OFF_ROUTE_PENDING
}
