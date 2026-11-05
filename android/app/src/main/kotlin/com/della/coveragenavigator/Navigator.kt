package com.della.coveragenavigator

import della.core.GraphIndex
import della.core.LocationObservation
import della.core.LonLat
import della.core.Maneuver
import della.core.RoutePlan
import della.core.angleDiff
import della.core.bearing
import della.core.bearingAtOffset
import della.core.classifyTurn
import della.core.edgeName
import della.core.haversine
import della.core.legalShortestPath
import della.core.slicePolyline
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turn-by-turn guidance, independent of coverage crediting.
 *
 * Coverage credit stays strict (matcher). Guidance is deliberately available from any
 * reasonable fix: on the planned route it announces the next maneuvers; away from the route
 * (e.g. before reaching the start, or after a wrong turn) it computes a legal approach path
 * back to the route and guides along it; off any mapped road it points towards the nearest road.
 */
data class NavInstruction(
    /** route | approach | to_road */
    val mode: String,
    /** maneuver type (continue, left, right, ..., turnaround, arrive) or "head" for to_road */
    val type: String,
    val roadName: String?,
    val distanceMeters: Double,
    val thenType: String? = null,
    val thenRoadName: String? = null,
    val thenDistanceMeters: Double? = null,
    /** Remaining distance to rejoin the planned route (approach) */
    val rejoinMeters: Double? = null,
    /** Absolute bearing to head to (to_road), and relative to the current heading if known */
    val headBearing: Double? = null,
    val relativeBearing: Double? = null,
    val turnaroundVerified: Boolean? = null,
    val approximate: Boolean = false,
    /** Next ~1.5 km of the path being guided, for highlighting */
    val ahead: List<List<LonLat>> = emptyList(),
    /** Stable key of the upcoming maneuver (for voice de-duplication) */
    val key: String = "",
    /** Lane guidance for the next maneuver (left to right), when lanes are mapped. */
    val lanes: List<LaneHint>? = null,
)

data class LaneHint(
    val turns: List<String>,
    /** This lane can be used for the next maneuver. */
    val ok: Boolean,
)

private val RIGHT = listOf("right", "slight_right", "sharp_right")
private val LEFT = listOf("left", "slight_left", "sharp_left")

/**
 * Which lanes serve a maneuver. Exact family first (slight_right: slight_right/right), then a
 * conservative fallback for slight turns (through). Unknown match -> null (never guess).
 */
fun laneHints(lanes: List<List<String>>?, maneuver: String): List<LaneHint>? {
    if (lanes == null || lanes.size < 2) return null
    val wanted: List<List<String>> = when (maneuver) {
        "continue" -> listOf(listOf("through", "none"))
        "right", "sharp_right" -> listOf(RIGHT)
        "slight_right" -> listOf(listOf("slight_right", "right"), listOf("through"))
        "left", "sharp_left" -> listOf(LEFT)
        "slight_left" -> listOf(listOf("slight_left", "left"), listOf("through"))
        "turnaround" -> listOf(listOf("reverse"))
        else -> return null
    }
    for (w in wanted) {
        val hints = lanes.map { t -> LaneHint(t, t.any { it in w }) }
        if (hints.any { it.ok } && hints.any { !it.ok }) return hints
        if (hints.all { it.ok }) return null // every lane works: nothing to advise
    }
    return null
}

/** Lanes of the road approaching a junction: the incoming edge, or the one before a short link. */
private fun approachLanes(gi: GraphIndex, incoming: List<Int>): List<List<String>>? {
    var walked = 0.0
    for (e in incoming) {
        val l = gi.edges[e].turnLanes
        if (l != null) return l
        walked += gi.length(e)
        if (walked > 60) break
    }
    return null
}

private class Seg(val edge: Int, val from: Double, val to: Double, val turnaround: String?)

private data class GuideMan(val type: String, val road: String?, val at: Double, val verified: Boolean?, val key: String, val segIndex: Int)

class Navigator {
    /** Furthest plan step reached by guidance (only moves forward, within a window). */
    var cursor = 0
    private var planId: String? = null
    private var approach: List<Seg>? = null
    private var approachTargetStep = -1
    private var approachEdge = -1
    private var approachAt = 0L

    fun reset() {
        cursor = 0
        planId = null
        approach = null
    }

    fun compute(
        gi: GraphIndex,
        plan: RoutePlan?,
        maneuvers: List<Maneuver>,
        stepStart: DoubleArray,
        fix: LocationObservation?,
        fixAgeMs: Long,
        nowMs: Long,
    ): NavInstruction? {
        if (plan == null || plan.steps.isEmpty() || fix == null) return null
        if (fixAgeMs > 8_000) return null
        val acc = fix.horizontalAccuracyMeters ?: return null
        if (acc > 60) return null
        if (plan.planId != planId) {
            planId = plan.planId
            cursor = 0
            approach = null
        }
        val p = LonLat(fix.lon, fix.lat)
        val heading = if ((fix.speedMps ?: 0.0) >= 2.5) fix.bearingDeg else null
        val approximate = acc > 25

        // 1) On the planned route? Search a window ahead of the cursor (and a little behind).
        var bestK = -1
        var bestOff = 0.0
        var bestD = Double.MAX_VALUE
        var acc0 = 0.0
        var k = max(0, cursor - 2)
        while (k < plan.steps.size && acc0 < 2_500) {
            val s = plan.steps[k]
            val i = gi.idx[s.edgeId]
            if (i != null) {
                val pr = gi.project(i, p)
                val off = pr.offset.coerceIn(s.fromOffsetMeters, s.toOffsetMeters)
                val d = if (pr.offset < s.fromOffsetMeters - 1 || pr.offset > s.toOffsetMeters + 1) pr.distance + 5 else pr.distance
                var ok = true
                if (heading != null) {
                    val b = bearingAtOffset(gi.edges[i].geometry, gi.cum[i], off)
                    if (abs(angleDiff(b, heading)) > 100) ok = false // opposite direction of a two-way road
                }
                // prefer earlier steps on ties (the route may pass the same place twice)
                if (ok && d < bestD - 3) {
                    bestD = d
                    bestK = k
                    bestOff = off
                }
            }
            acc0 += s.toOffsetMeters - s.fromOffsetMeters
            k++
        }
        val onRouteTol = max(25.0, min(acc, 40.0))
        if (bestK >= 0 && bestD <= onRouteTol) {
            cursor = max(cursor, bestK)
            approach = null
            val along = stepStart[bestK] + (bestOff - plan.steps[bestK].fromOffsetMeters)
            val upcoming = maneuvers.filter { it.stepIndex > bestK && it.type != "depart" && it.distanceFromStartMeters > along + 1 }
            val m = upcoming.firstOrNull()
            val n = upcoming.getOrNull(1)
            val ahead = planAhead(gi, plan, bestK, bestOff, 1_500.0)
            return if (m == null) {
                NavInstruction("route", "continue", edgeName(gi, gi.idx[plan.steps[bestK].edgeId]!!), 0.0, approximate = approximate, ahead = ahead, key = "${plan.planId}:end")
            } else {
                val incoming = ArrayList<Int>()
                for (kk in m.stepIndex - 1 downTo max(0, m.stepIndex - 4)) incoming.add(gi.idx[plan.steps[kk].edgeId]!!)
                NavInstruction(
                    "route", m.type, m.roadName, m.distanceFromStartMeters - along,
                    n?.type, n?.roadName, n?.let { it.distanceFromStartMeters - m.distanceFromStartMeters },
                    turnaroundVerified = m.turnaroundVerified, approximate = approximate, ahead = ahead,
                    key = "${plan.planId}:${m.stepIndex}",
                    lanes = laneHints(approachLanes(gi, incoming), m.type),
                )
            }
        }

        // 2) Not on the route: guide back to the planned route at the cursor step.
        val allow = { i: Int -> gi.edges[i].eligibility == "eligible" }
        val cands = gi.near(p, 80.0).filter(allow).map { it to gi.project(it, p) }.filter { it.second.distance <= 80 }
        if (cands.isEmpty()) {
            // Off any mapped road (parking, courtyard): point towards the start of the route.
            val target = plan.steps[min(cursor, plan.steps.size - 1)]
            val ti = gi.idx[target.edgeId]!!
            val tp = della.core.pointAtOffset(gi.edges[ti].geometry, gi.cum[ti], target.fromOffsetMeters)
            val b = bearing(p, tp)
            return NavInstruction(
                "to_road", "head", null, haversine(p, tp), headBearing = b,
                relativeBearing = heading?.let { angleDiff(it, b) }, approximate = approximate,
                ahead = listOf(listOf(p, tp)), key = "to_road",
            )
        }
        val minD = cands.minOf { it.second.distance }
        val near = cands.filter { it.second.distance <= minD + 8 }
        val targetStep = min(cursor, plan.steps.size - 1)
        val tgt = plan.steps[targetStep]
        val tgtEdge = gi.idx[tgt.edgeId]!!
        val nearestEdge = near.minBy { it.second.distance }.first
        val reuse = approach != null && approachTargetStep == targetStep && near.any { it.first == approachEdge } && nowMs - approachAt < 6_000
        if (!reuse) {
            var best: List<Seg>? = null
            var bestCost = Double.MAX_VALUE
            for ((e, pr) in near) {
                if (heading != null) {
                    val b = bearingAtOffset(gi.edges[e].geometry, gi.cum[e], pr.offset)
                    if (abs(angleDiff(b, heading)) > 100) continue
                }
                if (e == tgtEdge && pr.offset <= tgt.fromOffsetMeters + 5) {
                    val c = max(0.0, tgt.fromOffsetMeters - pr.offset)
                    if (c < bestCost) {
                        bestCost = c
                        best = listOf(Seg(e, pr.offset, max(pr.offset, tgt.fromOffsetMeters), null))
                    }
                    continue
                }
                val r = legalShortestPath(gi, intArrayOf(e), { it == tgtEdge }, allow)
                val path = r.path ?: continue
                val cost = (gi.length(e) - pr.offset) + r.cost / 1000.0
                if (cost < bestCost) {
                    bestCost = cost
                    val segs = ArrayList<Seg>()
                    segs.add(Seg(e, pr.offset, gi.length(e), null))
                    path.forEachIndexed { idx, ps ->
                        val last = idx == path.size - 1
                        segs.add(Seg(ps.edge, 0.0, if (last) tgt.fromOffsetMeters else gi.length(ps.edge), if (ps.t.uturn) ps.t.turnaround else null))
                    }
                    best = segs
                }
            }
            approach = best
            approachTargetStep = targetStep
            approachEdge = best?.firstOrNull()?.edge ?: nearestEdge
            approachAt = nowMs
        }
        val segs = approach
        if (segs == null) {
            val target = plan.steps[targetStep]
            val tp = della.core.pointAtOffset(gi.edges[tgtEdge].geometry, gi.cum[tgtEdge], target.fromOffsetMeters)
            val b = bearing(p, tp)
            return NavInstruction("to_road", "head", null, haversine(p, tp), headBearing = b, relativeBearing = heading?.let { angleDiff(it, b) }, approximate = true, ahead = listOf(listOf(p, tp)), key = "noroute")
        }
        // Position along the approach path: project onto its first segment(s).
        var along = 0.0
        var bestAlong = 0.0
        var bestDist = Double.MAX_VALUE
        for (s in segs.take(3)) {
            val pr = gi.project(s.edge, p)
            val off = pr.offset.coerceIn(s.from, s.to)
            if (pr.distance < bestDist) {
                bestDist = pr.distance
                bestAlong = along + (off - s.from)
            }
            along += s.to - s.from
        }
        val total = segs.sumOf { it.to - it.from }
        val mans = approachManeuvers(gi, segs, plan, targetStep)
        val upcoming = mans.filter { it.at > bestAlong + 1 }
        val m = upcoming.firstOrNull()
        val n = upcoming.getOrNull(1)
        val ahead = segs.map { s -> slicePolyline(gi.edges[s.edge].geometry, gi.cum[s.edge], s.from, max(s.from + 0.01, s.to)) }
        var lanes: List<LaneHint>? = null
        if (m != null && m.segIndex > 0) {
            val incoming = ArrayList<Int>()
            for (kk in m.segIndex - 1 downTo max(0, m.segIndex - 4)) incoming.add(segs[kk].edge)
            lanes = laneHints(approachLanes(gi, incoming), m.type)
        }
        return NavInstruction(
            "approach", m?.type ?: "continue", m?.road ?: edgeName(gi, segs.first().edge), (m?.at ?: total) - bestAlong,
            n?.type, n?.road, if (m != null && n != null) n.at - m.at else null,
            rejoinMeters = total - bestAlong, turnaroundVerified = m?.verified, approximate = approximate, ahead = ahead,
            key = "ap:$targetStep:${m?.key ?: "join"}",
            lanes = lanes,
        )
    }

    private fun approachManeuvers(gi: GraphIndex, segs: List<Seg>, plan: RoutePlan, targetStep: Int): List<GuideMan> {
        val out = ArrayList<GuideMan>()
        var d = 0.0
        for (k in segs.indices) {
            val s = segs[k]
            if (k > 0) {
                val prev = segs[k - 1].edge
                val type = if (s.turnaround != null) "turnaround" else classifyTurn(angleDiff(gi.endBearing[prev], gi.startBearing[s.edge]))
                val same = edgeName(gi, prev) == edgeName(gi, s.edge)
                if (!(type == "continue" && same) && !(type.startsWith("slight") && same)) {
                    out.add(GuideMan(type, edgeName(gi, s.edge), d, if (s.turnaround != null) s.turnaround == "verified" else null, "$k:${s.edge}", k))
                }
            }
            d += s.to - s.from
        }
        // Joining the planned route: announce the first maneuver of the route from the join point.
        out.add(GuideMan("join", edgeName(gi, gi.idx[plan.steps[targetStep].edgeId]!!), d, null, "join", -1))
        return out
    }

    private fun planAhead(gi: GraphIndex, plan: RoutePlan, k0: Int, off0: Double, maxM: Double): List<List<LonLat>> {
        val out = ArrayList<List<LonLat>>()
        var left = maxM
        var k = k0
        while (k < plan.steps.size && left > 0) {
            val s = plan.steps[k]
            val i = gi.idx[s.edgeId] ?: break
            val from = if (k == k0) off0 else s.fromOffsetMeters
            val to = min(s.toOffsetMeters, from + left)
            if (to - from > 0.05) out.add(slicePolyline(gi.edges[i].geometry, gi.cum[i], from, to))
            left -= (to - from)
            k++
        }
        return out
    }
}
