// Plan cursor tracking and persistent-deviation detection with hysteresis.

import type { GraphIndex } from './graph';
import { projectOnPolyline, cumulativeLengths } from './geo';
import type { LonLat, RoutePlan } from './types';

export interface GuidanceConfig {
  /** Planned distance ahead of the current step that still counts as on-route. */
  lookaheadMeters: number;
  lookaheadSteps: number;
  offRouteFixes: number;
  offRouteDistanceM: number;
  offsetSlackM: number;
}

export const DEFAULT_GUIDANCE_CONFIG: GuidanceConfig = {
  lookaheadMeters: 120,
  lookaheadSteps: 12,
  offRouteFixes: 3,
  offRouteDistanceM: 25,
  offsetSlackM: 10,
};

export interface GuidanceState {
  planId: string;
  cursor: number;
  offRouteCount: number;
}

export type GuidanceEvent = 'on_route' | 'off_route_pending' | 'deviation' | 'finished' | 'no_fix';

export function initialGuidance(plan: RoutePlan): GuidanceState {
  return { planId: plan.planId, cursor: 0, offRouteCount: 0 };
}

export function updateGuidance(
  gi: GraphIndex,
  plan: RoutePlan,
  st: GuidanceState,
  matched: { edgeId: string; offset: number; point: LonLat; committed: boolean } | null,
  reliable: boolean,
  cfg: GuidanceConfig = DEFAULT_GUIDANCE_CONFIG,
): { state: GuidanceState; event: GuidanceEvent } {
  if (!matched || !reliable) return { state: st, event: 'no_fix' };
  const steps = plan.steps;
  // Window: current step, the next step, and further steps starting within lookaheadMeters.
  let end = Math.min(steps.length - 1, st.cursor + 1);
  let acc = 0;
  for (let k = st.cursor; k < steps.length - 1 && k < st.cursor + cfg.lookaheadSteps; k++) {
    acc += steps[k].toOffsetMeters - steps[k].fromOffsetMeters;
    if (acc > cfg.lookaheadMeters) break;
    end = Math.max(end, k + 1);
  }
  for (let k = st.cursor; k <= end; k++) {
    const s = steps[k];
    if (s.edgeId === matched.edgeId && matched.offset >= s.fromOffsetMeters - cfg.offsetSlackM && matched.offset <= s.toOffsetMeters + cfg.offsetSlackM) {
      const finished = k === steps.length - 1 && matched.offset >= s.toOffsetMeters - cfg.offsetSlackM;
      return { state: { ...st, cursor: k, offRouteCount: 0 }, event: finished ? 'finished' : 'on_route' };
    }
  }
  // Not on a window edge. Wrong direction on the same physical road is always off-route.
  const mi = gi.idx.get(matched.edgeId);
  let wrongDirection = false;
  let minDist = Infinity;
  for (let k = st.cursor; k <= end; k++) {
    const si = gi.idx.get(steps[k].edgeId)!;
    if (mi !== undefined && gi.edges[si].physicalSegmentId === gi.edges[mi].physicalSegmentId) wrongDirection = true;
    const g = gi.edges[si].geometry;
    const d = projectOnPolyline(g, cumulativeLengths(g), matched.point).distance;
    if (d < minDist) minDist = d;
  }
  if (!matched.committed) return { state: st, event: 'off_route_pending' };
  if (!wrongDirection && minDist <= cfg.offRouteDistanceM) return { state: st, event: 'off_route_pending' };
  const count = st.offRouteCount + 1;
  if (count >= cfg.offRouteFixes) return { state: { ...st, offRouteCount: count }, event: 'deviation' };
  return { state: { ...st, offRouteCount: count }, event: 'off_route_pending' };
}
