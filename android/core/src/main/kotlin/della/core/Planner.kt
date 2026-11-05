// Directed coverage planner (rural-postman style heuristic). Port of web/src/core/planner.ts:
// deterministic nearest-feasible-requirement selection with legal shortest-path
// connectors over an edge-based state graph that honours turn restrictions
// (including via-way sequences), dead-end-only U-turns and the boundary policy.
// It does NOT claim optimality.
package della.core

import kotlin.math.abs
import kotlin.math.max

const val OUTSIDE_PENALTY_FACTOR = 9L // outside meters cost 10x

object TurnPenaltyMm {
    const val STRAIGHT = 0L
    const val RIGHT = 5_000L
    const val LEFT = 15_000L
    const val SHARP = 30_000L
    const val UTURN_VERIFIED = 60_000L
    const val UTURN_UNVERIFIED = 100_000L
}

fun turnPenalty(t: Transition): Long {
    if (t.uturn) return if (t.turnaround == "verified") TurnPenaltyMm.UTURN_VERIFIED else TurnPenaltyMm.UTURN_UNVERIFIED
    val a = t.turnAngle
    val ab = abs(a)
    if (ab < 30) return TurnPenaltyMm.STRAIGHT
    if (ab >= 150) return TurnPenaltyMm.SHARP
    return if (a > 0) TurnPenaltyMm.RIGHT else TurnPenaltyMm.LEFT
}

class PlanMeta(
    val planId: String,
    val graphHash: String,
    val zoneVersion: Int,
    val progressRevision: Long,
    val planningRevision: Long,
    val createdAt: String,
)

class PlannerInput(
    val gi: GraphIndex,
    val zoneInfo: List<EdgeZoneInfo>,
    val requirements: List<RequiredInterval>,
    /** Observed (confirmed) union per edgeId. */
    val observed: Map<String, List<Interval>>,
    /** Active exclusion requirement IDs (user-blocked). */
    val excluded: Set<String>,
    val policy: String,
    val start: StartState,
    val startPrefix: Prefix? = null,
    val returnToStart: Boolean = false,
    val maxIterations: Long? = null,
    val timeBudgetMs: Long? = null,
    val now: (() -> Long)? = null,
    val shouldCancel: (() -> Boolean)? = null,
    val meta: PlanMeta,
)

private class QItem(val cost: Long, val edge: Int, val key: String, val prefix: Prefix)

private fun lessQ(gi: GraphIndex): (QItem, QItem) -> Boolean = { x, y ->
    if (x.cost != y.cost) {
        x.cost < y.cost
    } else {
        val ex = gi.edges[x.edge].edgeId
        val ey = gi.edges[y.edge].edgeId
        if (ex != ey) ex < ey else x.key < y.key
    }
}

/** Tarjan SCC over edge-level legal transitions among traversable edges. */
fun edgeScc(gi: GraphIndex, allow: (Int) -> Boolean): IntArray {
    val n = gi.edges.size
    val comp = IntArray(n) { -1 }
    val index = IntArray(n) { -1 }
    val low = IntArray(n)
    val onStack = BooleanArray(n)
    val stack = ArrayList<Int>()
    var idx = 0
    var c = 0
    fun succ(v: Int): IntArray = gi.successors(intArrayOf(v), allow).map { it.next }.toIntArray()
    class Frame(val v: Int, val it: IntArray, var k: Int)
    for (s in 0 until n) {
        if (!allow(s) || index[s] != -1) continue
        val work = ArrayList<Frame>()
        work.add(Frame(s, succ(s), 0))
        index[s] = idx
        low[s] = idx
        idx++
        stack.add(s)
        onStack[s] = true
        while (work.isNotEmpty()) {
            val top = work[work.size - 1]
            if (top.k < top.it.size) {
                val w = top.it[top.k++]
                if (index[w] == -1) {
                    index[w] = idx
                    low[w] = idx
                    idx++
                    stack.add(w)
                    onStack[w] = true
                    work.add(Frame(w, succ(w), 0))
                } else if (onStack[w]) low[top.v] = minOf(low[top.v], index[w])
            } else {
                work.removeAt(work.size - 1)
                if (work.isNotEmpty()) low[work[work.size - 1].v] = minOf(low[work[work.size - 1].v], low[top.v])
                if (low[top.v] == index[top.v]) {
                    var w: Int
                    do {
                        w = stack.removeAt(stack.size - 1)
                        onStack[w] = false
                        comp[w] = c
                    } while (w != top.v)
                    c++
                }
            }
        }
    }
    return comp
}

class PathStep(val edge: Int, val t: Transition)

class ShortestPathResult(val path: List<PathStep>?, val endPrefix: Prefix?, val cost: Long, val settled: Long)

/**
 * Dijkstra over the edge-based legal state graph. Cost (integer mm) = edge length +
 * OUTSIDE_PENALTY_FACTOR * outside length + turn penalty. Ties: cost, then edgeId,
 * then automaton key (all compared as UTF-16 strings) -> identical on every platform.
 */
fun legalShortestPath(
    gi: GraphIndex,
    from: Prefix,
    isTarget: (Int) -> Boolean,
    allow: (Int) -> Boolean,
    zoneInfo: List<EdgeZoneInfo>? = null,
    shouldCancel: (() -> Boolean)? = null,
): ShortestPathResult {
    val less = lessQ(gi)
    val startKey = from.key()
    val dist = HashMap<String, Long>()
    dist[startKey] = 0L
    val parent = HashMap<String, Pair<String, Transition>>()
    val heap = MinHeap(less)
    heap.push(QItem(0L, from[from.size - 1], startKey, from))
    val done = HashSet<String>()
    var settled = 0L
    while (heap.size > 0) {
        val cur = heap.pop()!!
        if (done.contains(cur.key)) continue
        done.add(cur.key)
        settled++
        if (shouldCancel != null && (settled and 0xfffL) == 0L && shouldCancel()) break
        if (cur.key != startKey && isTarget(cur.edge)) {
            val path = ArrayList<PathStep>()
            var k = cur.key
            while (k != startKey) {
                val p = parent[k]!!
                path.add(PathStep(p.second.next, p.second))
                k = p.first
            }
            path.reverse()
            return ShortestPathResult(path, cur.prefix, cur.cost, settled)
        }
        for (t in gi.successors(cur.prefix, allow)) {
            val nk = t.prefix.key()
            if (done.contains(nk)) continue
            val outside = if (zoneInfo != null) OUTSIDE_PENALTY_FACTOR * Math.round(zoneInfo[t.next].outsideMeters * 1000) else 0L
            val nc = cur.cost + gi.edges[t.next].lengthMm + outside + turnPenalty(t)
            val old = dist[nk]
            if (old == null || nc < old) {
                dist[nk] = nc
                parent[nk] = cur.key to t
                heap.push(QItem(nc, t.next, nk, t.prefix))
            }
        }
    }
    return ShortestPathResult(null, null, Long.MAX_VALUE, settled)
}

/**
 * For each SCC c: value[c] = remaining requirement edges in c + max value over SCCs directly
 * reachable from c (condensation DAG). Tarjan numbers SCCs in reverse topological order,
 * so ascending ids are processed sinks-first.
 */
fun sccChainValues(gi: GraphIndex, comp: IntArray, allow: (Int) -> Boolean, remaining: Map<Int, *>): IntArray {
    var n = 0
    for (c in comp) if (c + 1 > n) n = c + 1
    val w = IntArray(n)
    for (e in remaining.keys) if (comp[e] >= 0) w[comp[e]]++
    val succ = Array(n) { ArrayList<Int>() }
    comp.forEachIndexed { i, c ->
        if (c < 0) return@forEachIndexed
        for (t in gi.successors(intArrayOf(i), allow)) {
            val d = comp[t.next]
            if (d >= 0 && d != c) succ[c].add(d)
        }
    }
    val value = IntArray(n)
    for (c in 0 until n) {
        var m = 0
        for (d in succ[c]) if (value[d] > m) m = value[d]
        value[c] = w[c] + m
    }
    return value
}

fun planCoverage(inp: PlannerInput): RoutePlan {
    val gi = inp.gi
    val zoneInfo = inp.zoneInfo
    val now = inp.now ?: { System.currentTimeMillis() }
    val t0 = now()
    val maxIterations = inp.maxIterations ?: 200_000L
    val timeBudgetMs = inp.timeBudgetMs ?: 20_000L
    val warnings = ArrayList<String>(gi.restrictionWarnings)

    val reqById = LinkedHashMap<String, RequiredInterval>()
    for (r in inp.requirements) reqById[r.requirementId] = r
    val blockedEdges = HashSet<Int>()
    for (id in inp.excluded) {
        val r = reqById[id]
        if (r != null && gi.idx.containsKey(r.edgeId)) blockedEdges.add(gi.idx[r.edgeId]!!)
    }
    val allow: (Int) -> Boolean = { i -> edgeTraversable(gi, zoneInfo, inp.policy, blockedEdges, i) }
    // The vehicle may already be on a road the user just reported blocked: it can still
    // leave along its current edge, so the start edge is exempt for the start step only.
    val allowStart: (Int) -> Boolean = { i -> edgeTraversable(gi, zoneInfo, inp.policy, emptySet(), i) }

    val unreachable = ArrayList<UnreachableRequirement>()
    val remainingByEdge = LinkedHashMap<Int, LinkedHashSet<String>>()
    val planned = HashMap<Int, List<Interval>>()
    fun unionFor(i: Int): List<Interval> {
        val id = gi.edges[i].edgeId
        val obs = inp.observed[id] ?: emptyList()
        val pl = planned[i] ?: emptyList()
        return if (pl.isNotEmpty()) unionIntervals(obs + pl) else obs
    }

    var plannable = 0
    for (r in inp.requirements) {
        val ei = gi.idx[r.edgeId] ?: continue
        if (requirementCovered(inp.observed[r.edgeId] ?: emptyList(), r)) continue
        if (inp.excluded.contains(r.requirementId)) {
            unreachable.add(UnreachableRequirement(r.requirementId, "blocked_by_user"))
            continue
        }
        val e = gi.edges[ei]
        if (e.eligibility == "needsReview") {
            unreachable.add(UnreachableRequirement(r.requirementId, "needs_review"))
            continue
        }
        if (!allow(ei)) {
            val cause = if (inp.policy == BoundaryPolicy.STRICT_INSIDE && zoneInfo[ei].outsideMeters > STRICT_OUTSIDE_TOL_M) "outside_policy" else "not_reachable_from_start"
            unreachable.add(UnreachableRequirement(r.requirementId, cause))
            continue
        }
        plannable++
        remainingByEdge.getOrPut(ei) { LinkedHashSet() }.add(r.requirementId)
    }

    val steps = ArrayList<PlanStep>()
    val covered = ArrayList<String>()
    val startEdge = gi.idx[inp.start.edgeId]
    fun basePlan(status: String, iterationsUsed: Long): RoutePlan = RoutePlan(
        planId = inp.meta.planId,
        graphHash = inp.meta.graphHash,
        zoneVersion = inp.meta.zoneVersion,
        basedOnProgressRevision = inp.meta.progressRevision,
        planningRevision = inp.meta.planningRevision,
        algorithm = PLANNER_ALGORITHM,
        algorithmVersion = PLANNER_VERSION,
        createdAt = inp.meta.createdAt,
        startState = inp.start,
        steps = steps,
        legs = buildLegs(steps),
        coveredRequirementIds = covered,
        unreachable = unreachable,
        totals = computeTotals(steps, inp.requirements, covered, inp.observed),
        limits = PlanLimits(maxIterations, timeBudgetMs, iterationsUsed),
        warnings = warnings,
        status = status,
    )

    if (startEdge == null || !allowStart(startEdge)) {
        warnings.add("start_edge_not_traversable")
        for ((_, ids) in remainingByEdge) for (id in ids) unreachable.add(UnreachableRequirement(id, "not_reachable_from_start"))
        return basePlan("failed", 0)
    }

    fun takeEdge(ei: Int, from: Double, to: Double, turnaround: String?) {
        val rem = remainingByEdge[ei]
        planned[ei] = addInterval(planned[ei] ?: emptyList(), Interval(from, to))
        val newly = ArrayList<String>()
        if (rem != null) {
            val u = unionFor(ei)
            for (id in rem.toList().sorted()) {
                if (requirementCovered(u, reqById[id]!!, COVERAGE_TOLERANCE_M)) {
                    newly.add(id)
                    rem.remove(id)
                }
            }
            if (rem.isEmpty()) remainingByEdge.remove(ei)
        }
        covered.addAll(newly)
        val zi = zoneInfo[ei]
        val outside = max(0.0, to - from - coveredWithin(zi.inside, from, to))
        steps.add(
            PlanStep(
                edgeId = gi.edges[ei].edgeId,
                fromOffsetMeters = from,
                toOffsetMeters = to,
                kind = if (newly.isNotEmpty()) "required" else if (outside > STRICT_OUTSIDE_TOL_M) "outside" else "transit",
                newlyCoveredRequirementIds = newly,
                outsideMeters = outside,
                turnaroundAtStart = turnaround,
            ),
        )
    }

    takeEdge(startEdge, inp.start.offsetMeters, gi.length(startEdge), null)
    val sp = inp.startPrefix
    var prefix: Prefix = if (sp != null && sp.isNotEmpty() && sp[sp.size - 1] == startEdge) sp else intArrayOf(startEdge)

    val comp = edgeScc(gi, allow)
    fun remainingInComp(c: Int): Boolean {
        for (ei in remainingByEdge.keys) if (comp[ei] == c) return true
        return false
    }

    var iterations = 0L
    var status = "complete"

    fun dijkstra(from: Prefix, isTarget: (Int) -> Boolean): Pair<List<PathStep>, Prefix>? {
        val r = legalShortestPath(gi, from, isTarget, allow, zoneInfo, inp.shouldCancel)
        iterations += r.settled
        return if (r.path != null) r.path to r.endPrefix!! else null
    }

    while (remainingByEdge.isNotEmpty()) {
        if (inp.shouldCancel?.invoke() == true) {
            status = "cancelled"
            break
        }
        if (iterations > maxIterations || now() - t0 > timeBudgetMs) {
            status = "partial"
            warnings.add("budget_exceeded")
            break
        }
        val curEdge = prefix[prefix.size - 1]
        val sameComp = comp[curEdge] >= 0 && remainingInComp(comp[curEdge])
        var res: Pair<List<PathStep>, Prefix>? = null
        if (sameComp) {
            res = dijkstra(prefix) { e -> remainingByEdge.containsKey(e) && comp[e] == comp[curEdge] }
        } else {
            // Leaving a strongly connected block is irreversible: enter the reachable block whose
            // downstream chain still holds the most remaining work (ties: nearest).
            val value = sccChainValues(gi, comp, allow, remainingByEdge)
            var best = -1
            val seen = BooleanArray(gi.edges.size)
            val queue = ArrayList<Int>()
            queue.add(curEdge)
            seen[curEdge] = true
            while (queue.isNotEmpty()) {
                val v = queue.removeAt(queue.size - 1)
                if (remainingByEdge.containsKey(v) && v != curEdge && comp[v] >= 0 && value[comp[v]] > best) best = value[comp[v]]
                for (t in gi.successors(intArrayOf(v), allow)) if (!seen[t.next]) {
                    seen[t.next] = true
                    queue.add(t.next)
                }
            }
            if (best > 0) res = dijkstra(prefix) { e -> remainingByEdge.containsKey(e) && comp[e] >= 0 && value[comp[e]] == best }
        }
        if (res == null) res = dijkstra(prefix) { e -> remainingByEdge.containsKey(e) }
        if (res == null) break
        for (ps in res.first) takeEdge(ps.edge, 0.0, gi.length(ps.edge), if (ps.t.uturn) ps.t.turnaround else null)
        prefix = res.second
    }
    if (status == "complete" && inp.shouldCancel?.invoke() == true && remainingByEdge.isNotEmpty()) status = "cancelled"

    if (status == "complete" && inp.returnToStart && remainingByEdge.isEmpty()) {
        val back = dijkstra(prefix) { e -> e == startEdge }
        if (back != null) {
            val last = back.first.size - 1
            back.first.forEachIndexed { k, ps ->
                takeEdge(ps.edge, 0.0, if (k == last) inp.start.offsetMeters else gi.length(ps.edge), if (ps.t.uturn) ps.t.turnaround else null)
            }
        } else warnings.add("return_to_start_unreachable")
    }

    for ((_, ids) in remainingByEdge.entries.sortedWith { a, b -> a.key.compareTo(b.key) }) {
        for (id in ids.toList().sorted()) {
            unreachable.add(
                UnreachableRequirement(
                    id,
                    if (status == "partial" || status == "cancelled") "budget_exceeded" else "not_reachable_from_start",
                ),
            )
        }
    }
    if (status == "complete" && unreachable.any { it.cause == "not_reachable_from_start" }) status = "partial"
    if (plannable == 0 && status == "complete") status = "no_requirements"
    val unverified = steps.count { it.turnaroundAtStart == "unverified" }
    if (unverified > 0) warnings.add("unverified_turnarounds:$unverified")
    if (steps.any { it.outsideMeters > STRICT_OUTSIDE_TOL_M }) warnings.add("outside_travel")
    unreachable.sortWith { a, b -> a.requirementId.compareTo(b.requirementId) }
    return basePlan(status, iterations)
}

fun buildLegs(steps: List<PlanStep>): List<PlanLeg> {
    val legs = ArrayList<PlanLeg>()
    steps.forEachIndexed { i, s ->
        val len = s.toOffsetMeters - s.fromOffsetMeters
        val last = legs.lastOrNull()
        if (last != null && last.kind == s.kind) {
            last.lastStep = i
            last.lengthMeters += len
        } else legs.add(PlanLeg(s.kind, i, i, len))
    }
    return legs
}

fun computeTotals(
    steps: List<PlanStep>,
    requirements: List<RequiredInterval>,
    covered: List<String>,
    observed: Map<String, List<Interval>>,
): PlanTotals {
    val reqById = HashMap<String, RequiredInterval>()
    for (r in requirements) reqById[r.requirementId] = r
    var total = 0.0
    var outside = 0.0
    var approach = 0.0
    for (s in steps) {
        val len = s.toOffsetMeters - s.fromOffsetMeters
        total += len
        outside += s.outsideMeters
        if (s.kind == "approach") approach += len
    }
    var newCoverage = 0.0
    for (id in covered) {
        val r = reqById[id] ?: continue
        newCoverage += r.lengthMeters - coveredWithin(observed[r.edgeId] ?: emptyList(), r.startOffsetMeters, r.endOffsetMeters)
    }
    return PlanTotals(
        totalMeters = total,
        newCoverageMeters = newCoverage,
        repeatMeters = max(0.0, total - outside - newCoverage - approach),
        outsideMeters = outside,
        approachMeters = approach,
    )
}
