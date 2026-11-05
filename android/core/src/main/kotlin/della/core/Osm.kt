// OpenStreetMap (Overpass JSON) -> canonical GraphSnapshot normalisation.
// Port of web/src/core/osm.ts. Data © OpenStreetMap contributors, ODbL 1.0.
// JavaScript truthiness (`if (tags.name)`) is reproduced with `truthy()`.
package della.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class OsmNode(val id: Long, val lat: Double, val lon: Double, val tags: Map<String, String>?)
class OsmWay(val id: Long, val nodes: List<Long>, val tags: Map<String, String>?)
class OsmMember(val type: String, val ref: Long, val role: String)
class OsmRelation(val id: Long, val members: List<OsmMember>, val tags: Map<String, String>?)

class OverpassResponse(
    val timestampOsmBase: String?,
    val nodes: List<OsmNode>,
    val ways: List<OsmWay>,
    val relations: List<OsmRelation>,
    /** Original element order (type, index into the typed list). */
    val order: List<Pair<Char, Int>>,
) {
    companion object {
        fun parse(root: JsonObject): OverpassResponse {
            val ts = (root["osm3s"] as? JsonObject)?.get("timestamp_osm_base")?.jsonPrimitive?.contentOrNull
            val nodes = ArrayList<OsmNode>()
            val ways = ArrayList<OsmWay>()
            val rels = ArrayList<OsmRelation>()
            val order = ArrayList<Pair<Char, Int>>()
            val elements = root["elements"]?.jsonArray ?: JsonArray(emptyList())
            for (el in elements) {
                val o = el.jsonObject
                val tags = (o["tags"] as? JsonObject)?.let { t -> LinkedHashMap<String, String>().apply { t.forEach { (k, v) -> put(k, (v as JsonPrimitive).content) } } }
                when (o["type"]?.jsonPrimitive?.content) {
                    "node" -> {
                        order.add('n' to nodes.size)
                        nodes.add(OsmNode(o["id"]!!.jsonPrimitive.long, o["lat"]!!.jsonPrimitive.double, o["lon"]!!.jsonPrimitive.double, tags))
                    }
                    "way" -> {
                        order.add('w' to ways.size)
                        ways.add(OsmWay(o["id"]!!.jsonPrimitive.long, (o["nodes"]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonPrimitive.long }, tags))
                    }
                    "relation" -> {
                        order.add('r' to rels.size)
                        val members = (o["members"]?.jsonArray ?: JsonArray(emptyList())).map {
                            val m = it.jsonObject
                            OsmMember(m["type"]!!.jsonPrimitive.content, m["ref"]!!.jsonPrimitive.long, m["role"]?.jsonPrimitive?.content ?: "")
                        }
                        rels.add(OsmRelation(o["id"]!!.jsonPrimitive.long, members, tags))
                    }
                }
            }
            return OverpassResponse(ts, nodes, ways, rels, order)
        }

        fun parse(text: String): OverpassResponse = parse(ContractJson.json.parseToJsonElement(text).jsonObject)
    }
}

const val OSM_ATTRIBUTION = "© OpenStreetMap contributors (ODbL 1.0)"

/** Highway values fetched from Overpass. Footways etc. are fetched only by fixtures. */
const val OVERPASS_HIGHWAY_REGEX =
    "^(motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|unclassified|residential|living_street|service|track|road|pedestrian|busway)$"

private val CAR_CLASSES = setOf(
    "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link",
    "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "service",
)
private val NON_CAR_CLASSES = setOf(
    "pedestrian", "footway", "path", "cycleway", "bridleway", "steps", "corridor", "platform", "busway",
    "bus_guideway", "escape", "raceway", "elevator", "via_ferrata", "sidewalk", "crossing",
)
private val LIFECYCLE = setOf("construction", "proposed", "abandoned", "disused", "razed")
private val ALLOW_VALUES = setOf("yes", "permissive", "designated", "destination")
private val DENY_VALUES = setOf("no", "private")
private val BLOCKING_BARRIERS = setOf(
    "bollard", "block", "jersey_barrier", "chain", "cycle_barrier", "kissing_gate", "stile", "turnstile",
    "full-height_turnstile", "fence", "wall", "log", "rope", "motorcycle_barrier", "bus_trap", "sump_buster",
    "planter", "debris", "spikes", "barrier_board", "yes",
)
private val GATE_BARRIERS = setOf("gate", "lift_gate", "swing_gate", "sliding_gate", "hampshire_gate", "wicket_gate", "bump_gate")
private val PASSABLE_BARRIERS = setOf("kerb", "cattle_grid", "height_restrictor", "entrance", "toll_booth", "border_control", "speed_camera")

internal fun truthy(s: String?): Boolean = !s.isNullOrEmpty()

private fun jsTrim(s: String): String = s.trim()

private fun accessValue(tags: Map<String, String>): Pair<String?, String?> {
    for (k in listOf("motorcar", "motor_vehicle", "vehicle", "access")) {
        val v = tags[k]
        if (v != null) return jsTrim(v.split(';')[0]) to k
    }
    return null to null
}

private fun explicitCarValue(tags: Map<String, String>): String? {
    for (k in listOf("motorcar", "motor_vehicle", "vehicle")) {
        val v = tags[k]
        if (v != null) return jsTrim(v.split(';')[0])
    }
    return null
}

private fun conditionalAccess(tags: Map<String, String>): String? {
    for (k in listOf("motorcar:conditional", "motor_vehicle:conditional", "vehicle:conditional", "access:conditional")) {
        val v = tags[k]
        if (v != null) return "$k=$v"
    }
    return null
}

/** Result of parseLengthCm: null = unknown/none, BELOW_DEFAULT marker, or centimetres. */
sealed class LengthCm {
    object None : LengthCm()
    object BelowDefault : LengthCm()
    data class Cm(val value: Long) : LengthCm()
}

private val FT_RE = Regex("^(\\d+(?:\\.\\d+)?)\\s*'\\s*(?:(\\d+(?:\\.\\d+)?)\\s*\")?\\z")
private val M_RE = Regex("^(\\d+(?:[.,]\\d+)?)\\s*(m|cm)?\\z")
private val KG_RE = Regex("^(\\d+(?:[.,]\\d+)?)\\s*(t|kg)?\\z")

/** Parse a length tag (maxwidth/maxheight) into centimetres. */
fun parseLengthCm(v: String?): LengthCm {
    if (v == null) return LengthCm.None
    val s = jsTrim(v).lowercase()
    if (s == "none" || s == "default" || s == "unsigned") return LengthCm.None
    if (s == "below_default") return LengthCm.BelowDefault
    val ft = FT_RE.find(s)
    if (ft != null) {
        val g2 = ft.groups[2]?.value
        return LengthCm.Cm(Math.round((ft.groupValues[1].toDouble() * 12 + (if (!g2.isNullOrEmpty()) g2.toDouble() else 0.0)) * 2.54))
    }
    val m = M_RE.find(s)
    if (m != null) {
        val n = m.groupValues[1].replaceFirst(',', '.').toDouble()
        return LengthCm.Cm(Math.round(if (m.groups[2]?.value == "cm") n else n * 100))
    }
    return LengthCm.None
}

fun parseWeightKg(v: String?): Long? {
    if (v == null) return null
    val m = KG_RE.find(jsTrim(v).lowercase()) ?: return null
    val n = m.groupValues[1].replaceFirst(',', '.').toDouble()
    return Math.round(if (m.groups[2]?.value == "kg") n else n * 1000)
}

data class WayClassification(
    val eligibility: String,
    val reason: String?,
    val motorVehicleAccess: String,
    val conditional: String?,
    val limits: VehicleLimits,
    val confidence: String,
)

fun classifyWay(tags: Map<String, String>, profile: VehicleProfile = PASSENGER_CAR): WayClassification {
    val hw = tags["highway"] ?: ""
    val (accValue, _) = accessValue(tags)
    val explicitCar = explicitCarValue(tags)
    val conditional = conditionalAccess(tags)
    val w = parseLengthCm(tags["maxwidth"])
    val h = parseLengthCm(tags["maxheight"])
    val limits = VehicleLimits(
        maxWidthCm = (w as? LengthCm.Cm)?.value,
        maxHeightCm = (h as? LengthCm.Cm)?.value,
        maxWeightKg = parseWeightKg(tags["maxweight"]),
    )
    var confidence = if (truthy(tags["fixme"]) || truthy(tags["FIXME"])) "low" else "high"
    fun res(eligibility: String, reason: String?) =
        WayClassification(eligibility, reason, accValue ?: "default", conditional, limits, confidence)

    if (tags["area"] == "yes") return res("excluded", "highway_area")
    if (hw in LIFECYCLE) return res("excluded", "not_in_service")
    if (hw in NON_CAR_CLASSES) {
        if (!(explicitCar != null && explicitCar in ALLOW_VALUES)) return res("excluded", "not_car_road_$hw")
        // explicitly open to cars: continue checks below
    } else if (hw !in CAR_CLASSES && hw != "track" && hw != "road") {
        return res("excluded", "not_a_road_" + (if (hw.isEmpty()) "untagged" else hw))
    }
    if (accValue != null) {
        if (accValue in DENY_VALUES) return res("excluded", "access_$accValue")
        if (accValue !in ALLOW_VALUES) return res("needsReview", "restricted_access_$accValue")
        if (accValue == "destination") confidence = "medium"
    }
    if (tags["oneway"] == "reversible" || tags["oneway"] == "alternating") return res("excluded", "reversible_oneway")
    if (w is LengthCm.BelowDefault || h is LengthCm.BelowDefault) return res("needsReview", "vehicle_limit_below_default")
    if (limits.maxWidthCm != null && limits.maxWidthCm < profile.widthMeters * 100) return res("excluded", "vehicle_limit_width")
    if (limits.maxHeightCm != null && limits.maxHeightCm < profile.heightMeters * 100) return res("excluded", "vehicle_limit_height")
    if (limits.maxWeightKg != null && limits.maxWeightKg < profile.weightTonnes * 1000) return res("excluded", "vehicle_limit_weight")
    if (hw == "service") {
        val sub = tags["service"]
        if (sub == "parking_aisle" || sub == "drive-through" || sub == "emergency_access") return res("excluded", "service_$sub")
        if (sub == "driveway" && !(accValue != null && accValue in ALLOW_VALUES && accValue != "destination")) {
            return res("needsReview", "driveway_access_unverified")
        }
    }
    if (hw == "track" && !(accValue != null && accValue in ALLOW_VALUES)) return res("needsReview", "track_car_access_unverified")
    if (hw == "road") return res("needsReview", "road_type_unknown")
    if (conditional != null && conditional.isNotEmpty()) return res("needsReview", "conditional_access")
    if (truthy(tags["oneway:conditional"]) || truthy(tags["oneway:motor_vehicle:conditional"])) return res("needsReview", "conditional_oneway")
    return res("eligible", null)
}

/** Directions permitted for motor vehicles: [forward, backward]. */
fun wayDirections(tags: Map<String, String>): Pair<Boolean, Boolean> {
    val ow = tags["oneway:motorcar"] ?: tags["oneway:motor_vehicle"] ?: tags["oneway:vehicle"] ?: tags["oneway"]
    if (ow == "yes" || ow == "true" || ow == "1") return true to false
    if (ow == "-1" || ow == "reverse") return false to true
    if (ow == "no" || ow == "false" || ow == "0") return true to true
    if (ow == "reversible" || ow == "alternating") return true to true // excluded by classification
    val hw = tags["highway"]
    if (tags["junction"] == "roundabout" || tags["junction"] == "circular") return true to false
    if (hw == "motorway" || hw == "motorway_link") return true to false
    return true to true
}

private fun nodePassage(tags: Map<String, String>?): Pair<String, String?> {
    if (tags == null || tags["barrier"] == null) return "free" to null
    val b = tags["barrier"]!!
    val acc = accessValue(tags).first
    if (acc != null && acc in DENY_VALUES) return "blocked" to b
    if (acc != null && acc in ALLOW_VALUES && acc != "destination") return "free" to b
    if (b in PASSABLE_BARRIERS) return "free" to b
    if (b in BLOCKING_BARRIERS) return "blocked" to b
    if (b in GATE_BARRIERS) return "unverified" to b
    return "unverified" to b
}

private fun nodeTurnaround(tags: Map<String, String>?): String {
    if (tags == null) return "none"
    if (tags["highway"] == "turning_circle") return "turning_circle"
    if (tags["highway"] == "turning_loop") return "turning_loop"
    return "none"
}

private fun names(tags: Map<String, String>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    if (truthy(tags["name"])) out["default"] = tags["name"]!!
    for (l in listOf("hy", "en", "ru", "fa")) if (truthy(tags["name:$l"])) out[l] = tags["name:$l"]!!
    if (!truthy(tags["name"]) && truthy(tags["ref"])) out["ref"] = tags["ref"]!!
    return out
}

data class NormalizeOptions(
    val providerId: String? = null,
    val fetchedAt: String,
    val dataState: String? = null,
    val profile: VehicleProfile? = null,
    val extraCompletenessNotes: List<String> = emptyList(),
)

private class Segment(val wayId: Long, val nodeIds: List<Long>, val physicalSegmentId: String)

private class RestrictionEntry(val from: String, val viaNode: String?, val viaEdges: List<String>, val to: String) {
    val key: String get() = from + "|" + viaEdges.joinToString(",") + "|" + to
}

fun normalizeOverpass(resp: OverpassResponse, opts: NormalizeOptions): GraphSnapshot {
    val profile = opts.profile ?: PASSENGER_CAR
    val osmNodes = HashMap<Long, OsmNode>()
    val ways = ArrayList<OsmWay>()
    val relations = ArrayList<OsmRelation>()
    for ((t, i) in resp.order) {
        when (t) {
            'n' -> osmNodes[resp.nodes[i].id] = resp.nodes[i]
            'w' -> ways.add(resp.ways[i])
            'r' -> relations.add(resp.relations[i])
        }
    }
    val sortedWays = ways.sortedWith { a, b -> a.id.compareTo(b.id) }
    val notes = ArrayList<String>()
    notes.add("OpenStreetMap data may be incomplete or outdated; field verification is required.")
    notes.add("Live closures and traffic are not available from this source.")
    notes.add("Conditional turn restrictions are applied at all times (conservative).")
    notes.addAll(opts.extraCompletenessNotes)
    var missingNodeRefs = 0

    val hwWays = sortedWays.filter { it.tags?.get("highway") != null }
    val usage = HashMap<Long, Int>()
    for (w in hwWays) {
        val seen = HashSet<Long>()
        for (n in w.nodes) {
            if (!osmNodes.containsKey(n)) {
                missingNodeRefs++
                continue
            }
            usage[n] = (usage[n] ?: 0) + (if (seen.contains(n)) 2 else 1)
            seen.add(n)
        }
    }
    if (missingNodeRefs > 0) notes.add("$missingNodeRefs way node references were missing from the source extract and were skipped.")

    fun isGraphNode(id: Long, idx: Int, len: Int): Boolean =
        idx == 0 || idx == len - 1 || (usage[id] ?: 0) > 1 || osmNodes[id]?.tags?.get("barrier") != null

    val graphNodeIds = HashSet<Long>()
    val segmentsByWay = HashMap<Long, List<Segment>>()
    val usedSegIds = HashSet<String>()
    fun segId(wid: Long, part: List<Long>): String {
        val base = "osm:w$wid:n${part[0]}-n${part[part.size - 1]}"
        var sid = base
        var k = 2
        while (usedSegIds.contains(sid)) sid = "$base~${k++}"
        usedSegIds.add(sid)
        return sid
    }
    for (w in hwWays) {
        // Split at missing node references: never join the remaining nodes across a gap.
        val runs = ArrayList<MutableList<Long>>()
        runs.add(ArrayList())
        for (n in w.nodes) {
            if (osmNodes.containsKey(n)) runs[runs.size - 1].add(n)
            else if (runs[runs.size - 1].isNotEmpty()) runs.add(ArrayList())
        }
        val segs = ArrayList<Segment>()
        for (ids in runs) {
        if (ids.size < 2) continue
        var start = 0
        for (i in 1 until ids.size) {
            if (isGraphNode(ids[i], i, ids.size)) {
                val nodeIds = ids.subList(start, i + 1)
                val dedup = nodeIds.filterIndexed { k, n -> k == 0 || n != nodeIds[k - 1] }
                if (dedup.size >= 2 && dedup[0] != dedup[dedup.size - 1]) {
                    val sid = segId(w.id, dedup)
                    segs.add(Segment(w.id, dedup, sid))
                    graphNodeIds.add(dedup[0])
                    graphNodeIds.add(dedup[dedup.size - 1])
                } else if (dedup.size >= 2) {
                    // closed loop segment without intermediate graph node: split at middle node
                    val mid = dedup.size / 2
                    for (part in listOf(dedup.subList(0, mid + 1), dedup.subList(mid, dedup.size))) {
                        if (part.size < 2) continue
                        val sid = segId(w.id, part)
                        segs.add(Segment(w.id, part.toList(), sid))
                        graphNodeIds.add(part[0])
                        graphNodeIds.add(part[part.size - 1])
                    }
                }
                start = i
            }
        }
        }
        segmentsByWay[w.id] = segs
    }

    fun pos(id: Long): LonLat {
        val n = osmNodes[id]!!
        return LonLat(quantize(n.lon), quantize(n.lat))
    }

    val nodes = graphNodeIds.sorted().map { id ->
        val t = osmNodes[id]?.tags
        val p = nodePassage(t)
        GraphNode(
            nodeId = "osm:n$id",
            position = pos(id),
            passage = p.first,
            barrier = p.second,
            turnaround = nodeTurnaround(t),
            sourceIds = listOf("node/$id"),
        )
    }
    val nodeById = nodes.associateBy { it.nodeId }

    val edges = ArrayList<DirectedEdge>()
    for (w in hwWays) {
        val tags = w.tags ?: emptyMap()
        val cls = classifyWay(tags, profile)
        val (fwd, bwd) = wayDirections(tags)
        for (seg in segmentsByWay[w.id] ?: emptyList()) {
            val geomF = seg.nodeIds.map { pos(it) }
            var len = 0.0
            for (i in 1 until geomF.size) len += haversine(geomF[i - 1], geomF[i])
            val lengthMm = Math.round(len * 1000)
            if (lengthMm <= 0) continue
            val a = "osm:n${seg.nodeIds[0]}"
            val b = "osm:n${seg.nodeIds[seg.nodeIds.size - 1]}"
            val endBarriers = listOf(nodeById[a]?.barrier, nodeById[b]?.barrier).filter { truthy(it) }.map { it!! }
            val fId = "${seg.physicalSegmentId}:f"
            val bId = "${seg.physicalSegmentId}:b"
            fun mk(edgeId: String, from: String, to: String, geometry: List<LonLat>, dir: String, recip: String?) = DirectedEdge(
                edgeId = edgeId,
                physicalSegmentId = seg.physicalSegmentId,
                fromNodeId = from,
                toNodeId = to,
                geometry = geometry,
                lengthMeters = lengthMm / 1000.0,
                lengthMm = lengthMm,
                directionIdentity = dir,
                reciprocalEdgeId = recip,
                sourceFeatureIds = listOf("way/${w.id}"),
                names = names(tags),
                surface = tags["surface"],
                roadClass = tags["highway"]!!,
                motorVehicleAccess = cls.motorVehicleAccess,
                conditionalAccess = cls.conditional,
                barriers = endBarriers,
                vehicleLimits = cls.limits,
                eligibility = cls.eligibility,
                eligibilityReason = cls.reason,
                dataConfidence = cls.confidence,
                provenance = "osm way/${w.id}",
            )
            if (fwd) edges.add(mk(fId, a, b, geomF, "forward", if (bwd) bId else null))
            if (bwd) edges.add(mk(bId, b, a, geomF.reversed(), "backward", if (fwd) fId else null))
        }
    }
    edges.sortWith { x, y -> x.edgeId.compareTo(y.edgeId) }

    // Turn restrictions
    val edgesByWay = HashMap<Long, MutableList<DirectedEdge>>()
    for (e in edges) {
        val wid = e.sourceFeatureIds[0].substring(4).toLong()
        edgesByWay.getOrPut(wid) { ArrayList() }.add(e)
    }
    val restrictions = ArrayList<TurnRestriction>()
    var skippedRestrictions = 0
    var ambiguousOnly = 0
    val edgeById = HashMap<String, DirectedEdge>()
    for (e in edges) edgeById[e.edgeId] = e
    val desiredAngle = mapOf("only_right_turn" to 90.0, "only_left_turn" to -90.0, "only_straight_on" to 0.0, "only_u_turn" to 180.0)
    var exceptedRestrictions = 0
    val condRe = Regex("^\\s*([a-z_]+)\\s*@\\s*(.+)\\z")
    for (r in relations.sortedWith { a, b -> a.id.compareTo(b.id) }) {
        val t = r.tags ?: emptyMap()
        if (t["type"] != "restriction" && !(t["type"] ?: "").startsWith("restriction")) continue
        var value: String? = t["restriction:motorcar"] ?: t["restriction"] ?: t["restriction:motor_vehicle"]
        var condition: String? = null
        if (!truthy(value) && truthy(t["restriction:conditional"])) {
            val m = condRe.find(t["restriction:conditional"]!!)
            if (m != null) {
                value = m.groupValues[1]
                condition = m.groupValues[2].trim()
            }
        }
        if (!truthy(value)) continue
        val v = value!!
        val except = (t["except"] ?: "").split(';').map { it.trim() }
        if (except.contains("motorcar") || except.contains("motor_vehicle")) {
            exceptedRestrictions++
            continue
        }
        val kind = if (v.startsWith("no_")) "no" else if (v.startsWith("only_")) "only" else null
        if (kind == null) continue
        val from = r.members.filter { it.role == "from" && it.type == "way" }
        val to = r.members.filter { it.role == "to" && it.type == "way" }
        val via = r.members.filter { it.role == "via" }
        if (from.size != 1 || to.size != 1 || via.isEmpty()) {
            skippedRestrictions++
            continue
        }
        val fromEdges: List<DirectedEdge> = edgesByWay[from[0].ref] ?: emptyList()
        val toEdges: List<DirectedEdge> = edgesByWay[to[0].ref] ?: emptyList()
        val entries = ArrayList<RestrictionEntry>()
        if (via.size == 1 && via[0].type == "node") {
            val vn = "osm:n${via[0].ref}"
            for (fe in fromEdges.filter { it.toNodeId == vn }) {
                for (te in toEdges.filter { it.fromNodeId == vn }) {
                    entries.add(RestrictionEntry(fe.edgeId, vn, emptyList(), te.edgeId))
                }
            }
        } else if (via.all { it.type == "way" }) {
            val viaWayIds = LinkedHashSet<Long>().apply { via.forEach { add(it.ref) } }
            val viaEdges = viaWayIds.flatMap { edgesByWay[it] ?: emptyList() }
            val viaOut = HashMap<String, MutableList<DirectedEdge>>()
            for (e in viaEdges) viaOut.getOrPut(e.fromNodeId) { ArrayList() }.add(e)
            for (fe in fromEdges) {
                // BFS along via edges from fe.toNodeId to a node where a to-edge starts
                val queue = ArrayDeque<Pair<String, List<String>>>()
                queue.addLast(fe.toNodeId to emptyList())
                var guard = 0
                while (queue.isNotEmpty() && guard++ < 200) {
                    val (node, path) = queue.removeFirst()
                    if (path.isNotEmpty()) {
                        for (te in toEdges.filter { it.fromNodeId == node }) {
                            entries.add(RestrictionEntry(fe.edgeId, null, path, te.edgeId))
                        }
                    }
                    if (path.size >= 20) continue
                    for (ve in viaOut[node] ?: emptyList<DirectedEdge>()) {
                        if (path.contains(ve.edgeId)) continue
                        if (ve.reciprocalEdgeId != null && ve.reciprocalEdgeId.isNotEmpty() && path.contains(ve.reciprocalEdgeId)) continue
                        queue.addLast(ve.toNodeId to (path + ve.edgeId))
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            skippedRestrictions++
            continue
        }
        // A to-way that passes through the junction yields several candidate to-edges. For a
        // directional only_* restriction keep the branch matching its direction; otherwise the
        // forbidden branch would become allowed. no_* keeps all candidates (conservative).
        val desired = desiredAngle[v]
        if (kind == "only" && desired != null) {
            val byBase = LinkedHashMap<String, MutableList<RestrictionEntry>>()
            for (en in entries) byBase.getOrPut(en.from + "|" + en.viaEdges.joinToString(",")) { ArrayList() }.add(en)
            val kept = ArrayList<RestrictionEntry>()
            for (group in byBase.values) {
                if (group.size == 1) {
                    kept.add(group[0])
                    continue
                }
                val lastIn = edgeById[if (group[0].viaEdges.isNotEmpty()) group[0].viaEdges[group[0].viaEdges.size - 1] else group[0].from]!!
                val inB = bearing(lastIn.geometry[lastIn.geometry.size - 2], lastIn.geometry[lastIn.geometry.size - 1])
                val scored = group.map { en ->
                    val te = edgeById[en.to]!!
                    val a = angleDiff(inB, bearing(te.geometry[0], te.geometry[1]))
                    en to Math.abs(angleDiff(a, desired))
                }.sortedWith { x, y ->
                    val d = x.second - y.second
                    if (d != 0.0) (if (d < 0) -1 else 1) else x.first.to.compareTo(y.first.to)
                }
                kept.add(scored[0].first)
                ambiguousOnly++
            }
            entries.clear()
            entries.addAll(kept)
        }
        val sortedEntries = entries.sortedWith { a, b -> a.key.compareTo(b.key) }
        sortedEntries.forEachIndexed { i, en ->
            restrictions.add(
                TurnRestriction(
                    restrictionId = if (sortedEntries.size > 1) "osm:r${r.id}#${i + 1}" else "osm:r${r.id}",
                    kind = kind,
                    sourceType = v,
                    fromEdgeId = en.from,
                    viaNodeId = en.viaNode,
                    viaEdgeIds = en.viaEdges,
                    toEdgeId = en.to,
                    appliesTo = listOf(profile.id),
                    condition = condition,
                    provenance = "osm relation/${r.id}",
                ),
            )
        }
    }
    restrictions.sortWith { a, b -> a.restrictionId.compareTo(b.restrictionId) }
    if (skippedRestrictions > 0) notes.add("$skippedRestrictions turn restriction relations could not be mapped to the extracted graph (likely outside the fetched area) and were not applied.")
    if (ambiguousOnly > 0) notes.add("$ambiguousOnly only_* restrictions had a to-way passing through the junction; the branch matching the restriction direction was used.")
    if (exceptedRestrictions > 0) notes.add("$exceptedRestrictions turn restrictions exempt passenger cars and were ignored.")

    val allPos = nodes.map { it.position }
    val bb = if (allPos.isNotEmpty()) bboxOf(allPos) else GeographicExtent(0.0, 0.0, 0.0, 0.0)
    val sourceTs = resp.timestampOsmBase ?: "unknown"
    val draft = GraphSnapshot(
        schemaVersion = SCHEMA_VERSION,
        graphId = "",
        contentHash = "",
        providerId = opts.providerId ?: "osm-overpass",
        normalizerVersion = NORMALIZER_VERSION,
        sourceDatasetVersion = sourceTs,
        fetchedAt = opts.fetchedAt,
        geographicExtent = bb,
        coordinateReferenceSystem = "EPSG:4326",
        attribution = OSM_ATTRIBUTION,
        capabilities = GraphCapabilities(
            turnRestrictions = true,
            viaWayRestrictions = true,
            conditionalRestrictions = "applied_conservatively",
            barriers = true,
            turnaroundData = true,
            liveClosures = false,
            traffic = false,
        ),
        freshness = Freshness(sourceTs, "OSM replication timestamp reported by the Overpass server."),
        completenessNotes = notes,
        dataState = if (missingNodeRefs > 0 && (opts.dataState ?: "available") == "available") "incomplete" else opts.dataState ?: "available",
        vehicleProfile = profile.id,
        nodes = nodes,
        directedEdges = edges,
        turnRestrictions = restrictions,
    )
    val hash = computeContentHash(draft)
    return draft.copy(contentHash = hash, graphId = "g-" + hash.take(16))
}

/** Overpass QL for a bounding box (already expanded by the caller). */
fun overpassQuery(bbox: GeographicExtent, timeoutS: Int = 90): String {
    fun f(v: Double) = String.format(java.util.Locale.ROOT, "%.7f", v)
    val b = "${f(bbox.south)},${f(bbox.west)},${f(bbox.north)},${f(bbox.east)}"
    return "[out:json][timeout:$timeoutS];way[\"highway\"~\"$OVERPASS_HIGHWAY_REGEX\"]($b)->.w;(.w;.w>;);out body;rel(bw.w)[\"type\"~\"^restriction\"];out body;"
}

/** Helper for JSON callers. */
internal fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
