// Zone geometry: circle approximation, validation, inclusion and polyline clipping.
// Port of web/src/core/zone.ts.
package della.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

const val DEFAULT_CIRCLE_TOLERANCE_M = 1.0
/** Boundary is inclusive within this distance. */
const val BOUNDARY_INCLUSION_TOL_M = 0.01

data class ZoneError(val code: String, val message: String, val detail: List<Int>? = null)

data class ZoneValidation(
    val ok: Boolean,
    val errors: List<ZoneError>,
    val warnings: List<String>,
    val areaSqMeters: Double,
    val perimeterMeters: Double,
)

private fun destination(center: LonLat, bearingDeg: Double, dist: Double): LonLat {
    val lat1 = center.lat * D2R
    val lon1 = center.lon * D2R
    val brng = bearingDeg * D2R
    val dr = dist / EARTH_RADIUS_M
    val lat2 = StrictMath.asin(StrictMath.sin(lat1) * StrictMath.cos(dr) + StrictMath.cos(lat1) * StrictMath.sin(dr) * StrictMath.cos(brng))
    val lon2 = lon1 + StrictMath.atan2(
        StrictMath.sin(brng) * StrictMath.sin(dr) * StrictMath.cos(lat1),
        StrictMath.cos(dr) - StrictMath.sin(lat1) * StrictMath.sin(lat2),
    )
    return LonLat(lon2 / D2R, lat2 / D2R)
}

/** Inscribed polygon whose maximum radial deviation (sagitta) <= tolerance. */
fun circleApproximation(center: LonLat, radiusMeters: Double, toleranceMeters: Double = DEFAULT_CIRCLE_TOLERANCE_M): CircleApproximation {
    val tol = min(toleranceMeters, radiusMeters * 0.5)
    var n = ceil(Math.PI / StrictMath.acos(1 - tol / radiusMeters))
    n = max(16.0, min(720.0, n))
    val ni = n.toInt()
    val coordinates = ArrayList<LonLat>(ni)
    for (i in 0 until ni) coordinates.add(quantizeLonLat(destination(center, (360.0 * i) / n, radiusMeters)))
    val sagitta = radiusMeters * (1 - StrictMath.cos(Math.PI / n))
    return CircleApproximation(ni, sagitta, coordinates)
}

fun zoneRing(def: ZoneDefinition): List<LonLat> = when (def) {
    is ZoneDefinition.Polygon -> {
        val c = def.coordinates.toMutableList()
        if (c.size > 1 && c[0].lon == c[c.size - 1].lon && c[0].lat == c[c.size - 1].lat) c.removeAt(c.size - 1)
        c
    }
    is ZoneDefinition.Circle -> (def.approximation ?: circleApproximation(def.center, def.radiusMeters)).coordinates
}

fun ringCentroid(ring: List<LonLat>): LonLat {
    var x = 0.0
    var y = 0.0
    for (p in ring) {
        x += p.lon
        y += p.lat
    }
    return LonLat(x / ring.size, y / ring.size)
}

private class TU(val t: Double, val u: Double)

private fun segIntersect(a: XY, b: XY, c: XY, d: XY): TU? {
    val rx = b.x - a.x
    val ry = b.y - a.y
    val sx = d.x - c.x
    val sy = d.y - c.y
    val den = rx * sy - ry * sx
    if (abs(den) < 1e-12) return null
    val qx = c.x - a.x
    val qy = c.y - a.y
    val t = (qx * sy - qy * sx) / den
    val u = (qx * ry - qy * rx) / den
    if (t < -1e-12 || t > 1 + 1e-12 || u < -1e-12 || u > 1 + 1e-12) return null
    return TU(t, u)
}

fun validateZone(def: ZoneDefinition): ZoneValidation {
    val errors = ArrayList<ZoneError>()
    val warnings = ArrayList<String>()
    if (def is ZoneDefinition.Circle) {
        if (!(def.radiusMeters > 0) || !def.radiusMeters.isFinite()) {
            errors.add(ZoneError("invalid_radius", "Circle radius must be a positive number of meters."))
            return ZoneValidation(false, errors, warnings, 0.0, 0.0)
        }
        if (def.radiusMeters < 20) errors.add(ZoneError("too_small", "Circle radius must be at least 20 m."))
        if (def.radiusMeters > 5000) warnings.add("large_zone")
    }
    val ring = zoneRing(def)
    if (ring.size < 3) {
        errors.add(ZoneError("too_few_vertices", "A polygon needs at least 3 distinct points."))
        return ZoneValidation(false, errors, warnings, 0.0, 0.0)
    }
    for (p in ring) {
        if (!p.lon.isFinite() || !p.lat.isFinite() || abs(p.lon) > 180 || abs(p.lat) > 85) {
            errors.add(ZoneError("invalid_coordinate", "Coordinate outside WGS84 range."))
            return ZoneValidation(false, errors, warnings, 0.0, 0.0)
        }
    }
    val proj = LocalProjection(ringCentroid(ring))
    val xy = ring.map { proj.toXY(it) }
    val n = xy.size
    for (i in 0 until n) {
        val a = xy[i]
        val b = xy[(i + 1) % n]
        if (jsHypot(b.x - a.x, b.y - a.y) < 0.01) {
            errors.add(ZoneError("duplicate_vertex", "Points ${i + 1} and ${((i + 1) % n) + 1} coincide.", listOf(i, (i + 1) % n)))
        }
    }
    if (def is ZoneDefinition.Polygon) {
        outer@ for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (j == i || (j + 1) % n == i || (i + 1) % n == j) continue
                val r = segIntersect(xy[i], xy[(i + 1) % n], xy[j], xy[(j + 1) % n])
                if (r != null) {
                    errors.add(ZoneError("self_intersection", "Edges ${i + 1} and ${j + 1} cross. Move a point to untangle the shape.", listOf(i, j)))
                    break@outer
                }
            }
        }
    }
    var area2 = 0.0
    var perim = 0.0
    for (i in 0 until n) {
        val a = xy[i]
        val b = xy[(i + 1) % n]
        area2 += a.x * b.y - b.x * a.y
        perim += jsHypot(b.x - a.x, b.y - a.y)
    }
    val area = abs(area2) / 2
    if (area < 100) errors.add(ZoneError("too_small", "Zone area must be at least 100 m²."))
    if (area > 25e6) warnings.add("large_zone")
    return ZoneValidation(errors.isEmpty(), errors, warnings, area, perim)
}

class ZoneIndex(def: ZoneDefinition) {
    val ring: List<LonLat> = zoneRing(def)
    val proj = LocalProjection(ringCentroid(ring))
    private val xy: List<XY> = ring.map { proj.toXY(it) }

    private fun distToBoundaryXY(p: XY): Double {
        var best = Double.POSITIVE_INFINITY
        val n = xy.size
        for (i in 0 until n) {
            val a = xy[i]
            val b = xy[(i + 1) % n]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val l2 = dx * dx + dy * dy
            var t = if (l2 > 0) ((p.x - a.x) * dx + (p.y - a.y) * dy) / l2 else 0.0
            t = max(0.0, min(1.0, t))
            val d = jsHypot(p.x - (a.x + dx * t), p.y - (a.y + dy * t))
            if (d < best) best = d
        }
        return best
    }

    fun containsXY(p: XY): Boolean {
        if (distToBoundaryXY(p) <= BOUNDARY_INCLUSION_TOL_M) return true
        var inside = false
        val n = xy.size
        var i = 0
        var j = n - 1
        while (i < n) {
            val a = xy[i]
            val b = xy[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < ((b.x - a.x) * (p.y - a.y)) / (b.y - a.y) + a.x) inside = !inside
            j = i++
        }
        return inside
    }

    fun contains(p: LonLat): Boolean = containsXY(proj.toXY(p))

    class ClipResult(val inside: List<Interval>, val length: Double)

    /** In-zone offset intervals (meters along the haversine length) for a polyline. */
    fun clip(coords: List<LonLat>): ClipResult {
        val cum = cumulativeLengths(coords)
        val pts = coords.map { proj.toXY(it) }
        val pieces = ArrayList<Interval>()
        val n = xy.size
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val ts = ArrayList<Double>()
            ts.add(0.0)
            ts.add(1.0)
            for (k in 0 until n) {
                val r = segIntersect(a, b, xy[k], xy[(k + 1) % n])
                if (r != null) ts.add(max(0.0, min(1.0, r.t)))
            }
            ts.sort()
            val segLen = cum[i] - cum[i - 1]
            for (k in 1 until ts.size) {
                val t0 = ts[k - 1]
                val t1 = ts[k]
                if (t1 - t0 <= 1e-12) continue
                val tm = (t0 + t1) / 2
                val mid = XY(a.x + (b.x - a.x) * tm, a.y + (b.y - a.y) * tm)
                if (containsXY(mid)) pieces.add(Interval(cum[i - 1] + segLen * t0, cum[i - 1] + segLen * t1))
            }
        }
        // Merge contiguous pieces.
        val merged = ArrayList<DoubleArray>()
        for (p in pieces) {
            val last = merged.lastOrNull()
            if (last != null && p.a <= last[1] + 1e-6) last[1] = max(last[1], p.b)
            else merged.add(doubleArrayOf(p.a, p.b))
        }
        return ClipResult(merged.map { Interval(it[0], it[1]) }, cum[cum.size - 1])
    }
}
