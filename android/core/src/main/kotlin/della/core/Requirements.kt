// Directed required-interval extraction (port of web/src/core/requirements.ts).
package della.core

import kotlin.math.max
import kotlin.math.min

const val MIN_REQUIRED_INTERVAL_M = 1.0
/** Max outside length for an edge to count as inside under STRICT_INSIDE. */
const val STRICT_OUTSIDE_TOL_M = 2.0
/** A requirement is complete when at most min(4 m, 25 % of it) is unconfirmed. */
const val COVERAGE_TOLERANCE_M = 4.0

class EdgeZoneInfo(val inside: List<Interval>, val insideMeters: Double, val outsideMeters: Double)

class RequirementsResult(
    val zoneInfo: List<EdgeZoneInfo>,
    val requirements: List<RequiredInterval>,
    val ignoredSlivers: Int,
    val ignoredSliverMeters: Double,
)

fun computeRequirements(gi: GraphIndex, zone: ZoneDefinition): RequirementsResult {
    val zi = ZoneIndex(zone)
    val zb = bboxOf(zi.ring)
    val pad = 0.0005
    val zoneInfo = ArrayList<EdgeZoneInfo>(gi.edges.size)
    val requirements = ArrayList<RequiredInterval>()
    var ignoredSlivers = 0
    var ignoredSliverMeters = 0.0
    gi.edges.forEachIndexed { i, e ->
        val eb = bboxOf(e.geometry)
        var inside: List<Interval> = emptyList()
        if (!(eb.west > zb.east + pad || eb.east < zb.west - pad || eb.south > zb.north + pad || eb.north < zb.south - pad)) {
            // Clip in the physical segment's forward frame so both directions agree exactly.
            val rec = e.reciprocalEdgeId
            if (e.directionIdentity == "backward" && !rec.isNullOrEmpty() && gi.idx.containsKey(rec)) {
                val fwd = gi.edges[gi.idx[rec]!!]
                val len = gi.length(i)
                val r = zi.clip(fwd.geometry).inside
                inside = r.map { Interval(max(0.0, len - it.b), max(0.0, len - it.a)) }.reversed()
            } else {
                inside = zi.clip(e.geometry).inside
            }
        }
        val l = gi.length(i)
        // Snap numeric noise (< 1 cm) at the edge ends.
        inside = inside
            .map { Interval(if (it.a < 0.01) 0.0 else min(it.a, l), if (it.b > l - 0.01) l else min(it.b, l)) }
            .filter { it.b > it.a }
        var insideMeters = 0.0
        for (iv in inside) insideMeters = insideMeters + iv.b - iv.a // JS: s + b - a
        zoneInfo.add(EdgeZoneInfo(inside, insideMeters, max(0.0, l - insideMeters)))
        if (e.eligibility == "excluded") return@forEachIndexed
        var k = 0
        for (iv in inside) {
            if (iv.b - iv.a < MIN_REQUIRED_INTERVAL_M) {
                ignoredSlivers++
                ignoredSliverMeters += iv.b - iv.a
                continue
            }
            requirements.add(
                RequiredInterval(
                    requirementId = "${e.edgeId}#${k++}",
                    edgeId = e.edgeId,
                    startOffsetMeters = iv.a,
                    endOffsetMeters = iv.b,
                    lengthMeters = iv.b - iv.a,
                    status = if (e.eligibility == "needsReview") RequirementStatus.NEEDS_REVIEW else RequirementStatus.UNVISITED,
                    exclusionReason = if (e.eligibility == "needsReview") e.eligibilityReason else null,
                ),
            )
        }
    }
    return RequirementsResult(zoneInfo, requirements, ignoredSlivers, ignoredSliverMeters)
}

fun edgeTraversable(gi: GraphIndex, zoneInfo: List<EdgeZoneInfo>, policy: String, blockedEdges: Set<Int>, i: Int): Boolean {
    val e = gi.edges[i]
    if (e.eligibility != "eligible") return false
    if (blockedEdges.contains(i)) return false
    if (policy == BoundaryPolicy.STRICT_INSIDE && zoneInfo[i].outsideMeters > STRICT_OUTSIDE_TOL_M) return false
    return true
}

fun requirementCovered(union: List<Interval>, r: RequiredInterval, tol: Double = COVERAGE_TOLERANCE_M): Boolean {
    val covered = coveredWithin(union, r.startOffsetMeters, r.endOffsetMeters)
    val uncovered = r.lengthMeters - covered
    return uncovered <= minOf(tol, 0.25 * r.lengthMeters)
}

fun requirementUncovered(union: List<Interval>, r: RequiredInterval): List<Interval> =
    uncoveredParts(union, r.startOffsetMeters, r.endOffsetMeters)
