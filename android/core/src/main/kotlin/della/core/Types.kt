// Canonical coverage-navigator data model, contract version 1.0.0.
// Port of web/src/core/types.ts. The JSON Schema in /contract/schema is authoritative.
// Enumerations are kept as their exact contract strings so JSON round-trips are lossless.
package della.core

import kotlinx.serialization.json.JsonObject

const val SCHEMA_VERSION = "1.0.0"
const val NORMALIZER_VERSION = "osm-normalizer-1.1.0"
const val PLANNER_ALGORITHM = "greedy-nearest-legal"
const val PLANNER_VERSION = "1.1.0"
const val MATCHER_VERSION = "seq-matcher-1.1.0"

/** WGS84 [longitude, latitude]. */
data class LonLat(val lon: Double, val lat: Double)

data class VehicleProfile(
    val id: String,
    val widthMeters: Double,
    val heightMeters: Double,
    val weightTonnes: Double,
)

val PASSENGER_CAR = VehicleProfile("passenger_car", 1.84, 1.5, 2.0)

data class GeographicExtent(val west: Double, val south: Double, val east: Double, val north: Double)

data class GraphCapabilities(
    val turnRestrictions: Boolean,
    val viaWayRestrictions: Boolean,
    val conditionalRestrictions: String,
    val barriers: Boolean,
    val turnaroundData: Boolean,
    val liveClosures: Boolean,
    val traffic: Boolean,
)

data class GraphNode(
    val nodeId: String,
    val position: LonLat,
    /** free | blocked | unverified */
    val passage: String,
    val barrier: String?,
    /** turning_circle | turning_loop | none */
    val turnaround: String,
    val sourceIds: List<String>,
)

data class VehicleLimits(val maxWidthCm: Long?, val maxHeightCm: Long?, val maxWeightKg: Long?)

data class DirectedEdge(
    val edgeId: String,
    val physicalSegmentId: String,
    val fromNodeId: String,
    val toNodeId: String,
    val geometry: List<LonLat>,
    val lengthMeters: Double,
    val lengthMm: Long,
    /** forward | backward */
    val directionIdentity: String,
    val reciprocalEdgeId: String?,
    val sourceFeatureIds: List<String>,
    val names: Map<String, String>,
    val surface: String?,
    val roadClass: String,
    val motorVehicleAccess: String,
    val conditionalAccess: String?,
    val barriers: List<String>,
    val vehicleLimits: VehicleLimits,
    /** eligible | excluded | needsReview */
    val eligibility: String,
    val eligibilityReason: String?,
    /** high | medium | low */
    val dataConfidence: String,
    val provenance: String,
    /** Lane turn indications in travel direction, left to right (OSM turn:lanes); null if unmapped. */
    val turnLanes: List<List<String>>? = null,
)

data class TurnRestriction(
    val restrictionId: String,
    /** no | only */
    val kind: String,
    val sourceType: String,
    val fromEdgeId: String,
    val viaNodeId: String?,
    val viaEdgeIds: List<String>,
    val toEdgeId: String,
    val appliesTo: List<String>,
    val condition: String?,
    val provenance: String,
)

data class Freshness(val sourceTimestamp: String, val note: String)

data class GraphSnapshot(
    val schemaVersion: String,
    val graphId: String,
    val contentHash: String,
    val providerId: String,
    val normalizerVersion: String,
    val sourceDatasetVersion: String,
    val fetchedAt: String,
    val geographicExtent: GeographicExtent,
    val coordinateReferenceSystem: String,
    val attribution: String,
    val capabilities: GraphCapabilities,
    val freshness: Freshness,
    val completenessNotes: List<String>,
    val dataState: String,
    val vehicleProfile: String,
    val nodes: List<GraphNode>,
    val directedEdges: List<DirectedEdge>,
    val turnRestrictions: List<TurnRestriction>,
)

data class CircleApproximation(val vertices: Int, val toleranceMeters: Double, val coordinates: List<LonLat>)

sealed class ZoneDefinition {
    data class Polygon(val coordinates: List<LonLat>) : ZoneDefinition()
    data class Circle(
        val center: LonLat,
        val radiusMeters: Double,
        /** Reproducible polygon approximation (closed ring not repeated). */
        val approximation: CircleApproximation? = null,
    ) : ZoneDefinition()
}

data class Zone(val zoneId: String, val zoneVersion: Int, val definition: ZoneDefinition)

object RequirementStatus {
    const val UNVISITED = "unvisited"
    const val PARTIAL = "partial"
    const val COVERED = "covered"
    const val BLOCKED = "blocked"
    const val UNREACHABLE = "unreachable"
    const val NEEDS_REVIEW = "needsReview"
    val ALL = listOf(UNVISITED, PARTIAL, COVERED, BLOCKED, UNREACHABLE, NEEDS_REVIEW)
}

data class RequiredInterval(
    val requirementId: String,
    val edgeId: String,
    val startOffsetMeters: Double,
    val endOffsetMeters: Double,
    val lengthMeters: Double,
    val status: String,
    val exclusionReason: String?,
)

data class LocationObservation(
    val observationId: String,
    /** wall clock, ms since epoch */
    val timestamp: Long,
    val monotonicMs: Double?,
    val lon: Double,
    val lat: Double,
    val horizontalAccuracyMeters: Double?,
    val speedMps: Double?,
    val speedAccuracyMps: Double?,
    val bearingDeg: Double?,
    val bearingAccuracyDeg: Double?,
    val altitudeMeters: Double?,
    val altitudeAccuracyMeters: Double?,
    /** gps | network | fused | browser | simulated_test */
    val source: String,
    val ageMs: Double?,
)

data class MatchedTraversal(
    val traversalId: String,
    val observationIds: List<String>,
    val edgeId: String,
    val startOffsetMeters: Double,
    val endOffsetMeters: Double,
    val confidence: Double,
    /** continuous | junction_interpolated */
    val evidence: String,
    val timestampStart: Long,
    val timestampEnd: Long,
    val manuallyReviewed: Boolean,
)

data class PlanStep(
    val edgeId: String,
    val fromOffsetMeters: Double,
    val toOffsetMeters: Double,
    /** required | transit | outside | approach */
    val kind: String,
    val newlyCoveredRequirementIds: List<String>,
    val outsideMeters: Double,
    /** verified | unverified | null */
    val turnaroundAtStart: String?,
)

data class PlanLeg(val kind: String, val firstStep: Int, var lastStep: Int, var lengthMeters: Double)

data class UnreachableRequirement(
    val requirementId: String,
    /** outside_policy | not_reachable_from_start | needs_review | blocked_by_user | budget_exceeded */
    val cause: String,
)

data class PlanTotals(
    val totalMeters: Double,
    val newCoverageMeters: Double,
    val repeatMeters: Double,
    val outsideMeters: Double,
    val approachMeters: Double,
)

data class StartState(
    val edgeId: String,
    val offsetMeters: Double,
    /** selected_start | gps_position */
    val source: String,
)

data class PlanLimits(val maxIterations: Long, val timeBudgetMs: Long, val iterationsUsed: Long)

data class RoutePlan(
    val planId: String,
    val graphHash: String,
    val zoneVersion: Int,
    val basedOnProgressRevision: Long,
    val planningRevision: Long,
    val algorithm: String,
    val algorithmVersion: String,
    val createdAt: String,
    val startState: StartState,
    val steps: List<PlanStep>,
    val legs: List<PlanLeg>,
    val coveredRequirementIds: List<String>,
    val unreachable: List<UnreachableRequirement>,
    val totals: PlanTotals,
    val limits: PlanLimits,
    val warnings: List<String>,
    /** complete | partial | cancelled | failed | no_requirements */
    val status: String,
)

data class ExclusionRecord(
    val requirementId: String,
    /** blocked | inaccessible | uncertain */
    val reason: String,
    val note: String?,
    val at: Long,
    var active: Boolean,
)

data class SnappedStart(val edgeId: String, val offsetMeters: Double, val position: LonLat, val distanceMeters: Double)

data class StartSelection(val requested: LonLat, val snapped: SnappedStart?, val confirmed: Boolean)

data class CoverageSession(
    val schemaVersion: String,
    val sessionId: String,
    val graphId: String,
    val contentHash: String,
    val zone: Zone,
    val vehicleProfile: String,
    /** STRICT_INSIDE | ALLOW_NECESSARY_CONNECTORS */
    val boundaryPolicy: String,
    val returnToStart: Boolean,
    val startSelection: StartSelection,
    val createdAt: String,
    /** preparing | ready | active | paused | ended */
    val status: String,
    val planningRevision: Long,
    val progressRevision: Long,
    val requiredIntervals: List<RequiredInterval>,
    val confirmedTraversals: List<MatchedTraversal>,
    val exclusions: List<ExclusionRecord>,
    val routePlan: RoutePlan?,
    val eventLogPosition: Long,
)

object EventType {
    const val SESSION_CREATED = "session_created"
    const val START_CONFIRMED = "start_confirmed"
    const val PLAN_ACCEPTED = "plan_accepted"
    const val OBSERVATION = "observation"
    const val TRAVERSAL_CONFIRMED = "traversal_confirmed"
    const val GAP_RECORDED = "gap_recorded"
    const val REQUIREMENT_EXCLUDED = "requirement_excluded"
    const val REQUIREMENT_RESTORED = "requirement_restored"
    const val PAUSED = "paused"
    const val RESUMED = "resumed"
    const val ENDED = "ended"
}

data class SessionEvent(
    val eventId: String,
    val seq: Long,
    val sessionId: String,
    val type: String,
    val timestamp: Long,
    val schemaVersion: String,
    val payload: JsonObject,
)

object BoundaryPolicy {
    const val STRICT_INSIDE = "STRICT_INSIDE"
    const val ALLOW_NECESSARY_CONNECTORS = "ALLOW_NECESSARY_CONNECTORS"
}
