// Lossless JSON mapping between the Kotlin model and contract 1.0.0 documents.
// Every required key is always written (null kept); optional keys are omitted when absent.
// Integral doubles are written without a fraction so documents look like the web output.
package della.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

class ContractFormatException(message: String) : IllegalArgumentException(message)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object ContractJson {
    val json = Json { prettyPrint = false }
    val pretty = Json { prettyPrint = true; prettyPrintIndent = " " }

    // ---------- primitives ----------
    fun num(d: Double): JsonPrimitive =
        if (d.isFinite() && d == Math.rint(d) && kotlin.math.abs(d) < 9.0e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

    fun num(l: Long): JsonPrimitive = JsonPrimitive(l)
    fun str(s: String?): JsonElement = if (s == null) JsonNull else JsonPrimitive(s)
    fun numOrNull(d: Double?): JsonElement = if (d == null) JsonNull else num(d)
    fun longOrNull(l: Long?): JsonElement = if (l == null) JsonNull else JsonPrimitive(l)
    fun strs(l: List<String>): JsonArray = JsonArray(l.map { JsonPrimitive(it) })
    fun ll(p: LonLat): JsonArray = JsonArray(listOf(num(p.lon), num(p.lat)))
    fun lls(l: List<LonLat>): JsonArray = JsonArray(l.map { ll(it) })
    fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*pairs))

    private fun fail(msg: String): Nothing = throw ContractFormatException(msg)
    fun JsonElement?.o(): JsonObject = this as? JsonObject ?: fail("expected object")
    fun JsonElement?.a(): JsonArray = this as? JsonArray ?: fail("expected array")
    fun JsonObject.req(k: String): JsonElement = this[k] ?: fail("missing $k")
    fun JsonObject.s(k: String): String {
        val p = req(k) as? JsonPrimitive ?: fail("$k: expected string")
        if (!p.isString) fail("$k: expected string")
        return p.content
    }
    fun JsonObject.sn(k: String): String? {
        val e = req(k)
        if (e is JsonNull) return null
        val p = e as? JsonPrimitive ?: fail("$k: expected string")
        if (!p.isString) fail("$k: expected string")
        return p.content
    }
    fun JsonObject.d(k: String): Double {
        val p = req(k) as? JsonPrimitive ?: fail("$k: expected number")
        if (p.isString) fail("$k: expected number")
        return p.doubleOrNull ?: fail("$k: expected number")
    }
    fun JsonObject.dn(k: String): Double? {
        val e = req(k)
        if (e is JsonNull) return null
        val p = e as? JsonPrimitive ?: fail("$k: expected number")
        if (p.isString) fail("$k: expected number")
        return p.doubleOrNull ?: fail("$k: expected number")
    }
    fun JsonObject.l(k: String): Long = d(k).let { Math.round(it) }
    fun JsonObject.ln(k: String): Long? = dn(k)?.let { Math.round(it) }
    fun JsonObject.i(k: String): Int = l(k).toInt()
    fun JsonObject.b(k: String): Boolean {
        val p = req(k) as? JsonPrimitive ?: fail("$k: expected boolean")
        if (p.isString) fail("$k: expected boolean")
        return p.booleanOrNull ?: fail("$k: expected boolean")
    }
    fun JsonObject.ss(k: String): List<String> = req(k).a().map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: fail("$k: expected strings") }
    fun lonLat(e: JsonElement): LonLat {
        val a = e.a()
        if (a.size != 2) fail("lonLat: expected 2 numbers")
        return LonLat(a[0].jsonPrimitive.doubleOrNull ?: fail("lon"), a[1].jsonPrimitive.doubleOrNull ?: fail("lat"))
    }
    fun JsonObject.ll(k: String): LonLat = lonLat(req(k))
    fun JsonObject.lls(k: String): List<LonLat> = req(k).a().map { lonLat(it) }

    // ---------- graph ----------
    fun encodeNode(n: GraphNode) = obj(
        "nodeId" to JsonPrimitive(n.nodeId), "position" to ll(n.position), "passage" to JsonPrimitive(n.passage),
        "barrier" to str(n.barrier), "turnaround" to JsonPrimitive(n.turnaround), "sourceIds" to strs(n.sourceIds),
    )

    fun decodeNode(e: JsonElement): GraphNode = e.o().run {
        GraphNode(s("nodeId"), ll("position"), s("passage"), sn("barrier"), s("turnaround"), ss("sourceIds"))
    }

    fun encodeEdge(e: DirectedEdge) = obj(
        "edgeId" to JsonPrimitive(e.edgeId),
        "physicalSegmentId" to JsonPrimitive(e.physicalSegmentId),
        "fromNodeId" to JsonPrimitive(e.fromNodeId),
        "toNodeId" to JsonPrimitive(e.toNodeId),
        "geometry" to lls(e.geometry),
        "lengthMeters" to num(e.lengthMeters),
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
        "vehicleLimits" to obj(
            "maxWidthCm" to longOrNull(e.vehicleLimits.maxWidthCm),
            "maxHeightCm" to longOrNull(e.vehicleLimits.maxHeightCm),
            "maxWeightKg" to longOrNull(e.vehicleLimits.maxWeightKg),
        ),
        "eligibility" to JsonPrimitive(e.eligibility),
        "eligibilityReason" to str(e.eligibilityReason),
        "dataConfidence" to JsonPrimitive(e.dataConfidence),
        "provenance" to JsonPrimitive(e.provenance),
    ).let { o -> if (e.turnLanes == null) o else JsonObject(LinkedHashMap(o).apply { put("turnLanes", JsonArray(e.turnLanes.map { strs(it) })) }) }

    fun decodeEdge(el: JsonElement): DirectedEdge = el.o().run {
        val vl = req("vehicleLimits").o()
        DirectedEdge(
            edgeId = s("edgeId"), physicalSegmentId = s("physicalSegmentId"), fromNodeId = s("fromNodeId"), toNodeId = s("toNodeId"),
            geometry = lls("geometry"), lengthMeters = d("lengthMeters"), lengthMm = l("lengthMm"), directionIdentity = s("directionIdentity"),
            reciprocalEdgeId = sn("reciprocalEdgeId"), sourceFeatureIds = ss("sourceFeatureIds"),
            names = LinkedHashMap<String, String>().apply { req("names").o().forEach { (k, v) -> put(k, v.jsonPrimitive.content) } },
            surface = sn("surface"), roadClass = s("roadClass"), motorVehicleAccess = s("motorVehicleAccess"),
            conditionalAccess = sn("conditionalAccess"), barriers = ss("barriers"),
            vehicleLimits = VehicleLimits(vl.ln("maxWidthCm"), vl.ln("maxHeightCm"), vl.ln("maxWeightKg")),
            eligibility = s("eligibility"), eligibilityReason = sn("eligibilityReason"), dataConfidence = s("dataConfidence"),
            provenance = s("provenance"),
            turnLanes = this["turnLanes"]?.takeIf { it !is JsonNull }?.a()?.map { l -> l.a().map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: fail("turnLanes: expected strings") } },
        )
    }

    fun encodeRestriction(r: TurnRestriction) = obj(
        "restrictionId" to JsonPrimitive(r.restrictionId), "kind" to JsonPrimitive(r.kind), "sourceType" to JsonPrimitive(r.sourceType),
        "fromEdgeId" to JsonPrimitive(r.fromEdgeId), "viaNodeId" to str(r.viaNodeId), "viaEdgeIds" to strs(r.viaEdgeIds),
        "toEdgeId" to JsonPrimitive(r.toEdgeId), "appliesTo" to strs(r.appliesTo), "condition" to str(r.condition),
        "provenance" to JsonPrimitive(r.provenance),
    )

    fun decodeRestriction(el: JsonElement): TurnRestriction = el.o().run {
        TurnRestriction(s("restrictionId"), s("kind"), s("sourceType"), s("fromEdgeId"), sn("viaNodeId"), ss("viaEdgeIds"), s("toEdgeId"), ss("appliesTo"), sn("condition"), s("provenance"))
    }

    fun encodeGraph(g: GraphSnapshot) = obj(
        "schemaVersion" to JsonPrimitive(g.schemaVersion),
        "graphId" to JsonPrimitive(g.graphId),
        "contentHash" to JsonPrimitive(g.contentHash),
        "providerId" to JsonPrimitive(g.providerId),
        "normalizerVersion" to JsonPrimitive(g.normalizerVersion),
        "sourceDatasetVersion" to JsonPrimitive(g.sourceDatasetVersion),
        "fetchedAt" to JsonPrimitive(g.fetchedAt),
        "geographicExtent" to obj(
            "west" to num(g.geographicExtent.west), "south" to num(g.geographicExtent.south),
            "east" to num(g.geographicExtent.east), "north" to num(g.geographicExtent.north),
        ),
        "coordinateReferenceSystem" to JsonPrimitive(g.coordinateReferenceSystem),
        "attribution" to JsonPrimitive(g.attribution),
        "capabilities" to obj(
            "turnRestrictions" to JsonPrimitive(g.capabilities.turnRestrictions),
            "viaWayRestrictions" to JsonPrimitive(g.capabilities.viaWayRestrictions),
            "conditionalRestrictions" to JsonPrimitive(g.capabilities.conditionalRestrictions),
            "barriers" to JsonPrimitive(g.capabilities.barriers),
            "turnaroundData" to JsonPrimitive(g.capabilities.turnaroundData),
            "liveClosures" to JsonPrimitive(g.capabilities.liveClosures),
            "traffic" to JsonPrimitive(g.capabilities.traffic),
        ),
        "freshness" to obj("sourceTimestamp" to JsonPrimitive(g.freshness.sourceTimestamp), "note" to JsonPrimitive(g.freshness.note)),
        "completenessNotes" to strs(g.completenessNotes),
        "dataState" to JsonPrimitive(g.dataState),
        "vehicleProfile" to JsonPrimitive(g.vehicleProfile),
        "nodes" to JsonArray(g.nodes.map { encodeNode(it) }),
        "directedEdges" to JsonArray(g.directedEdges.map { encodeEdge(it) }),
        "turnRestrictions" to JsonArray(g.turnRestrictions.map { encodeRestriction(it) }),
    )

    fun decodeGraph(el: JsonElement): GraphSnapshot = el.o().run {
        val ext = req("geographicExtent").o()
        val cap = req("capabilities").o()
        val fr = req("freshness").o()
        GraphSnapshot(
            schemaVersion = s("schemaVersion"), graphId = s("graphId"), contentHash = s("contentHash"), providerId = s("providerId"),
            normalizerVersion = s("normalizerVersion"), sourceDatasetVersion = s("sourceDatasetVersion"), fetchedAt = s("fetchedAt"),
            geographicExtent = GeographicExtent(ext.d("west"), ext.d("south"), ext.d("east"), ext.d("north")),
            coordinateReferenceSystem = s("coordinateReferenceSystem"), attribution = s("attribution"),
            capabilities = GraphCapabilities(
                cap.b("turnRestrictions"), cap.b("viaWayRestrictions"), cap.s("conditionalRestrictions"), cap.b("barriers"),
                cap.b("turnaroundData"), cap.b("liveClosures"), cap.b("traffic"),
            ),
            freshness = Freshness(fr.s("sourceTimestamp"), fr.s("note")),
            completenessNotes = ss("completenessNotes"), dataState = s("dataState"), vehicleProfile = s("vehicleProfile"),
            nodes = req("nodes").a().map { decodeNode(it) },
            directedEdges = req("directedEdges").a().map { decodeEdge(it) },
            turnRestrictions = req("turnRestrictions").a().map { decodeRestriction(it) },
        )
    }

    // ---------- zone ----------
    fun encodeZoneDefinition(z: ZoneDefinition): JsonObject = when (z) {
        is ZoneDefinition.Polygon -> obj("type" to JsonPrimitive("polygon"), "coordinates" to lls(z.coordinates))
        is ZoneDefinition.Circle -> {
            val m = linkedMapOf<String, JsonElement>("type" to JsonPrimitive("circle"), "center" to ll(z.center), "radiusMeters" to num(z.radiusMeters))
            z.approximation?.let {
                m["approximation"] = obj("vertices" to JsonPrimitive(it.vertices), "toleranceMeters" to num(it.toleranceMeters), "coordinates" to lls(it.coordinates))
            }
            JsonObject(m)
        }
    }

    fun decodeZoneDefinition(el: JsonElement): ZoneDefinition = el.o().run {
        when (s("type")) {
            "polygon" -> ZoneDefinition.Polygon(lls("coordinates"))
            "circle" -> ZoneDefinition.Circle(
                ll("center"), d("radiusMeters"),
                (this["approximation"] as? JsonObject)?.let { CircleApproximation(it.i("vertices"), it.d("toleranceMeters"), it.lls("coordinates")) },
            )
            else -> fail("unknown zone type")
        }
    }

    fun encodeZone(z: Zone) = obj("zoneId" to JsonPrimitive(z.zoneId), "zoneVersion" to JsonPrimitive(z.zoneVersion), "definition" to encodeZoneDefinition(z.definition))
    fun decodeZone(el: JsonElement): Zone = el.o().run { Zone(s("zoneId"), i("zoneVersion"), decodeZoneDefinition(req("definition"))) }

    // ---------- requirements / progress ----------
    fun encodeRequirement(r: RequiredInterval) = obj(
        "requirementId" to JsonPrimitive(r.requirementId), "edgeId" to JsonPrimitive(r.edgeId),
        "startOffsetMeters" to num(r.startOffsetMeters), "endOffsetMeters" to num(r.endOffsetMeters), "lengthMeters" to num(r.lengthMeters),
        "status" to JsonPrimitive(r.status), "exclusionReason" to str(r.exclusionReason),
    )

    fun decodeRequirement(el: JsonElement): RequiredInterval = el.o().run {
        RequiredInterval(s("requirementId"), s("edgeId"), d("startOffsetMeters"), d("endOffsetMeters"), d("lengthMeters"), s("status"), sn("exclusionReason"))
    }

    fun encodeObservation(o: LocationObservation) = obj(
        "observationId" to JsonPrimitive(o.observationId), "timestamp" to JsonPrimitive(o.timestamp), "monotonicMs" to numOrNull(o.monotonicMs),
        "lon" to num(o.lon), "lat" to num(o.lat), "horizontalAccuracyMeters" to numOrNull(o.horizontalAccuracyMeters),
        "speedMps" to numOrNull(o.speedMps), "speedAccuracyMps" to numOrNull(o.speedAccuracyMps), "bearingDeg" to numOrNull(o.bearingDeg),
        "bearingAccuracyDeg" to numOrNull(o.bearingAccuracyDeg), "altitudeMeters" to numOrNull(o.altitudeMeters),
        "altitudeAccuracyMeters" to numOrNull(o.altitudeAccuracyMeters), "source" to JsonPrimitive(o.source), "ageMs" to numOrNull(o.ageMs),
    )

    fun decodeObservation(el: JsonElement): LocationObservation = el.o().run {
        LocationObservation(
            s("observationId"), l("timestamp"), dn("monotonicMs"), d("lon"), d("lat"), dn("horizontalAccuracyMeters"), dn("speedMps"),
            dn("speedAccuracyMps"), dn("bearingDeg"), dn("bearingAccuracyDeg"), dn("altitudeMeters"), dn("altitudeAccuracyMeters"), s("source"), dn("ageMs"),
        )
    }

    fun encodeTraversal(t: MatchedTraversal) = obj(
        "traversalId" to JsonPrimitive(t.traversalId), "observationIds" to strs(t.observationIds), "edgeId" to JsonPrimitive(t.edgeId),
        "startOffsetMeters" to num(t.startOffsetMeters), "endOffsetMeters" to num(t.endOffsetMeters), "confidence" to num(t.confidence),
        "evidence" to JsonPrimitive(t.evidence), "timestampStart" to JsonPrimitive(t.timestampStart), "timestampEnd" to JsonPrimitive(t.timestampEnd),
        "manuallyReviewed" to JsonPrimitive(t.manuallyReviewed),
    )

    fun decodeTraversal(el: JsonElement): MatchedTraversal = el.o().run {
        MatchedTraversal(s("traversalId"), ss("observationIds"), s("edgeId"), d("startOffsetMeters"), d("endOffsetMeters"), d("confidence"), s("evidence"), l("timestampStart"), l("timestampEnd"), b("manuallyReviewed"))
    }

    fun encodeExclusion(x: ExclusionRecord) = obj(
        "requirementId" to JsonPrimitive(x.requirementId), "reason" to JsonPrimitive(x.reason), "note" to str(x.note),
        "at" to JsonPrimitive(x.at), "active" to JsonPrimitive(x.active),
    )

    fun decodeExclusion(el: JsonElement): ExclusionRecord = el.o().run { ExclusionRecord(s("requirementId"), s("reason"), sn("note"), l("at"), b("active")) }

    // ---------- plan ----------
    fun encodeStep(s: PlanStep) = obj(
        "edgeId" to JsonPrimitive(s.edgeId), "fromOffsetMeters" to num(s.fromOffsetMeters), "toOffsetMeters" to num(s.toOffsetMeters),
        "kind" to JsonPrimitive(s.kind), "newlyCoveredRequirementIds" to strs(s.newlyCoveredRequirementIds), "outsideMeters" to num(s.outsideMeters),
        "turnaroundAtStart" to str(s.turnaroundAtStart),
    )

    fun decodeStep(el: JsonElement): PlanStep = el.o().run {
        PlanStep(s("edgeId"), d("fromOffsetMeters"), d("toOffsetMeters"), s("kind"), ss("newlyCoveredRequirementIds"), d("outsideMeters"), sn("turnaroundAtStart"))
    }

    fun encodePlan(p: RoutePlan) = obj(
        "planId" to JsonPrimitive(p.planId), "graphHash" to JsonPrimitive(p.graphHash), "zoneVersion" to JsonPrimitive(p.zoneVersion),
        "basedOnProgressRevision" to JsonPrimitive(p.basedOnProgressRevision), "planningRevision" to JsonPrimitive(p.planningRevision),
        "algorithm" to JsonPrimitive(p.algorithm), "algorithmVersion" to JsonPrimitive(p.algorithmVersion), "createdAt" to JsonPrimitive(p.createdAt),
        "startState" to obj("edgeId" to JsonPrimitive(p.startState.edgeId), "offsetMeters" to num(p.startState.offsetMeters), "source" to JsonPrimitive(p.startState.source)),
        "steps" to JsonArray(p.steps.map { encodeStep(it) }),
        "legs" to JsonArray(p.legs.map { obj("kind" to JsonPrimitive(it.kind), "firstStep" to JsonPrimitive(it.firstStep), "lastStep" to JsonPrimitive(it.lastStep), "lengthMeters" to num(it.lengthMeters)) }),
        "coveredRequirementIds" to strs(p.coveredRequirementIds),
        "unreachable" to JsonArray(p.unreachable.map { obj("requirementId" to JsonPrimitive(it.requirementId), "cause" to JsonPrimitive(it.cause)) }),
        "totals" to obj(
            "totalMeters" to num(p.totals.totalMeters), "newCoverageMeters" to num(p.totals.newCoverageMeters), "repeatMeters" to num(p.totals.repeatMeters),
            "outsideMeters" to num(p.totals.outsideMeters), "approachMeters" to num(p.totals.approachMeters),
        ),
        "limits" to obj("maxIterations" to JsonPrimitive(p.limits.maxIterations), "timeBudgetMs" to JsonPrimitive(p.limits.timeBudgetMs), "iterationsUsed" to JsonPrimitive(p.limits.iterationsUsed)),
        "warnings" to strs(p.warnings),
        "status" to JsonPrimitive(p.status),
    )

    fun decodePlan(el: JsonElement): RoutePlan = el.o().run {
        val ss0 = req("startState").o()
        val tot = req("totals").o()
        val lim = req("limits").o()
        RoutePlan(
            planId = s("planId"), graphHash = s("graphHash"), zoneVersion = i("zoneVersion"), basedOnProgressRevision = l("basedOnProgressRevision"),
            planningRevision = l("planningRevision"), algorithm = s("algorithm"), algorithmVersion = s("algorithmVersion"), createdAt = s("createdAt"),
            startState = StartState(ss0.s("edgeId"), ss0.d("offsetMeters"), ss0.s("source")),
            steps = req("steps").a().map { decodeStep(it) },
            legs = req("legs").a().map { it.o().run { PlanLeg(s("kind"), i("firstStep"), i("lastStep"), d("lengthMeters")) } },
            coveredRequirementIds = ss("coveredRequirementIds"),
            unreachable = req("unreachable").a().map { it.o().run { UnreachableRequirement(s("requirementId"), s("cause")) } },
            totals = PlanTotals(tot.d("totalMeters"), tot.d("newCoverageMeters"), tot.d("repeatMeters"), tot.d("outsideMeters"), tot.d("approachMeters")),
            limits = PlanLimits(lim.l("maxIterations"), lim.l("timeBudgetMs"), lim.l("iterationsUsed")),
            warnings = ss("warnings"), status = s("status"),
        )
    }

    // ---------- session ----------
    fun encodeSession(s: CoverageSession) = obj(
        "schemaVersion" to JsonPrimitive(s.schemaVersion), "sessionId" to JsonPrimitive(s.sessionId), "graphId" to JsonPrimitive(s.graphId),
        "contentHash" to JsonPrimitive(s.contentHash), "zone" to encodeZone(s.zone), "vehicleProfile" to JsonPrimitive(s.vehicleProfile),
        "boundaryPolicy" to JsonPrimitive(s.boundaryPolicy), "returnToStart" to JsonPrimitive(s.returnToStart),
        "startSelection" to obj(
            "requested" to ll(s.startSelection.requested),
            "snapped" to (s.startSelection.snapped?.let {
                obj("edgeId" to JsonPrimitive(it.edgeId), "offsetMeters" to num(it.offsetMeters), "position" to ll(it.position), "distanceMeters" to num(it.distanceMeters))
            } ?: JsonNull),
            "confirmed" to JsonPrimitive(s.startSelection.confirmed),
        ),
        "createdAt" to JsonPrimitive(s.createdAt), "status" to JsonPrimitive(s.status),
        "planningRevision" to JsonPrimitive(s.planningRevision), "progressRevision" to JsonPrimitive(s.progressRevision),
        "requiredIntervals" to JsonArray(s.requiredIntervals.map { encodeRequirement(it) }),
        "confirmedTraversals" to JsonArray(s.confirmedTraversals.map { encodeTraversal(it) }),
        "exclusions" to JsonArray(s.exclusions.map { encodeExclusion(it) }),
        "routePlan" to (s.routePlan?.let { encodePlan(it) } ?: JsonNull),
        "eventLogPosition" to JsonPrimitive(s.eventLogPosition),
    )

    fun decodeSession(el: JsonElement): CoverageSession = el.o().run {
        val sel = req("startSelection").o()
        val snapped = sel.req("snapped").let { sn0 ->
            if (sn0 is JsonNull) null else sn0.o().run { SnappedStart(s("edgeId"), d("offsetMeters"), ll("position"), d("distanceMeters")) }
        }
        CoverageSession(
            schemaVersion = s("schemaVersion"), sessionId = s("sessionId"), graphId = s("graphId"), contentHash = s("contentHash"),
            zone = decodeZone(req("zone")), vehicleProfile = s("vehicleProfile"), boundaryPolicy = s("boundaryPolicy"),
            returnToStart = b("returnToStart"),
            startSelection = StartSelection(sel.ll("requested"), snapped, sel.b("confirmed")),
            createdAt = s("createdAt"), status = s("status"), planningRevision = l("planningRevision"), progressRevision = l("progressRevision"),
            requiredIntervals = req("requiredIntervals").a().map { decodeRequirement(it) },
            confirmedTraversals = req("confirmedTraversals").a().map { decodeTraversal(it) },
            exclusions = req("exclusions").a().map { decodeExclusion(it) },
            routePlan = req("routePlan").let { if (it is JsonNull) null else decodePlan(it) },
            eventLogPosition = l("eventLogPosition"),
        )
    }

    fun encodeEvent(e: SessionEvent) = obj(
        "eventId" to JsonPrimitive(e.eventId), "seq" to JsonPrimitive(e.seq), "sessionId" to JsonPrimitive(e.sessionId),
        "type" to JsonPrimitive(e.type), "timestamp" to JsonPrimitive(e.timestamp), "schemaVersion" to JsonPrimitive(e.schemaVersion),
        "payload" to e.payload,
    )

    fun decodeEvent(el: JsonElement): SessionEvent = el.o().run {
        SessionEvent(s("eventId"), l("seq"), s("sessionId"), s("type"), l("timestamp"), s("schemaVersion"), req("payload").o())
    }

    /** Lenient helpers for payloads (never throw). */
    fun payloadString(p: JsonObject, k: String): String? = (p[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    fun payloadStrings(p: JsonObject, k: String): List<String>? =
        (p[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { x -> x.isString }?.content }

    fun parse(text: String): JsonElement = json.parseToJsonElement(text)
    fun stringify(e: JsonElement): String = json.encodeToString(JsonElement.serializer(), e)
    fun prettyString(e: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), e)
}
