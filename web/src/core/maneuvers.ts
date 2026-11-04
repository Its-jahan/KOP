import type { GraphIndex } from './graph';
import { angleDiff } from './geo';
import type { LonLat, RoutePlan } from './types';

export type ManeuverType =
  | 'depart'
  | 'continue'
  | 'slight_right'
  | 'right'
  | 'sharp_right'
  | 'slight_left'
  | 'left'
  | 'sharp_left'
  | 'turnaround'
  | 'arrive';

export interface Maneuver {
  stepIndex: number;
  type: ManeuverType;
  turnaroundVerified: boolean | null;
  position: LonLat;
  distanceFromStartMeters: number;
  roadName: string | null;
}

export function edgeName(gi: GraphIndex, i: number): string | null {
  const n = gi.edges[i].names;
  return n.default ?? n.hy ?? n.en ?? n.ref ?? null;
}

export function classifyTurn(angle: number): ManeuverType {
  const a = Math.abs(angle);
  if (a < 25) return 'continue';
  if (a < 60) return angle > 0 ? 'slight_right' : 'slight_left';
  if (a < 135) return angle > 0 ? 'right' : 'left';
  return angle > 0 ? 'sharp_right' : 'sharp_left';
}

/** Maneuvers from a validated plan. Continue-on-same-road transitions are omitted. */
export function buildManeuvers(gi: GraphIndex, plan: RoutePlan): Maneuver[] {
  const out: Maneuver[] = [];
  let dist = 0;
  plan.steps.forEach((s, k) => {
    const i = gi.idx.get(s.edgeId)!;
    const e = gi.edges[i];
    if (k === 0) {
      out.push({ stepIndex: 0, type: 'depart', turnaroundVerified: null, position: e.geometry[0], distanceFromStartMeters: 0, roadName: edgeName(gi, i) });
    } else {
      const prev = gi.idx.get(plan.steps[k - 1].edgeId)!;
      let type: ManeuverType;
      if (s.turnaroundAtStart) type = 'turnaround';
      else type = classifyTurn(angleDiff(gi.endBearing[prev], gi.startBearing[i]));
      const sameName = edgeName(gi, prev) === edgeName(gi, i);
      if (!(type === 'continue' && sameName) && !(type.startsWith('slight') && sameName)) {
        out.push({
          stepIndex: k,
          type,
          turnaroundVerified: s.turnaroundAtStart ? s.turnaroundAtStart === 'verified' : null,
          position: e.geometry[0],
          distanceFromStartMeters: dist,
          roadName: edgeName(gi, i),
        });
      }
    }
    dist += s.toOffsetMeters - s.fromOffsetMeters;
  });
  if (plan.steps.length) {
    const last = plan.steps[plan.steps.length - 1];
    const i = gi.idx.get(last.edgeId)!;
    out.push({ stepIndex: plan.steps.length - 1, type: 'arrive', turnaroundVerified: null, position: gi.edges[i].geometry[gi.edges[i].geometry.length - 1], distanceFromStartMeters: dist, roadName: edgeName(gi, i) });
  }
  return out;
}

/** Cumulative distance at the start of each step. */
export function stepStartDistances(plan: RoutePlan): number[] {
  const out: number[] = [];
  let d = 0;
  for (const s of plan.steps) {
    out.push(d);
    d += s.toOffsetMeters - s.fromOffsetMeters;
  }
  return out;
}
