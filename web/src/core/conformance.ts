// Reference conformance runner for /contract/fixtures. The Android implementation
// must reproduce these outputs (see /contract/CONFORMANCE.md for comparison rules).

import { GraphIndex } from './graph';
import { buildManeuvers } from './maneuvers';
import { DEFAULT_MATCHER_CONFIG, initialMatcherState, processObservation, type MatcherConfig } from './matcher';
import { normalizeOverpass, type OverpassResponse } from './osm';
import { legalShortestPath, planCoverage } from './planner';
import { addInterval, type Interval } from './intervals';
import { computeRequirements } from './requirements';
import { initialGuidance, updateGuidance } from './guidance';
import { validatePlan } from './validator';
import type { BoundaryPolicy, LocationObservation, RoutePlan, ZoneDefinition } from './types';

export interface FixtureTrack {
  name: string;
  observations: LocationObservation[];
  nowMs?: number;
  /** Run guidance against the plan for this policy and replan on deviation. */
  guidancePolicy?: BoundaryPolicy;
  matcherConfig?: Partial<MatcherConfig>;
}

export interface FixtureInput {
  osm: OverpassResponse;
  zone: ZoneDefinition;
  policies: BoundaryPolicy[];
  start: { edgeId: string; offsetMeters: number } | null;
  returnToStart?: boolean;
  plannerLimits?: { maxIterations?: number };
  pathQueries?: { name: string; fromPrefix: string[]; toEdgeId: string }[];
  tracks?: FixtureTrack[];
  maneuvers?: boolean;
}

const r2 = (x: number) => Math.round(x * 100) / 100;
const r1 = (x: number) => Math.round(x * 10) / 10;

export async function runFixture(input: FixtureInput) {
  const g = await normalizeOverpass(input.osm, { fetchedAt: '1970-01-01T00:00:00.000Z', dataState: 'synthetic_test_fixture', providerId: 'fixture' });
  const gi = new GraphIndex(g);
  const req = computeRequirements(gi, input.zone);
  const graph = {
    contentHash: g.contentHash,
    graphId: g.graphId,
    nodeCount: g.nodes.length,
    edgeCount: g.directedEdges.length,
    restrictionCount: g.turnRestrictions.length,
    edgeIds: g.directedEdges.map((e) => e.edgeId),
    eligibility: Object.fromEntries(g.directedEdges.map((e) => [e.edgeId, e.eligibility])),
    eligibilityReason: Object.fromEntries(g.directedEdges.filter((e) => e.eligibilityReason).map((e) => [e.edgeId, e.eligibilityReason])),
    turnLanes: Object.fromEntries(g.directedEdges.filter((e) => e.turnLanes).map((e) => [e.edgeId, e.turnLanes!.map((l) => l.join(';')).join('|')])),
    nodePassage: Object.fromEntries(g.nodes.filter((n) => n.passage !== 'free').map((n) => [n.nodeId, n.passage])),
    restrictions: g.turnRestrictions.map((r) => ({ id: r.restrictionId, kind: r.kind, from: r.fromEdgeId, via: r.viaNodeId ?? r.viaEdgeIds, to: r.toEdgeId })),
  };
  const requirements = req.requirements.map((r) => ({
    requirementId: r.requirementId, edgeId: r.edgeId, start: r2(r.startOffsetMeters), end: r2(r.endOffsetMeters), status: r.status,
  }));
  const plans: Record<string, unknown> = {};
  const planObjs: Record<string, RoutePlan> = {};
  for (const policy of input.policies) {
    if (!input.start) continue;
    const plan = planCoverage({
      gi, zoneInfo: req.zoneInfo, requirements: req.requirements, observed: new Map(), excluded: new Set(), policy,
      start: { ...input.start, source: 'selected_start' }, returnToStart: input.returnToStart,
      maxIterations: input.plannerLimits?.maxIterations, timeBudgetMs: 1e9,
      meta: { planId: `fixture-${policy}`, graphHash: g.contentHash, zoneVersion: 1, progressRevision: 0, planningRevision: 1, createdAt: '1970-01-01T00:00:00.000Z' },
    });
    planObjs[policy] = plan;
    const v = validatePlan(gi, plan, { zoneInfo: req.zoneInfo, policy, requirements: req.requirements, observed: new Map(), excluded: new Set() });
    plans[policy] = {
      status: plan.status,
      edgeSequence: plan.steps.map((s) => s.edgeId),
      stepKinds: plan.steps.map((s) => s.kind),
      turnarounds: plan.steps.map((s, i) => (s.turnaroundAtStart ? `${i}:${s.turnaroundAtStart}` : null)).filter(Boolean),
      coveredRequirementIds: plan.coveredRequirementIds.slice().sort(),
      unreachable: plan.unreachable.map((u) => `${u.requirementId}:${u.cause}`),
      totals: Object.fromEntries(Object.entries(plan.totals).map(([k, v]) => [k, r1(v)])),
      valid: v.ok,
      validationErrors: v.errors,
      maneuvers: input.maneuvers ? buildManeuvers(gi, plan).map((m) => m.type + (m.turnaroundVerified === null ? '' : m.turnaroundVerified ? ':verified' : ':unverified')) : undefined,
    };
  }
  const paths: Record<string, string[] | null> = {};
  for (const q of input.pathQueries ?? []) {
    const allow = (i: number) => gi.edges[i].eligibility === 'eligible';
    const from = q.fromPrefix.map((id) => gi.idx.get(id)!);
    const target = gi.idx.get(q.toEdgeId)!;
    const r = legalShortestPath(gi, from, (e) => e === target, allow);
    paths[q.name] = r.path ? r.path.map((p) => gi.edges[p.edge].edgeId) : null;
  }
  const tracks: Record<string, unknown> = {};
  for (const tr of input.tracks ?? []) {
    let st = initialMatcherState();
    const cfg = { ...DEFAULT_MATCHER_CONFIG, ...(tr.matcherConfig ?? {}) };
    const union = new Map<string, Interval[]>();
    const traversalIds: string[] = [];
    const qualities: string[] = [];
    const gaps: string[] = [];
    const ignored: string[] = [];
    let plan = tr.guidancePolicy ? planObjs[tr.guidancePolicy] : undefined;
    let guidance = plan ? initialGuidance(plan) : undefined;
    let deviationAt: number | null = null;
    let replan: unknown = null;
    tr.observations.forEach((o, k) => {
      const res = processObservation(gi, st, o, tr.nowMs ?? o.timestamp, cfg);
      st = res.state;
      qualities.push(res.quality);
      if (res.gap) gaps.push(`${k}:${res.gap.reason}`);
      if (res.ignored) ignored.push(`${k}:${res.ignored}`);
      for (const t of res.traversals) {
        if (traversalIds.includes(t.traversalId)) continue;
        traversalIds.push(t.traversalId);
        union.set(t.edgeId, addInterval(union.get(t.edgeId) ?? [], [t.startOffsetMeters, t.endOffsetMeters]));
      }
      if (plan && guidance && deviationAt === null) {
        const gu = updateGuidance(gi, plan, guidance, res.matched, res.quality === 'reliable');
        guidance = gu.state;
        if (gu.event === 'deviation' && res.matched) {
          deviationAt = k;
          const mi = gi.idx.get(res.matched.edgeId)!;
          const newPlan = planCoverage({
            gi, zoneInfo: req.zoneInfo, requirements: req.requirements, observed: union, excluded: new Set(), policy: tr.guidancePolicy!,
            start: { edgeId: res.matched.edgeId, offsetMeters: res.matched.offset, source: 'gps_position' }, startPrefix: [mi], timeBudgetMs: 1e9,
            meta: { planId: 'replan', graphHash: g.contentHash, zoneVersion: 1, progressRevision: k, planningRevision: 2, createdAt: '1970-01-01T00:00:00.000Z' },
          });
          const v = validatePlan(gi, newPlan, { zoneInfo: req.zoneInfo, policy: tr.guidancePolicy!, requirements: req.requirements, observed: union, excluded: new Set(), startPrefix: [mi] });
          replan = {
            status: newPlan.status,
            firstEdge: newPlan.steps[0]?.edgeId ?? null,
            edgeSequence: newPlan.steps.map((s) => s.edgeId),
            coveredRequirementIds: newPlan.coveredRequirementIds.slice().sort(),
            valid: v.ok,
            validationErrors: v.errors,
          };
          plan = newPlan;
          guidance = initialGuidance(newPlan);
        }
      }
    });
    tracks[tr.name] = {
      unionByEdge: Object.fromEntries([...union.entries()].sort((a, b) => (a[0] < b[0] ? -1 : 1)).map(([k, v]) => [k, v.map(([a, b]) => [r2(a), r2(b)])])),
      traversalCount: traversalIds.length,
      qualities,
      gaps,
      ignored,
      deviationAt,
      replan,
    };
  }
  return { graph, requirements, plans, paths, tracks };
}
