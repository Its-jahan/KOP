// Full-screen pages opened from the menu drawer: Sessions (recovery, export/import),
// Settings (language, legend, about) and Diagnostics (provenance, GPS, lab mode in debug).
package com.della.coveragenavigator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
private fun Page(title: String, state: UiState, engine: Engine, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconBtn("←", stringResource(R.string.back)) { engine.setScreen(if (state.drive.sessionId != null) Screen.DRIVE else Screen.PREPARE) }
            Spacer(Modifier.width(8.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        }
        HorizontalDivider()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp).widthIn(max = 720.dp),
            content = content,
        )
    }
}

@Composable
fun SessionsScreen(state: UiState, engine: Engine, actions: AppActions) {
    var showExport by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { engine.recheckRecovery() }
    val df = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    Page(stringResource(R.string.sessions), state, engine) {
        val d = state.drive
        Section(stringResource(R.string.current_session)) {
            if (d.sessionId == null) {
                Text("—", color = mutedColor())
            } else {
                Text("${d.sessionId} · ${d.status}", fontSize = 13.sp)
                d.metrics?.let { Text(stringResource(R.string.progress_line, fmtMeters(it.confirmedMeters), fmtMeters(it.totalRequiredMeters), it.percentConfirmed), fontSize = 14.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    SmallButton(stringResource(R.string.drive), primary = true) { engine.setScreen(Screen.DRIVE) }
                    if (d.status != "active") SmallButton(stringResource(R.string.export)) { showExport = true }
                    if (d.status == "ended") SmallButton(stringResource(R.string.close_session)) { engine.closeSession() }
                }
            }
        }
        val r = state.recovery
        Section(stringResource(R.string.recovery_title)) {
            if (r == null || d.sessionId != null) {
                Text(stringResource(R.string.no_sessions), color = mutedColor(), fontSize = 14.sp)
            } else {
                Text(stringResource(R.string.recovery_text, df.format(java.util.Date(r.createdAt)), df.format(java.util.Date(r.updatedAt))) + if (r.lab) "\n" + stringResource(R.string.lab_banner) else "", fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    SmallButton(stringResource(R.string.recovery_resume), primary = true) { engine.resumeRecovered() }
                    SmallButton(stringResource(R.string.recovery_end)) { engine.endRecovered() }
                }
            }
        }
        Section(stringResource(R.string.diag_data)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(stringResource(R.string.import_session), enabled = d.sessionId == null) { actions.import() }
                SmallButton(stringResource(R.string.delete_all), enabled = d.status != "active") { confirmDelete = true }
            }
            Text(stringResource(R.string.export_privacy), fontSize = 12.sp, color = mutedColor())
        }
    }
    if (showExport) ExportDialog(onDismiss = { showExport = false }, actions = actions)
    if (confirmDelete) DeleteDialog(engine) { confirmDelete = false }
}

@Composable
private fun DeleteDialog(engine: Engine, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_all)) },
        text = { Text(stringResource(R.string.delete_confirm_text)) },
        confirmButton = { TextButton(onClick = { onDismiss(); engine.deleteAllData() }) { Text(stringResource(R.string.delete_all)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun DiagnosticsScreen(state: UiState, engine: Engine, actions: AppActions) {
    var confirmDelete by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    val g = state.graph
    Page(stringResource(R.string.diagnostics), state, engine) {
        Section(stringResource(R.string.diag_provenance)) {
            Text(stringResource(R.string.diag_provider_line, g.providerId ?: "—", g.dataState))
            Text("graphId: ${g.graphId ?: "—"}")
            Text("contentHash: ${g.contentHash ?: "—"}", fontSize = 12.sp)
            Text(stringResource(R.string.diag_source_version, g.sourceVersion ?: "—"))
            Text(stringResource(R.string.diag_fetched, g.fetchedAt ?: "—", g.endpoint ?: "—"))
            Text(stringResource(R.string.graph_ok, g.edgeCount, g.nodeCount, g.restrictionCount))
            Text(stringResource(R.string.diag_eligibility, g.eligibleEdges, g.excludedEdges, g.needsReviewEdges))
            Text(stringResource(R.string.count_required, state.counts.required, fmtMeters(state.counts.requiredMeters)), fontSize = 13.sp)
            Text(stringResource(R.string.count_needs_review, state.counts.needsReview, fmtMeters(state.counts.needsReviewMeters)), fontSize = 13.sp)
            Text(stringResource(R.string.count_unreachable, state.counts.unreachable, fmtMeters(state.counts.unreachableMeters)), fontSize = 13.sp)
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
    if (confirmDelete) DeleteDialog(engine) { confirmDelete = false }
    if (showExport) ExportDialog(onDismiss = { showExport = false }, actions = actions)
}

@Composable
fun SettingsScreen(state: UiState, engine: Engine, actions: AppActions) {
    val context = LocalContext.current
    val lang = Localized.language(context)
    Page(stringResource(R.string.settings), state, engine) {
        Section(stringResource(R.string.language)) {
            Segmented(listOf("فارسی" to (lang == "fa"), "English" to (lang == "en"))) { i ->
                val l = if (i == 0) "fa" else "en"
                if (l != lang) actions.setLanguage(l)
            }
        }
        Section(stringResource(R.string.legend)) {
            LegendPopover(Modifier.fillMaxWidth(), null)
        }
        Text(stringResource(R.string.about_text), fontSize = 13.sp)
        Text(stringResource(R.string.safety_note), fontSize = 12.sp, color = mutedColor(), modifier = Modifier.padding(top = 8.dp))
        Text("v" + BuildConfig.VERSION_NAME, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}
