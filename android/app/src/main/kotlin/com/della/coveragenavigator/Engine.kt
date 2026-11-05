// The single authoritative session/preparation state machine. All mutable state is
// confined to one engine thread; graph normalisation and planning run on
// Dispatchers.Default and come back through revision checks (decidePlan).
package com.della.coveragenavigator

import android.content.Context
import android.os.SystemClock
import della.core.ApplyContext
import della.core.BoundaryPolicy
import della.core.Campaign
import della.core.CampaignHome
import della.core.CampaignJson
import della.core.CampaignPlan
import della.core.CampaignSettings
import della.core.Interval
import della.core.campaignDayPlan
import della.core.confirmDay
import della.core.newlyDriven
import della.core.planCampaign
import della.core.undoLastDay
import della.core.Checkpoint
import della.core.ContractJson
import della.core.CoverageSession
import della.core.DerivedProgress
import della.core.EventType
import della.core.ExportOptions
import della.core.FailureInput
import della.core.GpsQuality
import della.core.GraphIndex
import della.core.GraphSnapshot
import della.core.GuidanceEvent
import della.core.GuidanceState
import della.core.LocationObservation
import della.core.LonLat
import della.core.MatchedPosition
import della.core.MatcherState
import della.core.Maneuver
import della.core.NormalizeOptions
import della.core.OverpassResponse
import della.core.PlanMeta
import della.core.PlanResultMeta
import della.core.PlannerInput
import della.core.PlanningContext
import della.core.RequirementsResult
import della.core.RoutePlan
import della.core.SessionBase
import della.core.SessionEvent
import della.core.SnappedStart
import della.core.StartSelection
import della.core.StartState
import della.core.StorageImage
import della.core.ValidationContext
import della.core.Zone
import della.core.ZoneDefinition
import della.core.activeExclusionIds
import della.core.applyEvents
import della.core.bboxOf
import della.core.bearingAtOffset
import della.core.buildExport
import della.core.buildManeuvers
import della.core.circleApproximation
import della.core.computeMetrics
import della.core.computeRequirements
import della.core.edgeName
import della.core.edgeScc
import della.core.edgeTraversable
import della.core.emptyProgress
import della.core.encodeExport
import della.core.expandBbox
import della.core.finalizeMatcher
import della.core.gapPayload
import della.core.initialGuidance
import della.core.initialMatcherState
import della.core.mainComponentPredicate
import della.core.mapFailure
import della.core.planCoverage
import della.core.processObservation
import della.core.recoverEventLog
import della.core.sha256Hex
import della.core.snapStart
import della.core.stepStartDistances
import della.core.updateGuidance
import della.core.validateImport
import della.core.validatePlan
import della.core.validateZone
import della.core.zoneRing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.math.max

const val MAX_GRAPH_EDGES = 60_000
private const val CAMPAIGN_KEY = "campaign"

fun isoNow(t: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))

/** Prepared (pre-session) graph + zone products. */
private class Prepared(
    val graph: GraphSnapshot,
    val gi: GraphIndex,
    val zone: ZoneDefinition,
    val req: RequirementsResult,
    val endpoint: String?,
)

/** Live session state. */
private class Active(
    val sessionId: String,
    val base: SessionBase,
    val graph: GraphSnapshot,
    val gi: GraphIndex,
    val req: RequirementsResult,
    val lab: Boolean,
    var seq: Long,
    val progress: DerivedProgress,
) {
    var matcher: MatcherState = initialMatcherState()
    var plan: RoutePlan? = null
    var planValid = false
    var maneuvers: List<Maneuver> = emptyList()
    var stepStart: DoubleArray = DoubleArray(0)
    var guidance: GuidanceState? = null
    var latestRequestedRev = 0L
    var acceptedRev = 0L
    var replanJob: Job? = null
    var needsReplanFromPosition = false
    var lastReplanAttempt = 0L
    var lastMatched: MatchedPosition? = null
    var lastQuality: String = GpsQuality.ACQUIRING
    var lastFix: LocationObservation? = null
    var lastFixElapsed = 0L
    var lastProvider: String? = null
    var obsCounter = 0L
    var noContinuation = false
    var lastGuidanceEvent: String? = null
    var lastMapUpdate = 0L
    val nav = Navigator()
    var lastHeading: Double? = null
    /** All computations use the hashed lengthMm. */
    val edgeLengths: Map<String, Double> = graph.directedEdges.associate { it.edgeId to it.lengthMm / 1000.0 }
    /** Events not yet durably written: retried in order, seq numbers are never reused or skipped. */
    val pendingWrites = ArrayList<SessionEvent>()
    var pendingEnded = false
    /** Outside travel the user approved (ALLOW_NECESSARY_CONNECTORS); replans exceeding it need approval. */
    var approvedOutsideMeters = 0.0
    var pendingOutsidePlan: RoutePlan? = null
    var pendingOutsideReason: String? = null
    /** Dedicated planner instance (thread) for this driving session only. */
    val planner = Executors.newSingleThreadExecutor { r -> Thread(r, "planner-$sessionId").apply { priority = Thread.NORM_PRIORITY - 1 } }.asCoroutineDispatcher()
}

class Engine(private val appContext: Context, private val store: Store) {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "coverage-engine") }.asCoroutineDispatcher()
    /** An unexpected exception must never kill the app mid-drive: report it and keep running. */
    private val crashGuard = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        android.util.Log.e("CoverageEngine", "engine task failed", e)
        _ui.update { it.copy(message = UiText(R.string.msg_internal_error, listOf(e.javaClass.simpleName)), messageId = it.messageId + 1) }
    }
    val scope = CoroutineScope(SupervisorJob() + dispatcher + crashGuard)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private var prepared: Prepared? = null
    private var previewPlan: RoutePlan? = null
    private var policy = BoundaryPolicy.STRICT_INSIDE
    private var returnToStart = false
    private var outsideApproved = false
    private var snapped: SnappedStart? = null
    private var fetchJob: Job? = null
    private var planJob: Job? = null
    private var active: Active? = null
    private var permissionDenied = false
    private var locationDisabled = false
    private var noProvider = false
    private var cameraSeq = 0L
    private var messageSeq = 0L
    private var lab: LabSimulator? = null

    init {
        scope.launch { checkRecovery() }
        scope.launch { loadCampaign() }
        scope.launch {
            while (isActive) {
                delay(1000)
                tick()
            }
        }
    }

    fun post(block: suspend Engine.() -> Unit) {
        scope.launch { block() }
    }

    private fun toast(id: Int, vararg args: Any) {
        _ui.update { it.copy(message = UiText(id, args.toList()), messageId = ++messageSeq) }
    }

    // ===================================================== preparation

    fun setScreen(s: Screen) = post {
        _ui.update { it.copy(screen = s) }
        if (s == Screen.CAMPAIGN) refreshCampaignReview()
    }
    fun setTapMode(m: TapMode) = post { _ui.update { it.copy(tapMode = m) } }
    fun setFollow(f: Boolean) = post { _ui.update { it.copy(follow = f) } }

    private var centeredOnMe = false
    private var myHeading: Double? = null

    /** Foreground "where am I" fix (all screens). Never credits coverage. */
    fun onPreviewFix(lon: Double, lat: Double, accuracy: Double?, speed: Double?, bearing: Double?) = post {
        if (speed != null && speed >= 2.5 && bearing != null) myHeading = bearing
        val me = MyLocation(lon, lat, accuracy, myHeading, SystemClock.elapsedRealtime())
        val driving = active?.progress?.status == "active"
        _ui.update { st ->
            var cam = st.camera
            if (!centeredOnMe && st.screen == Screen.PREPARE && st.zone.vertices.isEmpty() && st.zone.circleCenter == null) {
                centeredOnMe = true
                val d = 0.004
                cam = CameraCmd(++cameraSeq, lat - d, lon - d, lat + d, lon + d, 16.0)
            }
            st.copy(
                myLocation = me, myLocationState = "active", camera = cam,
                // while a session is actively driving, the drive publisher owns the marker
                layers = if (driving) st.layers else st.layers.copy(location = GeoJson.location(LonLat(lon, lat), accuracy, (accuracy ?: 99.0) <= 25, myHeading)),
            )
        }
    }

    fun onPreviewState(state: String) = post { _ui.update { it.copy(myLocationState = state) } }

    /** Center the map on the current position (and resume following while driving). */
    fun centerOnMe() = post {
        val me = _ui.value.myLocation ?: return@post
        val d = 0.0012
        val driving = active?.progress?.status == "active"
        _ui.update { it.copy(follow = true, camera = CameraCmd(++cameraSeq, me.lat - d, me.lon - d, me.lat + d, me.lon + d, if (driving) 17.5 else 17.0, bearing = if (driving) (me.heading ?: 0.0) else null, tilt = if (driving) 45.0 else null, follow = driving)) }
    }

    private val voice by lazy { Voice(appContext) }
    fun setVoice(on: Boolean) = post {
        _ui.update { it.copy(drive = it.drive.copy(voiceOn = on)) }
        if (!on) voice.stop()
    }
    fun setLabMode(on: Boolean) = post { if (active == null) _ui.update { it.copy(labMode = on && BuildConfig.DEBUG) } }

    fun focus(south: Double, west: Double, north: Double, east: Double) = post {
        _ui.update { it.copy(camera = CameraCmd(++cameraSeq, south, west, north, east)) }
    }

    fun setZoneMode(mode: ZoneMode) = post {
        if (active != null) return@post
        _ui.update { it.copy(zone = ZoneInfo(mode = mode, radiusMeters = it.zone.radiusMeters), tapMode = TapMode.ZONE) }
        invalidateGraph()
        refreshZone()
    }

    fun mapTap(p: LonLat) = post {
        if (active != null) return@post
        val s = _ui.value
        when (s.tapMode) {
            TapMode.ZONE -> {
                val z = s.zone
                if (z.mode == ZoneMode.POLYGON) _ui.update { it.copy(zone = z.copy(vertices = z.vertices + p)) }
                else _ui.update { it.copy(zone = z.copy(circleCenter = p)) }
                invalidateGraph()
                refreshZone()
            }
            TapMode.START -> setStartPoint(p, "map")
        }
    }

    fun moveVertex(index: Int, p: LonLat, final: Boolean) = post {
        if (active != null) return@post
        val z = _ui.value.zone
        if (index !in z.vertices.indices) return@post
        _ui.update { it.copy(zone = z.copy(vertices = z.vertices.toMutableList().also { l -> l[index] = p })) }
        if (final) invalidateGraph()
        refreshZone()
    }

    fun undoVertex() = post {
        val z = _ui.value.zone
        if (active != null || z.vertices.isEmpty()) return@post
        _ui.update { it.copy(zone = z.copy(vertices = z.vertices.dropLast(1))) }
        invalidateGraph()
        refreshZone()
    }

    fun clearZone() = post {
        if (active != null) return@post
        _ui.update { it.copy(zone = ZoneInfo(mode = it.zone.mode, radiusMeters = it.zone.radiusMeters)) }
        invalidateGraph()
        refreshZone()
    }

    fun setRadius(m: Double) = post {
        if (active != null) return@post
        _ui.update { it.copy(zone = it.zone.copy(radiusMeters = m)) }
        invalidateGraph()
        refreshZone()
    }

    private fun currentZoneDef(): ZoneDefinition? {
        val z = _ui.value.zone
        return when (z.mode) {
            ZoneMode.POLYGON -> if (z.vertices.size >= 3) ZoneDefinition.Polygon(z.vertices) else null
            ZoneMode.CIRCLE -> z.circleCenter?.let { c ->
                if (z.radiusMeters > 0) ZoneDefinition.Circle(c, z.radiusMeters, circleApproximation(c, z.radiusMeters)) else null
            }
        }
    }

    private fun refreshZone() {
        val z = _ui.value.zone
        val def = currentZoneDef()
        var zi = z.copy(valid = false, errors = emptyList(), areaSqMeters = 0.0, perimeterMeters = 0.0, vertexCount = z.vertices.size)
        val ring: List<LonLat>
        if (def != null) {
            val v = validateZone(def)
            zi = zi.copy(valid = v.ok, errors = v.errors.map { it.code } + v.warnings, areaSqMeters = v.areaSqMeters, perimeterMeters = v.perimeterMeters)
            ring = zoneRing(def)
        } else {
            ring = z.vertices
        }
        val vertices = if (z.mode == ZoneMode.POLYGON) z.vertices else listOfNotNull(z.circleCenter)
        val zoneGeo = GeoJson.zone(ring, vertices, def != null)
        _ui.update { it.copy(zone = zi, layers = it.layers.copy(zone = zoneGeo)) }
    }

    private fun invalidateGraph() {
        fetchJob?.cancel()
        planJob?.cancel()
        prepared = null
        previewPlan = null
        snapped = null
        policy = BoundaryPolicy.STRICT_INSIDE
        outsideApproved = false
        _ui.update {
            it.copy(
                graph = GraphInfo(), counts = RequirementCounts(), start = StartInfo(), plan = PlanInfo(), outsideApproved = false,
                layers = it.layers.copy(coverage = GeoJson.EMPTY, visited = GeoJson.EMPTY, plan = GeoJson.EMPTY, markers = GeoJson.EMPTY),
            )
        }
        refreshStartBlockers()
    }

    fun fetchGraph() = post {
        if (active != null) return@post
        val def = currentZoneDef()
        if (def == null || !validateZone(def).ok) {
            toast(R.string.msg_zone_invalid)
            return@post
        }
        invalidateGraph()
        val bbox = expandBbox(bboxOf(zoneRing(def)), Overpass.BBOX_EXPAND_M)
        _ui.update { it.copy(graph = GraphInfo(status = "fetching", dataState = "fetching", progress = "…")) }
        fetchJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    Overpass.fetch(bbox, { p -> _ui.update { s -> s.copy(graph = s.graph.copy(progress = p)) } }, { !isActive })
                }
                val built = withContext(Dispatchers.Default) {
                    val resp = OverpassResponse.parse(result.body)
                    val g = della.core.normalizeOverpass(resp, NormalizeOptions(fetchedAt = isoNow(), providerId = "osm-overpass", dataState = "available"))
                    if (g.directedEdges.isEmpty()) throw OverpassException(FailureInput.GraphEmpty, result.attempts, "empty")
                    if (g.directedEdges.size > MAX_GRAPH_EDGES) throw OverpassException(FailureInput.GraphTooLarge(g.directedEdges.size, MAX_GRAPH_EDGES), result.attempts, "too large")
                    val gi = GraphIndex(g)
                    val req = computeRequirements(gi, def)
                    Prepared(g, gi, def, req, Overpass.hostOf(result.endpoint))
                }
                withContext(Dispatchers.IO) { store.saveGraph(built.graph, built.endpoint) }
                prepared = built
                publishPrepared(result.attempts)
                val b = bboxOf(zoneRing(def))
                _ui.update { it.copy(camera = CameraCmd(++cameraSeq, b.south, b.west, b.north, b.east)) }
            } catch (e: OverpassException) {
                val o = mapFailure(e.failure)
                _ui.update { it.copy(graph = GraphInfo(status = "failed", dataState = o.dataState ?: "failed", failureCode = o.code, attempts = e.attempts)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                val o = mapFailure(FailureInput.GraphTooLarge(-1, MAX_GRAPH_EDGES))
                _ui.update { it.copy(graph = GraphInfo(status = "failed", dataState = o.dataState ?: "incomplete", failureCode = o.code)) }
            } catch (e: Exception) {
                _ui.update { it.copy(graph = GraphInfo(status = "failed", dataState = "failed", failureCode = "parse_error:${e.javaClass.simpleName}")) }
            }
        }
    }

    private fun publishPrepared(attempts: List<String>) {
        val p = prepared ?: return
        val g = p.graph
        val reasons = g.directedEdges.mapNotNull { it.eligibilityReason }.groupingBy { it }.eachCount()
        val gi = GraphInfo(
            status = "available", dataState = g.dataState, graphId = g.graphId, contentHash = g.contentHash, providerId = g.providerId,
            sourceVersion = g.sourceDatasetVersion, fetchedAt = g.fetchedAt, endpoint = p.endpoint, nodeCount = g.nodes.size,
            edgeCount = g.directedEdges.size, restrictionCount = g.turnRestrictions.size,
            eligibleEdges = g.directedEdges.count { it.eligibility == "eligible" },
            excludedEdges = g.directedEdges.count { it.eligibility == "excluded" },
            needsReviewEdges = g.directedEdges.count { it.eligibility == "needsReview" },
            notes = g.completenessNotes, attribution = g.attribution, attempts = attempts, exclusionReasons = reasons,
        )
        val statuses = p.req.requirements.associate { it.requirementId to it.status }
        _ui.update {
            it.copy(
                graph = gi, counts = countsFor(p, null), tapMode = TapMode.START,
                layers = it.layers.copy(coverage = GeoJson.coverage(p.gi, p.req.requirements, statuses)),
            )
        }
        refreshStartBlockers()
    }

    private fun countsFor(p: Prepared, plan: RoutePlan?): RequirementCounts {
        val reqs = p.req.requirements
        val nr = reqs.filter { it.status == "needsReview" }
        val unreachIds = plan?.unreachable?.filter { it.cause == "outside_policy" || it.cause == "not_reachable_from_start" }?.map { it.requirementId }?.toHashSet() ?: emptySet()
        val unr = reqs.filter { it.requirementId in unreachIds }
        return RequirementCounts(
            required = reqs.size, requiredMeters = reqs.sumOf { it.lengthMeters },
            plannable = reqs.size - nr.size, needsReview = nr.size, needsReviewMeters = nr.sumOf { it.lengthMeters },
            unreachable = unr.size, unreachableMeters = unr.sumOf { it.lengthMeters }, blocked = 0, ignoredSlivers = p.req.ignoredSlivers,
        )
    }

    private fun allowFor(p: Prepared, pol: String): (Int) -> Boolean = { i -> edgeTraversable(p.gi, p.req.zoneInfo, pol, emptySet(), i) }

    fun setStartPoint(pt: LonLat, source: String) = post { snapStartAt(pt, source, keepConfirmed = false) }

    private suspend fun snapStartAt(pt: LonLat, source: String, keepConfirmed: Boolean) {
        val p = prepared
        if (active != null) return
        if (p == null) {
            _ui.update { it.copy(start = StartInfo(requested = pt, error = "no_graph", source = source)) }
            return
        }
        val before = _ui.value.start
        previewPlan = null
        outsideApproved = false
        _ui.update { it.copy(outsideApproved = false) }
        val allow = allowFor(p, policy)
        val comp = withContext(Dispatchers.Default) { edgeScc(p.gi, allow) }
        val main = mainComponentPredicate(p.gi, comp)
        val r = snapStart(p.gi, pt, allow, 150.0, main)
        if (r == null) {
            snapped = null
            _ui.update {
                it.copy(start = StartInfo(requested = pt, error = "no_legal_road_nearby", source = source), plan = PlanInfo(policy = policy),
                    layers = it.layers.copy(markers = GeoJson.markers(pt, null, null), plan = GeoJson.EMPTY))
            }
        } else {
            setSnapped(p, pt, r.edgeId, r.offsetMeters, r.position, r.distanceMeters, main, source)
            // A policy change that keeps the same road keeps the user's confirmation.
            if (keepConfirmed && before.confirmed && before.snappedEdgeId == r.edgeId) _ui.update { it.copy(start = it.start.copy(confirmed = true)) }
        }
        refreshStartBlockers()
    }

    private fun setSnapped(p: Prepared, requested: LonLat, edgeId: String, offset: Double, pos: LonLat, dist: Double, main: (Int) -> Boolean, source: String) {
        val i = p.gi.idx[edgeId]!!
        val brg = bearingAtOffset(p.gi.edges[i].geometry, p.gi.cum[i], offset)
        val rec = p.gi.reciprocal[i]
        snapped = SnappedStart(edgeId, offset, pos, dist)
        _ui.update {
            it.copy(
                start = StartInfo(
                    requested = requested, snappedEdgeId = edgeId, snappedPosition = pos, distanceMeters = dist, bearingDeg = brg,
                    roadName = edgeName(p.gi, i), inMainComponent = main(i), hasReverse = rec >= 0 && allowFor(p, policy)(rec),
                    confirmed = false, source = source,
                ),
                plan = PlanInfo(policy = policy),
                layers = it.layers.copy(markers = GeoJson.markers(requested, pos, brg), plan = GeoJson.EMPTY),
            )
        }
    }

    /** Switch the snapped start to the opposite legal direction of the same road. */
    fun reverseStartDirection() = post {
        val p = prepared ?: return@post
        val s = snapped ?: return@post
        val i = p.gi.idx[s.edgeId] ?: return@post
        val rec = p.gi.reciprocal[i]
        if (rec < 0 || !allowFor(p, policy)(rec)) return@post
        val off = (p.gi.length(rec) - s.offsetMeters).coerceIn(0.0, p.gi.length(rec))
        val comp = withContext(Dispatchers.Default) { edgeScc(p.gi, allowFor(p, policy)) }
        previewPlan = null
        setSnapped(p, _ui.value.start.requested ?: s.position, p.gi.edges[rec].edgeId, off, s.position, s.distanceMeters, mainComponentPredicate(p.gi, comp), _ui.value.start.source)
        refreshStartBlockers()
    }

    fun confirmStart(confirmed: Boolean) = post {
        if (snapped == null) return@post
        _ui.update { it.copy(start = it.start.copy(confirmed = confirmed)) }
        refreshStartBlockers()
    }

    /** Boundary policy (segmented control). Outside travel needs an explicit approval before driving. */
    fun setPolicy(pol: String) = post {
        if (active != null || policy == pol) return@post
        policy = pol
        previewPlan = null
        outsideApproved = false
        _ui.update { it.copy(plan = PlanInfo(policy = policy), outsideApproved = false, layers = it.layers.copy(plan = GeoJson.EMPTY)) }
        resnapForPolicy()
    }

    fun setReturnToStart(on: Boolean) = post {
        if (active != null || returnToStart == on) return@post
        returnToStart = on
        previewPlan = null
        outsideApproved = false
        _ui.update { it.copy(returnToStart = on, plan = PlanInfo(policy = policy), outsideApproved = false, layers = it.layers.copy(plan = GeoJson.EMPTY)) }
        refreshStartBlockers()
    }

    fun setOutsideApproved(on: Boolean) = post {
        outsideApproved = on
        _ui.update { it.copy(outsideApproved = on) }
        refreshStartBlockers()
    }

    /** "Edit area": drop the loaded road network and go back to drawing (the zone itself is kept). */
    fun editArea() = post {
        if (active != null) return@post
        invalidateGraph()
        _ui.update { it.copy(tapMode = TapMode.ZONE) }
    }

    fun cancelPlan() = post {
        planJob?.cancel()
        _ui.update { it.copy(plan = it.plan.copy(computing = false)) }
        refreshStartBlockers()
    }

    fun recheckRecovery() = post { checkRecovery() }

    private suspend fun resnapForPolicy() {
        val r = _ui.value.start.requested ?: return
        val src = _ui.value.start.source
        snapStartAt(r, src, keepConfirmed = true)
    }

    private fun computePlan(p: Prepared, s: SnappedStart, pol: String, shouldCancel: (() -> Boolean)? = null): RoutePlan = planCoverage(
        PlannerInput(
            gi = p.gi, zoneInfo = p.req.zoneInfo, requirements = p.req.requirements, observed = emptyMap(), excluded = emptySet(),
            policy = pol, start = StartState(s.edgeId, s.offsetMeters, "selected_start"), returnToStart = returnToStart,
            timeBudgetMs = 20_000, shouldCancel = shouldCancel,
            meta = PlanMeta("preview-" + UUID.randomUUID().toString().take(8), p.graph.contentHash, 1, 0, 1, isoNow()),
        ),
    )

    fun previewPlan() = post {
        val p = prepared ?: run { toast(R.string.msg_need_graph); return@post }
        val s = snapped ?: run { toast(R.string.msg_need_start); return@post }
        planJob?.cancel()
        previewPlan = null
        outsideApproved = false
        _ui.update { it.copy(plan = it.plan.copy(computing = true, policy = policy), outsideApproved = false) }
        refreshStartBlockers()
        planJob = scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val plan = withContext(Dispatchers.Default) {
                val job = coroutineContext[Job]
                computePlan(p, s, policy) { job?.isActive == false }
            }
            acceptPreview(plan, SystemClock.elapsedRealtime() - t0)
        }
    }

    private fun acceptPreview(plan: RoutePlan, millis: Long) {
        val p = prepared ?: return
        val v = validatePlan(p.gi, plan, ValidationContext(p.req.zoneInfo, policy, p.req.requirements, emptyMap(), emptySet()))
        previewPlan = if (v.ok) plan else null
        _ui.update {
            it.copy(
                plan = planInfo(plan, v.ok, v.errors, millis),
                counts = countsFor(p, plan),
                layers = it.layers.copy(plan = GeoJson.plan(p.gi, plan)),
            )
        }
        refreshStartBlockers()
    }

    private fun planInfo(plan: RoutePlan, valid: Boolean, errors: List<String>, millis: Long) = PlanInfo(
        computing = false, policy = policy, status = plan.status, valid = valid, errors = errors.take(5),
        totalMeters = plan.totals.totalMeters, newCoverageMeters = plan.totals.newCoverageMeters, repeatMeters = plan.totals.repeatMeters,
        outsideMeters = plan.totals.outsideMeters, steps = plan.steps.size,
        legs = plan.legs.map { LegInfo(it.kind, it.lastStep - it.firstStep + 1, it.lengthMeters) },
        unreachableByCause = plan.unreachable.groupingBy { it.cause }.eachCount(), warnings = plan.warnings,
        unverifiedTurnarounds = plan.steps.count { it.turnaroundAtStart == "unverified" }, iterations = plan.limits.iterationsUsed, millis = millis,
    )

    private fun refreshStartBlockers() {
        val s = _ui.value
        val b = ArrayList<Int>()
        val g = prepared?.graph
        if (g == null) b.add(R.string.blocker_no_graph)
        else if (!s.labMode && (g.providerId != "osm-overpass" || g.dataState != "available")) b.add(R.string.blocker_not_real_graph)
        if (snapped == null) b.add(R.string.blocker_no_start)
        else if (!s.start.confirmed) b.add(R.string.blocker_start_unconfirmed)
        val pv = previewPlan
        if (s.plan.computing) b.add(R.string.blocker_planning)
        if (pv == null) b.add(R.string.blocker_no_plan)
        else {
            if (pv.steps.isEmpty() || pv.status == "failed" || pv.status == "no_requirements") b.add(R.string.blocker_plan_empty)
            if (pv.totals.outsideMeters > 2.0 && !outsideApproved) b.add(R.string.blocker_outside_unapproved)
        }
        _ui.update { it.copy(startBlockers = b) }
    }

    // ===================================================== session lifecycle

    /** Creates the session and its first events. Returns true when navigation may start (caller starts the service). */
    suspend fun startSession(): Boolean = withContext(dispatcher) {
        refreshStartBlockers()
        if (_ui.value.startBlockers.isNotEmpty() || active != null) return@withContext false
        val p = prepared!!
        val s = snapped!!
        val plan = previewPlan!!
        val labOn = _ui.value.labMode
        val sessionId = "s-" + UUID.randomUUID().toString()
        val zone = Zone("z-" + UUID.randomUUID().toString().take(8), 1, p.zone)
        val base = SessionBase(
            sessionId = sessionId, graphId = p.graph.graphId, contentHash = p.graph.contentHash, zone = zone,
            vehicleProfile = "passenger_car", boundaryPolicy = policy, returnToStart = returnToStart,
            startSelection = StartSelection(_ui.value.start.requested ?: s.position, s, true), createdAt = isoNow(),
        )
        val now = System.currentTimeMillis()
        val firstPlan = plan.copy(planId = "p-" + UUID.randomUUID().toString().take(8))
        val events = listOf(
            SessionEvent("$sessionId:1", 1, sessionId, EventType.SESSION_CREATED, now, della.core.SCHEMA_VERSION,
                ContractJson.obj("appVersion" to JsonPrimitive(BuildConfig.VERSION_NAME), "lab" to JsonPrimitive(labOn), "endpoint" to ContractJson.str(p.endpoint))),
            SessionEvent("$sessionId:2", 2, sessionId, EventType.START_CONFIRMED, now, della.core.SCHEMA_VERSION,
                ContractJson.obj("edgeId" to JsonPrimitive(s.edgeId), "offsetMeters" to ContractJson.num(s.offsetMeters))),
            SessionEvent("$sessionId:3", 3, sessionId, EventType.PLAN_ACCEPTED, now, della.core.SCHEMA_VERSION,
                ContractJson.obj("plan" to ContractJson.encodePlan(firstPlan), "reason" to JsonPrimitive("preview"))),
        )
        try {
            store.createSession(sessionId, p.graph.contentHash, encodeBase(base, labOn), events)
        } catch (e: Exception) {
            toast(R.string.msg_storage_error, e.javaClass.simpleName)
            return@withContext false
        }
        val progress = applyEvents(emptyProgress(), events, ApplyContext(sessionId, p.graph.contentHash, p.graph.directedEdges.associate { it.edgeId to it.lengthMm / 1000.0 }))
        val a = Active(sessionId, base, p.graph, p.gi, p.req, labOn, 3, progress)
        a.acceptedRev = firstPlan.planningRevision
        a.latestRequestedRev = firstPlan.planningRevision
        a.approvedOutsideMeters = firstPlan.totals.outsideMeters
        installPlan(a, firstPlan, true)
        retire(active)
        active = a
        lastStorageCheckpoint(a)
        _ui.update { it.copy(screen = Screen.DRIVE, follow = true) }
        publishDrive(a, forceMap = true)
        if (labOn) startLab(a)
        true
    }

    private fun encodeBase(b: SessionBase, lab: Boolean, imported: Boolean = false, day: CampaignTag? = null): String = ContractJson.stringify(
        ContractJson.obj(
            "sessionId" to JsonPrimitive(b.sessionId), "graphId" to JsonPrimitive(b.graphId), "contentHash" to JsonPrimitive(b.contentHash),
            "zone" to ContractJson.encodeZone(b.zone), "vehicleProfile" to JsonPrimitive(b.vehicleProfile), "boundaryPolicy" to JsonPrimitive(b.boundaryPolicy),
            "returnToStart" to JsonPrimitive(b.returnToStart),
            "startSelection" to (ContractJson.encodeSession(dummySession(b)) ["startSelection"]!!),
            "createdAt" to JsonPrimitive(b.createdAt), "lab" to JsonPrimitive(lab), "imported" to JsonPrimitive(imported),
        ).let { o ->
            if (day == null) o
            else JsonObject(o + mapOf("campaignId" to JsonPrimitive(day.campaignId), "campaignDay" to JsonPrimitive(day.day), "requirementIds" to ContractJson.strs(day.requirementIds)))
        },
    )

    /** Campaign-day marker stored with a session (web SessionHeader.campaignId). */
    private class CampaignTag(val campaignId: String, val day: Int, val requirementIds: List<String>)

    private fun campaignTag(json: String): CampaignTag? = try {
        val o = ContractJson.parse(json) as JsonObject
        val id = (o["campaignId"] as? JsonPrimitive)?.content
        if (id == null) null
        else CampaignTag(
            id, (o["campaignDay"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            (o["requirementIds"] as? kotlinx.serialization.json.JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList(),
        )
    } catch (e: Exception) {
        null
    }

    private fun dummySession(b: SessionBase) = CoverageSession(
        della.core.SCHEMA_VERSION, b.sessionId, b.graphId, b.contentHash, b.zone, b.vehicleProfile, b.boundaryPolicy, b.returnToStart,
        b.startSelection, b.createdAt, "ready", 0, 0, emptyList(), emptyList(), emptyList(), null, 0,
    )

    private fun isImported(json: String): Boolean = try {
        ((ContractJson.parse(json) as JsonObject)["imported"] as? JsonPrimitive)?.content == "true"
    } catch (e: Exception) {
        false
    }

    private fun decodeBase(json: String): Pair<SessionBase, Boolean> = with(ContractJson) {
        val o = parse(json).o()
        val sel = o.req("startSelection").o()
        val sn = sel.req("snapped").let { if (it is kotlinx.serialization.json.JsonNull) null else it.o().run { SnappedStart(s("edgeId"), d("offsetMeters"), ll("position"), d("distanceMeters")) } }
        SessionBase(
            o.s("sessionId"), o.s("graphId"), o.s("contentHash"), decodeZone(o.req("zone")), o.s("vehicleProfile"), o.s("boundaryPolicy"),
            o.b("returnToStart"), StartSelection(sel.ll("requested"), sn, sel.b("confirmed")), o.s("createdAt"),
        ) to ((o["lab"] as? JsonPrimitive)?.content == "true")
    }

    private fun installPlan(a: Active, plan: RoutePlan, valid: Boolean) {
        a.plan = plan
        a.planValid = valid && plan.steps.isNotEmpty()
        a.maneuvers = if (a.planValid) buildManeuvers(a.gi, plan) else emptyList()
        a.stepStart = stepStartDistances(plan)
        a.guidance = if (a.planValid) initialGuidance(plan) else null
        a.noContinuation = !a.planValid
        a.nav.reset()
    }

    /**
     * Persist then apply (never the other way round). A failed write keeps the events queued
     * (already numbered) and the next commit retries them first, in order, in one transaction,
     * so the log never skips a seq and progress is credited only once it is durable.
     */
    private fun commit(a: Active, events: List<SessionEvent>, ended: Boolean = false): Boolean {
        a.pendingWrites.addAll(events)
        a.pendingEnded = a.pendingEnded || ended
        if (a.pendingWrites.isEmpty()) return true
        val batch = a.pendingWrites.toList()
        return try {
            store.appendEvents(a.sessionId, batch, a.pendingEnded)
            a.pendingWrites.clear()
            val wasEnded = a.pendingEnded
            a.pendingEnded = false
            applyEvents(a.progress, batch, ApplyContext(a.sessionId, a.graph.contentHash, a.edgeLengths))
            if (batch.any { it.seq % 25L == 0L } || wasEnded) lastStorageCheckpoint(a)
            true
        } catch (e: Exception) {
            if (a.pendingWrites.size == batch.size) toast(R.string.msg_storage_error, e.javaClass.simpleName)
            false
        }
    }

    /** Stop the per-session planner and any pending work of a session that is no longer current. */
    private fun retire(a: Active?) {
        if (a == null) return
        a.replanJob?.cancel()
        try {
            a.planner.close()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun lastStorageCheckpoint(a: Active) {
        try {
            val last = store.lastEventRecord(a.sessionId) ?: return
            store.writeCheckpoint(Checkpoint(a.sessionId, a.seq - a.pendingWrites.size, sha256Hex(last)))
        } catch (e: Exception) {
            // Checkpoints are an integrity aid only; the event log stays authoritative.
        }
    }

    private fun ev(a: Active, type: String, payload: JsonObject, ts: Long = System.currentTimeMillis()): SessionEvent {
        a.seq++
        return SessionEvent("${a.sessionId}:${a.seq}", a.seq, a.sessionId, type, ts, della.core.SCHEMA_VERSION, payload)
    }

    fun pause() = post {
        val a = active ?: return@post
        if (a.progress.status != "active") return@post
        stopLab()
        val (st, trs) = finalizeMatcher(a.gi, a.matcher)
        a.matcher = st
        val evs = trs.map { ev(a, EventType.TRAVERSAL_CONFIRMED, ContractJson.obj("traversal" to ContractJson.encodeTraversal(it))) } +
            ev(a, EventType.PAUSED, JsonObject(emptyMap()))
        commit(a, evs)
        a.replanJob?.cancel()
        lastStorageCheckpoint(a)
        publishDrive(a, forceMap = true)
    }

    /** Caller (activity) must start the location service after this succeeds (non-lab). */
    suspend fun resume(): Boolean = withContext(dispatcher) {
        val a = active ?: return@withContext false
        if (a.progress.status == "active") return@withContext true
        if (a.progress.status == "ended") return@withContext false
        a.matcher = initialMatcherState() // never interpolate across a pause
        a.lastMatched = null
        a.lastFix = null
        a.lastQuality = GpsQuality.ACQUIRING
        a.needsReplanFromPosition = true
        // Never continue from the old cursor: guidance waits for a fresh plan from the real position.
        a.planValid = false
        a.guidance = null
        a.maneuvers = emptyList()
        a.noContinuation = false
        commit(a, listOf(ev(a, EventType.RESUMED, JsonObject(emptyMap()))))
        publishDrive(a, forceMap = true)
        if (a.lab) startLab(a)
        true
    }

    fun endSession() = post {
        val a = active ?: return@post
        stopLab()
        val (st, trs) = finalizeMatcher(a.gi, a.matcher)
        a.matcher = st
        val evs = (if (a.progress.status == "active") trs.map { ev(a, EventType.TRAVERSAL_CONFIRMED, ContractJson.obj("traversal" to ContractJson.encodeTraversal(it))) } else emptyList()) +
            ev(a, EventType.ENDED, JsonObject(emptyMap()))
        commit(a, evs, ended = true)
        a.replanJob?.cancel()
        a.pendingOutsidePlan = null
        publishDrive(a, forceMap = true)
        refreshCampaignReview()
    }

    /** Leave the ended session and return to preparation. */
    fun closeSession() = post {
        val a = active ?: return@post
        if (a.progress.status != "ended") return@post
        if (a.pendingWrites.isNotEmpty() && !commit(a, emptyList())) return@post
        retire(a)
        active = null
        _ui.update { st ->
            val me = st.myLocation
            st.copy(screen = Screen.PREPARE, drive = DriveInfo(), layers = st.layers.copy(visited = GeoJson.EMPTY, ahead = GeoJson.EMPTY,
                location = if (me != null) GeoJson.location(LonLat(me.lon, me.lat), me.accuracy, (me.accuracy ?: 99.0) <= 25, me.heading) else GeoJson.EMPTY))
        }
        publishPrepared(_ui.value.graph.attempts)
    }

    fun isLabSession(): Boolean = active?.lab == true

    /** Marks the requirement(s) under / just ahead of the current matched position blocked, then replans. */
    fun reportBlocked() = post {
        val a = active ?: return@post
        val m = a.lastMatched
        if (m == null || a.lastQuality != GpsQuality.RELIABLE) {
            toast(R.string.msg_block_needs_position)
            return@post
        }
        val ids = LinkedHashSet<String>()
        val covered = computeMetrics(a.req.requirements, a.progress).second
        fun addEdge(edgeId: String) {
            a.req.requirements.filter { it.edgeId == edgeId && covered[it.requirementId] != "covered" }.forEach { ids.add(it.requirementId) }
        }
        addEdge(m.edgeId)
        val plan = a.plan
        val g = a.guidance
        if (plan != null && g != null) {
            val cur = plan.steps.getOrNull(g.cursor)
            if (cur != null && cur.edgeId == m.edgeId && cur.toOffsetMeters - m.offset < 60) plan.steps.getOrNull(g.cursor + 1)?.let { addEdge(it.edgeId) }
        }
        if (ids.isEmpty()) {
            toast(R.string.msg_block_nothing)
            return@post
        }
        val ok = commit(
            a, listOf(
                ev(a, EventType.REQUIREMENT_EXCLUDED, ContractJson.obj(
                    "requirementIds" to ContractJson.strs(ids.toList()), "reason" to JsonPrimitive("blocked"),
                    "note" to JsonPrimitive("reported by driver near current position"),
                )),
            ),
        )
        if (ok) {
            toast(R.string.msg_blocked_n, ids.size)
            requestReplan(a, "blocked", 0)
        }
        publishDrive(a, forceMap = true)
    }

    // ===================================================== location input

    fun onLocationStatus(permissionDenied: Boolean, locationDisabled: Boolean, noProvider: Boolean) = post {
        this.permissionDenied = permissionDenied
        this.locationDisabled = locationDisabled
        this.noProvider = noProvider
        active?.let { publishDrive(it) }
    }

    /** A fix from the foreground service (or the LAB simulator in a lab session). */
    fun onFix(obsIn: LocationObservation, provider: String, elapsedMs: Long) = post {
        val a = active ?: return@post
        if (a.progress.status != "active") return@post // never credit while paused/ended
        if (a.lab != (obsIn.source == "simulated_test")) return@post // never mix simulated and live data
        a.obsCounter++
        val obs = obsIn.copy(observationId = "o${obsIn.timestamp}-${a.obsCounter}")
        a.lastFix = obs
        a.lastFixElapsed = elapsedMs
        a.lastProvider = provider
        val res = processObservation(a.gi, a.matcher, obs, obs.timestamp)
        a.matcher = res.state
        a.lastQuality = res.quality
        a.lastMatched = if (res.quality == GpsQuality.RELIABLE) res.matched else null
        val evs = ArrayList<SessionEvent>()
        evs.add(ev(a, EventType.OBSERVATION, ContractJson.obj("observation" to ContractJson.encodeObservation(obs), "quality" to JsonPrimitive(res.quality)), obs.timestamp))
        res.gap?.let { evs.add(ev(a, EventType.GAP_RECORDED, gapPayload(it.reason, it.fromT, it.toT), obs.timestamp)) }
        for (t in res.traversals) evs.add(ev(a, EventType.TRAVERSAL_CONFIRMED, ContractJson.obj("traversal" to ContractJson.encodeTraversal(t)), obs.timestamp))
        if (!commit(a, evs)) return@post
        // Guidance and deviation handling.
        val plan = a.plan
        val g = a.guidance
        if (plan != null && a.planValid && g != null) {
            val (ns, event) = updateGuidance(a.gi, plan, g, res.matched, res.quality == GpsQuality.RELIABLE)
            a.guidance = ns
            a.lastGuidanceEvent = event
            if (event == GuidanceEvent.DEVIATION) requestReplan(a, "deviation", 2500)
        }
        val matched = a.lastMatched
        if (matched != null && (a.needsReplanFromPosition || (a.noContinuation && SystemClock.elapsedRealtime() - a.lastReplanAttempt > 10_000))) {
            a.needsReplanFromPosition = false
            requestReplan(a, "position", 0)
        }
        publishDrive(a, forceMap = res.traversals.isNotEmpty() && SystemClock.elapsedRealtime() - a.lastMapUpdate > 2000)
    }

    // ===================================================== replanning

    private fun requestReplan(a: Active, reason: String, debounceMs: Long) {
        a.replanJob?.cancel()
        a.lastReplanAttempt = SystemClock.elapsedRealtime()
        a.latestRequestedRev = max(a.latestRequestedRev, a.acceptedRev) + 1
        val rev = a.latestRequestedRev
        _ui.update { it.copy(drive = it.drive.copy(replanning = true)) }
        a.replanJob = scope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val m = a.lastMatched
            if (m == null) {
                a.needsReplanFromPosition = true
                _ui.update { it.copy(drive = it.drive.copy(replanning = false)) }
                return@launch
            }
            val mi = a.gi.idx[m.edgeId] ?: return@launch
            val observed = LinkedHashMap(a.progress.union)
            val excluded = activeExclusionIds(a.progress)
            val progressRev = a.progress.progressRevision
            val meta = PlanMeta("p-" + UUID.randomUUID().toString().take(8), a.graph.contentHash, a.base.zone.zoneVersion, progressRev, rev, isoNow())
            val start = StartState(m.edgeId, m.offset, "gps_position")
            val plan = withContext(a.planner) {
                val job = coroutineContext[Job]
                planCoverage(
                    PlannerInput(
                        gi = a.gi, zoneInfo = a.req.zoneInfo, requirements = a.req.requirements, observed = observed, excluded = excluded,
                        policy = a.base.boundaryPolicy, start = start, startPrefix = intArrayOf(mi), returnToStart = a.base.returnToStart,
                        timeBudgetMs = 20_000, shouldCancel = { job?.isActive == false }, meta = meta,
                    ),
                )
            }
            // Back on the engine thread: revision-safe acceptance, only for the session that asked.
            if (active !== a || a.progress.status != "active") return@launch
            val decision = della.core.decidePlan(
                PlanningContext(a.graph.contentHash, a.base.zone.zoneVersion, a.latestRequestedRev, a.acceptedRev, a.progress.progressRevision),
                PlanResultMeta(plan.graphHash, plan.zoneVersion, plan.planningRevision, plan.basedOnProgressRevision, plan.status),
            )
            if (!decision.accept) {
                if (decision.reason == "failed") {
                    a.noContinuation = true
                    a.planValid = false
                    a.guidance = null
                }
                _ui.update { it.copy(drive = it.drive.copy(replanning = false)) }
                publishDrive(a)
                return@launch
            }
            val v = validatePlan(a.gi, plan, ValidationContext(a.req.zoneInfo, a.base.boundaryPolicy, a.req.requirements, observed, excluded, intArrayOf(mi)))
            if (!v.ok) {
                a.noContinuation = true
                a.planValid = false
                a.guidance = null
                _ui.update { it.copy(drive = it.drive.copy(replanning = false)) }
                publishDrive(a)
                return@launch
            }
            if (a.base.boundaryPolicy == BoundaryPolicy.ALLOW_NECESSARY_CONNECTORS && plan.totals.outsideMeters > a.approvedOutsideMeters + 10.0) {
                // More outside travel than the user approved: stop guidance and ask first.
                a.pendingOutsidePlan = plan
                a.pendingOutsideReason = reason
                a.planValid = false
                a.guidance = null
                a.maneuvers = emptyList()
                _ui.update { it.copy(drive = it.drive.copy(replanning = false), outsideApproval = OutsideApproval(plan.totals.outsideMeters, a.approvedOutsideMeters)) }
                publishDrive(a, forceMap = true)
                return@launch
            }
            acceptReplan(a, plan, reason, decision.reconcile == true, approvedOutside = null)
            _ui.update { it.copy(drive = it.drive.copy(replanning = false)) }
            publishDrive(a, forceMap = true)
        }
    }

    private fun acceptReplan(a: Active, plan: RoutePlan, reason: String, reconciled: Boolean, approvedOutside: Double?) {
        val payload = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "plan" to ContractJson.encodePlan(plan), "reason" to JsonPrimitive(reason), "reconciled" to JsonPrimitive(reconciled),
        )
        if (approvedOutside != null) payload["approvedOutsideMeters"] = ContractJson.num(approvedOutside)
        if (commit(a, listOf(ev(a, EventType.PLAN_ACCEPTED, JsonObject(payload))))) {
            a.acceptedRev = plan.planningRevision
            if (approvedOutside != null) a.approvedOutsideMeters = approvedOutside
            installPlan(a, plan, true)
        }
    }

    /** User decision on a replan that needs more outside travel than previously approved. */
    fun answerOutsideApproval(approve: Boolean) = post {
        val a = active ?: return@post
        val plan = a.pendingOutsidePlan
        a.pendingOutsidePlan = null
        _ui.update { it.copy(outsideApproval = null) }
        if (plan == null || a.progress.status != "active") return@post
        val decision = della.core.decidePlan(
            PlanningContext(a.graph.contentHash, a.base.zone.zoneVersion, a.latestRequestedRev, a.acceptedRev, a.progress.progressRevision),
            PlanResultMeta(plan.graphHash, plan.zoneVersion, plan.planningRevision, plan.basedOnProgressRevision, plan.status),
        )
        if (approve && decision.accept) {
            acceptReplan(a, plan, a.pendingOutsideReason ?: "deviation", decision.reconcile == true, approvedOutside = plan.totals.outsideMeters)
        } else {
            a.noContinuation = true
            if (approve) requestReplan(a, "position", 0) // superseded while asking: compute a fresh one
        }
        publishDrive(a, forceMap = true)
    }

    // ===================================================== publishing

    private fun gpsInfo(a: Active?): GpsInfo {
        val now = SystemClock.elapsedRealtime()
        if (a == null) return GpsInfo(state = if (permissionDenied) "permission_denied" else "acquiring")
        val lab = a.lab
        if (a.progress.status == "paused" || a.progress.status == "ended") return GpsInfo(state = "paused", simulated = lab)
        if (!lab && permissionDenied) return GpsInfo(state = "permission_denied")
        if (!lab && noProvider) return GpsInfo(state = "no_provider")
        if (!lab && locationDisabled) return GpsInfo(state = "location_disabled")
        val f = a.lastFix ?: return GpsInfo(state = "acquiring", simulated = lab)
        val age = now - a.lastFixElapsed // lastFixElapsed is the fix's own elapsed-realtime
        val state = when {
            age > 10_000 -> "stale"
            a.lastQuality == GpsQuality.RELIABLE -> "reliable"
            else -> "weak"
        }
        return GpsInfo(state, f.horizontalAccuracyMeters, age, a.lastProvider, lab)
    }

    private fun maneuverInfo(a: Active, gps: GpsInfo): ManeuverInfo? {
        val plan = a.plan ?: return null
        val g = a.guidance ?: return null
        val m = a.lastMatched ?: return null
        if (!a.planValid || gps.state != "reliable" || a.progress.status != "active") return null
        if ((gps.ageMs ?: Long.MAX_VALUE) > 5_000) return null // fix older than 5 s
        if (a.noContinuation || a.replanJob?.isActive == true || a.pendingOutsidePlan != null) return null
        if (a.lastGuidanceEvent == GuidanceEvent.DEVIATION || a.lastGuidanceEvent == GuidanceEvent.OFF_ROUTE_PENDING) return null
        val cur = plan.steps.getOrNull(g.cursor) ?: return null
        if (cur.edgeId != m.edgeId) return null
        val here = a.stepStart[g.cursor] + (m.offset - cur.fromOffsetMeters).coerceAtLeast(0.0)
        val next = a.maneuvers.firstOrNull { it.stepIndex > g.cursor } ?: return null
        return ManeuverInfo(next.type, next.roadName, (next.distanceFromStartMeters - here).coerceAtLeast(0.0), next.turnaroundVerified)
    }

    private fun publishDrive(a: Active, forceMap: Boolean = false) {
        val (metrics, statuses) = computeMetrics(a.req.requirements, a.progress)
        val gps = gpsInfo(a)
        val f = a.lastFix
        val me = _ui.value.myLocation
        val loc = when {
            f != null && a.progress.status == "active" -> GeoJson.location(LonLat(f.lon, f.lat), f.horizontalAccuracyMeters, gps.state == "reliable", a.lastHeading)
            me != null -> GeoJson.location(LonLat(me.lon, me.lat), me.accuracy, (me.accuracy ?: 99.0) <= 25, me.heading)
            else -> GeoJson.EMPTY
        }
        val plan = a.plan
        var layers = _ui.value.layers.copy(location = loc)
        if (forceMap) {
            a.lastMapUpdate = SystemClock.elapsedRealtime()
            layers = layers.copy(
                coverage = GeoJson.coverage(a.gi, a.req.requirements, statuses),
                visited = GeoJson.visited(a.gi, a.progress.union),
                plan = if (a.planValid) GeoJson.plan(a.gi, plan, a.guidance?.cursor ?: 0) else GeoJson.EMPTY,
            )
        }
        val now = SystemClock.elapsedRealtime()
        val nav = if (a.progress.status == "active" && a.planValid && a.pendingOutsidePlan == null && !permissionDenied) {
            a.nav.compute(a.gi, plan, a.maneuvers, a.stepStart, f, if (f != null) now - a.lastFixElapsed else Long.MAX_VALUE, System.currentTimeMillis())
        } else null
        layers = layers.copy(ahead = if (nav != null) GeoJson.lines(nav.ahead) else GeoJson.EMPTY)
        if (f != null && (f.speedMps ?: 0.0) >= 2.5 && f.bearingDeg != null) a.lastHeading = f.bearingDeg
        voice.announce(nav, _ui.value.drive.voiceOn && a.progress.status == "active")
        val drive = DriveInfo(
            sessionId = a.sessionId, status = a.progress.status, metrics = metrics, gps = gps, maneuver = maneuverInfo(a, gps),
            guidanceEvent = a.lastGuidanceEvent, replanning = a.replanJob?.isActive == true, planStatus = plan?.status, planValid = a.planValid,
            planRevision = a.acceptedRev, noContinuation = a.noContinuation, lab = a.lab, repeatMeters = plan?.totals?.repeatMeters ?: 0.0,
            outsideMeters = plan?.totals?.outsideMeters ?: 0.0, gaps = a.progress.gaps.size, traversals = a.progress.traversals.size,
            recoveredNote = _ui.value.drive.recoveredNote,
            pendingWrites = a.pendingWrites.size,
            nav = nav,
            voiceOn = _ui.value.drive.voiceOn,
        )
        var cam: CameraCmd? = _ui.value.camera
        if (_ui.value.follow && f != null && a.progress.status == "active" && _ui.value.screen == Screen.DRIVE) {
            val d = 0.0012
            cam = CameraCmd(++cameraSeq, f.lat - d, f.lon - d, f.lat + d, f.lon + d, 17.5, bearing = a.lastHeading ?: 0.0, tilt = 45.0, follow = true)
        }
        _ui.update { it.copy(drive = drive, layers = layers, camera = cam) }
        LocationService.updateNotification(appContext, metrics.percentConfirmed, a.progress.status)
    }

    private fun tick() {
        val a = active ?: return
        if (a.pendingWrites.isNotEmpty() && SystemClock.elapsedRealtime() % 5000 < 1000) {
            if (commit(a, emptyList())) publishDrive(a, forceMap = true)
        }
        if (a.progress.status == "active") {
            val gps = gpsInfo(a)
            val stale = (gps.ageMs ?: Long.MAX_VALUE) > 8_000
            _ui.update { it.copy(drive = it.drive.copy(gps = gps, maneuver = maneuverInfo(a, gps), nav = if (stale) null else it.drive.nav)) }
        }
    }

    // ===================================================== recovery

    private suspend fun checkRecovery() {
        if (active != null) return
        val s = withContext(Dispatchers.IO) { store.lastOpenSession() } ?: return
        val lab = try {
            decodeBase(s.baseJson).second
        } catch (e: Exception) {
            false
        }
        _ui.update { it.copy(recovery = RecoveryOffer(s.sessionId, s.createdAt, s.updatedAt, lab)) }
    }

    fun dismissRecovery() = post { _ui.update { it.copy(recovery = null) } }

    fun endRecovered() = post {
        val r = _ui.value.recovery ?: return@post
        withContext(Dispatchers.IO) { store.markEnded(r.sessionId) }
        _ui.update { it.copy(recovery = null) }
    }

    /** Rebuild the session from the event log; graph hash must verify. Session comes back paused. */
    fun resumeRecovered() = post {
        val r = _ui.value.recovery ?: return@post
        _ui.update { it.copy(recovery = null, busy = true) }
        val ok = loadSession(r.sessionId)
        _ui.update { it.copy(busy = false) }
        if (!ok) return@post
    }

    private suspend fun loadSession(sessionId: String): Boolean {
        val stored = withContext(Dispatchers.IO) { store.session(sessionId) } ?: return false
        val (base, lab) = try {
            decodeBase(stored.baseJson)
        } catch (e: Exception) {
            toast(R.string.msg_recovery_failed, "base")
            return false
        }
        val graph = withContext(Dispatchers.IO) { store.loadGraph(stored.contentHash) }
        if (graph == null || !withContext(Dispatchers.Default) { della.core.verifyGraphHash(graph) } || graph.contentHash != stored.contentHash) {
            toast(R.string.msg_recovery_graph_mismatch)
            return false
        }
        val records = withContext(Dispatchers.IO) { store.eventRecords(sessionId) }
        val cp = withContext(Dispatchers.IO) { store.checkpoint(sessionId) }
        // Native logs are contiguous (contract recovery rule). Imported logs may have seq gaps
        // (observations redacted by the exporter), so they only require strictly increasing seq.
        val rec = if (isImported(stored.baseJson)) recoverIncreasing(sessionId, records) else recoverEventLog(sessionId, StorageImage(cp, records))
        if (rec.discarded.isNotEmpty()) withContext(Dispatchers.IO) { store.truncateAfter(sessionId, rec.lastValidSeq) }
        val tag = campaignTag(stored.baseJson)
        val built = withContext(Dispatchers.Default) {
            val gi = GraphIndex(graph)
            val full = computeRequirements(gi, base.zone.definition)
            Triple(gi, if (tag != null) dayRequirements(full, tag.requirementIds.toHashSet()) else full, full)
        }
        val progress = applyEvents(emptyProgress(), rec.events, ApplyContext(sessionId, graph.contentHash, graph.directedEdges.associate { it.edgeId to it.lengthMm / 1000.0 }))
        val a = Active(sessionId, base, graph, built.first, built.second, lab, rec.lastValidSeq, progress)
        progress.plan?.let {
            a.acceptedRev = it.planningRevision
            a.latestRequestedRev = it.planningRevision
        }
        retire(active)
        active = a
        prepared = Prepared(graph, built.first, base.zone.definition, built.third, store.graphEndpoint(graph.contentHash))
        // Found 'active' after a process death / reboot: record it as paused (interrupted).
        // It resumes only by an explicit user action, with a fresh plan from the real position.
        if (progress.status == "active") commit(a, listOf(ev(a, EventType.PAUSED, ContractJson.obj("reason" to JsonPrimitive("interrupted")))))
        a.approvedOutsideMeters = progress.plan?.totals?.outsideMeters ?: 0.0
        // The old plan is not trusted for guidance after a restart: replan from the actual position on resume.
        a.plan = progress.plan
        a.planValid = false
        a.noContinuation = false
        a.needsReplanFromPosition = true
        val note = "events=${rec.events.size}, discarded=${rec.discarded.size}" + (cp?.let { ", checkpoint=${it.seq}" } ?: "")
        _ui.update { it.copy(screen = Screen.DRIVE, drive = it.drive.copy(recoveredNote = note), labMode = lab) }
        val b = bboxOf(zoneRing(base.zone.definition))
        _ui.update { it.copy(camera = CameraCmd(++cameraSeq, b.south, b.west, b.north, b.east)) }
        val ring = zoneRing(base.zone.definition)
        _ui.update { it.copy(layers = it.layers.copy(zone = GeoJson.zone(ring, emptyList(), true), markers = GeoJson.EMPTY)) }
        publishDrive(a, forceMap = true)
        return true
    }

    // ===================================================== export / import / data

    /** Builds the contract export package text on the engine thread. */
    suspend fun exportText(includeObservations: Boolean, includeGraph: Boolean): String? = withContext(dispatcher) {
        val a = active ?: return@withContext null
        val events = rec(a)
        val doc = della.core.sessionDocument(a.base, a.req.requirements, a.progress)
        val pkg = buildExport(a.graph, doc, events, ExportOptions("android", BuildConfig.VERSION_NAME, includeGraph, includeObservations, isoNow()))
        ContractJson.prettyString(encodeExport(pkg))
    }

    private fun rec(a: Active): List<SessionEvent> {
        val stored = store.session(a.sessionId)
        val records = store.eventRecords(a.sessionId)
        return if (stored != null && isImported(stored.baseJson)) recoverIncreasing(a.sessionId, records).events
        else recoverEventLog(a.sessionId, StorageImage(null, records)).events
    }

    private fun recoverIncreasing(sessionId: String, records: List<String>): della.core.RecoveredLog {
        val events = ArrayList<SessionEvent>()
        val discarded = ArrayList<della.core.DiscardedRecord>()
        var last = 0L
        var broken = false
        records.forEachIndexed { i, raw ->
            if (broken) {
                discarded.add(della.core.DiscardedRecord(i, "after_corruption"))
                return@forEachIndexed
            }
            val e = try {
                ContractJson.decodeEvent(ContractJson.parse(raw))
            } catch (x: Exception) {
                null
            }
            if (e == null || e.sessionId != sessionId || e.seq <= last) {
                discarded.add(della.core.DiscardedRecord(i, if (e == null) "corrupt_record" else "sequence_gap"))
                broken = true
                return@forEachIndexed
            }
            events.add(e)
            last = e.seq
        }
        return della.core.RecoveredLog(events, discarded, last)
    }

    /** Validates and stores an imported package; the source file is never modified. */
    suspend fun importText(text: String): List<String> = withContext(dispatcher) {
        if (active != null) return@withContext listOf("session_active")
        val r = withContext(Dispatchers.Default) { validateImport(text) { h -> store.loadGraph(h)?.takeIf { g -> della.core.verifyGraphHash(g) } } }
        if (!r.ok) return@withContext r.errors
        val pkg = r.pkg!!
        val g = r.graph!!
        if (store.session(pkg.session.sessionId) != null) return@withContext listOf("session_already_exists:${pkg.session.sessionId}")
        try {
            store.saveGraph(g, null)
            val s = pkg.session
            val base = SessionBase(s.sessionId, s.graphId, s.contentHash, s.zone, s.vehicleProfile, s.boundaryPolicy, s.returnToStart, s.startSelection, s.createdAt)
            store.createSession(s.sessionId, s.contentHash, encodeBase(base, false, imported = true), pkg.events)
            if (s.status == "ended") store.markEnded(s.sessionId)
        } catch (e: Exception) {
            return@withContext listOf("storage_error:${e.javaClass.simpleName}")
        }
        if (pkg.session.status != "ended") loadSession(pkg.session.sessionId)
        emptyList()
    }

    fun deleteAllData() = post {
        if (active?.progress?.status == "active") {
            toast(R.string.msg_delete_while_active)
            return@post
        }
        retire(active)
        active = null
        withContext(Dispatchers.IO) { store.deleteAll() }
        campaignJob?.cancel()
        campaign = null
        campaignCtx = null
        campaignPlan = null
        campaignReview = null
        invalidateGraph()
        _ui.update { UiState(zone = it.zone, layers = MapLayers(zone = it.layers.zone)) }
        toast(R.string.msg_deleted)
    }

    fun diagnosticsSummary(): String {
        val a = active
        val p = prepared
        return buildString {
            append("app ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("abi ").append(android.os.Build.SUPPORTED_ABIS.joinToString(",")).append(", sdk ").append(android.os.Build.VERSION.SDK_INT).append('\n')
            append("sessions stored ").append(store.sessionCount()).append('\n')
            p?.let { append("graph ").append(it.graph.graphId).append(" edges ").append(it.graph.directedEdges.size).append('\n') }
            a?.let { append("session ").append(it.sessionId).append(" seq ").append(it.seq).append(" status ").append(it.progress.status).append('\n') }
        }
    }


    // ===================================================== monthly plan (multi-day campaign)
    // Port of the campaign part of web/src/ui/App.tsx + CampaignPage.tsx. One active campaign
    // is persisted in the settings table; its day sessions carry the campaign id in base_json.

    private class CampaignCtx(val graph: GraphSnapshot, val gi: GraphIndex, val req: RequirementsResult)
    private class PendingReview(val sessionId: String, val day: Int?, val driven: Map<String, List<Interval>>)

    private var campaign: Campaign? = null
    private var campaignCtx: CampaignCtx? = null
    private var campaignPlan: CampaignPlan? = null
    private var campaignReview: PendingReview? = null
    private var campaignError: String? = null
    private var campaignJob: Job? = null

    private fun dayRequirements(full: RequirementsResult, ids: Set<String>) =
        RequirementsResult(full.zoneInfo, full.requirements.filter { it.requirementId in ids }, full.ignoredSlivers, full.ignoredSliverMeters)

    private suspend fun loadCampaign() {
        val text = try {
            withContext(Dispatchers.IO) { store.getSetting(CAMPAIGN_KEY) }
        } catch (e: Exception) {
            null
        }
        if (text == null) {
            publishCampaign()
            return
        }
        val c = try {
            CampaignJson.decode(ContractJson.parse(text))
        } catch (e: Exception) {
            null
        }
        if (c == null) {
            campaignError = "corrupt"
            publishCampaign()
            return
        }
        campaign = c
        refreshCampaign()
    }

    /** (Re)load the campaign graph if needed, re-plan the remaining days and the review. */
    private fun refreshCampaign() {
        val c = campaign
        campaignJob?.cancel()
        if (c == null) {
            publishCampaign()
            return
        }
        campaignError = null
        _ui.update { it.copy(campaign = it.campaign.copy(exists = true, loading = true, error = null)) }
        campaignJob = scope.launch {
            var ctx = campaignCtx
            if (ctx == null || ctx.graph.contentHash != c.contentHash) {
                val graph = withContext(Dispatchers.IO) { store.loadGraph(c.contentHash) }
                if (graph == null || graph.contentHash != c.contentHash || !withContext(Dispatchers.Default) { della.core.verifyGraphHash(graph) }) {
                    campaignError = "graph_missing"
                    publishCampaign()
                    return@launch
                }
                ctx = withContext(Dispatchers.Default) {
                    val gi = GraphIndex(graph)
                    CampaignCtx(graph, gi, computeRequirements(gi, c.zone))
                }
                campaignCtx = ctx
            }
            val cx = ctx
            val plan = withContext(Dispatchers.Default) {
                val job = coroutineContext[Job]
                planCampaign(cx.gi, cx.req.zoneInfo, cx.req.requirements, c, "c-" + UUID.randomUUID().toString().take(8), isoNow(), shouldCancel = { job?.isActive == false })
            }
            if (campaign !== c) return@launch
            campaignPlan = plan
            loadReview(c, cx)
            publishCampaign()
        }
    }

    /** End-of-day review: the newest campaign session not yet confirmed (web: campaign review effect). */
    private fun refreshCampaignReview() {
        val c = campaign ?: return
        val ctx = campaignCtx ?: return
        if (campaignJob?.isActive == true) return // a running refresh reloads the review at its end
        scope.launch {
            loadReview(c, ctx)
            if (campaign === c) publishCampaign()
        }
    }

    private suspend fun loadReview(c: Campaign, ctx: CampaignCtx) {
        val sessions = try {
            withContext(Dispatchers.IO) { store.allSessions() }
        } catch (e: Exception) {
            emptyList()
        }
        val done = c.history.map { it.sessionId }.toHashSet()
        var pending: StoredSession? = null
        var tag: CampaignTag? = null
        for (s in sessions) {
            val t = campaignTag(s.baseJson) ?: continue
            if (t.campaignId == c.campaignId && s.sessionId !in done) {
                pending = s
                tag = t
                break
            }
        }
        if (pending == null) {
            campaignReview = null
            return
        }
        val records = withContext(Dispatchers.IO) { store.eventRecords(pending.sessionId) }
        val events = recoverEventLog(pending.sessionId, StorageImage(null, records)).events
        val prog = applyEvents(emptyProgress(), events, ApplyContext(pending.sessionId, ctx.graph.contentHash, ctx.graph.directedEdges.associate { it.edgeId to it.lengthMm / 1000.0 }))
        campaignReview = if (prog.traversals.isNotEmpty()) PendingReview(pending.sessionId, tag?.day, LinkedHashMap(prog.union)) else null
    }

    private fun publishCampaign() {
        val c = campaign
        if (c == null) {
            _ui.update { it.copy(campaign = CampaignUi(error = campaignError)) }
            return
        }
        val ctx = campaignCtx
        val plan = campaignPlan
        val base = CampaignUi(
            exists = true, loading = campaignJob?.isActive == true && campaignError == null, error = campaignError, name = c.name,
            hoursPerDay = c.settings.hoursPerDay, speedKmh = c.settings.speedKmh,
            history = c.history.map { CampaignHistoryUi(it.date, it.confirmedMeters) },
        )
        if (ctx == null || plan == null) {
            _ui.update { it.copy(campaign = base.copy(loading = campaignError == null)) }
            return
        }
        val conf = c.confirmed.toMap()
        var total = 0.0
        var done = 0.0
        for (r in ctx.req.requirements) {
            if (r.status == "needsReview") continue
            total += r.lengthMeters
            for (iv in conf[r.edgeId] ?: emptyList()) done += max(0.0, minOf(iv.b, r.endOffsetMeters) - maxOf(iv.a, r.startOffsetMeters))
        }
        done = minOf(done, total)
        val review = campaignReview?.let { rv ->
            val reqs = newlyDriven(ctx.req.requirements, conf, rv.driven)
            val groups = LinkedHashMap<String, MutableList<della.core.RequiredInterval>>()
            for (r in reqs) {
                val i = ctx.gi.idx[r.edgeId]
                val n = if (i == null) r.edgeId else ctx.gi.edges[i].names["default"] ?: ctx.gi.edges[i].names["en"] ?: "—"
                groups.getOrPut(n) { ArrayList() }.add(r)
            }
            CampaignReviewUi(
                rv.sessionId, rv.day,
                groups.entries.sortedByDescending { it.value.size }.map { (n, rs) -> StreetGroupUi(n, rs.map { it.requirementId }, rs.sumOf { it.lengthMeters }) },
            )
        }
        _ui.update {
            it.copy(
                campaign = base.copy(
                    loading = false, percentDone = if (total > 0) 100 * done / total else 0.0, doneMeters = done, remainingMeters = plan.remainingMeters,
                    unplannable = plan.unplannable.size,
                    days = plan.days.map { d -> CampaignDayUi(c.history.size + d.day, d.totalMeters, d.minutes, d.targetIds.size) },
                    review = review,
                ),
            )
        }
    }

    private suspend fun saveCampaign(c: Campaign?): Boolean {
        try {
            withContext(Dispatchers.IO) { store.setSetting(CAMPAIGN_KEY, c?.let { ContractJson.stringify(CampaignJson.encode(it)) }) }
        } catch (e: Exception) {
            toast(R.string.msg_storage_error, e.javaClass.simpleName)
            return false
        }
        campaign = c
        campaignPlan = null
        campaignReview = null
        refreshCampaign()
        return true
    }

    /** Create the campaign from the prepared area and the confirmed start point (= fixed home). */
    fun createCampaign(hoursPerDay: Double, speedKmh: Double, name: String) = post {
        val p = prepared ?: return@post
        val s = snapped ?: return@post
        if (!_ui.value.start.confirmed || p.graph.dataState != "available" || hoursPerDay <= 0 || speedKmh <= 0) return@post
        try {
            withContext(Dispatchers.IO) { store.saveGraph(p.graph, p.endpoint) }
        } catch (e: Exception) {
            toast(R.string.msg_storage_error, e.javaClass.simpleName)
            return@post
        }
        val c = Campaign(
            campaignId = "c-" + UUID.randomUUID().toString(), name = name, createdAt = isoNow(), graphId = p.graph.graphId, contentHash = p.graph.contentHash,
            zone = p.zone, policy = policy, home = CampaignHome(_ui.value.start.requested ?: s.position, s.edgeId, s.offsetMeters),
            settings = CampaignSettings(hoursPerDay, speedKmh), confirmed = emptyList(), history = emptyList(),
        )
        campaignCtx = CampaignCtx(p.graph, p.gi, p.req)
        saveCampaign(c)
    }

    /** Confirm the reviewed day; requirement IDs in `unchecked` were not really driven. Re-plans the rest. */
    fun confirmCampaignDay(unchecked: Set<String>) = post {
        val c = campaign ?: return@post
        val ctx = campaignCtx ?: return@post
        val rv = campaignReview ?: return@post
        val ok = newlyDriven(ctx.req.requirements, c.confirmed.toMap(), rv.driven).filter { it.requirementId !in unchecked }
        saveCampaign(confirmDay(c, ok, rv.driven, rv.sessionId, isoNow()))
    }

    fun undoCampaignDay() = post {
        val c = campaign ?: return@post
        val ctx = campaignCtx ?: return@post
        if (c.history.isEmpty()) return@post
        saveCampaign(undoLastDay(c, ctx.req.requirements))
    }

    fun deleteCampaign() = post {
        if (saveCampaign(null)) {
            campaignCtx = null
            campaignError = null
            publishCampaign()
        }
    }

    /** Draw one day's route (approach + coverage) on the map and fit it. */
    fun showCampaignDay(index: Int) = post {
        val plan = campaignPlan ?: return@post
        val ctx = campaignCtx ?: return@post
        val d = plan.days.getOrNull(index) ?: return@post
        val rp = campaignDayPlan(plan, index, ctx.req.requirements, "p-" + UUID.randomUUID().toString().take(8), isoNow())
        val b = d.bbox
        _ui.update {
            it.copy(
                screen = Screen.PREPARE,
                layers = it.layers.copy(plan = GeoJson.plan(ctx.gi, rp)),
                camera = CameraCmd(++cameraSeq, b.south, b.west, b.north, b.east),
            )
        }
    }

    /**
     * Start a session for one campaign day: requirements = that day's targets only, plan =
     * approach from home + that day's steps (web driveCampaignDay). Caller starts the service.
     */
    suspend fun startCampaignDay(index: Int): Boolean = withContext(dispatcher) {
        val c = campaign
        val ctx = campaignCtx
        val plan = campaignPlan
        if (c == null || ctx == null || plan == null) return@withContext false
        val d = plan.days.getOrNull(index) ?: return@withContext false
        val cur = active
        if (cur != null) {
            if (cur.progress.status == "ended" && cur.pendingWrites.isEmpty()) {
                retire(cur)
                active = null
            } else {
                toast(R.string.msg_campaign_session_open)
                return@withContext false
            }
        }
        val labOn = _ui.value.labMode
        val sessionId = "s-" + UUID.randomUUID().toString()
        val dayNo = c.history.size + 1
        val rp = campaignDayPlan(plan, index, ctx.req.requirements, "p-" + UUID.randomUUID().toString().take(8), isoNow())
        val home = SnappedStart(c.home.edgeId, c.home.offsetMeters, c.home.requested, 0.0)
        val base = SessionBase(
            sessionId = sessionId, graphId = c.graphId, contentHash = c.contentHash, zone = Zone(c.campaignId, 1, c.zone),
            // Driving to and from home may leave the zone; the day route is fixed by the campaign plan.
            vehicleProfile = "passenger_car", boundaryPolicy = c.policy, returnToStart = false,
            startSelection = StartSelection(c.home.requested, home, true), createdAt = isoNow(),
        )
        val approvedOutside = rp.totals.outsideMeters + d.approachMeters
        val now = System.currentTimeMillis()
        val events = listOf(
            SessionEvent("$sessionId:1", 1, sessionId, EventType.SESSION_CREATED, now, della.core.SCHEMA_VERSION,
                ContractJson.obj(
                    "appVersion" to JsonPrimitive(BuildConfig.VERSION_NAME), "lab" to JsonPrimitive(labOn), "endpoint" to ContractJson.str(store.graphEndpoint(c.contentHash)),
                    "approvedOutsideMeters" to ContractJson.num(approvedOutside), "campaignId" to JsonPrimitive(c.campaignId), "day" to JsonPrimitive(dayNo),
                )),
            SessionEvent("$sessionId:2", 2, sessionId, EventType.START_CONFIRMED, now, della.core.SCHEMA_VERSION,
                ContractJson.obj("edgeId" to JsonPrimitive(c.home.edgeId), "offsetMeters" to ContractJson.num(c.home.offsetMeters))),
            SessionEvent("$sessionId:3", 3, sessionId, EventType.PLAN_ACCEPTED, now, della.core.SCHEMA_VERSION,
                ContractJson.obj("plan" to ContractJson.encodePlan(rp), "reason" to JsonPrimitive("campaign_day"))),
        )
        try {
            store.createSession(sessionId, c.contentHash, encodeBase(base, labOn, day = CampaignTag(c.campaignId, dayNo, d.targetIds)), events)
        } catch (e: Exception) {
            toast(R.string.msg_storage_error, e.javaClass.simpleName)
            return@withContext false
        }
        val req = dayRequirements(ctx.req, d.targetIds.toHashSet())
        val progress = applyEvents(emptyProgress(), events, ApplyContext(sessionId, c.contentHash, ctx.graph.directedEdges.associate { it.edgeId to it.lengthMm / 1000.0 }))
        val a = Active(sessionId, base, ctx.graph, ctx.gi, req, labOn, 3, progress)
        a.acceptedRev = rp.planningRevision
        a.latestRequestedRev = rp.planningRevision
        a.approvedOutsideMeters = approvedOutside
        installPlan(a, rp, true)
        active = a
        lastStorageCheckpoint(a)
        val ring = zoneRing(c.zone)
        _ui.update { it.copy(screen = Screen.DRIVE, follow = true, layers = it.layers.copy(zone = GeoJson.zone(ring, emptyList(), true), markers = GeoJson.EMPTY)) }
        publishDrive(a, forceMap = true)
        if (labOn) startLab(a)
        refreshCampaignReview()
        true
    }

    // ===================================================== LAB MODE (debug builds only)

    private fun startLab(a: Active) {
        if (!BuildConfig.DEBUG || !a.lab) return
        stopLab()
        lab = LabSimulator(scope) { a.plan to a.gi }.also { sim ->
            sim.start { obs -> onFix(obs, "lab", SystemClock.elapsedRealtime()) }
        }
    }

    private fun stopLab() {
        lab?.stop()
        lab = null
    }
}
