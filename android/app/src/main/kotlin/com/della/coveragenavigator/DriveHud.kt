// Driving HUD (mirrors web DriveHud/ManeuverCard/LaneStrip): dark maneuver card on top,
// compact bottom bar with progress ring, GPS state, Pause/Resume and a "More" sheet.
package com.della.coveragenavigator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Lane arrow rotation per OSM turn value (degrees), same table as web LANE_ROT. */
internal fun laneRotation(turn: String): Float = when (turn) {
    "left" -> -90f
    "slight_left" -> -45f
    "sharp_left" -> -135f
    "right" -> 90f
    "slight_right" -> 45f
    "sharp_right" -> 135f
    "merge_to_left" -> -30f
    "merge_to_right" -> 30f
    else -> 0f // through, none, reverse
}

@Composable
fun DriveHud(state: UiState, engine: Engine, actions: AppActions, wide: Boolean, onMenu: () -> Unit) {
    val d = state.drive
    var more by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    var confirmBlocked by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        // top: maneuver card (leaves room for the right-side controls)
        Box(Modifier.align(AbsoluteAlignment.TopLeft).padding(start = 10.dp, top = 10.dp, end = 70.dp).widthIn(max = 560.dp)) {
            val n = d.nav
            if (d.status == "active" && n != null && !d.replanning) ManeuverCard(n) else StatusCard(d)
        }
        // bottom: compact drive bar
        Surface(
            modifier = Modifier.align(if (wide) AbsoluteAlignment.BottomLeft else Alignment.BottomCenter)
                .then(if (wide) Modifier.padding(start = 12.dp, bottom = 12.dp).width(560.dp) else Modifier.fillMaxWidth()),
            shape = if (wide) RoundedCornerShape(16.dp) else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            shadowElevation = 10.dp, color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val m = d.metrics
                    val pct = (m?.percentConfirmed ?: 0.0).coerceIn(0.0, 100.0)
                    ProgressRing(pct)
                    Column(Modifier.weight(1f)) {
                        if (m != null) {
                            Text(
                                "${fmtMeters(m.confirmedMeters)} / ${fmtMeters(m.totalRequiredMeters)} ${stringResource(R.string.confirmed_short)}",
                                fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(R.string.remaining_short) + " " + fmtMeters(m.remainingMeters) + " · " +
                                    stringResource(R.string.blocked_short) + " " + fmtMeters(m.blockedMeters) + " · " +
                                    stringResource(R.string.unreachable_short) + " " + fmtMeters(m.unreachableMeters) + " · " +
                                    stringResource(R.string.needs_review_short) + " " + fmtMeters(m.needsReviewMeters),
                                fontSize = 12.sp, color = mutedColor(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        GpsLine(d)
                    }
                    when (d.status) {
                        "active" -> RoundButton("⏸", stringResource(R.string.pause)) { actions.pause() }
                        "paused" -> RoundButton("▶", stringResource(R.string.resume), go = true) { actions.resume() }
                    }
                    RoundButton("⋯", stringResource(R.string.more)) { more = !more }
                }
                d.recoveredNote?.let { Text(stringResource(R.string.recovered_note, it), fontSize = 12.sp, color = Palette.warn, modifier = Modifier.padding(top = 6.dp)) }
                if (d.pendingWrites > 0) Text(stringResource(R.string.storage_pending, d.pendingWrites), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                if (more) {
                    HorizontalDivider(Modifier.padding(top = 10.dp))
                    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (d.status == "active") MoreItem("⛔ " + stringResource(R.string.report_blocked)) { more = false; confirmBlocked = true }
                        MoreItem("🗺 " + stringResource(R.string.prepare)) { more = false; engine.setScreen(Screen.PREPARE) }
                        MoreItem("☰ " + stringResource(R.string.menu)) { more = false; onMenu() }
                        if (d.status != "active") MoreItem("⇪ " + stringResource(R.string.export)) { more = false; showExport = true }
                        if (d.status == "ended") MoreItem("✓ " + stringResource(R.string.close_session)) { more = false; engine.closeSession() }
                        if (d.status != "ended") MoreItem("■ " + stringResource(R.string.end_session), color = Palette.danger) { more = false; confirmEnd = true }
                        Text(stringResource(R.string.guidance_signs), fontSize = 12.sp, color = mutedColor())
                    }
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
    if (confirmBlocked) {
        AlertDialog(
            onDismissRequest = { confirmBlocked = false },
            title = { Text(stringResource(R.string.report_blocked)) },
            text = { Text(stringResource(R.string.legend_blocked)) },
            confirmButton = { TextButton(onClick = { confirmBlocked = false; engine.reportBlocked() }) { Text(stringResource(R.string.report_blocked)) } },
            dismissButton = { TextButton(onClick = { confirmBlocked = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showExport) ExportDialog(onDismiss = { showExport = false }, actions = actions)
}

@Composable
private fun MoreItem(text: String, color: Color? = null, onClick: () -> Unit) {
    Text(
        text, fontSize = 16.sp, color = color ?: MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 46.dp).padding(horizontal = 8.dp, vertical = 12.dp),
    )
}

@Composable
private fun RoundButton(glyph: String, label: String, go: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.size(60.dp).background(if (go) Palette.go else surface2(), RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        val fg = if (go) Color.White else MaterialTheme.colorScheme.onSurface
        Text(glyph, fontSize = 20.sp, color = fg)
        Text(label, fontSize = 11.sp, color = fg, maxLines = 1)
    }
}

@Composable
private fun ProgressRing(pct: Double) {
    val track = MaterialTheme.colorScheme.outline
    Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(54.dp)) {
            val w = 6.dp.toPx()
            val s = Size(size.width - w, size.height - w)
            val o = Offset(w / 2, w / 2)
            drawArc(track, 0f, 360f, false, o, s, style = Stroke(w))
            drawArc(Palette.done, -90f, (pct * 3.6).toFloat(), false, o, s, style = Stroke(w))
        }
        Text(String.format(Locale.getDefault(), "%.0f%%", pct), fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun GpsLine(d: DriveInfo) {
    val g = d.gps
    val ok = g.state == "reliable" && d.status == "active"
    val color = if (ok) Palette.ok else Palette.warn
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(9.dp).background(if (ok) Palette.done else Palette.warn, CircleShape))
        val detail = buildString {
            g.accuracy?.let { append(String.format(Locale.getDefault(), " · ±%.0f m", it)) }
            g.ageMs?.let { append(String.format(Locale.getDefault(), " · %.0f s", it / 1000.0)) }
        }
        Text((if (g.simulated) "LAB" else "GPS") + ": " + (if (d.status == "paused") stringResource(R.string.gps_paused) else gpsStateText(g.state)) + detail, fontSize = 12.sp, color = color, maxLines = 1)
    }
}

/** Card shown instead of a maneuver (paused, replanning, waiting for GPS ...). */
@Composable
private fun StatusCard(d: DriveInfo) {
    val mv = d.maneuver
    val text = when {
        d.status == "paused" -> stringResource(R.string.guidance_paused)
        d.status == "ended" -> stringResource(R.string.guidance_ended)
        d.replanning -> stringResource(R.string.guidance_replanning)
        d.noContinuation -> stringResource(R.string.guidance_no_continuation)
        d.guidanceEvent == "finished" -> stringResource(R.string.guidance_finished)
        mv != null -> (if (mv.type == "turnaround" && mv.turnaroundVerified != true) stringResource(R.string.turnaround_only_if_safe) else maneuverText(mv.type)) + " · " + fmtMeters(mv.distanceMeters)
        d.gps.state != "reliable" -> stringResource(R.string.no_guidance)
        d.guidanceEvent == "off_route_pending" -> stringResource(R.string.guidance_off_route)
        else -> stringResource(R.string.guidance_follow_route)
    }
    Surface(shape = RoundedCornerShape(20.dp), color = Palette.hud, shadowElevation = 6.dp) {
        Text(text, color = Color.White, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
    }
}

/** Maneuver card: arrow, distance, action + street, lane strip, then-chip, approach/approximate notes. */
@Composable
fun ManeuverCard(n: NavInstruction) {
    val now = n.distanceMeters < 40 && n.mode != "to_road"
    val glyph = when {
        n.mode == "to_road" -> "➤"
        n.type == "join" -> "⤴"
        else -> maneuverGlyph(n.type)
    }
    val rot = if (n.mode == "to_road") ((n.relativeBearing ?: n.headBearing ?: 0.0) - 90.0).toFloat() else 0f
    Surface(shape = RoundedCornerShape(20.dp), color = if (now) Palette.hudNow else Palette.hud, shadowElevation = 8.dp) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(glyph, fontSize = 54.sp, color = Color.White, modifier = Modifier.widthIn(min = 56.dp).rotate(rot))
                Column(Modifier.padding(start = 14.dp)) {
                    Text(fmtMeters(n.distanceMeters), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    val action = when {
                        n.mode == "to_road" -> stringResource(R.string.nav_head_to_road)
                        n.type == "join" -> stringResource(R.string.nav_join_route)
                        n.type == "turnaround" && n.turnaroundVerified != true -> stringResource(R.string.voice_turnaround_if_safe)
                        else -> maneuverText(n.type)
                    }
                    Text(
                        action + (n.roadName?.let { " " + stringResource(R.string.nav_onto, it) } ?: ""),
                        fontSize = 18.sp, color = Color.White.copy(alpha = 0.95f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            n.lanes?.let { LaneStrip(it) }
            val showThen = n.thenType != null && n.thenDistanceMeters != null && n.thenDistanceMeters < 400
            if (n.mode == "approach" || showThen || n.approximate) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (showThen) {
                        Text(
                            stringResource(R.string.nav_then) + " " + (if (n.thenType == "join") "⤴" else maneuverGlyph(n.thenType!!)),
                            color = Color.White, fontSize = 15.sp,
                            modifier = Modifier.background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    if (n.mode == "approach" && n.rejoinMeters != null) {
                        Text(stringResource(R.string.nav_go_to_route) + " · " + fmtMeters(n.rejoinMeters), color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp)
                    }
                    if (n.approximate) Text(stringResource(R.string.nav_approximate), color = Color(0xFFFDE68A), fontSize = 15.sp)
                }
            }
        }
    }
}

/** One box per lane (left to right, always LTR); usable lanes bright with a border, others dim. */
@Composable
fun LaneStrip(lanes: List<LaneHint>) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp).background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp)).padding(6.dp)
                .semantics { contentDescription = "lanes" },
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        ) {
            lanes.forEach { l ->
                val fg = if (l.ok) Color.White else Color.White.copy(alpha = 0.35f)
                Box(
                    Modifier.height(40.dp).widthIn(min = 40.dp)
                        .background(if (l.ok) Color.White.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(8.dp))
                        .border(2.dp, if (l.ok) Color.White.copy(alpha = 0.7f) else Color.Transparent, RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        l.turns.forEachIndexed { k, t ->
                            Text(
                                if (t == "reverse") "↶" else "↑", color = fg, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.offset(x = (-10 * k).dp).rotate(laneRotation(t)),
                            )
                        }
                    }
                }
            }
        }
    }
}
