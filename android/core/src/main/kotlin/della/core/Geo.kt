// Port of web/src/core/geo.ts. StrictMath (fdlibm) is used for all transcendental
// functions so results are reproducible across JVM and Android and track V8 closely;
// the order of floating-point operations follows the TypeScript source exactly.
package della.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

const val EARTH_RADIUS_M = 6371008.8
internal const val D2R = Math.PI / 180

/** JS Math.round: nearest integer, ties toward +infinity. */
fun jsRound(v: Double): Double = Math.round(v).toDouble()

fun quantize(v: Double): Double = jsRound(v * 1e7) / 1e7

fun quantizeLonLat(p: LonLat): LonLat = LonLat(quantize(p.lon), quantize(p.lat))

/** V8's Math.hypot (max-scaled, Kahan-compensated sum of squares). */
fun jsHypot(x: Double, y: Double): Double {
    val ax = abs(x)
    val ay = abs(y)
    if (ax == Double.POSITIVE_INFINITY || ay == Double.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY
    if (ax.isNaN() || ay.isNaN()) return Double.NaN
    val m = max(ax, ay)
    if (m == 0.0) return 0.0
    var sum = 0.0
    var compensation = 0.0
    for (v in doubleArrayOf(ax, ay)) {
        val n = v / m
        val summand = n * n - compensation
        val preliminary = sum + summand
        compensation = (preliminary - sum) - summand
        sum = preliminary
    }
    return sqrt(sum) * m
}

/** Great-circle distance in meters (haversine). */
fun haversine(a: LonLat, b: LonLat): Double {
    val lat1 = a.lat * D2R
    val lat2 = b.lat * D2R
    val dLat = lat2 - lat1
    val dLon = (b.lon - a.lon) * D2R
    val s1 = StrictMath.sin(dLat / 2)
    val s2 = StrictMath.sin(dLon / 2)
    val s = s1 * s1 + StrictMath.cos(lat1) * StrictMath.cos(lat2) * (s2 * s2)
    return 2 * EARTH_RADIUS_M * StrictMath.asin(min(1.0, sqrt(s)))
}

/** Initial bearing a->b in degrees [0,360). */
fun bearing(a: LonLat, b: LonLat): Double {
    val lat1 = a.lat * D2R
    val lat2 = b.lat * D2R
    val dLon = (b.lon - a.lon) * D2R
    val y = StrictMath.sin(dLon) * StrictMath.cos(lat2)
    val x = StrictMath.cos(lat1) * StrictMath.sin(lat2) - StrictMath.sin(lat1) * StrictMath.cos(lat2) * StrictMath.cos(dLon)
    val deg = StrictMath.atan2(y, x) / D2R
    return (deg + 360) % 360
}

/** Signed smallest difference b - a in degrees, range (-180, 180]. */
fun angleDiff(a: Double, b: Double): Double {
    var d = ((b - a) % 360 + 360) % 360
    if (d > 180) d -= 360
    return d
}

class XY(val x: Double, val y: Double)

/** Local tangent-plane projection (equirectangular about an origin). */
class LocalProjection(origin: LonLat) {
    val lon0 = origin.lon
    val lat0 = origin.lat
    private val kx = EARTH_RADIUS_M * StrictMath.cos(lat0 * D2R) * D2R
    private val ky = EARTH_RADIUS_M * D2R
    fun toXY(p: LonLat): XY = XY((p.lon - lon0) * kx, (p.lat - lat0) * ky)
    fun toLonLat(xy: XY): LonLat = LonLat(lon0 + xy.x / kx, lat0 + xy.y / ky)
}

fun polylineLength(coords: List<LonLat>): Double {
    var s = 0.0
    for (i in 1 until coords.size) s += haversine(coords[i - 1], coords[i])
    return s
}

fun cumulativeLengths(coords: List<LonLat>): DoubleArray {
    val out = DoubleArray(coords.size)
    for (i in 1 until coords.size) out[i] = out[i - 1] + haversine(coords[i - 1], coords[i])
    return out
}

/** Point at a given offset (meters) along polyline. */
fun pointAtOffset(coords: List<LonLat>, cum: DoubleArray, offset: Double): LonLat {
    if (offset <= 0) return coords[0]
    val total = cum[cum.size - 1]
    if (offset >= total) return coords[coords.size - 1]
    var i = 1
    while (i < cum.size && cum[i] < offset) i++
    val segLen = cum[i] - cum[i - 1]
    val t = if (segLen > 0) (offset - cum[i - 1]) / segLen else 0.0
    val a = coords[i - 1]
    val b = coords[i]
    return LonLat(a.lon + (b.lon - a.lon) * t, a.lat + (b.lat - a.lat) * t)
}

/** Bearing of the polyline at offset. */
fun bearingAtOffset(coords: List<LonLat>, cum: DoubleArray, offset: Double): Double {
    var i = 1
    while (i < cum.size - 1 && cum[i] < offset) i++
    return bearing(coords[i - 1], coords[i])
}

data class Projection(val offset: Double, val distance: Double, val point: LonLat)

/** Project a point onto a polyline using a local plane around the point. */
fun projectOnPolyline(coords: List<LonLat>, cum: DoubleArray, p: LonLat): Projection {
    val proj = LocalProjection(p)
    var best = Projection(0.0, Double.POSITIVE_INFINITY, coords[0])
    for (i in 1 until coords.size) {
        val a = proj.toXY(coords[i - 1])
        val b = proj.toXY(coords[i])
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        var t = if (len2 > 0) -(a.x * dx + a.y * dy) / len2 else 0.0
        t = max(0.0, min(1.0, t))
        val x = a.x + dx * t
        val y = a.y + dy * t
        val d = jsHypot(x, y)
        if (d < best.distance) {
            val segLen = cum[i] - cum[i - 1]
            best = Projection(cum[i - 1] + segLen * t, d, proj.toLonLat(XY(x, y)))
        }
    }
    return best
}

/** Sub-polyline between two offsets (start < end). */
fun slicePolyline(coords: List<LonLat>, cum: DoubleArray, start: Double, end: Double): List<LonLat> {
    val out = ArrayList<LonLat>()
    out.add(pointAtOffset(coords, cum, start))
    for (i in 1 until coords.size - 1) {
        if (cum[i] > start && cum[i] < end) out.add(coords[i])
    }
    out.add(pointAtOffset(coords, cum, end))
    return out
}

fun bboxOf(coords: List<LonLat>): GeographicExtent {
    var west = Double.POSITIVE_INFINITY
    var south = Double.POSITIVE_INFINITY
    var east = Double.NEGATIVE_INFINITY
    var north = Double.NEGATIVE_INFINITY
    for (p in coords) {
        if (p.lon < west) west = p.lon
        if (p.lon > east) east = p.lon
        if (p.lat < south) south = p.lat
        if (p.lat > north) north = p.lat
    }
    return GeographicExtent(west, south, east, north)
}

/** Expand bbox by meters. */
fun expandBbox(b: GeographicExtent, meters: Double): GeographicExtent {
    val latMid = (b.south + b.north) / 2
    val dLat = meters / (EARTH_RADIUS_M * D2R)
    val dLon = meters / (EARTH_RADIUS_M * StrictMath.cos(latMid * D2R) * D2R)
    return GeographicExtent(b.west - dLon, b.south - dLat, b.east + dLon, b.north + dLat)
}

internal fun floorToInt(v: Double): Int = floor(v).toInt()
