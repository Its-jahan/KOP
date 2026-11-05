// Street sets (port of web/src/core/streets.ts): an explicit list of streets (e.g. traced from a
// client's assignment map) that restricts which roads inside a zone are required. The zone
// polygon still decides where the route may drive; only requirements on the listed streets stay.
package della.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class StreetSet(
    /** Stable id, e.g. "yerevan-client-2026-11-05". */
    val id: String,
    val nameFa: String,
    val nameEn: String,
    /** Zone outline (closed ring not repeated) that contains every street. */
    val ring: List<LonLat>,
    /** Street centre lines (OSM geometry). */
    val lines: List<List<LonLat>>,
) {
    fun name(lang: String) = if (lang == "fa") nameFa else nameEn
}

/** Max distance (m) from a street line for a sample to count as on the street. */
const val STREET_TOL_M = 8.0
/** Share of a requirement's samples that must lie on listed streets. */
const val STREET_MIN_FRACTION = 0.75
private const val SAMPLE_STEP_M = 5.0
private const val CELL_M = 50.0

/** Spatial index of line segments in a local equirectangular frame (metres). */
private class SegIndex(lines: List<List<LonLat>>) {
    private val lat0: Double
    private val kx: Double
    private val ky = 110_574.0
    private val cells = HashMap<Long, MutableList<Int>>()
    private val seg = ArrayList<Double>()

    init {
        var s = 0.0
        var n = 0
        for (l in lines) for (p in l) { s += p.lat; n++ }
        lat0 = if (n > 0) s / n else 0.0
        kx = 111_320.0 * cos(Math.toRadians(lat0))
        for (l in lines) {
            for (i in 0 until l.size - 1) {
                val x1 = l[i].lon * kx; val y1 = (l[i].lat - lat0) * ky
                val x2 = l[i + 1].lon * kx; val y2 = (l[i + 1].lat - lat0) * ky
                val k = seg.size / 4
                seg.add(x1); seg.add(y1); seg.add(x2); seg.add(y2)
                val c0x = floor((min(x1, x2) - STREET_TOL_M) / CELL_M).toLong(); val c1x = floor((max(x1, x2) + STREET_TOL_M) / CELL_M).toLong()
                val c0y = floor((min(y1, y2) - STREET_TOL_M) / CELL_M).toLong(); val c1y = floor((max(y1, y2) + STREET_TOL_M) / CELL_M).toLong()
                for (cx in c0x..c1x) for (cy in c0y..c1y) cells.getOrPut(key(cx, cy)) { ArrayList() }.add(k)
            }
        }
    }

    private fun key(cx: Long, cy: Long) = (cx shl 32) xor (cy and 0xffffffffL)

    fun near(p: LonLat, tol: Double): Boolean {
        val x = p.lon * kx
        val y = (p.lat - lat0) * ky
        val a = cells[key(floor(x / CELL_M).toLong(), floor(y / CELL_M).toLong())] ?: return false
        val t2 = tol * tol
        for (k in a) {
            val x1 = seg[4 * k]; val y1 = seg[4 * k + 1]; val x2 = seg[4 * k + 2]; val y2 = seg[4 * k + 3]
            val dx = x2 - x1; val dy = y2 - y1
            val l2 = dx * dx + dy * dy
            val u = if (l2 > 0) max(0.0, min(1.0, ((x - x1) * dx + (y - y1) * dy) / l2)) else 0.0
            val ex = x1 + u * dx - x; val ey = y1 + u * dy - y
            if (ex * ex + ey * ey <= t2) return true
        }
        return false
    }
}

/** Keep only requirements that run along the given streets. */
fun filterRequirementsByStreets(
    gi: GraphIndex,
    requirements: List<RequiredInterval>,
    lines: List<List<LonLat>>,
    tolM: Double = STREET_TOL_M,
    minFraction: Double = STREET_MIN_FRACTION,
): List<RequiredInterval> {
    val idx = SegIndex(lines)
    return requirements.filter { r ->
        val i = gi.idx[r.edgeId] ?: return@filter false
        val geom = gi.edges[i].geometry
        val cum = gi.cum[i]
        val scale = cum[cum.size - 1] / max(gi.length(i), 1e-9)
        val len = r.endOffsetMeters - r.startOffsetMeters
        val n = max(3, ceil(len / SAMPLE_STEP_M).toInt() + 1)
        var hit = 0
        for (k in 0 until n) {
            val off = (r.startOffsetMeters + len * k / (n - 1)) * scale
            if (idx.near(pointAtOffset(geom, cum, off), tolM)) hit++
        }
        hit.toDouble() / n >= minFraction
    }
}

/** Street-set file codec: our JSON format (same as the web preset) or a GeoJSON FeatureCollection. */
object StreetSetJson {
    fun parse(el: JsonElement, fallbackName: String): StreetSet = with(ContractJson) {
        val o = el as? JsonObject ?: throw IllegalArgumentException("unrecognised street set file")
        if (o["ring"] is JsonArray && o["lines"] is JsonArray) {
            val nm = o["name"] as? JsonObject
            return StreetSet(
                (o["id"] as? JsonPrimitive)?.content ?: fallbackName,
                (nm?.get("fa") as? JsonPrimitive)?.content ?: fallbackName,
                (nm?.get("en") as? JsonPrimitive)?.content ?: fallbackName,
                o.lls("ring"), o.req("lines").a().map { l -> l.a().map { lonLat(it) } },
            )
        }
        if ((o["type"] as? JsonPrimitive)?.content == "FeatureCollection" && o["features"] is JsonArray) {
            var ring: List<LonLat>? = null
            val lines = ArrayList<List<LonLat>>()
            for (f in o.req("features").a()) {
                val g = (f as? JsonObject)?.get("geometry") as? JsonObject ?: continue
                val c = g["coordinates"] ?: continue
                when ((g["type"] as? JsonPrimitive)?.content) {
                    "Polygon" -> if (ring == null) ring = c.a()[0].a().map { lonLat(it) }.dropLast(1)
                    "LineString" -> lines.add(c.a().map { lonLat(it) })
                    "MultiLineString" -> c.a().forEach { l -> lines.add(l.a().map { lonLat(it) }) }
                }
            }
            if (ring == null || lines.isEmpty()) throw IllegalArgumentException("street set needs one Polygon and at least one LineString")
            return StreetSet(fallbackName, fallbackName, fallbackName, ring, lines)
        }
        throw IllegalArgumentException("unrecognised street set file")
    }

    fun encode(s: StreetSet): JsonObject = with(ContractJson) {
        obj(
            "id" to JsonPrimitive(s.id), "name" to obj("fa" to JsonPrimitive(s.nameFa), "en" to JsonPrimitive(s.nameEn)),
            "ring" to lls(s.ring), "lines" to JsonArray(s.lines.map { lls(it) }),
        )
    }
}
