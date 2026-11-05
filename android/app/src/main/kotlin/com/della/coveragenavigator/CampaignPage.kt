// "Monthly plan" page (port of web/src/ui/CampaignPage.tsx): one big area driven over many
// days from a fixed start point. Create form, KPIs, end-of-day review, daily list, history.
package com.della.coveragenavigator

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Unicode first-strong isolate / pop (web <bdi>) so street names keep their own direction. */
private val FSI = Char(0x2068).toString()
private val PDI = Char(0x2069).toString()

/** Parses user input with Latin, Persian or Arabic-Indic digits and either decimal separator. */
internal fun parseLocalNumber(s: String): Double? {
    val sb = StringBuilder()
    for (ch in s.trim()) {
        sb.append(
            when (ch) {
                in '۰'..'۹' -> '0' + (ch - '۰')
                in '٠'..'٩' -> '0' + (ch - '٠')
                '٫', ',', '،' -> '.'
                else -> ch
            },
        )
    }
    return sb.toString().toDoubleOrNull()
}

private fun fmtNum(v: Double, digits: Int = 0): String =
    if (digits == 0) String.format(Locale.getDefault(), "%.0f", v)
    else String.format(Locale.getDefault(), "%.${digits}f", v).let { t -> if (t.contains('.') || t.contains('٫')) t.trimEnd('0', '۰').trimEnd('.', '٫') else t }

/** Short date like web toLocaleDateString (fa-IR: Solar Hijri calendar where available, en-GB: dd/MM/yyyy). */
private fun fmtDate(t: Long, lang: String): String {
    if (lang == "fa" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        return android.icu.text.DateFormat.getDateInstance(android.icu.text.DateFormat.SHORT, android.icu.util.ULocale("fa_IR@calendar=persian")).format(Date(t))
    }
    return SimpleDateFormat("dd/MM/yyyy", if (lang == "fa") Locale("fa") else Locale.UK).format(Date(t))
}

private fun parseIso(s: String): Long = try {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)?.time ?: 0L
} catch (e: Exception) {
    0L
}

@Composable
private fun Card(highlight: androidx.compose.ui.graphics.Color? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .then(if (highlight != null) Modifier.border(2.dp, highlight, shape) else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape))
            .background(surface2(), shape).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
private fun ConfirmDialog(text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun CampaignScreen(state: UiState, engine: Engine, actions: AppActions) {
    val lang = Localized.language(LocalContext.current)
    val c = state.campaign
    Page(stringResource(R.string.campaign_title), state, engine) {
        if (!c.exists) {
            CreateForm(state, engine)
            if (c.error == "corrupt") Text(stringResource(R.string.campaign_corrupt), color = Palette.warn, fontSize = 13.sp)
            return@Page
        }
        var confirmDelete by remember { mutableStateOf(false) }
        var confirmUndo by remember { mutableStateOf(false) }
        if (c.error == "graph_missing") {
            Text(stringResource(R.string.campaign_graph_missing), color = Palette.warn)
        } else if (c.loading) {
            Text(stringResource(R.string.campaign_loading), color = mutedColor())
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
        } else {
            Text(c.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(vertical = 4.dp))
            Card {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Kpi(fmtNum(c.percentDone, 1) + "%", stringResource(R.string.campaign_done))
                    Kpi(fmtMeters(c.doneMeters), stringResource(R.string.campaign_confirmed))
                    Kpi(fmtMeters(c.remainingMeters), stringResource(R.string.campaign_remaining))
                    Kpi(fmtInt(c.days.size), stringResource(R.string.campaign_days_left))
                }
                val finish = System.currentTimeMillis() + maxOf(0, c.days.size - 1) * 86_400_000L
                Text(
                    stringResource(R.string.campaign_settings_line, fmtNum(c.hoursPerDay, 1), fmtNum(c.speedKmh)) + " · " +
                        stringResource(R.string.campaign_finish) + " " + fmtDate(finish, lang),
                    fontSize = 13.sp, color = mutedColor(),
                )
                if (c.unplannable > 0) Text(stringResource(R.string.campaign_unplannable, fmtInt(c.unplannable)), fontSize = 13.sp, color = Palette.warn)
            }

            c.review?.let { ReviewCard(c, it, engine) }

            Text(stringResource(R.string.campaign_days), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            c.days.forEachIndexed { i, d ->
                Card(highlight = if (i == 0) Palette.primary else null) {
                    Text(
                        (if (i == 0) stringResource(R.string.campaign_today) + " · " else "") + stringResource(R.string.campaign_day) + " " + fmtInt(d.dayNo),
                        fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    )
                    Text(
                        fmtMeters(d.totalMeters) + " · ~" + fmtNum(Math.round(d.minutes / 6.0) / 10.0, 1) + " " + stringResource(R.string.campaign_hours_short) +
                            " · " + fmtInt(d.segments) + " " + stringResource(R.string.campaign_segments),
                        fontSize = 13.sp, color = mutedColor(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        SmallButton("🗺 " + stringResource(R.string.campaign_show)) { engine.showCampaignDay(i) }
                        if (i == 0) SmallButton("▶ " + stringResource(R.string.campaign_drive_today), primary = true) { actions.startCampaignDay(0) }
                    }
                }
            }
            if (c.days.isEmpty()) Text(stringResource(R.string.campaign_all_done), color = Palette.ok, modifier = Modifier.padding(vertical = 6.dp))

            if (c.history.isNotEmpty()) {
                Text(stringResource(R.string.campaign_history), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                c.history.forEach { h -> Text("• " + fmtDate(parseIso(h.date), lang) + " · " + fmtMeters(h.meters), fontSize = 13.sp) }
                SmallButton("↶ " + stringResource(R.string.campaign_undo), modifier = Modifier.padding(top = 4.dp)) { confirmUndo = true }
            }
        }
        TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 12.dp)) {
            Text(stringResource(R.string.campaign_delete), color = Palette.danger)
        }
        if (confirmUndo) ConfirmDialog(stringResource(R.string.campaign_undo_confirm), stringResource(R.string.campaign_undo), { engine.undoCampaignDay() }) { confirmUndo = false }
        if (confirmDelete) ConfirmDialog(stringResource(R.string.campaign_delete_confirm), stringResource(R.string.campaign_delete), { engine.deleteCampaign() }) { confirmDelete = false }
    }
}

@Composable
private fun CreateForm(state: UiState, engine: Engine) {
    var name by rememberSaveable { mutableStateOf("") }
    var hours by rememberSaveable { mutableStateOf("4") }
    var speed by rememberSaveable { mutableStateOf("14") }
    val canCreate = state.graph.status == "available" && state.graph.dataState == "available" && state.start.confirmed
    val h = parseLocalNumber(hours)
    val v = parseLocalNumber(speed)
    val defaultName = stringResource(R.string.campaign_title)
    Text(stringResource(R.string.campaign_intro), fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
    OutlinedTextField(
        value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.campaign_name)) },
        placeholder = { Text(stringResource(R.string.campaign_name_ph)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
    OutlinedTextField(
        value = hours, onValueChange = { hours = it }, label = { Text(stringResource(R.string.campaign_hours_per_day)) }, singleLine = true,
        isError = h == null || h <= 0, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
    OutlinedTextField(
        value = speed, onValueChange = { speed = it }, label = { Text(stringResource(R.string.campaign_avg_speed)) }, singleLine = true,
        isError = v == null || v <= 0, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
    BigButton(
        stringResource(R.string.campaign_create), modifier = Modifier.padding(top = 8.dp),
        enabled = canCreate && h != null && h > 0 && v != null && v > 0,
    ) { engine.createCampaign(h!!, v!!, name.trim().ifEmpty { defaultName }) }
    if (!canCreate) Text(stringResource(R.string.campaign_create_hint), fontSize = 13.sp, color = mutedColor(), modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ReviewCard(c: CampaignUi, r: CampaignReviewUi, engine: Engine) {
    var unchecked by remember(r.sessionId) { mutableStateOf(setOf<String>()) }
    Card(highlight = Palette.done) {
        val label = c.name + (r.day?.let { " · " + stringResource(R.string.campaign_day) + " " + fmtInt(it) } ?: "")
        Text(stringResource(R.string.campaign_review_title) + " · " + label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        if (r.groups.isEmpty()) {
            Text(stringResource(R.string.campaign_review_none), fontSize = 13.sp, color = mutedColor())
        } else {
            val all = r.groups.flatMap { it.requirementIds }
            val kept = r.groups.sumOf { g -> if (g.requirementIds.all { it !in unchecked }) g.meters else 0.0 }
            Text(stringResource(R.string.campaign_review_text, fmtInt(all.size - unchecked.size), fmtMeters(kept)), fontSize = 13.sp)
            Expander(stringResource(R.string.campaign_review_list)) {
                r.groups.forEach { g ->
                    val on = g.requirementIds.all { it !in unchecked }
                    CheckRow(on, FSI + g.name + PDI + " · " + fmtMeters(g.meters)) { checked ->
                        unchecked = if (checked) unchecked - g.requirementIds.toSet() else unchecked + g.requirementIds
                    }
                }
            }
        }
        BigButton("✓ " + stringResource(R.string.campaign_confirm), color = Palette.go, modifier = Modifier.padding(top = 4.dp)) {
            engine.confirmCampaignDay(unchecked)
        }
    }
}
