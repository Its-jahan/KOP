// Immutable UI snapshots published by the Engine (no large mutable graph in Compose state).
package com.della.coveragenavigator

import della.core.CoverageMetrics
import della.core.LonLat

enum class Screen { PREPARE, DRIVE, DIAGNOSTICS, SETTINGS, SESSIONS }
enum class ZoneMode { POLYGON, CIRCLE }
enum class TapMode { ZONE, START }

data class UiText(val id: Int, val args: List<Any> = emptyList())

data class ZoneInfo(
    val mode: ZoneMode = ZoneMode.POLYGON,
    val vertices: List<LonLat> = emptyList(),
    val circleCenter: LonLat? = null,
    val radiusMeters: Double = 300.0,
    val valid: Boolean = false,
    val errors: List<String> = emptyList(),
    val areaSqMeters: Double = 0.0,
    val perimeterMeters: Double = 0.0,
    val vertexCount: Int = 0,
)

data class GraphInfo(
    val status: String = "none", // none | fetching | available | failed
    val progress: String? = null,
    val failureCode: String? = null,
    val dataState: String = "not_configured",
    val graphId: String? = null,
    val contentHash: String? = null,
    val providerId: String? = null,
    val sourceVersion: String? = null,
    val fetchedAt: String? = null,
    val endpoint: String? = null,
    val nodeCount: Int = 0,
    val edgeCount: Int = 0,
    val restrictionCount: Int = 0,
    val eligibleEdges: Int = 0,
    val excludedEdges: Int = 0,
    val needsReviewEdges: Int = 0,
    val notes: List<String> = emptyList(),
    val attribution: String? = null,
    val attempts: List<String> = emptyList(),
    val exclusionReasons: Map<String, Int> = emptyMap(),
)

data class RequirementCounts(
    val required: Int = 0,
    val requiredMeters: Double = 0.0,
    val plannable: Int = 0,
    val needsReview: Int = 0,
    val needsReviewMeters: Double = 0.0,
    val unreachable: Int = 0,
    val unreachableMeters: Double = 0.0,
    val blocked: Int = 0,
    val ignoredSlivers: Int = 0,
)

data class StartInfo(
    val requested: LonLat? = null,
    val snappedEdgeId: String? = null,
    val snappedPosition: LonLat? = null,
    val distanceMeters: Double = 0.0,
    val bearingDeg: Double? = null,
    val roadName: String? = null,
    val inMainComponent: Boolean = true,
    val hasReverse: Boolean = false,
    val confirmed: Boolean = false,
    val error: String? = null,
    val source: String = "map",
)

data class LegInfo(val kind: String, val steps: Int, val meters: Double)

data class PlanInfo(
    val computing: Boolean = false,
    val policy: String = "STRICT_INSIDE",
    val status: String? = null,
    val valid: Boolean = false,
    val errors: List<String> = emptyList(),
    val totalMeters: Double = 0.0,
    val newCoverageMeters: Double = 0.0,
    val repeatMeters: Double = 0.0,
    val outsideMeters: Double = 0.0,
    val steps: Int = 0,
    val legs: List<LegInfo> = emptyList(),
    val unreachableByCause: Map<String, Int> = emptyMap(),
    val warnings: List<String> = emptyList(),
    val unverifiedTurnarounds: Int = 0,
    val iterations: Long = 0,
    val millis: Long = 0,
)

data class GpsInfo(
    val state: String = "acquiring", // acquiring|reliable|weak|stale|permission_denied|location_disabled|paused|no_provider
    val accuracy: Double? = null,
    val ageMs: Long? = null,
    val provider: String? = null,
    val simulated: Boolean = false,
)

data class ManeuverInfo(val type: String, val roadName: String?, val distanceMeters: Double, val turnaroundVerified: Boolean?)

data class DriveInfo(
    val sessionId: String? = null,
    val status: String = "none", // none|active|paused|ended
    val metrics: CoverageMetrics? = null,
    val gps: GpsInfo = GpsInfo(),
    val maneuver: ManeuverInfo? = null,
    val guidanceEvent: String? = null,
    val replanning: Boolean = false,
    val planStatus: String? = null,
    val planValid: Boolean = false,
    val planRevision: Long = 0,
    val noContinuation: Boolean = false,
    val lab: Boolean = false,
    val repeatMeters: Double = 0.0,
    val outsideMeters: Double = 0.0,
    val gaps: Int = 0,
    val traversals: Int = 0,
    val recoveredNote: String? = null,
    val pendingWrites: Int = 0,
    /** Turn-by-turn instruction (route, approach to the route, or head to a road). */
    val nav: NavInstruction? = null,
    val voiceOn: Boolean = true,
)

/** Always-on "where am I" (all screens), independent of coverage crediting. */
data class MyLocation(val lon: Double, val lat: Double, val accuracy: Double?, val heading: Double?, val atMs: Long)

data class OutsideApproval(val newOutsideMeters: Double, val approvedOutsideMeters: Double)

data class MapLayers(
    val zone: String = GeoJson.EMPTY,
    val coverage: String = GeoJson.EMPTY,
    val visited: String = GeoJson.EMPTY,
    val plan: String = GeoJson.EMPTY,
    val markers: String = GeoJson.EMPTY,
    val location: String = GeoJson.EMPTY,
    /** The next ~1.5 km of the path being guided (highlighted). */
    val ahead: String = GeoJson.EMPTY,
)

data class CameraCmd(
    val id: Long, val south: Double, val west: Double, val north: Double, val east: Double, val zoom: Double? = null,
    /** Driving camera: heading-up, tilted, vehicle placed in the lower part of the screen. */
    val bearing: Double? = null, val tilt: Double? = null, val follow: Boolean = false,
)

data class RecoveryOffer(val sessionId: String, val createdAt: Long, val updatedAt: Long, val lab: Boolean)

data class UiState(
    val screen: Screen = Screen.PREPARE,
    val tapMode: TapMode = TapMode.ZONE,
    val zone: ZoneInfo = ZoneInfo(),
    val graph: GraphInfo = GraphInfo(),
    val counts: RequirementCounts = RequirementCounts(),
    val start: StartInfo = StartInfo(),
    val plan: PlanInfo = PlanInfo(),
    /** Plan round trip back to the start (web "return to start"). */
    val returnToStart: Boolean = false,
    /** User ticked "approve outside travel" for the current preview plan. */
    val outsideApproved: Boolean = false,
    val outsideApproval: OutsideApproval? = null,
    val drive: DriveInfo = DriveInfo(),
    val layers: MapLayers = MapLayers(),
    val camera: CameraCmd? = null,
    val follow: Boolean = true,
    val recovery: RecoveryOffer? = null,
    val message: UiText? = null,
    val messageId: Long = 0,
    val startBlockers: List<Int> = emptyList(),
    val labMode: Boolean = false,
    val busy: Boolean = false,
    val myLocation: MyLocation? = null,
    /** idle | active | permission_denied | location_disabled */
    val myLocationState: String = "idle",
)
