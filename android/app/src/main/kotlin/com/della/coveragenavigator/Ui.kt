// Compose UI: preparation (parked) flow, driving view, diagnostics, settings and dialogs.
// Landscape-first with a side panel; portrait stacks map above the panel. Large targets.
package com.della.coveragenavigator

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// ------------------------------------------------------------------ formatting helpers

fun fmtMeters(m: Double): String =
    if (m >= 1000) String.format(Locale.getDefault(), "%.2f km", m / 1000) else String.format(Locale.getDefault(), "%.0f m", m)

fun fmtArea(m2: Double): String =
    if (m2 >= 1_000_000) String.format(Locale.getDefault(), "%.2f km²", m2 / 1_000_000) else String.format(Locale.getDefault(), "%.0f m²", m2)

@Composable
private fun zoneErrorText(code: String): String = when (code) {
    "self_intersection" -> stringResource(R.string.zone_err_self_intersection)
    "too_small" -> stringResource(R.string.zone_err_too_small)
    "duplicate_vertex" -> stringResource(R.string.zone_err_duplicate)
    "too_few_vertices" -> stringResource(R.string.zone_err_too_few)
    "invalid_radius" -> stringResource(R.string.zone_err_radius)
    "invalid_coordinate" -> stringResource(R.string.zone_err_coordinate)
    "large_zone" -> stringResource(R.string.zone_warn_large)
    else -> code
}

@Composable
private fun gpsStateText(state: String): String = when (state) {
    "acquiring" -> stringResource(R.string.gps_acquiring)
    "reliable" -> stringResource(R.string.gps_reliable)
    "weak" -> stringResource(R.string.gps_weak)
    "stale" -> stringResource(R.string.gps_stale)
    "permission_denied" -> stringResource(R.string.gps_permission_denied)
    "location_disabled" -> stringResource(R.string.gps_location_disabled)
    "paused" -> stringResource(R.string.gps_paused)
    "no_provider" -> stringResource(R.string.gps_no_provider)
    else -> state
}

@Composable
private fun maneuverText(type: String): String = when (type) {
    "depart" -> stringResource(R.string.mv_depart)
    "continue" -> stringResource(R.string.mv_continue)
    "slight_right" -> stringResource(R.string.mv_slight_right)
    "right" -> stringResource(R.string.mv_right)
    "sharp_right" -> stringResource(R.string.mv_sharp_right)
    "slight_left" -> stringResource(R.string.mv_slight_left)
    "left" -> stringResource(R.string.mv_left)
    "sharp_left" -> stringResource(R.string.mv_sharp_left)
    "turnaround" -> stringResource(R.string.mv_turnaround)
    "arrive" -> stringResource(R.string.mv_arrive)
    else -> type
}

private fun maneuverGlyph(type: String): String = when (type) {
    "slight_right" -> "↗"
    "right" -> "→"
    "sharp_right" -> "↘"
    "slight_left" -> "↖"
    "left" -> "←"
    "sharp_left" -> "↙"
    "turnaround" -> "↶"
    "arrive" -> "◉"
    else -> "↑"
}

@Composable
private fun legKindText(kind: String): String = when (kind) {
    "required" -> stringResource(R.string.leg_required)
    "transit" -> stringResource(R.string.leg_transit)
    "outside" -> stringResource(R.string.leg_outside)
    "approach" -> stringResource(R.string.leg_approach)
    else -> kind
}

@Composable
private fun causeText(cause: String): String = when (cause) {
    "outside_policy" -> stringResource(R.string.cause_outside_policy)
    "not_reachable_from_start" -> stringResource(R.string.cause_not_reachable)
    "needs_review" -> stringResource(R.string.cause_needs_review)
    "blocked_by_user" -> stringResource(R.string.cause_blocked)
    "budget_exceeded" -> stringResource(R.string.cause_budget)
    else -> cause
}

@Composable
private fun planStatusText(status: String?): String = when (status) {
    "complete" -> stringResource(R.string.plan_complete)
    "partial" -> stringResource(R.string.plan_partial)
    "failed" -> stringResource(R.string.plan_failed)
    "cancelled" -> stringResource(R.string.plan_cancelled)
    "no_requirements" -> stringResource(R.string.plan_no_requirements)
    null -> "—"
    else -> status
}

@Composable
private fun failureText(code: String?): String = when {
    code == null -> ""
    code == "provider_quota_exceeded" -> stringResource(R.string.fail_quota)
    code == "provider_timeout" -> stringResource(R.string.fail_timeout)
    code == "network_unavailable" -> stringResource(R.string.fail_network)
    code == "no_roads_in_area" -> stringResource(R.string.fail_empty)
    code == "graph_exceeds_tested_limit" -> stringResource(R.string.fail_too_large, MAX_GRAPH_EDGES)
    else -> stringResource(R.string.fail_other, code)
}

// ------------------------------------------------------------------ root

@Composable
fun AppRoot(engine: Engine, bridge: UiBridge, actions: AppActions) {
    val state by engine.ui.collectAsState()
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        DisposableEffect(bridge) {
            bridge.sink = { s -> scope.launch { snackbar.showSnackbar(s) } }
            onDispose { bridge.sink = null }
        }
        LaunchedEffect(state.messageId) {
            val m = state.message ?: return@LaunchedEffect
            snackbar.showSnackbar(context.getString(m.id, *m.args.toTypedArray()))
        }
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                when (state.screen) {
                    Screen.PREPARE -> PrepareScreen(state, engine, actions)
                    Screen.DRIVE -> DriveScreen(state, engine, actions)
                    Screen.DIAGNOSTICS -> DiagnosticsScreen(state, engine, actions)
                    Screen.SETTINGS -> SettingsScreen(engine, actions)
                }
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
                if (state.labMode) LabBanner(Modifier.align(Alignment.TopCenter))
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
            state.recovery?.let { RecoveryDialog(it, engine) }
            state.connectorOffer?.let { ConnectorDialog(it, engine) }
            state.outsideApproval?.let { OutsideApprovalDialog(it, engine) }
        }
    }
}

@Composable
private fun LabBanner(modifier: Modifier) {
    Text(
        stringResource(R.string.lab_banner), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp,
        modifier = modifier.background(Color(0xFFC62828), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun BigButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 56.dp),
        colors = if (color != null) ButtonDefaults.buttonColors(containerColor = color) else ButtonDefaults.buttonColors(),
    ) { Text(text, fontSize = 17.sp) }
}

@Composable
private fun SmallButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 52.dp)) { Text(text, fontSize = 15.sp) }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        content()
        Spacer(Modifier.height(6.dp))
        HorizontalDivider()
    }
}

@Composable
private fun SplitLayout(map: @Composable (Modifier) -> Unit, panel: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        if (w > h) {
            Row(Modifier.fillMaxSize()) {
                map(Modifier.weight(1f).fillMaxHeight())
                panel(Modifier.width(if (w > 900.dp) 420.dp else 360.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                map(Modifier.weight(1f).fillMaxWidth())
                panel(Modifier.fillMaxWidth().height(h * 0.48f))
            }
        }
    }
}

// ------------------------------------------------------------------ preparation

@Composable
fun PrepareScreen(state: UiState, engine: Engine, actions: AppActions) {
    SplitLayout(
        map = { mod ->
            Box(mod) {
                MapPane(
                    layers = state.layers, camera = state.camera, vertices = if (state.zone.mode == ZoneMode.POLYGON) state.zone.vertices else emptyList(),
                    dragEnabled = state.tapMode == TapMode.ZONE && state.zone.mode == ZoneMode.POLYGON,
                    onTap = { engine.mapTap(it) }, onVertexMoved = { i, p, f -> engine.moveVertex(i, p, f) }, onUserPan = {},
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    stringResource(if (state.tapMode == TapMode.ZONE) R.string.tap_hint_zone else R.string.tap_hint_start),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0xCC000000), RoundedCornerShape(6.dp)).padding(8.dp),
                    color = Color.White,
                )
                Text(
                    stringResource(R.string.osm_attribution),
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color(0xCCFFFFFF)).padding(horizontal = 4.dp),
                    color = Color.Black, fontSize = 12.sp,
                )
            }
        },
        panel = { mod -> PreparePanel(state, engine, actions, mod) },
    )
}

@Composable
private fun PreparePanel(state: UiState, engine: Engine, actions: AppActions, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { engine.setScreen(Screen.DIAGNOSTICS) }) { Text(stringResource(R.string.diagnostics)) }
            TextButton(onClick = { engine.setScreen(Screen.SETTINGS) }) { Text(stringResource(R.string.settings)) }
        }
        Section(stringResource(R.string.sec_search)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(stringResource(R.string.search_hint)) }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                SmallButton(stringResource(R.string.search), enabled = query.isNotBlank() && !searching) {
                    searching = true
                    searchError = null
                    scope.launch {
                        try {
                            results = withContext(Dispatchers.IO) { Nominatim.search(query.trim(), Localized.language(context)) }
                            if (results.isEmpty()) searchError = context.getString(R.string.search_none)
                        } catch (e: Exception) {
                            searchError = context.getString(R.string.search_failed, e.message ?: e.javaClass.simpleName)
                        }
                        searching = false
                    }
                }
            }
            Text(stringResource(R.string.search_note), fontSize = 12.sp)
            searchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            results.forEach { r ->
                Text(
                    r.displayName, maxLines = 2,
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (r.south != null && r.north != null && r.west != null && r.east != null) engine.focus(r.south, r.west, r.north, r.east)
                        else engine.focus(r.lat - 0.02, r.lon - 0.02, r.lat + 0.02, r.lon + 0.02)
                        results = emptyList()
                    }.padding(vertical = 10.dp),
                )
            }
        }
        Section(stringResource(R.string.sec_zone)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = state.zone.mode == ZoneMode.POLYGON, onClick = { engine.setZoneMode(ZoneMode.POLYGON) })
                Text(stringResource(R.string.zone_polygon), modifier = Modifier.clickable { engine.setZoneMode(ZoneMode.POLYGON) })
                Spacer(Modifier.width(12.dp))
                RadioButton(selected = state.zone.mode == ZoneMode.CIRCLE, onClick = { engine.setZoneMode(ZoneMode.CIRCLE) })
                Text(stringResource(R.string.zone_circle), modifier = Modifier.clickable { engine.setZoneMode(ZoneMode.CIRCLE) })
            }
            if (state.tapMode != TapMode.ZONE) SmallButton(stringResource(R.string.edit_zone)) { engine.setTapMode(TapMode.ZONE) }
            if (state.zone.mode == ZoneMode.POLYGON) {
                Text(stringResource(R.string.zone_polygon_help))
                Text(stringResource(R.string.zone_vertices, state.zone.vertices.size))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton(stringResource(R.string.undo), enabled = state.zone.vertices.isNotEmpty()) { engine.undoVertex() }
                    SmallButton(stringResource(R.string.clear), enabled = state.zone.vertices.isNotEmpty()) { engine.clearZone() }
                }
            } else {
                Text(stringResource(R.string.zone_circle_help))
                var radiusText by remember(state.zone.radiusMeters) { mutableStateOf(state.zone.radiusMeters.toInt().toString()) }
                OutlinedTextField(
                    value = radiusText, singleLine = true, label = { Text(stringResource(R.string.radius_m)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    onValueChange = { t ->
                        radiusText = t.filter { it.isDigit() }.take(5)
                        radiusText.toIntOrNull()?.let { if (it > 0) engine.setRadius(it.toDouble()) }
                    },
                )
                Slider(value = state.zone.radiusMeters.toFloat().coerceIn(20f, 5000f), valueRange = 20f..5000f, onValueChange = { engine.setRadius(Math.round(it / 10f) * 10.0) })
                Text(stringResource(R.string.radius_value, state.zone.radiusMeters.toInt()))
            }
            if (state.zone.areaSqMeters > 0) Text(stringResource(R.string.zone_area, fmtArea(state.zone.areaSqMeters), fmtMeters(state.zone.perimeterMeters)))
            state.zone.errors.forEach { Text("• " + zoneErrorText(it), color = if (it == "large_zone") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error) }
        }
        Section(stringResource(R.string.sec_graph)) {
            BigButton(stringResource(R.string.fetch_graph), enabled = state.zone.valid && state.graph.status != "fetching", modifier = Modifier.fillMaxWidth()) { engine.fetchGraph() }
            Text(stringResource(R.string.graph_source_note), fontSize = 12.sp)
            when (state.graph.status) {
                "fetching" -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    Text(stringResource(R.string.graph_fetching, state.graph.progress ?: ""))
                }
                "failed" -> {
                    Text(failureText(state.graph.failureCode), color = MaterialTheme.colorScheme.error)
                    state.graph.attempts.takeLast(4).forEach { Text("· $it", fontSize = 12.sp) }
                }
                "available" -> {
                    Text(stringResource(R.string.graph_ok, state.graph.edgeCount, state.graph.nodeCount, state.graph.restrictionCount))
                    Text(stringResource(R.string.count_required, state.counts.required, fmtMeters(state.counts.requiredMeters)))
                    Text(stringResource(R.string.count_needs_review, state.counts.needsReview, fmtMeters(state.counts.needsReviewMeters)))
                    Text(stringResource(R.string.count_unreachable, state.counts.unreachable, fmtMeters(state.counts.unreachableMeters)))
                    Text(stringResource(R.string.count_blocked, state.counts.blocked))
                    Text(stringResource(R.string.count_excluded_edges, state.graph.excludedEdges), fontSize = 12.sp)
                    Text(stringResource(R.string.osm_attribution), fontSize = 12.sp)
                }
                else -> Text(stringResource(R.string.graph_none))
            }
        }
        Section(stringResource(R.string.sec_start)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(stringResource(R.string.start_tap), enabled = state.graph.status == "available") { engine.setTapMode(TapMode.START) }
                SmallButton(stringResource(R.string.start_gps), enabled = state.graph.status == "available") { actions.useGpsForStart() }
            }
            val st = state.start
            when {
                st.error == "no_legal_road_nearby" -> Text(stringResource(R.string.start_no_road), color = MaterialTheme.colorScheme.error)
                st.error == "no_graph" -> Text(stringResource(R.string.blocker_no_graph), color = MaterialTheme.colorScheme.error)
                st.snappedEdgeId != null -> {
                    Text(stringResource(R.string.start_snapped, st.roadName ?: stringResource(R.string.unnamed_road), fmtMeters(st.distanceMeters), (st.bearingDeg ?: 0.0).toInt()))
                    if (!st.inMainComponent) Text(stringResource(R.string.start_not_main), color = MaterialTheme.colorScheme.tertiary)
                    if (st.hasReverse) SmallButton(stringResource(R.string.start_reverse)) { engine.reverseStartDirection() }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { engine.confirmStart(!st.confirmed) }) {
                        Checkbox(checked = st.confirmed, onCheckedChange = { engine.confirmStart(it) })
                        Text(stringResource(R.string.start_confirm))
                    }
                }
            }
        }
        Section(stringResource(R.string.sec_boundary)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { engine.setStrictPolicy() }) {
                RadioButton(selected = state.plan.policy == "STRICT_INSIDE", onClick = { engine.setStrictPolicy() })
                Text(stringResource(R.string.policy_strict))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = state.start.snappedEdgeId != null) { engine.requestConnectors() }) {
                RadioButton(selected = state.plan.policy == "ALLOW_NECESSARY_CONNECTORS", enabled = state.start.snappedEdgeId != null, onClick = { engine.requestConnectors() })
                Text(stringResource(R.string.policy_connectors))
            }
        }
        Section(stringResource(R.string.sec_plan)) {
            BigButton(stringResource(R.string.compute_route), enabled = state.start.snappedEdgeId != null && !state.plan.computing, modifier = Modifier.fillMaxWidth()) { engine.previewPlan() }
            val p = state.plan
            if (p.computing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            if (p.status != null) {
                Text(stringResource(R.string.plan_status_line, planStatusText(p.status), stringResource(if (p.valid) R.string.valid else R.string.invalid)))
                Text(stringResource(R.string.plan_totals, fmtMeters(p.totalMeters), fmtMeters(p.newCoverageMeters), fmtMeters(p.repeatMeters), fmtMeters(p.outsideMeters)))
                p.legs.take(12).forEach { Text("· ${legKindText(it.kind)}: ${fmtMeters(it.meters)} (${it.steps})", fontSize = 13.sp) }
                if (p.legs.size > 12) Text(stringResource(R.string.more_legs, p.legs.size - 12), fontSize = 13.sp)
                p.unreachableByCause.forEach { (c, n) -> Text(stringResource(R.string.unplanned_cause, n, causeText(c)), color = MaterialTheme.colorScheme.tertiary) }
                if (p.unverifiedTurnarounds > 0) Text(stringResource(R.string.plan_unverified_turnarounds, p.unverifiedTurnarounds), color = MaterialTheme.colorScheme.tertiary)
                if (p.outsideMeters > 2.0) Text(stringResource(R.string.plan_outside_warning, fmtMeters(p.outsideMeters)), color = MaterialTheme.colorScheme.tertiary)
                p.errors.forEach { Text("✗ $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                Text(stringResource(R.string.plan_note, p.millis, p.iterations), fontSize = 12.sp)
            }
        }
        Section(stringResource(R.string.sec_go)) {
            val canStart = state.startBlockers.isEmpty()
            BigButton(stringResource(R.string.start_navigation), enabled = canStart, modifier = Modifier.fillMaxWidth(), color = Color(0xFF2E7D32)) {
                if (state.labMode) actions.startLabNavigation() else actions.startNavigation()
            }
            state.startBlockers.forEach { Text("• " + stringResource(it), color = MaterialTheme.colorScheme.error) }
            Text(stringResource(R.string.safety_note), fontSize = 12.sp)
        }
    }
}

// ------------------------------------------------------------------ driving

@Composable
fun DriveScreen(state: UiState, engine: Engine, actions: AppActions) {
    val d = state.drive
    var confirmEnd by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        MapPane(
            layers = state.layers, camera = state.camera, vertices = emptyList(), dragEnabled = false,
            onTap = {}, onVertexMoved = { _, _, _ -> }, onUserPan = { if (state.follow) engine.setFollow(false) },
            modifier = Modifier.fillMaxSize(), driving = true,
        )
        Column(Modifier.align(Alignment.TopStart).padding(8.dp).widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NavBanner(d)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                GpsChip(d.gps)
                SmallButton(stringResource(if (d.voiceOn) R.string.voice_on else R.string.voice_off)) { engine.setVoice(!d.voiceOn) }
                if (!state.follow && d.status == "active") SmallButton(stringResource(R.string.recenter)) { engine.setFollow(true) }
            }
        }
        Text(
            stringResource(R.string.osm_attribution),
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color(0xCCFFFFFF)).padding(horizontal = 4.dp),
            color = Color.Black, fontSize = 12.sp,
        )
        Surface(
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).widthIn(max = 560.dp),
            shape = RoundedCornerShape(12.dp), tonalElevation = 6.dp, shadowElevation = 6.dp,
        ) {
            Column(Modifier.padding(12.dp)) {
                val m = d.metrics
                if (m != null) {
                    Text(
                        stringResource(R.string.progress_line, fmtMeters(m.confirmedMeters), fmtMeters(m.totalRequiredMeters), m.percentConfirmed),
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    )
                    LinearProgressIndicator(progress = { (m.percentConfirmed / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    Text(stringResource(R.string.progress_detail, fmtMeters(m.remainingMeters), fmtMeters(m.blockedMeters), fmtMeters(m.unreachableMeters), fmtMeters(m.needsReviewMeters)), fontSize = 13.sp)
                    Text(stringResource(R.string.progress_counts, m.counts["covered"] ?: 0, m.counts["partial"] ?: 0, m.counts["unvisited"] ?: 0), fontSize = 13.sp)
                }
                d.recoveredNote?.let { Text(stringResource(R.string.recovered_note, it), fontSize = 12.sp) }
                if (d.pendingWrites > 0) Text(stringResource(R.string.storage_pending, d.pendingWrites), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (d.status) {
                        "active" -> BigButton(stringResource(R.string.pause)) { actions.pause() }
                        "paused" -> BigButton(stringResource(R.string.resume), color = Color(0xFF2E7D32)) { actions.resume() }
                    }
                    if (d.status == "active") BigButton(stringResource(R.string.report_blocked), color = Color(0xFF6D4C41)) { engine.reportBlocked() }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (d.status != "ended") SmallButton(stringResource(R.string.end_session)) { confirmEnd = true }
                    if (d.status != "active") SmallButton(stringResource(R.string.export)) { showExport = true }
                    if (d.status != "active") SmallButton(stringResource(R.string.diagnostics)) { engine.setScreen(Screen.DIAGNOSTICS) }
                    if (d.status == "ended") SmallButton(stringResource(R.string.close_session)) { engine.closeSession() }
                }
            }
        }
    }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(stringResource(R.string.end_session)) },
            text = { Text(stringResource(R.string.end_confirm_text)) },
            confirmButton = { TextButton(onClick = { confirmEnd = false; actions.end() }) { Text(stringResource(R.string.end_session)) } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showExport) ExportDialog(onDismiss = { showExport = false }, actions = actions)
}

@Composable
private fun GpsChip(g: GpsInfo) {
    val color = when (g.state) {
        "reliable" -> Color(0xFF2E7D32)
        "weak", "acquiring" -> Color(0xFFEF6C00)
        "paused" -> Color(0xFF546E7A)
        else -> Color(0xFFC62828)
    }
    val detail = buildString {
        g.accuracy?.let { append(String.format(Locale.getDefault(), " ±%.0f m", it)) }
        g.ageMs?.let { append(String.format(Locale.getDefault(), " · %.1f s", it / 1000.0)) }
        g.provider?.let { append(" · ").append(it) }
    }
    Text(
        (if (g.simulated) "LAB · " else "GPS · ") + gpsStateText(g.state) + detail,
        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Big turn-by-turn banner: what to do next, how far, and onto which street. */
@Composable
private fun NavBanner(d: DriveInfo) {
    val n = d.nav
    val green = Color(0xFF1B5E20)
    if (n == null || d.status != "active" || d.replanning) {
        GuidanceCard(d)
        return
    }
    Surface(shape = RoundedCornerShape(16.dp), color = green, shadowElevation = 8.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val glyph = when (n.mode) {
                    "to_road" -> "➤"
                    else -> if (n.type == "join") "⤴" else maneuverGlyph(n.type)
                }
                val rot = if (n.mode == "to_road") ((n.relativeBearing ?: n.headBearing ?: 0.0) - 90.0).toFloat() else 0f
                Text(glyph, fontSize = 64.sp, color = Color.White, modifier = Modifier.padding(end = 16.dp).graphicsLayer { rotationZ = rot })
                Column {
                    Text(fmtMeters(n.distanceMeters), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    val action = when {
                        n.mode == "to_road" -> stringResource(R.string.nav_head_to_road)
                        n.type == "join" -> stringResource(R.string.nav_join_route)
                        n.type == "turnaround" && n.turnaroundVerified != true -> stringResource(R.string.voice_turnaround_if_safe)
                        else -> maneuverText(n.type)
                    }
                    Text(action, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    n.roadName?.let { Text(stringResource(R.string.nav_onto, it), fontSize = 20.sp, color = Color(0xFFE8F5E9)) }
                }
            }
            if (n.mode == "approach") {
                Text(stringResource(R.string.nav_go_to_route) + " · " + stringResource(R.string.nav_to_route_remaining, fmtMeters(n.rejoinMeters ?: 0.0)), color = Color(0xFFC8E6C9), fontSize = 16.sp)
            }
            if (n.thenType != null && n.thenDistanceMeters != null && n.thenDistanceMeters < 400) {
                Text(
                    stringResource(R.string.nav_then) + ": " + (if (n.thenType == "join") stringResource(R.string.nav_join_route) else maneuverGlyph(n.thenType) + " " + maneuverText(n.thenType)) + (n.thenRoadName?.let { " · $it" } ?: ""),
                    color = Color.White, fontSize = 18.sp,
                    modifier = Modifier.padding(top = 6.dp).background(Color(0x33000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            if (n.approximate) Text(stringResource(R.string.nav_approximate), color = Color(0xFFFFE082), fontSize = 14.sp)
        }
    }
}

@Composable
private fun GuidanceCard(d: DriveInfo) {
    val mv = d.maneuver
    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(12.dp)) {
            when {
                d.status == "paused" -> Text(stringResource(R.string.guidance_paused), fontSize = 20.sp)
                d.status == "ended" -> Text(stringResource(R.string.guidance_ended), fontSize = 20.sp)
                d.replanning -> Text(stringResource(R.string.guidance_replanning), fontSize = 20.sp)
                d.noContinuation -> Text(stringResource(R.string.guidance_no_continuation), fontSize = 18.sp, color = MaterialTheme.colorScheme.error)
                mv != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(maneuverGlyph(mv.type), fontSize = 44.sp, modifier = Modifier.padding(end = 12.dp))
                        Column {
                            Text(fmtMeters(mv.distanceMeters), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            Text(maneuverText(mv.type) + (mv.roadName?.let { " · $it" } ?: ""), fontSize = 18.sp)
                        }
                    }
                    if (mv.type == "turnaround" && mv.turnaroundVerified != true) {
                        Text(stringResource(R.string.turnaround_only_if_safe), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
                d.gps.state != "reliable" -> Text(stringResource(R.string.guidance_wait_gps), fontSize = 18.sp)
                d.guidanceEvent == "off_route_pending" -> Text(stringResource(R.string.guidance_off_route), fontSize = 18.sp)
                d.guidanceEvent == "finished" -> Text(stringResource(R.string.guidance_finished), fontSize = 18.sp)
                else -> Text(stringResource(R.string.guidance_follow_route), fontSize = 18.sp)
            }
            Text(stringResource(R.string.guidance_signs), fontSize = 12.sp)
        }
    }
}

// ------------------------------------------------------------------ dialogs

@Composable
private fun RecoveryDialog(r: RecoveryOffer, engine: Engine) {
    val df = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    AlertDialog(
        onDismissRequest = { engine.dismissRecovery() },
        title = { Text(stringResource(R.string.recovery_title)) },
        text = {
            Text(stringResource(R.string.recovery_text, df.format(java.util.Date(r.createdAt)), df.format(java.util.Date(r.updatedAt))) + if (r.lab) "\n" + stringResource(R.string.lab_banner) else "")
        },
        confirmButton = { TextButton(onClick = { engine.resumeRecovered() }) { Text(stringResource(R.string.recovery_resume)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { engine.endRecovered() }) { Text(stringResource(R.string.recovery_end)) }
                TextButton(onClick = { engine.dismissRecovery() }) { Text(stringResource(R.string.later)) }
            }
        },
    )
}

@Composable
private fun ConnectorDialog(o: ConnectorOffer, engine: Engine) {
    AlertDialog(
        onDismissRequest = { engine.dismissConnectors() },
        title = { Text(stringResource(R.string.connectors_title)) },
        text = { Text(stringResource(R.string.connectors_text, fmtMeters(o.outsideMeters), fmtMeters(o.totalMeters), o.coveredCount)) },
        confirmButton = { TextButton(onClick = { engine.confirmConnectors() }) { Text(stringResource(R.string.connectors_accept)) } },
        dismissButton = { TextButton(onClick = { engine.dismissConnectors() }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun OutsideApprovalDialog(o: OutsideApproval, engine: Engine) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.outside_approval_title)) },
        text = { Text(stringResource(R.string.outside_approval_text, fmtMeters(o.newOutsideMeters), fmtMeters(o.approvedOutsideMeters))) },
        confirmButton = { TextButton(onClick = { engine.answerOutsideApproval(true) }) { Text(stringResource(R.string.connectors_accept)) } },
        dismissButton = { TextButton(onClick = { engine.answerOutsideApproval(false) }) { Text(stringResource(R.string.outside_approval_decline)) } },
    )
}

@Composable
private fun ExportDialog(onDismiss: () -> Unit, actions: AppActions) {
    var obs by remember { mutableStateOf(false) }
    var graph by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = obs, onCheckedChange = { obs = it })
                    Text(stringResource(R.string.export_observations))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = graph, onCheckedChange = { graph = it })
                    Text(stringResource(R.string.export_graph))
                }
                Text(stringResource(R.string.export_privacy), fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); actions.export(obs, graph) }) { Text(stringResource(R.string.export)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ------------------------------------------------------------------ diagnostics / settings

@Composable
fun DiagnosticsScreen(state: UiState, engine: Engine, actions: AppActions) {
    var confirmDelete by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    val g = state.graph
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            BigButton(stringResource(R.string.back)) { engine.setScreen(if (state.drive.sessionId != null) Screen.DRIVE else Screen.PREPARE) }
        }
        Section(stringResource(R.string.diag_provenance)) {
            Text(stringResource(R.string.diag_provider_line, g.providerId ?: "—", g.dataState))
            Text("graphId: ${g.graphId ?: "—"}")
            Text("contentHash: ${g.contentHash ?: "—"}", fontSize = 12.sp)
            Text(stringResource(R.string.diag_source_version, g.sourceVersion ?: "—"))
            Text(stringResource(R.string.diag_fetched, g.fetchedAt ?: "—", g.endpoint ?: "—"))
            Text(stringResource(R.string.graph_ok, g.edgeCount, g.nodeCount, g.restrictionCount))
            Text(stringResource(R.string.diag_eligibility, g.eligibleEdges, g.excludedEdges, g.needsReviewEdges))
            g.exclusionReasons.entries.sortedByDescending { it.value }.take(10).forEach { Text("· ${it.key}: ${it.value}", fontSize = 12.sp) }
            g.attempts.forEach { Text("↳ $it", fontSize = 12.sp) }
        }
        Section(stringResource(R.string.diag_completeness)) {
            if (g.notes.isEmpty()) Text("—")
            g.notes.forEach { Text("• $it", fontSize = 13.sp) }
            Text(stringResource(R.string.diag_data_caveat), fontSize = 13.sp)
        }
        Section(stringResource(R.string.diag_attribution)) {
            Text(stringResource(R.string.osm_attribution))
            Text(stringResource(R.string.diag_services), fontSize = 13.sp)
        }
        Section(stringResource(R.string.diag_gps)) {
            val gp = state.drive.gps
            Text("${gpsStateText(gp.state)} · ${gp.provider ?: "—"} · ${gp.accuracy?.let { String.format(Locale.getDefault(), "±%.0f m", it) } ?: "—"} · ${gp.ageMs?.let { "${it / 1000.0} s" } ?: "—"}")
            Text(stringResource(R.string.diag_gps_note), fontSize = 13.sp)
        }
        Section(stringResource(R.string.diag_session)) {
            val d = state.drive
            Text("session: ${d.sessionId ?: "—"} · ${d.status}")
            Text(stringResource(R.string.diag_session_line, d.traversals, d.gaps, d.planRevision, planStatusText(d.planStatus)))
            Text(engine.diagnosticsSummary(), fontSize = 12.sp)
        }
        Section(stringResource(R.string.diag_data)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(stringResource(R.string.export), enabled = state.drive.sessionId != null && state.drive.status != "active") { showExport = true }
                SmallButton(stringResource(R.string.import_session), enabled = state.drive.sessionId == null) { actions.import() }
            }
            SmallButton(stringResource(R.string.delete_all), enabled = state.drive.status != "active") { confirmDelete = true }
        }
        if (BuildConfig.DEBUG) {
            Section(stringResource(R.string.lab_section)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = state.labMode, enabled = state.drive.sessionId == null, onCheckedChange = { engine.setLabMode(it) })
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.lab_banner))
                }
                Text(stringResource(R.string.lab_note), fontSize = 12.sp)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_all)) },
            text = { Text(stringResource(R.string.delete_confirm_text)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; engine.deleteAllData() }) { Text(stringResource(R.string.delete_all)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showExport) ExportDialog(onDismiss = { showExport = false }, actions = actions)
}

@Composable
fun SettingsScreen(engine: Engine, actions: AppActions) {
    val context = LocalContext.current
    val lang = Localized.language(context)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            BigButton(stringResource(R.string.back)) { engine.setScreen(Screen.PREPARE) }
        }
        Section(stringResource(R.string.language)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { actions.setLanguage("fa") }.padding(vertical = 8.dp)) {
                RadioButton(selected = lang == "fa", onClick = { actions.setLanguage("fa") })
                Text("فارسی", fontSize = 18.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { actions.setLanguage("en") }.padding(vertical = 8.dp)) {
                RadioButton(selected = lang == "en", onClick = { actions.setLanguage("en") })
                Text("English", fontSize = 18.sp)
            }
        }
        Section(stringResource(R.string.legend)) {
            LegendRow(Color(0xFFD32F2F), stringResource(R.string.legend_unvisited))
            LegendRow(Color(0xFFF9A825), stringResource(R.string.legend_partial))
            LegendRow(Color(0xFF2E7D32), stringResource(R.string.legend_covered))
            LegendRow(Color(0xFF4E342E), stringResource(R.string.legend_blocked))
            LegendRow(Color(0xFF757575), stringResource(R.string.legend_unreachable))
            LegendRow(Color(0xFF8E24AA), stringResource(R.string.legend_needs_review))
            LegendRow(Color(0x803949AB), stringResource(R.string.legend_plan))
            LegendRow(Color(0x80EF6C00), stringResource(R.string.legend_outside))
            LegendRow(Color(0xFF1E88E5), stringResource(R.string.legend_location))
            Text(stringResource(R.string.legend_direction), fontSize = 13.sp)
        }
        Text(stringResource(R.string.about_text), fontSize = 13.sp)
    }
}

@Composable
private fun LegendRow(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Box(Modifier.width(36.dp).height(8.dp).background(c, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(10.dp))
        Text(label)
    }
}
