// Directed coverage planner (rural-postman style heuristic):
// deterministic nearest-feasible-requirement selection with legal shortest-path
// connectors over an edge-based state graph that honours turn restrictions
// (including via-way sequences), dead-end-only U-turns and the boundary policy.
// It does NOT claim optimality.

import type { GraphIndex, Prefix, Transition } from './graph';
import { MinHeap } from './heap';
import { addInterval, coveredWithin, type Interval } from './intervals';
import {
  COVERAGE_TOLERANCE_M,
  STRICT_OUTSIDE_TOL_M,
  edgeTraversable,
  requirementCovered,
  type EdgeZoneInfo,
} from './requirements';
import {
  PLANNER_ALGORITHM,
  PLANNER_VERSION,
  type BoundaryPolicy,
  type PlanLeg,
  type PlanStep,
  type RequiredInterval,
  type RoutePlan,
  type StartState,
  type UnreachableRequirement,
} from './types';

export const OUTSIDE_PENALTY_FACTOR = 9; // outside meters cost 10x
export const TURN_PENALTY_MM = {
  straight: 0,
  right: 5_000,
  left: 15_000,
  sharp: 30_000,
  uturnVerified: 60_000,
  uturnUnverified: 100_000,
};

export function turnPenalty(t: Transition): number {
  if (t.uturn) return t.turnaround === 'verified' ? TURN_PENALTY_MM.uturnVerified : TURN_PENALTY_MM.uturnUnverified;
  const a = t.turnAngle;
  const abs = Math.abs(a);
  if (abs < 30) return TURN_PENALTY_MM.straight;
  if (abs >= 150) return TURN_PENALTY_MM.sharp;
  return a > 0 ? TURN_PENALTY_MM.right : TURN_PENALTY_MM.left;
}

export interface PlannerInput {
  gi: GraphIndex;
  zoneInfo: EdgeZoneInfo[];
  requirements: RequiredInterval[];
  /** Observed (confirmed) union per edgeId. */
  observed: Map<string, Interval[]>;
  /** Active exclusion requirement IDs (user-blocked). */
  excluded: Set<string>;
  policy: BoundaryPolicy;
  start: StartState;
  startPrefix?: Prefix;
  returnToStart?: boolean;
  maxIterations?: number;
  timeBudgetMs?: number;
  now?: () => number;
  shouldCancel?: () => boolean;
  meta: {
    planId: string;
    graphHash: string;
    zoneVersion: number;
    progressRevision: number;
    planningRevision: number;
    createdAt: string;
  };
}

interface QItem {
  cost: number;
  edge: number;
  key: string;
  prefix: Prefix;
}

const lessQ = (gi: GraphIndex) => (x: QItem, y: QItem) => {
  if (x.cost !== y.cost) return x.cost < y.cost;
  const ex = gi.edges[x.edge].edgeId, ey = gi.edges[y.edge].edgeId;
  if (ex !== ey) return ex < ey;
  return x.key < y.key;
};

/** Tarjan SCC over edge-level legal transitions among traversable edges. */
export function edgeScc(gi: GraphIndex, allow: (i: number) => boolean): Int32Array {
  const n = gi.edges.length;
  const comp = new Int32Array(n).fill(-1);
  const index = new Int32Array(n).fill(-1);
  const low = new Int32Array(n);
  const onStack = new Uint8Array(n);
  const stack: number[] = [];
  let idx = 0, c = 0;
  const succ = (v: number) => gi.successors([v], allow).map((t) => t.next);
  for (let s = 0; s < n; s++) {
    if (!allow(s) || index[s] !== -1) continue;
    const work: { v: number; it: number[]; k: number }[] = [{ v: s, it: succ(s), k: 0 }];
    index[s] = low[s] = idx++;
    stack.push(s);
    onStack[s] = 1;
    while (work.length) {
      const top = work[work.length - 1];
      if (top.k < top.it.length) {
        const w = top.it[top.k++];
        if (index[w] === -1) {
          index[w] = low[w] = idx++;
          stack.push(w);
          onStack[w] = 1;
          work.push({ v: w, it: succ(w), k: 0 });
        } else if (onStack[w]) low[top.v] = Math.min(low[top.v], index[w]);
      } else {
        work.pop();
        if (work.length) low[work[work.length - 1].v] = Math.min(low[work[work.length - 1].v], low[top.v]);
        if (low[top.v] === index[top.v]) {
          let w: number;
          do {
            w = stack.pop()!;
            onStack[w] = 0;
            comp[w] = c;
          } while (w !== top.v);
          c++;
        }
      }
    }
  }
  return comp;
}

/**
 * Dijkstra over the edge-based legal state graph. Cost (integer mm) = edge length +
 * OUTSIDE_PENALTY_FACTOR * outside length + turn penalty. Ties: cost, then edgeId,
 * then automaton key (all compared as UTF-16 strings) -> identical on every platform.
 */
export function legalShortestPath(
  gi: GraphIndex,
  from: Prefix,
  isTarget: (edge: number) => boolean,
  allow: (i: number) => boolean,
  zoneInfo?: EdgeZoneInfo[],
): { path: { edge: number; t: Transition }[] | null; endPrefix: Prefix | null; cost: number; settled: number } {
  const less = lessQ(gi);
  const startKey = from.join(',');
  const dist = new Map<string, number>([[startKey, 0]]);
  const parent = new Map<string, { key: string; t: Transition }>();
  const heap = new MinHeap<QItem>(less);
  heap.push({ cost: 0, edge: from[from.length - 1], key: startKey, prefix: from });
  const done = new Set<string>();
  let settled = 0;
  while (heap.size) {
    const cur = heap.pop()!;
    if (done.has(cur.key)) continue;
    done.add(cur.key);
    settled++;
    if (cur.key !== startKey && isTarget(cur.edge)) {
      const path: { edge: number; t: Transition }[] = [];
      let k = cur.key;
      while (k !== startKey) {
        const p = parent.get(k)!;
        path.push({ edge: p.t.next, t: p.t });
        k = p.key;
      }
      path.reverse();
      return { path, endPrefix: cur.prefix, cost: cur.cost, settled };
    }
    for (const t of gi.successors(cur.prefix, allow)) {
      const nk = t.prefix.join(',');
      if (done.has(nk)) continue;
      const outside = zoneInfo ? OUTSIDE_PENALTY_FACTOR * Math.round(zoneInfo[t.next].outsideMeters * 1000) : 0;
      const nc = cur.cost + gi.edges[t.next].lengthMm + outside + turnPenalty(t);
      if (nc < (dist.get(nk) ?? Infinity)) {
        dist.set(nk, nc);
        parent.set(nk, { key: cur.key, t });
        heap.push({ cost: nc, edge: t.next, key: nk, prefix: t.prefix });
      }
    }
  }
  return { path: null, endPrefix: null, cost: Infinity, settled };
}

export function planCoverage(inp: PlannerInput): RoutePlan {
  const { gi, zoneInfo } = inp;
  const now = inp.now ?? (() => Date.now());
  const t0 = now();
  const maxIterations = inp.maxIterations ?? 200_000;
  const timeBudgetMs = inp.timeBudgetMs ?? 20_000;
  const warnings: string[] = [...gi.restrictionWarnings];

  const reqById = new Map(inp.requirements.map((r) => [r.requirementId, r]));
  const blockedEdges = new Set<number>();
  for (const id of inp.excluded) {
    const r = reqById.get(id);
    if (r && gi.idx.has(r.edgeId)) blockedEdges.add(gi.idx.get(r.edgeId)!);
  }
  const allow = (i: number) => edgeTraversable(gi, zoneInfo, inp.policy, blockedEdges, i);
  // The vehicle may already be on a road the user just reported blocked: it can still
  // leave along its current edge, so the start edge is exempt for the start step only.
  const allowStart = (i: number) => edgeTraversable(gi, zoneInfo, inp.policy, new Set(), i);

  const unreachable: UnreachableRequirement[] = [];
  const remainingByEdge = new Map<number, Set<string>>();
  const planned = new Map<number, Interval[]>();
  const unionFor = (i: number): Interval[] => {
    const id = gi.edges[i].edgeId;
    const obs = inp.observed.get(id) ?? [];
    const pl = planned.get(i) ?? [];
    return pl.length ? [...obs, ...pl].sort((a, b) => a[0] - b[0]) : obs;
  };

  let plannable = 0;
  for (const r of inp.requirements) {
    const ei = gi.idx.get(r.edgeId);
    if (ei === undefined) continue;
    if (requirementCovered(inp.observed.get(r.edgeId) ?? [], r)) continue;
    if (inp.excluded.has(r.requirementId)) {
      unreachable.push({ requirementId: r.requirementId, cause: 'blocked_by_user' });
      continue;
    }
    const e = gi.edges[ei];
    if (e.eligibility === 'needsReview') {
      unreachable.push({ requirementId: r.requirementId, cause: 'needs_review' });
      continue;
    }
    if (!allow(ei)) {
      const cause = inp.policy === 'STRICT_INSIDE' && zoneInfo[ei].outsideMeters > STRICT_OUTSIDE_TOL_M ? 'outside_policy' : 'not_reachable_from_start';
      unreachable.push({ requirementId: r.requirementId, cause });
      continue;
    }
    plannable++;
    const s = remainingByEdge.get(ei) ?? new Set<string>();
    s.add(r.requirementId);
    remainingByEdge.set(ei, s);
  }

  const steps: PlanStep[] = [];
  const covered: string[] = [];
  const startEdge = gi.idx.get(inp.start.edgeId);
  const basePlan = (status: RoutePlan['status'], iterationsUsed: number): RoutePlan => ({
    planId: inp.meta.planId,
    graphHash: inp.meta.graphHash,
    zoneVersion: inp.meta.zoneVersion,
    basedOnProgressRevision: inp.meta.progressRevision,
    planningRevision: inp.meta.planningRevision,
    algorithm: PLANNER_ALGORITHM,
    algorithmVersion: PLANNER_VERSION,
    createdAt: inp.meta.createdAt,
    startState: inp.start,
    steps,
    legs: buildLegs(steps),
    coveredRequirementIds: covered,
    unreachable,
    totals: computeTotals(steps, inp.requirements, covered, inp.observed),
    limits: { maxIterations, timeBudgetMs, iterationsUsed },
    warnings,
    status,
  });

  if (startEdge === undefined || !allowStart(startEdge)) {
    warnings.push('start_edge_not_traversable');
    for (const [, ids] of remainingByEdge) for (const id of ids) unreachable.push({ requirementId: id, cause: 'not_reachable_from_start' });
    return basePlan('failed', 0);
  }

  const takeEdge = (ei: number, from: number, to: number, turnaround: PlanStep['turnaroundAtStart']) => {
    const rem = remainingByEdge.get(ei);
    planned.set(ei, addInterval(planned.get(ei) ?? [], [from, to]));
    const newly: string[] = [];
    if (rem) {
      const u = unionFor(ei);
      for (const id of [...rem].sort()) {
        if (requirementCovered(u, reqById.get(id)!, COVERAGE_TOLERANCE_M)) {
          newly.push(id);
          rem.delete(id);
        }
      }
      if (rem.size === 0) remainingByEdge.delete(ei);
    }
    covered.push(...newly);
    const zi = zoneInfo[ei];
    const outside = Math.max(0, to - from - coveredWithin(zi.inside, from, to));
    steps.push({
      edgeId: gi.edges[ei].edgeId,
      fromOffsetMeters: from,
      toOffsetMeters: to,
      kind: newly.length ? 'required' : outside > STRICT_OUTSIDE_TOL_M ? 'outside' : 'transit',
      newlyCoveredRequirementIds: newly,
      outsideMeters: outside,
      turnaroundAtStart: turnaround,
    });
  };

  takeEdge(startEdge, inp.start.offsetMeters, gi.length(startEdge), null);
  let prefix: Prefix = inp.startPrefix && inp.startPrefix[inp.startPrefix.length - 1] === startEdge ? inp.startPrefix : [startEdge];

  const comp = edgeScc(gi, allow);
  const remainingInComp = (c: number) => {
    for (const ei of remainingByEdge.keys()) if (comp[ei] === c) return true;
    return false;
  };

  let iterations = 0;
  let status: RoutePlan['status'] = 'complete';

  const dijkstra = (from: Prefix, isTarget: (edge: number) => boolean) => {
    const r = legalShortestPath(gi, from, isTarget, allow, zoneInfo);
    iterations += r.settled;
    return r.path ? { path: r.path, endPrefix: r.endPrefix! } : null;
  };

  while (remainingByEdge.size > 0) {
    if (inp.shouldCancel?.()) {
      status = 'cancelled';
      break;
    }
    if (iterations > maxIterations || now() - t0 > timeBudgetMs) {
      status = 'partial';
      warnings.push('budget_exceeded');
      break;
    }
    const curEdge = prefix[prefix.length - 1];
    const sameComp = comp[curEdge] >= 0 && remainingInComp(comp[curEdge]);
    let res = dijkstra(prefix, (e) => remainingByEdge.has(e) && (!sameComp || comp[e] === comp[curEdge]));
    if (!res && sameComp) res = dijkstra(prefix, (e) => remainingByEdge.has(e));
    if (!res) break;
    for (const { edge, t } of res.path) {
      takeEdge(edge, 0, gi.length(edge), t.uturn ? t.turnaround : null);
    }
    prefix = res.endPrefix;
  }

  if (status === 'complete' && inp.returnToStart && remainingByEdge.size === 0) {
    const back = dijkstra(prefix, (e) => e === startEdge);
    if (back) {
      const last = back.path.length - 1;
      back.path.forEach(({ edge, t }, k) => {
        takeEdge(edge, 0, k === last ? inp.start.offsetMeters : gi.length(edge), t.uturn ? t.turnaround : null);
      });
    } else warnings.push('return_to_start_unreachable');
  }

  for (const [, ids] of [...remainingByEdge.entries()].sort((a, b) => a[0] - b[0])) {
    for (const id of [...ids].sort()) {
      unreachable.push({
        requirementId: id,
        cause: status === 'partial' || status === 'cancelled' ? 'budget_exceeded' : 'not_reachable_from_start',
      });
    }
  }
  if (status === 'complete' && unreachable.some((u) => u.cause === 'not_reachable_from_start')) status = 'partial';
  if (plannable === 0 && status === 'complete') status = 'no_requirements';
  const unverified = steps.filter((s) => s.turnaroundAtStart === 'unverified').length;
  if (unverified) warnings.push(`unverified_turnarounds:${unverified}`);
  if (steps.some((s) => s.outsideMeters > STRICT_OUTSIDE_TOL_M)) warnings.push('outside_travel');
  unreachable.sort((a, b) => (a.requirementId < b.requirementId ? -1 : a.requirementId > b.requirementId ? 1 : 0));
  return basePlan(status, iterations);
}

export function buildLegs(steps: PlanStep[]): PlanLeg[] {
  const legs: PlanLeg[] = [];
  steps.forEach((s, i) => {
    const len = s.toOffsetMeters - s.fromOffsetMeters;
    const last = legs[legs.length - 1];
    if (last && last.kind === s.kind) {
      last.lastStep = i;
      last.lengthMeters += len;
    } else legs.push({ kind: s.kind, firstStep: i, lastStep: i, lengthMeters: len });
  });
  return legs;
}

export function computeTotals(
  steps: PlanStep[],
  requirements: RequiredInterval[],
  covered: string[],
  observed: Map<string, Interval[]>,
) {
  const reqById = new Map(requirements.map((r) => [r.requirementId, r]));
  let total = 0, outside = 0, approach = 0;
  for (const s of steps) {
    const len = s.toOffsetMeters - s.fromOffsetMeters;
    total += len;
    outside += s.outsideMeters;
    if (s.kind === 'approach') approach += len;
  }
  let newCoverage = 0;
  for (const id of covered) {
    const r = reqById.get(id);
    if (!r) continue;
    newCoverage += r.lengthMeters - coveredWithin(observed.get(r.edgeId) ?? [], r.startOffsetMeters, r.endOffsetMeters);
  }
  return {
    totalMeters: total,
    newCoverageMeters: newCoverage,
    repeatMeters: Math.max(0, total - outside - newCoverage - approach),
    outsideMeters: outside,
    approachMeters: approach,
  };
}
