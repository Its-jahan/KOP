// Turn-by-turn guidance, independent of coverage crediting (port of the Android Navigator).
//
// Coverage credit stays strict (matcher). Guidance is available from any reasonable fix:
// on the planned route it announces the next maneuvers; away from the route (before reaching
// the start, or after a wrong turn) it computes a legal approach path back to the route; off
// any mapped road it points towards the route.

import type { GraphIndex } from './graph';
import { angleDiff, bearing, bearingAtOffset, haversine, pointAtOffset, slicePolyline } from './geo';
import { classifyTurn, edgeName, type Maneuver } from './maneuvers';
import { legalShortestPath } from './planner';
import type { LocationObservation, LonLat, RoutePlan } from './types';

export interface NavInstruction {
  mode: 'route' | 'approach' | 'to_road';
  /** maneuver type (continue, left, right, ..., turnaround, arrive, join) or 'head' */
  type: string;
  roadName: string | null;
  distanceMeters: number;
  thenType: string | null;
  thenRoadName: string | null;
  thenDistanceMeters: number | null;
  rejoinMeters: number | null;
  headBearing: number | null;
  relativeBearing: number | null;
  turnaroundVerified: boolean | null;
  approximate: boolean;
  /** Next ~1.5 km of the guided path, for highlighting. */
  ahead: LonLat[][];
  /** Stable key of the upcoming maneuver (voice de-duplication). */
  key: string;
  /** Lane guidance for the next maneuver (left to right), when lanes are mapped. */
  lanes: LaneHint[] | null;
}

export interface LaneHint {
  turns: string[];
  /** This lane can be used for the next maneuver. */
  ok: boolean;
}

const RIGHT = ['right', 'slight_right', 'sharp_right'];
const LEFT = ['left', 'slight_left', 'sharp_left'];

/**
 * Which lanes serve a maneuver. Exact family first (slight_right: slight_right/right), then a
 * conservative fallback for slight turns (through). Unknown match → null (never guess).
 */
export function laneHints(lanes: string[][] | undefined, maneuver: string): LaneHint[] | null {
  if (!lanes || lanes.length < 2) return null;
  let wanted: string[][];
  if (maneuver === 'continue') wanted = [['through', 'none']];
  else if (maneuver === 'right' || maneuver === 'sharp_right') wanted = [RIGHT];
  else if (maneuver === 'slight_right') wanted = [['slight_right', 'right'], ['through']];
  else if (maneuver === 'left' || maneuver === 'sharp_left') wanted = [LEFT];
  else if (maneuver === 'slight_left') wanted = [['slight_left', 'left'], ['through']];
  else if (maneuver === 'turnaround') wanted = [['reverse']];
  else return null;
  for (const w of wanted) {
    const hints = lanes.map((t) => ({ turns: t, ok: t.some((x) => w.includes(x)) }));
    if (hints.some((h) => h.ok) && hints.some((h) => !h.ok)) return hints;
    if (hints.every((h) => h.ok)) return null; // every lane works: nothing to advise
  }
  return null;
}

/** Lanes of the road approaching a junction: the incoming edge, or the one before a short link. */
function approachLanes(gi: GraphIndex, incoming: number[]): string[][] | undefined {
  let walked = 0;
  for (const e of incoming) {
    const l = gi.edges[e].turnLanes;
    if (l) return l;
    walked += gi.length(e);
    if (walked > 60) break;
  }
  return undefined;
}

interface Seg {
  edge: number;
  from: number;
  to: number;
  turnaround: 'verified' | 'unverified' | null;
}

const base = (p: Partial<NavInstruction> & Pick<NavInstruction, 'mode' | 'type' | 'distanceMeters' | 'key'>): NavInstruction => ({
  roadName: null, thenType: null, thenRoadName: null, thenDistanceMeters: null, rejoinMeters: null,
  headBearing: null, relativeBearing: null, turnaroundVerified: null, approximate: false, ahead: [], lanes: null, ...p,
});

export class Navigator {
  /** Furthest plan step reached by guidance (only moves forward). */
  cursor = 0;
  private planId: string | null = null;
  private approach: Seg[] | null = null;
  private approachTargetStep = -1;
  private approachEdge = -1;
  private approachAt = 0;

  reset() {
    this.cursor = 0;
    this.planId = null;
    this.approach = null;
  }

  compute(
    gi: GraphIndex,
    plan: RoutePlan | null,
    maneuvers: Maneuver[],
    stepStart: number[],
    fix: LocationObservation | null,
    fixAgeMs: number,
    nowMs: number,
  ): NavInstruction | null {
    if (!plan || plan.steps.length === 0 || !fix) return null;
    if (fixAgeMs > 8000) return null;
    const acc = fix.horizontalAccuracyMeters;
    if (acc === null || acc > 60) return null;
    if (plan.planId !== this.planId) {
      this.planId = plan.planId;
      this.cursor = 0;
      this.approach = null;
    }
    const p: LonLat = [fix.lon, fix.lat];
    const heading = (fix.speedMps ?? 0) >= 2.5 ? fix.bearingDeg : null;
    const approximate = acc > 25;

    // 1) On the planned route? Search a window ahead of the cursor.
    let bestK = -1, bestOff = 0, bestD = Infinity, acc0 = 0;
    for (let k = Math.max(0, this.cursor - 2); k < plan.steps.length && acc0 < 2500; k++) {
      const s = plan.steps[k];
      const i = gi.idx.get(s.edgeId);
      if (i !== undefined) {
        const pr = gi.project(i, p);
        const off = Math.min(Math.max(pr.offset, s.fromOffsetMeters), s.toOffsetMeters);
        const d = pr.offset < s.fromOffsetMeters - 1 || pr.offset > s.toOffsetMeters + 1 ? pr.distance + 5 : pr.distance;
        let ok = true;
        if (heading !== null && Math.abs(angleDiff(bearingAtOffset(gi.edges[i].geometry, gi.cum[i], off), heading)) > 100) ok = false;
        if (ok && d < bestD - 3) {
          bestD = d;
          bestK = k;
          bestOff = off;
        }
      }
      acc0 += s.toOffsetMeters - s.fromOffsetMeters;
    }
    if (bestK >= 0 && bestD <= Math.max(25, Math.min(acc, 40))) {
      this.cursor = Math.max(this.cursor, bestK);
      this.approach = null;
      const along = stepStart[bestK] + (bestOff - plan.steps[bestK].fromOffsetMeters);
      const upcoming = maneuvers.filter((m) => m.stepIndex > bestK && m.type !== 'depart' && m.distanceFromStartMeters > along + 1);
      const m = upcoming[0];
      const n = upcoming[1];
      const ahead = planAhead(gi, plan, bestK, bestOff, 1500);
      if (!m) {
        return base({ mode: 'route', type: 'continue', roadName: edgeName(gi, gi.idx.get(plan.steps[bestK].edgeId)!), distanceMeters: 0, approximate, ahead, key: `${plan.planId}:end` });
      }
      const incoming: number[] = [];
      for (let k = m.stepIndex - 1; k >= Math.max(0, m.stepIndex - 4); k--) incoming.push(gi.idx.get(plan.steps[k].edgeId)!);
      return base({
        lanes: laneHints(approachLanes(gi, incoming), m.type),
        mode: 'route', type: m.type, roadName: m.roadName, distanceMeters: m.distanceFromStartMeters - along,
        thenType: n?.type ?? null, thenRoadName: n?.roadName ?? null, thenDistanceMeters: n ? n.distanceFromStartMeters - m.distanceFromStartMeters : null,
        turnaroundVerified: m.turnaroundVerified, approximate, ahead, key: `${plan.planId}:${m.stepIndex}`,
      });
    }

    // 2) Not on the route: guide back to the planned route at the cursor step.
    const allow = (i: number) => gi.edges[i].eligibility === 'eligible';
    const cands = gi.near(p, 80).filter(allow).map((i) => ({ i, pr: gi.project(i, p) })).filter((c) => c.pr.distance <= 80);
    const targetStep = Math.min(this.cursor, plan.steps.length - 1);
    const tgt = plan.steps[targetStep];
    const tgtEdge = gi.idx.get(tgt.edgeId)!;
    const toRoute = (key: string): NavInstruction => {
      const tp = pointAtOffset(gi.edges[tgtEdge].geometry, gi.cum[tgtEdge], tgt.fromOffsetMeters);
      const b = bearing(p, tp);
      return base({ mode: 'to_road', type: 'head', distanceMeters: haversine(p, tp), headBearing: b, relativeBearing: heading !== null ? angleDiff(heading, b) : null, approximate, ahead: [[p, tp]], key });
    };
    if (cands.length === 0) return toRoute('to_road');
    const minD = Math.min(...cands.map((c) => c.pr.distance));
    const near = cands.filter((c) => c.pr.distance <= minD + 8);
    const reuse = this.approach !== null && this.approachTargetStep === targetStep && near.some((c) => c.i === this.approachEdge) && nowMs - this.approachAt < 6000;
    if (!reuse) {
      let best: Seg[] | null = null;
      let bestCost = Infinity;
      for (const { i: e, pr } of near) {
        if (heading !== null && Math.abs(angleDiff(bearingAtOffset(gi.edges[e].geometry, gi.cum[e], pr.offset), heading)) > 100) continue;
        if (e === tgtEdge && pr.offset <= tgt.fromOffsetMeters + 5) {
          const c = Math.max(0, tgt.fromOffsetMeters - pr.offset);
          if (c < bestCost) {
            bestCost = c;
            best = [{ edge: e, from: pr.offset, to: Math.max(pr.offset, tgt.fromOffsetMeters), turnaround: null }];
          }
          continue;
        }
        const r = legalShortestPath(gi, [e], (x) => x === tgtEdge, allow);
        if (!r.path) continue;
        const cost = gi.length(e) - pr.offset + r.cost / 1000;
        if (cost < bestCost) {
          bestCost = cost;
          const segs: Seg[] = [{ edge: e, from: pr.offset, to: gi.length(e), turnaround: null }];
          r.path.forEach((ps, idx) => {
            const last = idx === r.path!.length - 1;
            segs.push({ edge: ps.edge, from: 0, to: last ? tgt.fromOffsetMeters : gi.length(ps.edge), turnaround: ps.t.uturn ? ps.t.turnaround : null });
          });
          best = segs;
        }
      }
      this.approach = best;
      this.approachTargetStep = targetStep;
      this.approachEdge = best?.[0]?.edge ?? near[0].i;
      this.approachAt = nowMs;
    }
    const segs = this.approach;
    if (!segs) return { ...toRoute('noroute'), approximate: true };
    let along = 0, bestAlong = 0, bestDist = Infinity;
    for (const s of segs.slice(0, 3)) {
      const pr = gi.project(s.edge, p);
      const off = Math.min(Math.max(pr.offset, s.from), s.to);
      if (pr.distance < bestDist) {
        bestDist = pr.distance;
        bestAlong = along + (off - s.from);
      }
      along += s.to - s.from;
    }
    const total = segs.reduce((a, s) => a + s.to - s.from, 0);
    const mans = approachManeuvers(gi, segs, plan, targetStep);
    const upcoming = mans.filter((m) => m.at > bestAlong + 1);
    const m = upcoming[0];
    const n = upcoming[1];
    const ahead = segs.map((s) => slicePolyline(gi.edges[s.edge].geometry, gi.cum[s.edge], s.from, Math.max(s.from + 0.01, s.to)));
    let lanes: LaneHint[] | null = null;
    if (m && m.segIndex > 0) {
      const incoming: number[] = [];
      for (let k = m.segIndex - 1; k >= Math.max(0, m.segIndex - 4); k--) incoming.push(segs[k].edge);
      lanes = laneHints(approachLanes(gi, incoming), m.type);
    }
    return base({
      lanes,
      mode: 'approach', type: m?.type ?? 'continue', roadName: m?.road ?? edgeName(gi, segs[0].edge), distanceMeters: (m?.at ?? total) - bestAlong,
      thenType: n?.type ?? null, thenRoadName: n?.road ?? null, thenDistanceMeters: m && n ? n.at - m.at : null,
      rejoinMeters: total - bestAlong, turnaroundVerified: m?.verified ?? null, approximate, ahead, key: `ap:${targetStep}:${m?.key ?? 'join'}`,
    });
  }
}

function approachManeuvers(gi: GraphIndex, segs: Seg[], plan: RoutePlan, targetStep: number) {
  const out: { type: string; road: string | null; at: number; verified: boolean | null; key: string; segIndex: number }[] = [];
  let d = 0;
  segs.forEach((s, k) => {
    if (k > 0) {
      const prev = segs[k - 1].edge;
      const type = s.turnaround ? 'turnaround' : classifyTurn(angleDiff(gi.endBearing[prev], gi.startBearing[s.edge]));
      const same = edgeName(gi, prev) === edgeName(gi, s.edge);
      if (!(type === 'continue' && same) && !(type.startsWith('slight') && same)) {
        out.push({ type, road: edgeName(gi, s.edge), at: d, verified: s.turnaround ? s.turnaround === 'verified' : null, key: `${k}:${s.edge}`, segIndex: k });
      }
    }
    d += s.to - s.from;
  });
  out.push({ type: 'join', road: edgeName(gi, gi.idx.get(plan.steps[targetStep].edgeId)!), at: d, verified: null, key: 'join', segIndex: -1 });
  return out;
}

function planAhead(gi: GraphIndex, plan: RoutePlan, k0: number, off0: number, maxM: number): LonLat[][] {
  const out: LonLat[][] = [];
  let left = maxM;
  for (let k = k0; k < plan.steps.length && left > 0; k++) {
    const s = plan.steps[k];
    const i = gi.idx.get(s.edgeId);
    if (i === undefined) break;
    const from = k === k0 ? off0 : s.fromOffsetMeters;
    const to = Math.min(s.toOffsetMeters, from + left);
    if (to - from > 0.05) out.push(slicePolyline(gi.edges[i].geometry, gi.cum[i], from, to));
    left -= to - from;
  }
  return out;
}
