// Revision-safe plan acceptance (revision.ts), event-log recovery (recovery.ts) and
// failure-state mapping (states.ts).
package della.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

// ---------------- revision.ts ----------------

data class PlanningContext(
    val graphHash: String,
    val zoneVersion: Int,
    val latestRequestedPlanningRevision: Long,
    val acceptedPlanningRevision: Long,
    val progressRevision: Long,
)

data class PlanResultMeta(
    val graphHash: String,
    val zoneVersion: Int,
    val planningRevision: Long,
    val basedOnProgressRevision: Long,
    val status: String,
)

/** accept=true carries reconcile; accept=false carries reason. */
data class PlanDecision(val accept: Boolean, val reconcile: Boolean? = null, val reason: String? = null)

/**
 * A result is accepted only if it answers the newest request for the same graph and
 * zone. If progress advanced while computing, it is accepted with `reconcile` so the
 * caller skips steps whose requirements are already confirmed.
 */
fun decidePlan(ctx: PlanningContext, r: PlanResultMeta): PlanDecision {
    if (r.graphHash != ctx.graphHash) return PlanDecision(false, reason = "graph_mismatch")
    if (r.zoneVersion != ctx.zoneVersion) return PlanDecision(false, reason = "zone_mismatch")
    if (r.planningRevision <= ctx.acceptedPlanningRevision) return PlanDecision(false, reason = "older_than_accepted")
    if (r.planningRevision < ctx.latestRequestedPlanningRevision) return PlanDecision(false, reason = "superseded")
    if (r.status == "failed") return PlanDecision(false, reason = "failed")
    if (r.status == "cancelled") return PlanDecision(false, reason = "cancelled")
    return PlanDecision(true, reconcile = r.basedOnProgressRevision < ctx.progressRevision)
}

// ---------------- recovery.ts ----------------

data class Checkpoint(val sessionId: String, val seq: Long, val checksum: String)

class StorageImage(val checkpoint: Checkpoint?, val eventRecords: List<String>)

data class DiscardedRecord(val index: Int, val reason: String)

class RecoveredLog(val events: List<SessionEvent>, val discarded: List<DiscardedRecord>, val lastValidSeq: Long)

private fun isEventShape(x: JsonElement): Boolean {
    val e = x as? JsonObject ?: return false
    fun isStr(k: String) = (e[k] as? JsonPrimitive)?.isString == true
    val seq = (e["seq"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return false
    if (seq != Math.rint(seq) || seq.isInfinite()) return false
    val payload = e["payload"]
    return isStr("eventId") && isStr("sessionId") && isStr("type") && (payload is JsonObject || payload is JsonArray)
}

/**
 * Keep the longest valid prefix of strictly increasing events for the session.
 * A corrupt record or a sequence break ends the valid log; later records are
 * discarded (never partially applied). Duplicate eventIds are dropped.
 */
fun recoverEventLog(sessionId: String, img: StorageImage): RecoveredLog {
    val events = ArrayList<SessionEvent>()
    val discarded = ArrayList<DiscardedRecord>()
    val ids = HashSet<String>()
    var last = 0L
    var broken = false
    img.eventRecords.forEachIndexed { index, raw ->
        if (broken) {
            discarded.add(DiscardedRecord(index, "after_corruption"))
            return@forEachIndexed
        }
        val parsed = try {
            ContractJson.parse(raw)
        } catch (e: Exception) {
            discarded.add(DiscardedRecord(index, "corrupt_record"))
            broken = true
            return@forEachIndexed
        }
        if (!isEventShape(parsed)) {
            discarded.add(DiscardedRecord(index, "invalid_record"))
            broken = true
            return@forEachIndexed
        }
        val o = parsed as JsonObject
        val evSession = (o["sessionId"] as JsonPrimitive).content
        if (evSession != sessionId) {
            discarded.add(DiscardedRecord(index, "foreign_session"))
            return@forEachIndexed
        }
        val eventId = (o["eventId"] as JsonPrimitive).content
        if (ids.contains(eventId)) {
            discarded.add(DiscardedRecord(index, "duplicate_event"))
            return@forEachIndexed
        }
        val seq = (o["seq"] as JsonPrimitive).doubleOrNull!!
        if (seq != (last + 1).toDouble()) {
            discarded.add(DiscardedRecord(index, "sequence_gap"))
            broken = true
            return@forEachIndexed
        }
        val ev = SessionEvent(
            eventId = eventId,
            seq = seq.toLong(),
            sessionId = evSession,
            type = (o["type"] as JsonPrimitive).content,
            timestamp = (o["timestamp"] as? JsonPrimitive)?.doubleOrNull?.let { Math.round(it) } ?: 0L,
            schemaVersion = (o["schemaVersion"] as? JsonPrimitive)?.content ?: SCHEMA_VERSION,
            payload = (o["payload"] as? JsonObject) ?: JsonObject(emptyMap()),
        )
        ids.add(eventId)
        events.add(ev)
        last = ev.seq
    }
    return RecoveredLog(events, discarded, last)
}

// ---------------- states.ts ----------------

sealed class FailureInput {
    object LocationPermissionDenied : FailureInput()
    object LocationDisabled : FailureInput()
    object LocationUnavailable : FailureInput()
    object LocationTimeout : FailureInput()
    data class GraphHttp(val status: Int) : FailureInput()
    object GraphNetworkError : FailureInput()
    object GraphTimeout : FailureInput()
    object GraphEmpty : FailureInput()
    object GraphIncomplete : FailureInput()
    object ProviderNotConfigured : FailureInput()
    object CredentialsMissing : FailureInput()
    data class GraphTooLarge(val edges: Int, val limit: Int) : FailureInput()

    companion object {
        fun fromJson(o: JsonObject): FailureInput {
            fun s(k: String) = (o[k] as JsonPrimitive).content
            return when (s("kind")) {
                "location_permission_denied" -> LocationPermissionDenied
                "location_disabled" -> LocationDisabled
                "location_unavailable" -> LocationUnavailable
                "location_timeout" -> LocationTimeout
                "graph_http" -> GraphHttp(s("status").toDouble().toInt())
                "graph_network_error" -> GraphNetworkError
                "graph_timeout" -> GraphTimeout
                "graph_empty" -> GraphEmpty
                "graph_incomplete" -> GraphIncomplete
                "provider_not_configured" -> ProviderNotConfigured
                "credentials_missing" -> CredentialsMissing
                "graph_too_large" -> GraphTooLarge(s("edges").toDouble().toInt(), s("limit").toDouble().toInt())
                else -> throw IllegalArgumentException("unknown failure kind")
            }
        }
    }
}

data class StateOutcome(
    /** permission_denied | location_disabled | acquiring | null */
    val gpsState: String?,
    val dataState: String?,
    val canStartNavigation: Boolean,
    val code: String,
)

fun mapFailure(f: FailureInput): StateOutcome = when (f) {
    FailureInput.LocationPermissionDenied -> StateOutcome("permission_denied", null, false, "gps_permission_denied")
    FailureInput.LocationDisabled -> StateOutcome("location_disabled", null, false, "gps_disabled")
    FailureInput.LocationUnavailable -> StateOutcome("acquiring", null, false, "gps_unavailable")
    FailureInput.LocationTimeout -> StateOutcome("acquiring", null, false, "gps_timeout")
    is FailureInput.GraphHttp -> when (f.status) {
        429 -> StateOutcome(null, "failed", false, "provider_quota_exceeded")
        401, 403 -> StateOutcome(null, "credentials_missing", false, "provider_auth")
        504 -> StateOutcome(null, "failed", false, "provider_timeout")
        else -> StateOutcome(null, "failed", false, "provider_http_${f.status}")
    }
    FailureInput.GraphNetworkError -> StateOutcome(null, "failed", false, "network_unavailable")
    FailureInput.GraphTimeout -> StateOutcome(null, "failed", false, "provider_timeout")
    FailureInput.GraphIncomplete -> StateOutcome(null, "incomplete", false, "provider_partial_response")
    FailureInput.GraphEmpty -> StateOutcome(null, "area_unsupported", false, "no_roads_in_area")
    FailureInput.ProviderNotConfigured -> StateOutcome(null, "not_configured", false, "provider_not_configured")
    FailureInput.CredentialsMissing -> StateOutcome(null, "credentials_missing", false, "credentials_missing")
    is FailureInput.GraphTooLarge -> StateOutcome(null, "incomplete", false, "graph_exceeds_tested_limit")
}
