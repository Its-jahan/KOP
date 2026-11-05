// Graph content hash (port of web/src/core/hash.ts): SHA-256 (lowercase hex) of the
// RFC 8785 canonical JSON of the hash document defined in /contract/CANONICALIZATION.md.
package della.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun e7(v: Double): Long = Math.round(v * 1e7)

private fun str(s: String?): JsonElement = if (s == null) JsonNull else JsonPrimitive(s)
private fun lng(v: Long?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)
private fun strs(l: List<String>): JsonArray = JsonArray(l.map { JsonPrimitive(it) })

fun canonicalHashDocument(g: GraphSnapshot): JsonObject {
    val nodes = g.nodes.sortedWith { a, b -> a.nodeId.compareTo(b.nodeId) }.map { n ->
        JsonObject(
            linkedMapOf(
                "nodeId" to JsonPrimitive(n.nodeId),
                "lonE7" to JsonPrimitive(e7(n.position.lon)),
                "latE7" to JsonPrimitive(e7(n.position.lat)),
                "passage" to JsonPrimitive(n.passage),
                "barrier" to str(n.barrier),
                "turnaround" to JsonPrimitive(n.turnaround),
                "sourceIds" to strs(n.sourceIds),
            ),
        )
    }
    val edges = g.directedEdges.sortedWith { a, b -> a.edgeId.compareTo(b.edgeId) }.map { e ->
        JsonObject(
            linkedMapOf(
                "edgeId" to JsonPrimitive(e.edgeId),
                "physicalSegmentId" to JsonPrimitive(e.physicalSegmentId),
                "fromNodeId" to JsonPrimitive(e.fromNodeId),
                "toNodeId" to JsonPrimitive(e.toNodeId),
                "geometryE7" to JsonArray(e.geometry.map { p -> JsonArray(listOf(JsonPrimitive(e7(p.lon)), JsonPrimitive(e7(p.lat)))) }),
                "lengthMm" to JsonPrimitive(e.lengthMm),
                "directionIdentity" to JsonPrimitive(e.directionIdentity),
                "reciprocalEdgeId" to str(e.reciprocalEdgeId),
                "sourceFeatureIds" to strs(e.sourceFeatureIds),
                "names" to JsonObject(e.names.mapValues { JsonPrimitive(it.value) }),
                "surface" to str(e.surface),
                "roadClass" to JsonPrimitive(e.roadClass),
                "motorVehicleAccess" to JsonPrimitive(e.motorVehicleAccess),
                "conditionalAccess" to str(e.conditionalAccess),
                "barriers" to strs(e.barriers),
                "vehicleLimits" to JsonObject(
                    linkedMapOf(
                        "maxWidthCm" to lng(e.vehicleLimits.maxWidthCm),
                        "maxHeightCm" to lng(e.vehicleLimits.maxHeightCm),
                        "maxWeightKg" to lng(e.vehicleLimits.maxWeightKg),
                    ),
                ),
                "eligibility" to JsonPrimitive(e.eligibility),
                "eligibilityReason" to str(e.eligibilityReason),
                "dataConfidence" to JsonPrimitive(e.dataConfidence),
            ),
        )
    }
    val restrictions = g.turnRestrictions.sortedWith { a, b -> a.restrictionId.compareTo(b.restrictionId) }.map { r ->
        JsonObject(
            linkedMapOf(
                "restrictionId" to JsonPrimitive(r.restrictionId),
                "kind" to JsonPrimitive(r.kind),
                "sourceType" to JsonPrimitive(r.sourceType),
                "fromEdgeId" to JsonPrimitive(r.fromEdgeId),
                "viaNodeId" to str(r.viaNodeId),
                "viaEdgeIds" to strs(r.viaEdgeIds),
                "toEdgeId" to JsonPrimitive(r.toEdgeId),
                "appliesTo" to strs(r.appliesTo),
                "condition" to str(r.condition),
            ),
        )
    }
    return JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(g.schemaVersion),
            "providerId" to JsonPrimitive(g.providerId),
            "normalizerVersion" to JsonPrimitive(g.normalizerVersion),
            "sourceDatasetVersion" to JsonPrimitive(g.sourceDatasetVersion),
            "coordinateReferenceSystem" to JsonPrimitive(g.coordinateReferenceSystem),
            "vehicleProfile" to JsonPrimitive(g.vehicleProfile),
            "nodes" to JsonArray(nodes),
            "directedEdges" to JsonArray(edges),
            "turnRestrictions" to JsonArray(restrictions),
        ),
    )
}

fun canonicalGraphBytes(g: GraphSnapshot): String = canonicalize(canonicalHashDocument(g))

fun computeContentHash(g: GraphSnapshot): String = sha256Hex(canonicalGraphBytes(g))

/** Hash check plus consistency of non-hashed derived fields (lengthMeters, geometry endpoints). */
fun verifyGraphHash(g: GraphSnapshot): Boolean {
    if (g.graphId != "g-" + g.contentHash.take(16)) return false
    val nodes = HashMap<String, GraphNode>()
    for (n in g.nodes) nodes[n.nodeId] = n
    for (e in g.directedEdges) {
        if (Math.abs(e.lengthMeters - e.lengthMm / 1000.0) > 1e-6) return false
        val a = nodes[e.fromNodeId] ?: return false
        val b = nodes[e.toNodeId] ?: return false
        if (e.geometry.isEmpty()) return false
        val s = e.geometry[0]
        val t = e.geometry[e.geometry.size - 1]
        if (s.lon != a.position.lon || s.lat != a.position.lat || t.lon != b.position.lon || t.lat != b.position.lat) return false
    }
    return computeContentHash(g) == g.contentHash
}
