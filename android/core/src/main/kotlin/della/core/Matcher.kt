// Sequence-aware, accuracy-aware directed map matcher (port of web/src/core/matcher.ts).
// Coverage is credited only for plausible, direction-consistent observed movement
// along graph geometry.
package della.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class MatcherConfig(
    val maxAccuracyM: Double = 30.0,
    val maxFixAgeMs: Double = 10_000.0,
    val maxGapMs: Double = 5_000.0,
    val maxSpeedMps: Double = 50.0,
    val searchRadiusMinM: Double = 12.0,
    val searchRadiusMaxM: Double = 45.0,
    val minAdvanceM: Double = 3.0,
    val commitMinM: Double = 12.0,
    val ambiguityMarginM: Double = 6.0,
    val maxJunctionPathM: Double = 80.0,
    val minMovingSpeedMps: Double = 1.0,
    val headingMinSpeedMps: Double = 3.0,
    val headingToleranceDeg: Double = 60.0,
    /** Fixes stamped further in the future than this are rejected (clock skew). */
    val maxFutureMs: Double = 5_000.0,
    /** Speed cap used for junction interpolation plausibility. */
    val maxInterpolateSpeedMps: Double = 30.0,
    /** Consecutive reverse-direction fixes needed to flip a committed run. */
    val reverseConfirmFixes: Int = 2,
    /** Consecutive fixes much closer to an unconnected road before the anchor is dropped. */
    val parallelConflictFixes: Int = 2,
    /** Window for estimating speed from positions when the fix has no speed. */
    val speedWindowMs: Double = 3_000.0,
)

val DEFAULT_MATCHER_CONFIG = MatcherConfig()

object GpsQuality {
    const val ACQUIRING = "acquiring"
    const val RELIABLE = "reliable"
    const val WEAK_AMBIGUOUS = "weak_ambiguous"
    const val STALE = "stale"
    const val OFF_GRAPH = "off_graph"
}

data class Anchor(val edge: Int, val offset: Double, val t: Long, val obsId: String)

data class Piece(
    val edge: Int,
    var a: Double,
    var b: Double,
    val obsIds: MutableList<String>,
    val t0: Long,
    var t1: Long,
    var junction: Boolean,
)

data class LastFix(val t: Long, val lon: Double, val lat: Double, val id: String)

data class WindowFix(val t: Long, val lon: Double, val lat: Double)

class MatcherState(
    val version: String,
    var anchor: Anchor?,
    var lastFix: LastFix?,
    var lastGoodT: Long?,
    var pending: MutableList<Piece>,
    var pendingLength: Double,
    var committed: Boolean,
    val recentIds: MutableList<String>,
    var reverseStreak: Int = 0,
    var conflictStreak: Int = 0,
    var window: MutableList<WindowFix> = ArrayList(),
) {
    fun copy(): MatcherState = MatcherState(
        version, anchor, lastFix, lastGoodT,
        pending.map { it.copy(obsIds = it.obsIds.toMutableList()) }.toMutableList(),
        pendingLength, committed, recentIds.toMutableList(), reverseStreak, conflictStreak, window.toMutableList(),
    )
}

fun initialMatcherState(): MatcherState = MatcherState(MATCHER_VERSION, null, null, null, ArrayList(), 0.0, false, ArrayList(), 0, 0, ArrayList())

data class MatchedPosition(val edgeId: String, val offset: Double, val point: LonLat, val committed: Boolean)

data class MatchGap(val reason: String, val fromT: Long?, val toT: Long)

class MatchResult(
    val state: MatcherState,
    val traversals: List<MatchedTraversal>,
    val quality: String,
    /** duplicate | out_of_order | null */
    val ignored: String?,
    val matched: MatchedPosition?,
    val gap: MatchGap?,
)

private class Cand(val edge: Int, val offset: Double, val dist: Double, val point: LonLat)

private class PieceSpec(val edge: Int, val a: Double, val b: Double, val junction: Boolean)

private class Option(val c: Cand, val score: Double, val pieces: List<PieceSpec>, val advance: Double, val reverse: Boolean)

private fun flush(gi: GraphIndex, st: MatcherState): List<MatchedTraversal> {
    val out = ArrayList<MatchedTraversal>()
    // merge consecutive pieces on the same edge
    val merged = ArrayList<Piece>()
    for (p in st.pending) {
        val last = merged.lastOrNull()
        if (last != null && last.edge == p.edge && abs(last.b - p.a) < 1e-6) {
            last.b = p.b
            last.t1 = p.t1
            for (id in p.obsIds) if (!last.obsIds.contains(id)) last.obsIds.add(id)
            last.junction = last.junction || p.junction
        } else merged.add(p.copy(obsIds = p.obsIds.toMutableList()))
    }
    for (p in merged) {
        if (p.b - p.a <= 1e-6) continue
        val edgeId = gi.edges[p.edge].edgeId
        out.add(
            MatchedTraversal(
                traversalId = "tr:${p.obsIds[0]}:${p.obsIds[p.obsIds.size - 1]}:$edgeId:${Math.round(p.a * 1000)}",
                observationIds = p.obsIds.toList(),
                edgeId = edgeId,
                startOffsetMeters = p.a,
                endOffsetMeters = p.b,
                confidence = if (p.junction) 0.7 else 0.9,
                evidence = if (p.junction) "junction_interpolated" else "continuous",
                timestampStart = p.t0,
                timestampEnd = p.t1,
                manuallyReviewed = false,
            ),
        )
    }
    st.pending = ArrayList()
    st.pendingLength = 0.0
    return out
}

private class JPath(val mid: List<Int>, val len: Double)

/** Bounded legal path search from end of `from` edge to start of `to` edge. */
private fun junctionPath(gi: GraphIndex, from: Int, to: Int, maxLen: Double): List<JPath> {
    val res = ArrayList<JPath>()
    val allow: (Int) -> Boolean = { i -> gi.edges[i].eligibility != "excluded" }
    class Item(val prefix: Prefix, val mid: List<Int>, val len: Double)
    val stack = ArrayList<Item>()
    stack.add(Item(intArrayOf(from), emptyList(), 0.0))
    var guard = 0
    while (stack.isNotEmpty() && guard++ < 2000) {
        val cur = stack.removeAt(stack.size - 1)
        for (t in gi.successors(cur.prefix, allow)) {
            if (t.uturn) continue
            if (t.next == to) {
                res.add(JPath(cur.mid, cur.len))
                continue
            }
            val nl = cur.len + gi.length(t.next)
            if (nl > maxLen || cur.mid.size >= 4) continue
            stack.add(Item(t.prefix, cur.mid + t.next, nl))
        }
    }
    return res
}

private fun pointOn(gi: GraphIndex, edge: Int, offset: Double): LonLat {
    val e = gi.edges[edge]
    val cum = gi.cum[edge]
    var i = 1
    while (i < cum.size - 1 && cum[i] < offset) i++
    val segLen = cum[i] - cum[i - 1]
    val t = if (segLen > 0) max(0.0, min(1.0, (offset - cum[i - 1]) / segLen)) else 0.0
    val a = e.geometry[i - 1]
    val b = e.geometry[i]
    return LonLat(a.lon + (b.lon - a.lon) * t, a.lat + (b.lat - a.lat) * t)
}

fun processObservation(
    gi: GraphIndex,
    prev: MatcherState,
    obs: LocationObservation,
    nowMs: Long,
    cfg: MatcherConfig = DEFAULT_MATCHER_CONFIG,
): MatchResult {
    val st = prev.copy()
    val traversals = ArrayList<MatchedTraversal>()
    fun result(quality: String, matched: MatchedPosition? = null, gap: MatchGap? = null): MatchResult =
        MatchResult(st, traversals, quality, null, matched, gap)

    if (st.recentIds.contains(obs.observationId)) return MatchResult(prev, traversals, GpsQuality.RELIABLE, "duplicate", null, null)
    val lf0 = st.lastFix
    if (lf0 != null && obs.timestamp <= lf0.t) return MatchResult(prev, traversals, GpsQuality.RELIABLE, "out_of_order", null, null)
    st.recentIds.add(obs.observationId)
    if (st.recentIds.size > 64) st.recentIds.removeAt(0)

    fun breakAnchor(reason: String): MatchGap? {
        val hadAnchor = st.anchor != null
        if (st.committed) traversals.addAll(flush(gi, st))
        st.pending = ArrayList()
        st.pendingLength = 0.0
        st.committed = false
        st.anchor = null
        st.reverseStreak = 0
        st.conflictStreak = 0
        if (reason != "ambiguous" && reason != "off_graph") st.window = ArrayList()
        return if (hadAnchor) MatchGap(reason, st.lastGoodT, obs.timestamp) else null
    }

    val age = obs.ageMs ?: (nowMs - obs.timestamp).toDouble()
    if (age > cfg.maxFixAgeMs) return result(GpsQuality.STALE, gap = breakAnchor("stale"))
    // A fix stamped in the future (clock skew) must not become the ordering reference.
    if ((obs.timestamp - nowMs).toDouble() > cfg.maxFutureMs || age < -cfg.maxFutureMs) return result(GpsQuality.STALE, gap = breakAnchor("stale"))
    val acc = obs.horizontalAccuracyMeters
    if (acc == null || !(acc <= cfg.maxAccuracyM)) {
        return result(GpsQuality.WEAK_AMBIGUOUS, gap = breakAnchor("accuracy"))
    }
    val p = LonLat(obs.lon, obs.lat)
    var gap: MatchGap? = null
    val lf = st.lastFix
    if (lf != null) {
        val dt = (obs.timestamp - lf.t).toDouble()
        val d = haversine(LonLat(lf.lon, lf.lat), p)
        if (dt > cfg.maxGapMs) {
            gap = breakAnchor("gap")
        } else if ((d / dt) * 1000 > cfg.maxSpeedMps) {
            st.lastFix = LastFix(obs.timestamp, obs.lon, obs.lat, obs.observationId)
            return result(GpsQuality.WEAK_AMBIGUOUS, gap = breakAnchor("jump"))
        }
    }
    st.lastFix = LastFix(obs.timestamp, obs.lon, obs.lat, obs.observationId)
    // Position-derived speed over >= speedWindowMs (used when the fix carries no speed).
    var windowSpeed: Double? = null
    for (k in st.window.indices.reversed()) {
        val w = st.window[k]
        if ((obs.timestamp - w.t).toDouble() >= cfg.speedWindowMs) {
            windowSpeed = (haversine(LonLat(w.lon, w.lat), p) / (obs.timestamp - w.t).toDouble()) * 1000
            break
        }
    }
    st.window.add(WindowFix(obs.timestamp, obs.lon, obs.lat))
    if (st.window.size > 12) st.window.removeAt(0)

    val radius = max(cfg.searchRadiusMinM, min(cfg.searchRadiusMaxM, acc * 1.5 + 5))
    val cands = ArrayList<Cand>()
    for (i in gi.near(p, radius)) {
        val pr = gi.project(i, p)
        if (pr.distance <= radius) cands.add(Cand(i, pr.offset, pr.distance, pr.point))
    }
    if (cands.isEmpty()) return result(GpsQuality.OFF_GRAPH, gap = gap ?: breakAnchor("off_graph"))

    val speed = obs.speedMps
    val speedEstimate = speed ?: windowSpeed
    val moving: Boolean? = if (speedEstimate == null) null else speedEstimate >= cfg.minMovingSpeedMps
    val headingUsable = speed != null && speed >= cfg.headingMinSpeedMps && obs.bearingDeg != null
    fun headingPenalty(c: Cand): Double {
        if (!headingUsable) return 0.0
        val b = bearingAtOffset(gi.edges[c.edge].geometry, gi.cum[c.edge], c.offset)
        return if (abs(angleDiff(b, obs.bearingDeg!!)) > cfg.headingToleranceDeg) 15.0 else 0.0
    }
    val minAdvance = max(cfg.minAdvanceM, if (speed == null) acc else acc * 0.5)
    fun physOf(i: Int) = gi.edges[i].physicalSegmentId
    fun matchedOut(edge: Int, offset: Double, point: LonLat) = MatchedPosition(gi.edges[edge].edgeId, offset, point, st.committed)

    fun reanchor(g: MatchGap?): MatchResult {
        val scored = cands.map { it to (it.dist + headingPenalty(it)) }.sortedWith { x, y ->
            val ds = x.second - y.second
            if (ds != 0.0) (if (ds < 0) -1 else 1) else x.first.edge - y.first.edge
        }
        val best = scored[0]
        val rival = scored.firstOrNull { physOf(it.first.edge) != physOf(best.first.edge) && it.second - best.second < cfg.ambiguityMarginM }
        if (rival != null) return result(GpsQuality.WEAK_AMBIGUOUS, gap = g)
        st.anchor = Anchor(best.first.edge, best.first.offset, obs.timestamp, obs.observationId)
        st.lastGoodT = obs.timestamp
        st.committed = false
        st.pending = ArrayList()
        st.pendingLength = 0.0
        st.reverseStreak = 0
        st.conflictStreak = 0
        return result(GpsQuality.RELIABLE, matched = matchedOut(best.first.edge, best.first.offset, best.first.point), gap = g)
    }

    val an = st.anchor
    if (an != null) {
        val anLen = gi.length(an.edge)
        val anPoint = if (gi.edges[an.edge].geometry.isNotEmpty()) pointOn(gi, an.edge, an.offset) else p
        val dtS = max(0.001, (obs.timestamp - an.t) / 1000.0)
        val maxTravel = min(cfg.maxSpeedMps, cfg.maxInterpolateSpeedMps) * dtS + 2 * acc + 10
        val options = ArrayList<Option>()
        for (c in cands) {
            if (c.edge == an.edge) {
                val adv = c.offset - an.offset
                // Backward motion on a two-way segment is represented by the reciprocal-edge option.
                if (adv < 0 && gi.reciprocal[an.edge] >= 0) continue
                options.add(
                    Option(
                        c, c.dist + headingPenalty(c),
                        if (adv > 0) listOf(PieceSpec(c.edge, an.offset, c.offset, false)) else emptyList(),
                        adv, adv < 0,
                    ),
                )
            } else if (physOf(c.edge) == physOf(an.edge)) {
                // reciprocal direction of the same physical segment: express in anchor frame
                val advInAnchor = anLen - c.offset - an.offset
                if (advInAnchor < 0) {
                    // moving backwards along anchor edge == forwards along reciprocal
                    val from = anLen - an.offset
                    options.add(Option(c, c.dist + headingPenalty(c), listOf(PieceSpec(c.edge, from, c.offset, false)), -advInAnchor, true))
                }
            } else {
                val paths = junctionPath(gi, an.edge, c.edge, cfg.maxJunctionPathM)
                if (paths.isEmpty()) continue
                val sorted = paths.sortedWith { x, y -> x.len.compareTo(y.len) }
                val best = sorted[0]
                val total = anLen - an.offset + best.len + c.offset
                if (total > maxTravel) continue
                val crow = haversine(anPoint, c.point)
                val detour = max(0.0, total - crow)
                val unique = sorted.size == 1 || sorted[1].len - best.len > 10
                val pieces = ArrayList<PieceSpec>()
                if (anLen - an.offset > 0) pieces.add(PieceSpec(an.edge, an.offset, anLen, false))
                if (unique) for (m in best.mid) pieces.add(PieceSpec(m, 0.0, gi.length(m), true))
                if (c.offset > 0) pieces.add(PieceSpec(c.edge, 0.0, c.offset, !unique))
                options.add(Option(c, c.dist + 0.5 * detour + headingPenalty(c), if (unique) pieces else emptyList(), total, false))
            }
        }
        if (options.isEmpty()) {
            gap = gap ?: breakAnchor("ambiguous")
            // fall through to re-anchoring below
        } else {
            val sortedOpts = options.sortedWith { x, y ->
                val ds = x.score - y.score
                if (ds != 0.0) (if (ds < 0) -1 else 1) else x.c.edge - y.c.edge
            }
            val best = sortedOpts[0]
            val rival = sortedOpts.firstOrNull { physOf(it.c.edge) != physOf(best.c.edge) && it.score - best.score < cfg.ambiguityMarginM }
            if (rival != null && best.c.edge != an.edge && physOf(best.c.edge) != physOf(an.edge)) {
                return result(GpsQuality.WEAK_AMBIGUOUS, gap = breakAnchor("ambiguous"))
            }
            // A road with no legal continuity from the anchor is clearly closer: the anchor may be
            // on the wrong (parallel) road. Withhold credit; drop the anchor if it persists.
            val reachable = sortedOpts.map { physOf(it.c.edge) }.toHashSet()
            var nearestOther = Double.POSITIVE_INFINITY
            for (c in cands) if (!reachable.contains(physOf(c.edge)) && c.dist < nearestOther) nearestOther = c.dist
            if (best.c.dist - nearestOther > cfg.ambiguityMarginM) {
                st.conflictStreak++
                if (st.conflictStreak < cfg.parallelConflictFixes) return result(GpsQuality.WEAK_AMBIGUOUS, gap = gap)
                gap = gap ?: breakAnchor("ambiguous")
                return reanchor(gap)
            }
            st.conflictStreak = 0
            val stationary = (moving == false) || abs(best.advance) < minAdvance
            if (stationary) {
                st.anchor = an.copy(t = obs.timestamp) // still at the anchor; keeps interpolation windows short
                st.reverseStreak = 0
                return result(GpsQuality.RELIABLE, matched = matchedOut(an.edge, an.offset, pointOn(gi, an.edge, an.offset)), gap = gap)
            }
            if (best.reverse && best.c.edge != an.edge) {
                // Reversal on the same physical segment. A heading that contradicts it, or a single
                // fix on a committed run, is treated as noise (no credit, anchor unchanged).
                if (headingUsable) {
                    val b = bearingAtOffset(gi.edges[best.c.edge].geometry, gi.cum[best.c.edge], best.c.offset)
                    if (abs(angleDiff(b, obs.bearingDeg!!)) > 90) {
                        st.reverseStreak = 0
                        return result(GpsQuality.WEAK_AMBIGUOUS, matched = matchedOut(an.edge, an.offset, pointOn(gi, an.edge, an.offset)), gap = gap)
                    }
                }
                if (st.committed && ++st.reverseStreak < cfg.reverseConfirmFixes) {
                    return result(GpsQuality.WEAK_AMBIGUOUS, matched = matchedOut(an.edge, an.offset, pointOn(gi, an.edge, an.offset)), gap = gap)
                }
                st.reverseStreak = 0
                // direction flip on the same physical segment
                if (!st.committed) {
                    st.pending = ArrayList()
                    st.pendingLength = 0.0
                } else {
                    traversals.addAll(flush(gi, st))
                    st.committed = false
                }
                st.anchor = Anchor(best.c.edge, best.c.offset, obs.timestamp, obs.observationId)
                st.lastGoodT = obs.timestamp
                val pc = best.pieces.firstOrNull()
                if (pc != null && pc.b > pc.a) {
                    st.pending.add(Piece(pc.edge, pc.a, pc.b, mutableListOf(an.obsId, obs.observationId), an.t, obs.timestamp, pc.junction))
                    st.pendingLength += pc.b - pc.a
                }
                if (st.pendingLength >= cfg.commitMinM) {
                    st.committed = true
                    traversals.addAll(flush(gi, st))
                }
                return result(GpsQuality.RELIABLE, matched = matchedOut(best.c.edge, best.c.offset, best.c.point), gap = gap)
            }
            if (best.reverse) {
                // backward movement on a one-way edge without reciprocal: treat as noise
                return result(GpsQuality.WEAK_AMBIGUOUS, matched = matchedOut(an.edge, an.offset, pointOn(gi, an.edge, an.offset)), gap = gap)
            }
            st.reverseStreak = 0
            for (pc in best.pieces) {
                if (pc.b - pc.a <= 0) continue
                st.pending.add(Piece(pc.edge, pc.a, pc.b, mutableListOf(an.obsId, obs.observationId), an.t, obs.timestamp, pc.junction))
                st.pendingLength += pc.b - pc.a
            }
            st.anchor = Anchor(best.c.edge, best.c.offset, obs.timestamp, obs.observationId)
            st.lastGoodT = obs.timestamp
            if (!st.committed && st.pendingLength >= cfg.commitMinM) st.committed = true
            if (st.committed) traversals.addAll(flush(gi, st))
            return result(GpsQuality.RELIABLE, matched = matchedOut(best.c.edge, best.c.offset, best.c.point), gap = gap)
        }
    }

    return reanchor(gap)
}

/** Flush tentative pieces when the session pauses/ends (only if direction was committed). */
fun finalizeMatcher(gi: GraphIndex, prev: MatcherState): Pair<MatcherState, List<MatchedTraversal>> {
    val st = prev.copy()
    val traversals = if (st.committed) flush(gi, st) else emptyList()
    st.pending = ArrayList()
    st.pendingLength = 0.0
    st.anchor = null
    st.committed = false
    return st to traversals
}
