// Compose UI (map-first navigator, mirrors web/src/ui/App.tsx): full-screen map with floating
// controls, preparation as a 3-step bottom sheet (side panel on wide landscape screens), menu
// drawer, driving HUD (DriveHud.kt) and full-screen pages (Pages.kt).
package com.della.coveragenavigator

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// ------------------------------------------------------------------ palette (= web PALETTE)

object Palette {
    val todo = Color(0xFFFF5A1F)
    val done = Color(0xFF12A150)
    val partial = Color(0xFFF4B400)
    val blocked = Color(0xFF6B7280)
    val unreach = Color(0xFF8E5CC4)
    val review = Color(0xFFA0785A)
    val route = Color(0xFF1A73E8)
    val routeCasing = Color(0xFF0B3D91)
    val plan = Color(0xFF1A73E8)
    val outside = Color(0xFFC2185B)
    val primary = Color(0xFF1A73E8)
    val go = Color(0xFF12A150)
    val danger = Color(0xFFD92D20)
    val warn = Color(0xFFB45309)
    val ok = Color(0xFF0F7B3F)
    val hud = Color(0xFF1F2937)
    val hudNow = Color(0xFF0F7B3F)
}

// ------------------------------------------------------------------ formatting helpers

fun fmtMeters(m: Double): String =
    if (m >= 1000) String.format(Locale.getDefault(), "%.2f km", m / 1000) else String.format(Locale.getDefault(), "%.0f m", m)

fun fmtArea(m2: Double): String =
    if (m2 >= 1_000_000) String.format(Locale.getDefault(), "%.3f km²", m2 / 1_000_000) else String.format(Locale.getDefault(), "%.0f m²", m2)

fun fmtInt(n: Int): String = String.format(Locale.getDefault(), "%d", n)

@Composable
internal fun zoneErrorText(code: String): String = when (code) {
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
internal fun gpsStateText(state: String): String = when (state) {
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
internal fun maneuverText(type: String): String = when (type) {
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
    else -> stringResource(R.string.mv_continue)
}

/** Same glyphs as web MANEUVER_ICON. */
internal fun maneuverGlyph(type: String): String = when (type) {
    "slight_right" -> "↗"
    "right" -> "→"
    "sharp_right" -> "↘"
    "slight_left" -> "↖"
    "left" -> "←"
    "sharp_left" -> "↙"
    "turnaround" -> "⤸"
    "arrive" -> "■"
    else -> "↑"
}

@Composable
internal fun causeText(cause: String): String = when (cause) {
    "outside_policy" -> stringResource(R.string.cause_outside_policy)
    "not_reachable_from_start" -> stringResource(R.string.cause_not_reachable)
    "needs_review" -> stringResource(R.string.cause_needs_review)
    "blocked_by_user" -> stringResource(R.string.cause_blocked)
    "budget_exceeded" -> stringResource(R.string.cause_budget)
    else -> cause
}

@Composable
internal fun planStatusText(status: String?): String = when (status) {
    "complete" -> stringResource(R.string.plan_complete)
    "partial" -> stringResource(R.string.plan_partial)
    "failed" -> stringResource(R.string.plan_failed)
    "cancelled" -> stringResource(R.string.plan_cancelled)
    "no_requirements" -> stringResource(R.string.plan_no_requirements)
    null -> "—"
    else -> status
}

@Composable
internal fun failureText(code: String?): String = when {
    code == null -> ""
    code == "provider_quota_exceeded" -> stringResource(R.string.fail_quota)
    code == "provider_timeout" -> stringResource(R.string.fail_timeout)
    code == "network_unavailable" -> stringResource(R.string.fail_network)
    code == "no_roads_in_area" -> stringResource(R.string.fail_empty)
    code == "graph_exceeds_tested_limit" -> stringResource(R.string.fail_too_large, MAX_GRAPH_EDGES)
    else -> stringResource(R.string.fail_other, code)
}

/** surface-2 of the web tokens (segmented controls, plan card, round buttons). */
@Composable
internal fun surface2(): Color = MaterialTheme.colorScheme.surfaceVariant

@Composable
internal fun mutedColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant

// ------------------------------------------------------------------ root

@Composable
fun AppRoot(engine: Engine, bridge: UiBridge, actions: AppActions) {
    val state by engine.ui.collectAsState()
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(
            primary = Palette.primary, background = Color(0xFF0B0F14), surface = Color(0xFF161B22), surfaceVariant = Color(0xFF21262D),
            onSurface = Color(0xFFF3F4F6), onSurfaceVariant = Color(0xFF9CA3AF), outline = Color(0xFF30363D),
        )
    } else {
        lightColorScheme(
            primary = Palette.primary, background = Color.White, surface = Color.White, surfaceVariant = Color(0xFFF3F4F6),
            onSurface = Color(0xFF111827), onSurfaceVariant = Color(0xFF6B7280), outline = Color(0xFFE5E7EB), error = Palette.danger,
        )
    }
    MaterialTheme(colorScheme = scheme) {
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
        var menuOpen by rememberSaveable { mutableStateOf(false) }
        var legendOpen by rememberSaveable { mutableStateOf(false) }
        var zoomSeq by remember { mutableLongStateOf(0L) }
        var zoom by remember { mutableStateOf<ZoomCmd?>(null) }
        val inDrive = state.screen == Screen.DRIVE && state.drive.sessionId != null
        val page = state.screen == Screen.DIAGNOSTICS || state.screen == Screen.SETTINGS || state.screen == Screen.SESSIONS || state.screen == Screen.CAMPAIGN
        val drawing = state.screen == Screen.PREPARE && state.tapMode == TapMode.ZONE && state.zone.mode == ZoneMode.POLYGON && state.drive.sessionId == null

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                // One full-screen map for every mode.
                MapPane(
                    layers = state.layers, camera = state.camera,
                    vertices = if (drawing) state.zone.vertices else emptyList(), dragEnabled = drawing,
                    onTap = { if (state.screen == Screen.PREPARE) engine.mapTap(it) },
                    onVertexMoved = { i, p, f -> engine.moveVertex(i, p, f) },
                    onUserPan = { if (inDrive && state.follow) engine.setFollow(false) },
                    modifier = Modifier.fillMaxSize(), driving = inDrive, zoom = zoom,
                )
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val wide = maxWidth >= 900.dp && maxWidth > maxHeight
                        val h = maxHeight
                        // right-side map controls (always on the right, as on the web); below the sheet
                        Column(
                            Modifier.align(if (inDrive) AbsoluteAlignment.BottomRight else AbsoluteAlignment.CenterRight)
                                .padding(end = 10.dp, bottom = if (inDrive) (if (wide) 24.dp else 150.dp) else 0.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Fab("+", stringResource(R.string.zoom_in)) { zoom = ZoomCmd(++zoomSeq, 1.0) }
                            Fab("−", stringResource(R.string.zoom_out)) { zoom = ZoomCmd(++zoomSeq, -1.0) }
                            Fab("◧", stringResource(R.string.legend)) { legendOpen = !legendOpen }
                            if (inDrive && state.drive.status == "active") {
                                Fab(if (state.drive.voiceOn) "🔊" else "🔇", stringResource(if (state.drive.voiceOn) R.string.voice_on else R.string.voice_off)) {
                                    engine.setVoice(!state.drive.voiceOn)
                                }
                            }
                            val on = state.follow && inDrive
                            Fab("➤", stringResource(R.string.my_location), bg = if (on) Palette.primary else null, fg = if (on) Color.White else Palette.primary) {
                                actions.showMyLocation()
                            }
                        }
                        // "where am I" chip: above the drive bar, or beside the side panel on wide screens
                        if (inDrive || wide) {
                            Box(
                                Modifier.align(AbsoluteAlignment.BottomLeft)
                                    .padding(start = if (wide) (if (inDrive) 584.dp else 424.dp) else 10.dp, bottom = if (wide) 16.dp else 150.dp),
                            ) { WhereChip(state, actions) }
                        }

                        if (!inDrive) PrepareSheet(state, engine, actions, wide, h)
                        else DriveHud(state, engine, actions, wide, onMenu = { menuOpen = true })

                        // top-left: menu + search (prepare)
                        if (!inDrive) {
                            Column(
                                Modifier.align(AbsoluteAlignment.TopLeft).padding(start = 10.dp, top = 10.dp, end = if (wide) 10.dp else 72.dp)
                                    .widthIn(max = if (wide) 400.dp else 560.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Fab("☰", stringResource(R.string.menu), size = 44.dp) { menuOpen = true }
                                    PlaceSearch(engine, Modifier.weight(1f))
                                }
                                if (state.labMode) LabBanner()
                                if (!wide) WhereChip(state, actions)
                            }
                            state.recovery?.let {
                                Box(Modifier.align(AbsoluteAlignment.TopLeft).padding(start = if (wide) 424.dp else 10.dp, top = if (wide) 10.dp else 104.dp, end = 72.dp)) {
                                    ResumeBanner(it, engine)
                                }
                            }
                        } else if (state.labMode) {
                            Box(Modifier.align(Alignment.TopCenter).padding(top = 4.dp)) { LabBanner() }
                        }
                        if (legendOpen) LegendPopover(Modifier.align(AbsoluteAlignment.CenterRight).padding(end = 70.dp)) { legendOpen = false }
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                    }
                }
                if (page) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            when (state.screen) {
                                Screen.DIAGNOSTICS -> DiagnosticsScreen(state, engine, actions)
                                Screen.SETTINGS -> SettingsScreen(state, engine, actions)
                                Screen.SESSIONS -> SessionsScreen(state, engine, actions)
                                Screen.CAMPAIGN -> CampaignScreen(state, engine, actions)
                                else -> {}
                            }
                        }
                    }
                }
                if (menuOpen) MenuDrawer(state, engine, actions) { menuOpen = false }
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 96.dp))
            }
            state.outsideApproval?.let { OutsideApprovalDialog(it, engine) }
        }
    }
}

// ------------------------------------------------------------------ building blocks

@Composable
internal fun Fab(glyph: String, label: String, size: Dp = 48.dp, bg: Color? = null, fg: Color? = null, onClick: () -> Unit) {
    Surface(
        shape = CircleShape, color = bg ?: MaterialTheme.colorScheme.surface, shadowElevation = 4.dp,
        modifier = Modifier.size(size).semantics { contentDescription = label }.clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(glyph, fontSize = 21.sp, color = fg ?: MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun LabBanner() {
    Text(
        stringResource(R.string.lab_banner), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
        modifier = Modifier.background(Color(0xFFC62828), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** Large primary action (web `button.big`). */
@Composable
internal fun BigButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Palette.primary, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
}

@Composable
internal fun SmallButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    if (primary) {
        Button(
            onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 46.dp), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.primary, contentColor = Color.White),
        ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 46.dp), shape = RoundedCornerShape(12.dp)) {
            Text(text, fontSize = 15.sp)
        }
    }
}

@Composable
internal fun LinkText(text: String, onClick: () -> Unit) {
    Text(text, color = Palette.primary, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp))
}

/** Segmented control (web `.seg`). */
@Composable
internal fun Segmented(options: List<Pair<String, Boolean>>, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    Row(Modifier.background(surface2(), RoundedCornerShape(12.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { i, (label, on) ->
            Box(
                Modifier
                    .then(if (on) Modifier.shadow(1.dp, RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)) else Modifier)
                    .clickable(enabled = enabled) { onSelect(i) }
                    .heightIn(min = 40.dp).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.onSurface else mutedColor(),
                )
            }
        }
    }
}

@Composable
internal fun IconBtn(glyph: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).semantics { contentDescription = label },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
    ) { Text(glyph, fontSize = 18.sp) }
}

@Composable
internal fun CheckRow(checked: Boolean, label: String, enabled: Boolean = true, color: Color? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) }) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = { onChange(it) })
        Text(label, fontSize = 14.sp, color = color ?: MaterialTheme.colorScheme.onSurface)
    }
}

/** Collapsible "details" (web `<details>`). */
@Composable
internal fun Expander(title: String, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text((if (open) "▾ " else "▸ ") + title, fontSize = 13.sp, color = mutedColor(), modifier = Modifier.clickable { open = !open }.padding(vertical = 6.dp))
        if (open) Column(Modifier.padding(start = 12.dp), content = content)
    }
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        content()
        Spacer(Modifier.height(6.dp))
        HorizontalDivider()
    }
}

// ------------------------------------------------------------------ search + chips

@Composable
private fun PlaceSearch(engine: Engine, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun run() {
        if (query.isBlank() || searching) return
        searching = true
        error = null
        scope.launch {
            try {
                results = withContext(Dispatchers.IO) { Nominatim.search(query.trim(), Localized.language(context)) }
            } catch (e: Exception) {
                error = context.getString(R.string.search_failed, e.message ?: e.javaClass.simpleName)
            }
            searching = false
        }
    }
    Column(modifier) {
        Surface(shape = RoundedCornerShape(22.dp), shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface, modifier = Modifier.height(44.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 6.dp)) {
                Text("⌕", fontSize = 18.sp, color = mutedColor())
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(stringResource(R.string.search_hint), color = mutedColor(), fontSize = 14.sp, maxLines = 1)
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = TextStyle(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { run() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = context.getString(R.string.search) },
                    )
                }
                if (searching) Text(stringResource(R.string.search) + "…", fontSize = 12.sp, color = mutedColor())
                else if (query.isNotBlank()) TextButton(onClick = { run() }) { Text(stringResource(R.string.search)) }
            }
        }
        val r = results
        if (error != null || (r != null && r.isEmpty())) {
            Text(
                error ?: stringResource(R.string.search_none), fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        if (r != null && r.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 4.dp, modifier = Modifier.padding(top = 6.dp).heightIn(max = 320.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(6.dp)) {
                    r.forEach { p ->
                        val parts = p.displayName.split(",")
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                if (p.south != null && p.north != null && p.west != null && p.east != null) engine.focus(p.south, p.west, p.north, p.east)
                                else engine.focus(p.lat - 0.02, p.lon - 0.02, p.lat + 0.02, p.lon + 0.02)
                                results = null
                                query = parts[0]
                            }.padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(parts[0].trim(), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(parts.drop(1).take(2).joinToString(",").trim(), fontSize = 12.sp, color = mutedColor(), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WhereChip(state: UiState, actions: AppActions) {
    val me = state.myLocation
    val text = when {
        me != null -> stringResource(R.string.you_are_here) + (me.accuracy?.let { String.format(Locale.getDefault(), " · ±%.0f m", it) } ?: "")
        state.myLocationState == "permission_denied" -> stringResource(R.string.gps_permission_denied)
        state.myLocationState == "location_disabled" -> stringResource(R.string.gps_location_disabled)
        else -> stringResource(R.string.locating)
    }
    Surface(shape = RoundedCornerShape(50), shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface, modifier = Modifier.clickable { actions.showMyLocation() }) {
        Text(text, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun ResumeBanner(r: RecoveryOffer, engine: Engine) {
    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.resume_banner) + if (r.lab) " · " + stringResource(R.string.lab_banner) else "", fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
            SmallButton(stringResource(R.string.recovery_resume), primary = true) { engine.resumeRecovered() }
            TextButton(onClick = { engine.dismissRecovery() }) { Text("×", fontSize = 18.sp) }
        }
    }
}

@Composable
internal fun LegendPopover(modifier: Modifier, onClose: (() -> Unit)?) {
    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 6.dp, modifier = modifier.widthIn(max = 300.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.legend), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (onClose != null) TextButton(onClick = onClose) { Text("×", fontSize = 18.sp) }
            }
            LegendRow(Palette.todo, stringResource(R.string.legend_unvisited))
            LegendRow(Palette.done, stringResource(R.string.legend_covered))
            LegendRow(Palette.partial, stringResource(R.string.legend_partial))
            LegendRow(Palette.route, stringResource(R.string.legend_route_ahead), thick = true)
            LegendRow(Palette.plan.copy(alpha = 0.5f), stringResource(R.string.legend_plan), thick = true)
            LegendRow(Palette.outside.copy(alpha = 0.5f), stringResource(R.string.legend_outside), thick = true)
            LegendRow(Palette.blocked, stringResource(R.string.legend_blocked), dashed = true)
            LegendRow(Palette.unreach, stringResource(R.string.legend_unreachable), dashed = true)
            LegendRow(Palette.review, stringResource(R.string.legend_needs_review), dashed = true)
            Text(stringResource(R.string.legend_directions_short), fontSize = 12.sp, color = mutedColor(), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun LegendRow(c: Color, label: String, thick: Boolean = false, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        if (dashed) {
            Row(Modifier.width(34.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(4) { Box(Modifier.width(6.dp).height(5.dp).background(c, RoundedCornerShape(2.dp))) }
            }
        } else {
            Box(Modifier.width(34.dp).height(if (thick) 9.dp else 5.dp).background(c, RoundedCornerShape(3.dp)))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 14.sp)
    }
}

// ------------------------------------------------------------------ menu drawer

@Composable
private fun MenuDrawer(state: UiState, engine: Engine, actions: AppActions, onClose: () -> Unit) {
    val context = LocalContext.current
    val lang = Localized.language(context)
    Box(Modifier.fillMaxSize().background(Color(0x59000000)).clickable(onClick = onClose)) {
        Surface(
            Modifier.align(Alignment.TopStart).fillMaxHeight().widthIn(max = 320.dp).fillMaxWidth(0.85f).clickable(enabled = false) {},
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 10.dp))
                fun go(s: Screen) {
                    engine.setScreen(s)
                    onClose()
                }
                if (state.drive.sessionId != null) DrawerItem("🚗 " + stringResource(R.string.drive)) { go(Screen.DRIVE) }
                DrawerItem("🗺 " + stringResource(R.string.prepare)) { go(Screen.PREPARE) }
                DrawerItem("📅 " + stringResource(R.string.campaign_title)) { go(Screen.CAMPAIGN) }
                DrawerItem("🗂 " + stringResource(R.string.sessions)) { go(Screen.SESSIONS) }
                DrawerItem("⚙ " + stringResource(R.string.settings)) { go(Screen.SETTINGS) }
                DrawerItem("🩺 " + stringResource(R.string.diagnostics)) { go(Screen.DIAGNOSTICS) }
                Box(Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                    Segmented(listOf("فارسی" to (lang == "fa"), "English" to (lang == "en"))) { i ->
                        val l = if (i == 0) "fa" else "en"
                        if (l != lang) actions.setLanguage(l)
                    }
                }
                Text(stringResource(R.string.osm_attribution), fontSize = 12.sp, color = mutedColor(), modifier = Modifier.padding(horizontal = 14.dp))
            }
        }
    }
}

@Composable
private fun DrawerItem(text: String, onClick: () -> Unit) {
    Text(text, fontSize = 17.sp, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 12.dp))
}

// ------------------------------------------------------------------ preparation sheet

private enum class Step { AREA, START, ROUTE }

@Composable
private fun PrepareSheet(state: UiState, engine: Engine, actions: AppActions, wide: Boolean, screenH: Dp) {
    val prepared = state.graph.status == "available"
    val step = when {
        !prepared -> Step.AREA
        !state.start.confirmed -> Step.START
        else -> Step.ROUTE
    }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    // A tap on the map picks the start; leave "picking" (compact sheet) once a start is set.
    LaunchedEffect(state.start.requested) { if (state.start.requested != null) picking = false }
    LaunchedEffect(step) { if (step != Step.START) picking = false }
    val peek = !wide && (picking || (step == Step.AREA && !sheetOpen))
    val min = !wide && step != Step.AREA && sheetOpen && !picking

    val box = if (wide) {
        Modifier.padding(start = 12.dp, top = 66.dp, bottom = 12.dp).width(400.dp).fillMaxHeight()
    } else {
        Modifier.fillMaxWidth().heightIn(max = screenH * (if (peek) 0.34f else if (min) 0.30f else 0.62f))
    }
    Box(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.align(if (wide) AbsoluteAlignment.TopLeft else Alignment.BottomCenter).then(box),
            shape = if (wide) RoundedCornerShape(16.dp) else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            shadowElevation = 10.dp, color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                if (!wide) {
                    Box(
                        Modifier.fillMaxWidth().clickable { sheetOpen = !sheetOpen }.padding(vertical = 8.dp).semantics { contentDescription = "sheet" },
                        contentAlignment = Alignment.Center,
                    ) { Box(Modifier.width(64.dp).height(5.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp))) }
                } else {
                    Spacer(Modifier.height(12.dp))
                }
                Stepper(step, prepared, state.start.confirmed, state.plan.status != null, compact = peek)
                if (prepared) {
                    val reqM = state.counts.requiredMeters - state.counts.needsReviewMeters
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            stringResource(R.string.graph_loaded) + " · " + stringResource(R.string.required_short) + " " + fmtMeters(reqM) +
                                (if (state.counts.needsReviewMeters > 0) " · " + stringResource(R.string.needs_review_short) + " " + fmtMeters(state.counts.needsReviewMeters) else ""),
                            fontSize = 14.sp, color = Palette.ok, modifier = Modifier.weight(1f, fill = false),
                        )
                        LinkText(stringResource(R.string.edit_area)) { engine.editArea() }
                    }
                }
                // Scrollable step content; on the Route step "Start driving" stays pinned below it.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    when (step) {
                        Step.AREA -> AreaStep(state, engine, peek)
                        Step.START -> StartStep(state, engine, actions, picking, min) { picking = true }
                        Step.ROUTE -> RouteStep(state, engine, min)
                    }
                }
                if (step == Step.ROUTE) {
                    Spacer(Modifier.height(6.dp))
                    BigButton("▶ " + stringResource(R.string.start_driving), enabled = state.startBlockers.isEmpty(), color = Palette.go) {
                        if (state.labMode) actions.startLabNavigation() else actions.startNavigation()
                    }
                    if (!min && state.startBlockers.isNotEmpty()) {
                        Text(
                            stringResource(R.string.start_disabled) + ": " + state.startBlockers.map { stringResource(it) }.joinToString(" · "),
                            fontSize = 12.sp, color = mutedColor(), modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Stepper(step: Step, areaDone: Boolean, startDone: Boolean, routeDone: Boolean, compact: Boolean) {
    Row(Modifier.fillMaxWidth().padding(bottom = if (compact) 4.dp else 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Triple(Step.AREA, R.string.step_area, areaDone), Triple(Step.START, R.string.step_start, startDone), Triple(Step.ROUTE, R.string.step_route, routeDone))
            .forEachIndexed { i, (k, label, done) ->
                val cur = k == step
                Row(
                    Modifier.weight(1f).background(surface2(), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = if (compact) 4.dp else 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier.size(22.dp).background(if (done) Palette.done else if (cur) Palette.primary else MaterialTheme.colorScheme.outline, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (done) "✓" else fmtInt(i + 1), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    Text(
                        stringResource(label), fontSize = 14.sp, maxLines = 1,
                        color = if (cur) MaterialTheme.colorScheme.onSurface else mutedColor(), fontWeight = if (cur) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
    }
}

@Composable
private fun StatusLine(text: String, color: Color? = null) {
    Text(text, fontSize = 15.sp, color = color ?: MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp), content = content)
}

@Composable
private fun AreaStep(state: UiState, engine: Engine, peek: Boolean) {
    val z = state.zone
    val fetching = state.graph.status == "fetching"
    ButtonRow {
        Segmented(
            listOf("⬠ " + stringResource(R.string.zone_polygon) to (z.mode == ZoneMode.POLYGON), "◯ " + stringResource(R.string.zone_circle) to (z.mode == ZoneMode.CIRCLE)),
        ) { i -> engine.setZoneMode(if (i == 0) ZoneMode.POLYGON else ZoneMode.CIRCLE) }
        if (z.mode == ZoneMode.POLYGON) {
            IconBtn("↶", stringResource(R.string.undo), enabled = z.vertices.isNotEmpty()) { engine.undoVertex() }
            IconBtn("✕", stringResource(R.string.clear), enabled = z.vertices.isNotEmpty()) { engine.clearZone() }
        } else {
            RadiusField(z.radiusMeters) { engine.setRadius(it) }
        }
    }
    val hasZone = if (z.mode == ZoneMode.POLYGON) z.vertices.size >= 3 else z.circleCenter != null
    val hard = z.errors.filter { it != "large_zone" }
    when {
        !hasZone -> StatusLine(stringResource(if (z.mode == ZoneMode.POLYGON) R.string.tap_to_add_vertex else R.string.tap_to_set_center), mutedColor())
        z.valid -> {
            StatusLine(stringResource(R.string.zone_area_line, fmtArea(z.areaSqMeters), fmtMeters(z.perimeterMeters)), Palette.ok)
            if ("large_zone" in z.errors) Text(zoneErrorText("large_zone"), fontSize = 13.sp, color = Palette.warn)
        }
        else -> StatusLine(stringResource(R.string.zone_invalid) + ": " + hard.map { zoneErrorText(it) }.joinToString(" "), MaterialTheme.colorScheme.error)
    }
    BigButton(stringResource(if (fetching) R.string.fetching_roads else R.string.fetch_roads), enabled = z.valid && !fetching) { engine.fetchGraph() }
    if (fetching) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.graph.progress ?: "", fontSize = 12.sp, color = mutedColor(), modifier = Modifier.weight(1f, fill = false))
            LinkText(stringResource(R.string.cancel)) { engine.editArea() }
        }
    }
    if (state.graph.status == "failed") {
        Text(failureText(state.graph.failureCode), color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        state.graph.attempts.takeLast(4).forEach { Text("· $it", fontSize = 12.sp, color = mutedColor()) }
    }
    if (!peek) Text(stringResource(R.string.graph_source_note), fontSize = 12.sp, color = mutedColor(), modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun RadiusField(radius: Double, onChange: (Double) -> Unit) {
    var text by remember(radius) { mutableStateOf(radius.toInt().toString()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.radius_m), fontSize = 14.sp)
        Spacer(Modifier.width(6.dp))
        BasicTextField(
            value = text, singleLine = true,
            onValueChange = { t ->
                text = t.filter { it.isDigit() }.take(5)
                text.toIntOrNull()?.let { if (it > 0) onChange(it.toDouble()) }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.width(80.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun StartStep(state: UiState, engine: Engine, actions: AppActions, picking: Boolean, min: Boolean, onPick: () -> Unit) {
    val st = state.start
    if (!min) {
        ButtonRow {
            SmallButton("📍 " + stringResource(R.string.tap_for_start), primary = !picking, enabled = !state.plan.computing) {
                engine.setTapMode(TapMode.START)
                onPick()
            }
            SmallButton("➤ " + stringResource(R.string.use_my_location)) { actions.useGpsForStart() }
        }
    }
    when {
        st.error == "no_legal_road_nearby" -> StatusLine(stringResource(R.string.start_no_road), MaterialTheme.colorScheme.error)
        st.error == "no_graph" -> StatusLine(stringResource(R.string.blocker_no_graph), MaterialTheme.colorScheme.error)
        st.snappedEdgeId != null -> {
            StatusLine(stringResource(R.string.start_snapped_short, st.roadName ?: stringResource(R.string.unnamed_road), fmtMeters(st.distanceMeters)))
            if (!min) {
                if (!st.inMainComponent) Text(stringResource(R.string.start_not_main), fontSize = 13.sp, color = Palette.warn)
                ButtonRow {
                    SmallButton("⇄ " + stringResource(R.string.flip_direction), enabled = st.hasReverse) { engine.reverseStartDirection() }
                    SmallButton("✓ " + stringResource(R.string.confirm_start), primary = true) { engine.confirmStart(true) }
                }
            }
        }
        else -> StatusLine(stringResource(R.string.tap_hint_start), mutedColor())
    }
}

@Composable
private fun RouteStep(state: UiState, engine: Engine, min: Boolean) {
    val p = state.plan
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusLine(stringResource(R.string.start_at, state.start.roadName ?: "—"))
        if (!min) LinkText(stringResource(R.string.change)) { engine.confirmStart(false) }
    }
    if (min) return
    ButtonRow {
        Segmented(
            listOf(stringResource(R.string.policy_strict_short) to (p.policy == "STRICT_INSIDE"), stringResource(R.string.policy_allow_short) to (p.policy == "ALLOW_NECESSARY_CONNECTORS")),
            enabled = !p.computing,
        ) { i -> engine.setPolicy(if (i == 0) "STRICT_INSIDE" else "ALLOW_NECESSARY_CONNECTORS") }
    }
    CheckRow(state.returnToStart, stringResource(R.string.return_to_start), enabled = !p.computing) { engine.setReturnToStart(it) }
    ButtonRow {
        val has = p.status != null
        SmallButton(
            stringResource(if (p.computing) R.string.planning else if (has) R.string.replan else R.string.plan_route),
            primary = !has, enabled = !p.computing,
        ) { engine.previewPlan() }
        if (p.computing) SmallButton(stringResource(R.string.cancel)) { engine.cancelPlan() }
    }
    if (p.computing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
    if (p.status != null && !p.computing) PlanCard(p, state.outsideApproved) { engine.setOutsideApproved(it) }
    Expander(stringResource(R.string.safety_title)) {
        Text(stringResource(R.string.safety_note), fontSize = 12.sp, color = mutedColor())
        Text(stringResource(R.string.guidance_signs), fontSize = 12.sp, color = mutedColor())
    }
}

@Composable
private fun PlanCard(p: PlanInfo, approved: Boolean, onApprove: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).background(surface2(), RoundedCornerShape(14.dp)).padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi(fmtMeters(p.totalMeters), stringResource(R.string.total_distance))
            Kpi(fmtMeters(p.newCoverageMeters), stringResource(R.string.new_coverage))
            Kpi(fmtMeters(p.repeatMeters), stringResource(R.string.repeats))
            if (p.outsideMeters > 2) Kpi(fmtMeters(p.outsideMeters), stringResource(R.string.outside_zone), Palette.warn)
        }
        val unplanned = p.unreachableByCause.values.sum()
        Text(
            buildString {
                append(planStatusText(p.status)).append(" · ").append(if (p.valid) "✓" else "✗")
            } + (if (unplanned > 0) " · " + stringResource(R.string.unreachable_short) + ": " + fmtInt(unplanned) else "") +
                (if (p.unverifiedTurnarounds > 0) " · " + stringResource(R.string.unverified_turnarounds) + ": " + fmtInt(p.unverifiedTurnarounds) else ""),
            fontSize = 13.sp, color = if (p.valid) Palette.ok else MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp),
        )
        Expander(stringResource(R.string.details)) {
            val causes = p.unreachableByCause.entries.map { (c, n) -> causeText(c) + " " + fmtInt(n) }
            Text(causes.joinToString(" · ").ifEmpty { "—" }, fontSize = 12.sp, color = mutedColor())
            Text(stringResource(R.string.plan_note, p.millis, p.iterations), fontSize = 12.sp, color = mutedColor())
            if (p.unverifiedTurnarounds > 0) Text(stringResource(R.string.plan_unverified_turnarounds, p.unverifiedTurnarounds), fontSize = 12.sp, color = Palette.warn)
            p.errors.forEach { Text("✗ $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
        if (p.outsideMeters > 2) {
            CheckRow(approved, stringResource(R.string.approve_outside, fmtMeters(p.outsideMeters)), color = Palette.warn) { onApprove(it) }
        }
    }
}

@Composable
internal fun RowScope.Kpi(value: String, label: String, color: Color? = null) {
    Column(Modifier.weight(1f)) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = color ?: MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(label, fontSize = 12.sp, color = mutedColor(), maxLines = 2)
    }
}

// ------------------------------------------------------------------ dialogs

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
internal fun ExportDialog(onDismiss: () -> Unit, actions: AppActions) {
    var obs by remember { mutableStateOf(false) }
    var graph by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column {
                CheckRow(obs, stringResource(R.string.export_observations)) { obs = it }
                CheckRow(graph, stringResource(R.string.export_graph)) { graph = it }
                Text(stringResource(R.string.export_privacy), fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); actions.export(obs, graph) }) { Text(stringResource(R.string.export)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
