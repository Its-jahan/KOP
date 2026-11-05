// Start snapping (snap.ts) and maneuver derivation (maneuvers.ts).
package della.core

import kotlin.math.abs

data class SnapAlternative(val edgeId: String, val distanceMeters: Double)

data class SnapResult(
    val edgeId: String,
    val offsetMeters: Double,
    val position: LonLat,
    val distanceMeters: Double,
    val alternatives: List<SnapAlternative>,
)

/**
 * Snap a requested start point to the nearest legal directed edge accepted by
 * `allow`. Both directions of a two-way road are returned as alternatives; the
 * caller must show the snapped point and direction for confirmation.
 */
fun snapStart(
    gi: GraphIndex,
    p: LonLat,
    allow: (Int) -> Boolean,
    maxDistance: Double = 150.0,
    /** Optional preference (e.g. edge belongs to the main connected component). */
    preferred: ((Int) -> Boolean)? = null,
    preferenceSlackM: Double = 30.0,
): SnapResult? {
    class F(val i: Int, val d: Double, val offset: Double, val point: LonLat)
    val found = ArrayList<F>()
    for (i in gi.near(p, maxDistance)) {
        if (!allow(i)) continue
        val pr = gi.project(i, p)
        if (pr.distance <= maxDistance) found.add(F(i, pr.distance, pr.offset, pr.point))
    }
    if (found.isEmpty()) return null
    val sorted = found.sortedWith { a, b ->
        val dd = a.d - b.d
        if (dd != 0.0) (if (dd < 0) -1 else 1) else if (gi.edges[a.i].edgeId < gi.edges[b.i].edgeId) -1 else 1
    }
    var best = sorted[0]
    if (preferred != null && !preferred(best.i)) {
        val alt = sorted.firstOrNull { preferred(it.i) && it.d <= best.d + preferenceSlackM }
        if (alt != null) best = alt
    }
    return SnapResult(
        edgeId = gi.edges[best.i].edgeId,
        offsetMeters = best.offset,
        position = best.point,
        distanceMeters = best.d,
        alternatives = sorted.filter { it !== best }.take(5).map { SnapAlternative(gi.edges[it.i].edgeId, it.d) },
    )
}

/** Preference predicate: edge belongs to the strongly connected component holding the most traversable length. */
fun mainComponentPredicate(gi: GraphIndex, comp: IntArray): (Int) -> Boolean {
    val len = HashMap<Int, Double>()
    comp.forEachIndexed { i, c -> if (c >= 0) len[c] = (len[c] ?: 0.0) + gi.edges[i].lengthMeters }
    var best = -1
    var bestLen = -1.0
    for ((c, l) in len.entries.sortedBy { it.key }) if (l > bestLen) {
        best = c
        bestLen = l
    }
    return { i -> comp[i] == best }
}

// ---------------- maneuvers.ts ----------------

data class Maneuver(
    val stepIndex: Int,
    /** depart | continue | slight_right | right | sharp_right | slight_left | left | sharp_left | turnaround | arrive */
    val type: String,
    val turnaroundVerified: Boolean?,
    val position: LonLat,
    val distanceFromStartMeters: Double,
    val roadName: String?,
)

fun edgeName(gi: GraphIndex, i: Int): String? {
    val n = gi.edges[i].names
    return n["default"] ?: n["hy"] ?: n["en"] ?: n["ref"]
}

fun classifyTurn(angle: Double): String {
    val a = abs(angle)
    if (a < 25) return "continue"
    if (a < 60) return if (angle > 0) "slight_right" else "slight_left"
    if (a < 135) return if (angle > 0) "right" else "left"
    return if (angle > 0) "sharp_right" else "sharp_left"
}

/** Maneuvers from a validated plan. Continue-on-same-road transitions are omitted. */
fun buildManeuvers(gi: GraphIndex, plan: RoutePlan): List<Maneuver> {
    val out = ArrayList<Maneuver>()
    var dist = 0.0
    plan.steps.forEachIndexed { k, s ->
        val i = gi.idx[s.edgeId]!!
        val e = gi.edges[i]
        if (k == 0) {
            out.add(Maneuver(0, "depart", null, e.geometry[0], 0.0, edgeName(gi, i)))
        } else {
            val prev = gi.idx[plan.steps[k - 1].edgeId]!!
            val type = if (!s.turnaroundAtStart.isNullOrEmpty()) "turnaround" else classifyTurn(angleDiff(gi.endBearing[prev], gi.startBearing[i]))
            val sameName = edgeName(gi, prev) == edgeName(gi, i)
            if (!(type == "continue" && sameName) && !(type.startsWith("slight") && sameName)) {
                out.add(
                    Maneuver(
                        stepIndex = k,
                        type = type,
                        turnaroundVerified = if (!s.turnaroundAtStart.isNullOrEmpty()) s.turnaroundAtStart == "verified" else null,
                        position = e.geometry[0],
                        distanceFromStartMeters = dist,
                        roadName = edgeName(gi, i),
                    ),
                )
            }
        }
        dist += s.toOffsetMeters - s.fromOffsetMeters
    }
    if (plan.steps.isNotEmpty()) {
        val last = plan.steps[plan.steps.size - 1]
        val i = gi.idx[last.edgeId]!!
        out.add(Maneuver(plan.steps.size - 1, "arrive", null, gi.edges[i].geometry[gi.edges[i].geometry.size - 1], dist, edgeName(gi, i)))
    }
    return out
}

/** Cumulative distance at the start of each step. */
fun stepStartDistances(plan: RoutePlan): DoubleArray {
    val out = DoubleArray(plan.steps.size)
    var d = 0.0
    plan.steps.forEachIndexed { k, s ->
        out[k] = d
        d += s.toOffsetMeters - s.fromOffsetMeters
    }
    return out
}
