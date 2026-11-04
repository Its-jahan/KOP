/// <reference lib="webworker" />
// CPU-heavy work off the main thread: OSM normalisation + hashing, requirement
// clipping, and coverage planning with validation. Cancellation = terminate().

import { normalizeOverpass, type OverpassResponse } from '../core/osm';
import { GraphIndex } from '../core/graph';
import { computeRequirements, edgeTraversable, type EdgeZoneInfo } from '../core/requirements';
import { edgeScc, planCoverage } from '../core/planner';
import { validatePlan } from '../core/validator';
import { mainComponentPredicate } from '../core/snap';
import type { Interval } from '../core/intervals';
import type { BoundaryPolicy, GraphSnapshot, RequiredInterval, RoutePlan, StartState, ZoneDefinition } from '../core/types';

export type WorkerRequest =
  | { id: number; type: 'normalize'; overpass: OverpassResponse; fetchedAt: string }
  | { id: number; type: 'load'; graph: GraphSnapshot }
  | { id: number; type: 'requirements'; zone: ZoneDefinition; policy: BoundaryPolicy }
  | {
      id: number;
      type: 'plan';
      requirements: RequiredInterval[];
      zone: ZoneDefinition;
      observed: [string, Interval[]][];
      excluded: string[];
      policy: BoundaryPolicy;
      start: StartState;
      startPrefix?: string[];
      returnToStart: boolean;
      meta: { planId: string; graphHash: string; zoneVersion: number; progressRevision: number; planningRevision: number; createdAt: string };
      timeBudgetMs: number;
    };

export type WorkerResponse =
  | { id: number; ok: true; type: 'normalize'; graph: GraphSnapshot; ms: number }
  | { id: number; ok: true; type: 'load'; ms: number }
  | { id: number; ok: true; type: 'requirements'; requirements: RequiredInterval[]; mainComponentEdgeIds: string[]; ignoredSlivers: number; ms: number }
  | { id: number; ok: true; type: 'plan'; plan: RoutePlan; validation: { ok: boolean; errors: string[] }; ms: number }
  | { id: number; ok: false; error: string };

let gi: GraphIndex | null = null;
let zoneCache: { key: string; zoneInfo: EdgeZoneInfo[]; requirements: RequiredInterval[] } | null = null;

function zoneInfoFor(zone: ZoneDefinition) {
  const key = JSON.stringify(zone);
  if (!zoneCache || zoneCache.key !== key) {
    const r = computeRequirements(gi!, zone);
    zoneCache = { key, zoneInfo: r.zoneInfo, requirements: r.requirements };
  }
  return zoneCache;
}

self.onmessage = async (ev: MessageEvent<WorkerRequest>) => {
  const m = ev.data;
  const t0 = performance.now();
  try {
    if (m.type === 'normalize') {
      const graph = await normalizeOverpass(m.overpass, { fetchedAt: m.fetchedAt });
      gi = new GraphIndex(graph);
      zoneCache = null;
      post({ id: m.id, ok: true, type: 'normalize', graph, ms: performance.now() - t0 });
    } else if (m.type === 'load') {
      gi = new GraphIndex(m.graph);
      zoneCache = null;
      post({ id: m.id, ok: true, type: 'load', ms: performance.now() - t0 });
    } else if (m.type === 'requirements') {
      if (!gi) throw new Error('graph_not_loaded');
      const r = computeRequirements(gi, m.zone);
      zoneCache = { key: JSON.stringify(m.zone), zoneInfo: r.zoneInfo, requirements: r.requirements };
      const allow = (i: number) => edgeTraversable(gi!, r.zoneInfo, m.policy, new Set(), i);
      const main = mainComponentPredicate(gi, edgeScc(gi, allow));
      const mainIds = gi.edges.filter((_, i) => main(i)).map((e) => e.edgeId);
      post({ id: m.id, ok: true, type: 'requirements', requirements: r.requirements, mainComponentEdgeIds: mainIds, ignoredSlivers: r.ignoredSlivers, ms: performance.now() - t0 });
    } else if (m.type === 'plan') {
      if (!gi) throw new Error('graph_not_loaded');
      const z = zoneInfoFor(m.zone);
      const observed = new Map(m.observed);
      const excluded = new Set(m.excluded);
      const startPrefix = m.startPrefix?.map((id) => gi!.idx.get(id)).filter((x): x is number => x !== undefined);
      const plan = planCoverage({
        gi, zoneInfo: z.zoneInfo, requirements: m.requirements, observed, excluded, policy: m.policy, start: m.start,
        startPrefix, returnToStart: m.returnToStart, timeBudgetMs: m.timeBudgetMs, meta: m.meta,
      });
      const validation = validatePlan(gi, plan, { zoneInfo: z.zoneInfo, policy: m.policy, requirements: m.requirements, observed, excluded, startPrefix });
      post({ id: m.id, ok: true, type: 'plan', plan, validation, ms: performance.now() - t0 });
    }
  } catch (e) {
    post({ id: m.id, ok: false, error: e instanceof Error ? e.message : String(e) });
  }
};

function post(r: WorkerResponse) {
  (self as unknown as DedicatedWorkerGlobalScope).postMessage(r);
}
