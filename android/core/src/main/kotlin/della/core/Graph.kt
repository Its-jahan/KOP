// Runtime graph index: adjacency, legal transition automaton (turn restrictions incl.
// via-way), dead-end/turnaround rules and a spatial grid for snapping and matching.
// Port of web/src/core/graph.ts. Successor lists are in edgeId order.
package della.core

import kotlin.math.max
import kotlin.math.min

typealias Prefix = IntArray

fun Prefix.key(): String = joinToString(",")

class Transition(
    val next: Int,
    val prefix: Prefix,
    val uturn: Boolean,
    /** verified | unverified | null */
    val turnaround: String?,
    /** Signed heading change in degrees (positive = right turn). */
    val turnAngle: Double,
)

class GraphIndex(val g: GraphSnapshot) {
    val edges: List<DirectedEdge> = g.directedEdges.sortedWith { a, b -> a.edgeId.compareTo(b.edgeId) }
    val idx = HashMap<String, Int>()
    val nodes = HashMap<String, GraphNode>()
    val out = HashMap<String, MutableList<Int>>()
    val cum: Array<DoubleArray>
    val reciprocal: IntArray
    val physical = HashMap<String, MutableList<Int>>()
    val deadEnd: BooleanArray
    val endBearing: DoubleArray
    val startBearing: DoubleArray
    private val forbidden = HashSet<String>()
    private val prefixes = HashSet<String>()
    private var maxSeqLen = 1
    val restrictionWarnings = ArrayList<String>()
    private val proj: LocalProjection
    private val cell = 50.0
    private val grid = HashMap<Long, MutableList<Int>>()

    init {
        edges.forEachIndexed { i, e -> idx[e.edgeId] = i }
        for (n in g.nodes) nodes[n.nodeId] = n
        cum = Array(edges.size) { cumulativeLengths(edges[it].geometry) }
        reciprocal = IntArray(edges.size) { -1 }
        endBearing = DoubleArray(edges.size)
        startBearing = DoubleArray(edges.size)
        edges.forEachIndexed { i, e ->
            val r = e.reciprocalEdgeId
            if (!r.isNullOrEmpty() && idx.containsKey(r)) reciprocal[i] = idx[r]!!
            out.getOrPut(e.fromNodeId) { ArrayList() }.add(i)
            physical.getOrPut(e.physicalSegmentId) { ArrayList() }.add(i)
            val geo = e.geometry
            startBearing[i] = bearing(geo[0], geo[1])
            endBearing[i] = bearing(geo[geo.size - 2], geo[geo.size - 1])
        }
        deadEnd = BooleanArray(edges.size)
        edges.forEachIndexed { i, e ->
            val node = nodes[e.toNodeId]
            if (node == null || node.passage != "free") {
                deadEnd[i] = true
            } else {
                val cont = (out[e.toNodeId] ?: emptyList<Int>()).any { j ->
                    edges[j].physicalSegmentId != e.physicalSegmentId && edges[j].eligibility == "eligible"
                }
                deadEnd[i] = !cont
            }
        }
        buildRestrictions()
        val ext = g.geographicExtent
        proj = LocalProjection(LonLat((ext.west + ext.east) / 2, (ext.south + ext.north) / 2))
        edges.forEachIndexed { i, e ->
            for (k in 1 until e.geometry.size) {
                val a = proj.toXY(e.geometry[k - 1])
                val b = proj.toXY(e.geometry[k])
                val x0 = floorToInt(min(a.x, b.x) / cell)
                val x1 = floorToInt(max(a.x, b.x) / cell)
                val y0 = floorToInt(min(a.y, b.y) / cell)
                val y1 = floorToInt(max(a.y, b.y) / cell)
                for (x in x0..x1) for (y in y0..y1) {
                    val l = grid.getOrPut(cellKey(x, y)) { ArrayList() }
                    if (l.isEmpty() || l[l.size - 1] != i) l.add(i)
                }
            }
        }
    }

    private fun cellKey(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

    private class OnlyGroup(val base: IntArray, val allowed: HashSet<Int>)

    private fun buildRestrictions() {
        val onlyGroups = HashMap<String, OnlyGroup>()
        for (r in g.turnRestrictions) {
            val ids = ArrayList<String>()
            ids.add(r.fromEdgeId)
            ids.addAll(r.viaEdgeIds)
            ids.add(r.toEdgeId)
            if (!ids.all { idx.containsKey(it) }) {
                restrictionWarnings.add("restriction ${r.restrictionId} references unknown edges")
                continue
            }
            val seq = IntArray(ids.size) { idx[ids[it]]!! }
            if (r.kind == "no") {
                addForbidden(seq)
            } else {
                val base = seq.copyOfRange(0, seq.size - 1)
                val key = r.restrictionId.split('#')[0] + "|" + base.key()
                val grp = onlyGroups.getOrPut(key) { OnlyGroup(base, HashSet()) }
                grp.allowed.add(seq[seq.size - 1])
            }
        }
        for (k in onlyGroups.keys.sorted()) {
            val grp = onlyGroups[k]!!
            val last = edges[grp.base[grp.base.size - 1]]
            for (x in out[last.toNodeId] ?: emptyList<Int>()) {
                if (!grp.allowed.contains(x)) addForbidden(grp.base + x)
            }
        }
    }

    private fun addForbidden(seq: IntArray) {
        forbidden.add(seq.key())
        for (l in 2 until seq.size) prefixes.add(seq.copyOfRange(0, l).key())
        if (seq.size > maxSeqLen) maxSeqLen = seq.size
    }

    /** Apply automaton step. Returns null when the move completes a forbidden sequence. */
    fun step(prefix: Prefix, next: Int): Prefix? {
        val cand = prefix + next
        val n = cand.size
        for (l in 2..min(n, maxSeqLen)) {
            if (forbidden.contains(cand.copyOfRange(n - l, n).key())) return null
        }
        var l = min(n, maxSeqLen - 1)
        while (l >= 2) {
            val s = cand.copyOfRange(n - l, n)
            if (prefixes.contains(s.key())) return s
            l--
        }
        return intArrayOf(next)
    }

    fun isUturn(a: Int, b: Int): Boolean = edges[a].physicalSegmentId == edges[b].physicalSegmentId

    fun turnaroundAt(edge: Int): String {
        val n = nodes[edges[edge].toNodeId]
        return if (n != null && n.turnaround != "none") "verified" else "unverified"
    }

    /**
     * Legal successors of the state (prefix ending at edge e), restricted to edges
     * accepted by `allow`. U-turns only at dead ends; never through a non-free node.
     */
    fun successors(prefix: Prefix, allow: (Int) -> Boolean): List<Transition> {
        val e = prefix[prefix.size - 1]
        val edge = edges[e]
        val node = nodes[edge.toNodeId]
        val res = ArrayList<Transition>()
        val nodeFree = node != null && node.passage == "free"
        for (j in out[edge.toNodeId] ?: emptyList<Int>()) {
            if (!allow(j)) continue
            val uturn = isUturn(e, j)
            if (uturn) {
                if (!deadEnd[e]) continue
            } else if (!nodeFree) continue
            val np = step(prefix, j) ?: continue
            res.add(
                Transition(
                    next = j,
                    prefix = np,
                    uturn = uturn,
                    turnaround = if (uturn) turnaroundAt(e) else null,
                    turnAngle = angleDiff(endBearing[e], startBearing[j]),
                ),
            )
        }
        return res
    }

    /** Whether the two-edge transition a->b is legal (ignores longer via-way context). */
    fun pairLegal(a: Int, b: Int): Boolean {
        val ea = edges[a]
        if (ea.toNodeId != edges[b].fromNodeId) return false
        if (isUturn(a, b)) return deadEnd[a] && step(intArrayOf(a), b) != null
        val node = nodes[ea.toNodeId]
        if (node == null || node.passage != "free") return false
        return step(intArrayOf(a), b) != null
    }

    /** Candidate edges near a point (unique indices, sorted). */
    fun near(p: LonLat, radius: Double): List<Int> {
        val xy = proj.toXY(p)
        val x0 = floorToInt((xy.x - radius) / cell)
        val x1 = floorToInt((xy.x + radius) / cell)
        val y0 = floorToInt((xy.y - radius) / cell)
        val y1 = floorToInt((xy.y + radius) / cell)
        val set = HashSet<Int>()
        for (x in x0..x1) for (y in y0..y1) grid[cellKey(x, y)]?.let { set.addAll(it) }
        return set.sorted()
    }

    fun project(i: Int, p: LonLat): Projection = projectOnPolyline(edges[i].geometry, cum[i], p)

    /** Authoritative length (derived from hashed lengthMm, never from lengthMeters). */
    fun length(i: Int): Double = edges[i].lengthMm / 1000.0
}
