// Portable session export/import (Android <-> web). Port of web/src/core/portable.ts:
// validates against the frozen JSON Schema plus semantic checks the schema cannot express.
package della.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class GraphRef(
    val graphId: String,
    val contentHash: String,
    val providerId: String,
    val normalizerVersion: String,
    val sourceDatasetVersion: String,
    val attribution: String,
)

data class ExportDiagnostics(val observationsIncluded: Boolean, val gapCount: Int, val notes: List<String>)

data class SessionExportPackage(
    val packageType: String,
    val contractVersion: String,
    val exportedAt: String,
    val exporterPlatform: String,
    val exporterAppVersion: String,
    val graphRef: GraphRef,
    val graph: GraphSnapshot?,
    val session: CoverageSession,
    val events: List<SessionEvent>,
    val diagnostics: ExportDiagnostics,
)

data class ExportOptions(
    val platform: String,
    val appVersion: String,
    val includeGraph: Boolean,
    val includeObservations: Boolean,
    val exportedAt: String,
)

fun buildExport(graph: GraphSnapshot, session: CoverageSession, events: List<SessionEvent>, opts: ExportOptions): SessionExportPackage {
    val ev = if (opts.includeObservations) events else events.filter { it.type != EventType.OBSERVATION }
    return SessionExportPackage(
        packageType = "coverage-session-export",
        contractVersion = SCHEMA_VERSION,
        exportedAt = opts.exportedAt,
        exporterPlatform = opts.platform,
        exporterAppVersion = opts.appVersion,
        graphRef = GraphRef(graph.graphId, graph.contentHash, graph.providerId, graph.normalizerVersion, graph.sourceDatasetVersion, graph.attribution),
        graph = if (opts.includeGraph) graph else null,
        session = session,
        events = ev,
        diagnostics = ExportDiagnostics(
            observationsIncluded = opts.includeObservations,
            gapCount = events.count { it.type == EventType.GAP_RECORDED },
            notes = if (opts.includeGraph) listOf("Graph included under ODbL 1.0; keep attribution.")
            else listOf("Graph not included: the importing device needs the identical graph (same contentHash)."),
        ),
    )
}

fun encodeExport(p: SessionExportPackage): JsonObject = ContractJson.obj(
    "packageType" to JsonPrimitive(p.packageType),
    "contractVersion" to JsonPrimitive(p.contractVersion),
    "exportedAt" to JsonPrimitive(p.exportedAt),
    "exporter" to ContractJson.obj("platform" to JsonPrimitive(p.exporterPlatform), "appVersion" to JsonPrimitive(p.exporterAppVersion)),
    "graphRef" to ContractJson.obj(
        "graphId" to JsonPrimitive(p.graphRef.graphId), "contentHash" to JsonPrimitive(p.graphRef.contentHash),
        "providerId" to JsonPrimitive(p.graphRef.providerId), "normalizerVersion" to JsonPrimitive(p.graphRef.normalizerVersion),
        "sourceDatasetVersion" to JsonPrimitive(p.graphRef.sourceDatasetVersion), "attribution" to JsonPrimitive(p.graphRef.attribution),
    ),
    "graph" to (p.graph?.let { ContractJson.encodeGraph(it) } ?: JsonNull),
    "session" to ContractJson.encodeSession(p.session),
    "events" to JsonArray(p.events.map { ContractJson.encodeEvent(it) }),
    "diagnostics" to ContractJson.obj(
        "observationsIncluded" to JsonPrimitive(p.diagnostics.observationsIncluded),
        "gapCount" to JsonPrimitive(p.diagnostics.gapCount),
        "notes" to ContractJson.strs(p.diagnostics.notes),
    ),
)

fun decodeExport(el: JsonElement): SessionExportPackage = with(ContractJson) {
    val o = el.o()
    val ex = o.req("exporter").o()
    val gr = o.req("graphRef").o()
    val dg = o.req("diagnostics").o()
    SessionExportPackage(
        packageType = o.s("packageType"),
        contractVersion = o.s("contractVersion"),
        exportedAt = o.s("exportedAt"),
        exporterPlatform = ex.s("platform"),
        exporterAppVersion = ex.s("appVersion"),
        graphRef = GraphRef(gr.s("graphId"), gr.s("contentHash"), gr.s("providerId"), gr.s("normalizerVersion"), gr.s("sourceDatasetVersion"), gr.s("attribution")),
        graph = o.req("graph").let { if (it is JsonNull) null else decodeGraph(it) },
        session = decodeSession(o.req("session")),
        events = o.req("events").a().map { decodeEvent(it) },
        diagnostics = ExportDiagnostics(dg.b("observationsIncluded"), dg.i("gapCount"), dg.ss("notes")),
    )
}

class ImportResult(val ok: Boolean, val errors: List<String>, val pkg: SessionExportPackage? = null, val graph: GraphSnapshot? = null)

/** Validate an import. Never mutates the input; callers keep the original file on rejection. */
fun validateImport(raw: String, knownGraphs: (String) -> GraphSnapshot?): ImportResult {
    val parsed: JsonElement = try {
        ContractJson.parse(raw)
    } catch (e: Exception) {
        return ImportResult(false, listOf("corrupt_json"))
    }
    val po = parsed as? JsonObject
    val packageType = (po?.get("packageType") as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (packageType != "coverage-session-export") return ImportResult(false, listOf("not_a_session_export"))
    val cv = po["contractVersion"]
    if (!(cv is JsonPrimitive && cv.isString && cv.content == SCHEMA_VERSION)) {
        val shown = when {
            cv == null -> "undefined"
            cv is JsonPrimitive -> cv.content
            else -> cv.toString()
        }
        return ImportResult(false, listOf("unsupported_contract_version:$shown"))
    }
    val sv = SchemaValidator.contract.validateDef("sessionExportPackage", parsed)
    if (!sv.ok) return ImportResult(false, listOf("schema_invalid") + sv.errors)
    val pkg = try {
        decodeExport(parsed)
    } catch (e: IllegalArgumentException) {
        return ImportResult(false, listOf("schema_invalid", e.message ?: "decode_failed"))
    }
    val errors = ArrayList<String>()
    var graph = pkg.graph
    if (graph != null) {
        if (!verifyGraphHash(graph)) errors.add("graph_hash_mismatch")
        if (graph.contentHash != pkg.graphRef.contentHash) errors.add("graph_ref_mismatch")
        if (graph.coordinateReferenceSystem != "EPSG:4326") errors.add("unexpected_crs")
    } else {
        graph = knownGraphs(pkg.graphRef.contentHash)
        if (graph == null) errors.add("graph_unavailable_on_this_device")
    }
    if (pkg.session.contentHash != pkg.graphRef.contentHash) errors.add("session_graph_mismatch")
    if (pkg.session.schemaVersion != SCHEMA_VERSION) errors.add("session_schema_version")
    if (graph != null) {
        val len = HashMap<String, Double>()
        for (e in graph.directedEdges) len[e.edgeId] = e.lengthMm / 1000.0
        val eps = 0.01
        fun checkTraversal(edgeId: String?, start: Double?, end: Double?, traversalId: String?, where: String) {
            val l = if (edgeId != null) len[edgeId] else null
            if (edgeId == null || l == null) errors.add("unknown_edge:$where")
            else if (!(start != null && end != null && start >= -eps && end <= l + eps && end > start)) errors.add("impossible_offsets:${traversalId ?: "undefined"}")
        }
        // Requirements must be exactly what this zone + graph produce (no silent denominator change).
        try {
            val expected = computeRequirements(GraphIndex(graph), pkg.session.zone.definition).requirements
            val got = LinkedHashMap<String, RequiredInterval>()
            for (r in pkg.session.requiredIntervals) got[r.requirementId] = r
            if (expected.size != got.size) {
                errors.add("requirements_mismatch")
            } else {
                for (r in expected) {
                    val gr = got[r.requirementId]
                    if (gr == null || gr.edgeId != r.edgeId || Math.abs(gr.startOffsetMeters - r.startOffsetMeters) > 0.05 || Math.abs(gr.endOffsetMeters - r.endOffsetMeters) > 0.05) {
                        errors.add("requirements_mismatch")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            errors.add("zone_invalid")
        }
        for (t in pkg.session.confirmedTraversals) checkTraversal(t.edgeId, t.startOffsetMeters, t.endOffsetMeters, t.traversalId, t.edgeId)
        for (e in pkg.events) {
            if (e.type != EventType.TRAVERSAL_CONFIRMED) continue
            val t = e.payload["traversal"] as? JsonObject
            fun str(k: String) = (t?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun dbl(k: String) = (t?.get(k) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
            checkTraversal(str("edgeId"), dbl("startOffsetMeters"), dbl("endOffsetMeters"), str("traversalId"), e.eventId)
        }
        val reqIds = pkg.session.requiredIntervals.map { it.requirementId }.toHashSet()
        for (x in pkg.session.exclusions) if (!reqIds.contains(x.requirementId)) errors.add("unknown_exclusion:${x.requirementId}")
        for (e in pkg.events) {
            if (e.type == EventType.REQUIREMENT_EXCLUDED || e.type == EventType.REQUIREMENT_RESTORED) {
                val ids = e.payload["requirementIds"] as? JsonArray
                if (ids == null || ids.any { id -> !((id as? JsonPrimitive)?.isString == true && reqIds.contains(id.content)) }) errors.add("unknown_exclusion:${e.eventId}")
            }
        }
        if (pkg.session.graphId != graph.graphId) errors.add("session_graph_mismatch")
    }
    var lastSeq = 0L
    for (e in pkg.events) {
        if (e.sessionId != pkg.session.sessionId) errors.add("foreign_event:${e.eventId}")
        if (e.seq <= lastSeq) errors.add("non_monotonic_event:${e.eventId}")
        lastSeq = e.seq
    }
    if (errors.isNotEmpty()) return ImportResult(false, LinkedHashSet(errors).toList().take(30))
    return ImportResult(true, emptyList(), pkg, graph)
}
