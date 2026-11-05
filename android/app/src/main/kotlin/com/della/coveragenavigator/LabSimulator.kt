// LAB MODE – simulated. Debug builds only, explicitly enabled by the user before a session
// starts, never selected automatically. Produces 1 Hz fixes (source = simulated_test) that
// drive along the current valid plan so the UI and pipeline can be exercised while parked.
// A lab session never accepts live fixes and a live session never accepts simulated ones.
package com.della.coveragenavigator

import della.core.GraphIndex
import della.core.LocationObservation
import della.core.RoutePlan
import della.core.bearingAtOffset
import della.core.pointAtOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LabSimulator(private val scope: CoroutineScope, private val source: () -> Pair<RoutePlan?, GraphIndex>) {
    private var job: Job? = null
    private val speedMps = 8.0

    fun start(emit: (LocationObservation) -> Unit) {
        if (!BuildConfig.DEBUG) return
        job?.cancel()
        job = scope.launch {
            var planId: String? = null
            var step = 0
            var along = 0.0
            var t = System.currentTimeMillis()
            while (isActive) {
                delay(1000)
                t += 1000
                val (plan, gi) = source()
                if (plan == null || plan.steps.isEmpty()) continue
                if (plan.planId != planId) {
                    planId = plan.planId
                    step = 0
                    along = plan.steps[0].fromOffsetMeters
                }
                var remaining = speedMps
                while (remaining > 0 && step < plan.steps.size) {
                    val s = plan.steps[step]
                    val left = s.toOffsetMeters - along
                    if (left > remaining) {
                        along += remaining
                        remaining = 0.0
                    } else {
                        remaining -= left
                        step++
                        along = plan.steps.getOrNull(step)?.fromOffsetMeters ?: 0.0
                    }
                }
                if (step >= plan.steps.size) continue
                val s = plan.steps[step]
                val i = gi.idx[s.edgeId] ?: continue
                val p = pointAtOffset(gi.edges[i].geometry, gi.cum[i], along)
                emit(
                    LocationObservation(
                        observationId = "lab$t", timestamp = t, monotonicMs = null, lon = p.lon, lat = p.lat,
                        horizontalAccuracyMeters = 5.0, speedMps = speedMps, speedAccuracyMps = null,
                        bearingDeg = bearingAtOffset(gi.edges[i].geometry, gi.cum[i], along), bearingAccuracyDeg = null,
                        altitudeMeters = null, altitudeAccuracyMeters = null, source = "simulated_test", ageMs = 0.0,
                    ),
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
