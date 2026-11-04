import { describe, expect, it } from 'vitest';
import { Osm, rect, track } from '../../scripts/fixture-lib';
import { normalizeOverpass } from '../../src/core/osm';
import { GraphIndex } from '../../src/core/graph';
import { computeRequirements, edgeTraversable, STRICT_OUTSIDE_TOL_M } from '../../src/core/requirements';
import { planCoverage } from '../../src/core/planner';
import { validatePlan } from '../../src/core/validator';
import { initialMatcherState, processObservation } from '../../src/core/matcher';
import { addInterval, totalLength, unionIntervals, type Interval } from '../../src/core/intervals';
import { applyEvents, computeMetrics, emptyProgress } from '../../src/core/session';
import { circleApproximation, validateZone } from '../../src/core/zone';
import { decidePlan } from '../../src/core/revision';
import type { BoundaryPolicy, SessionEvent } from '../../src/core/types';

function rng(seed: number) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

async function randomGrid(seed: number, n = 6) {
  const r = rng(seed);
  const o = new Osm();
  const id = (i: number, j: number) => i * n + j + 1;
  for (let i = 0; i < n; i++) for (let j = 0; j < n; j++) o.node(id(i, j), j * 90 + (r() - 0.5) * 20, i * 90 + (r() - 0.5) * 20, r() < 0.05 ? { barrier: 'bollard' } : undefined);
  let w = 1000;
  const ways: { w: number; a: number; b: number }[] = [];
  const add = (a: number, b: number) => {
    if (r() < 0.12) return;
    const tags: Record<string, string> = { highway: r() < 0.1 ? 'track' : 'residential' };
    const x = r();
    if (x < 0.25) tags.oneway = 'yes';
    else if (x < 0.3) tags.oneway = '-1';
    if (r() < 0.05) tags.access = 'private';
    ways.push({ w, a, b });
    o.way(w++, [a, b], tags);
  };
  for (let i = 0; i < n; i++) for (let j = 0; j < n - 1; j++) add(id(i, j), id(i, j + 1));
  for (let j = 0; j < n; j++) for (let i = 0; i < n - 1; i++) add(id(i, j), id(i + 1, j));
  // random restrictions at shared nodes
  let rel = 1;
  for (let k = 0; k < 6 && ways.length > 2; k++) {
    const a = ways[Math.floor(r() * ways.length)];
    const b = ways.find((x) => x !== a && (x.a === a.b || x.b === a.b));
    if (b) o.rel(rel++, [{ type: 'way', ref: a.w, role: 'from' }, { type: 'node', ref: a.b, role: 'via' }, { type: 'way', ref: b.w, role: 'to' }], { type: 'restriction', restriction: r() < 0.7 ? 'no_left_turn' : 'only_straight_on' });
  }
  const g = await normalizeOverpass(o.json(), { fetchedAt: 'x', dataState: 'synthetic_test_fixture', providerId: 'fixture' });
  return { g, gi: new GraphIndex(g), size: (n - 1) * 90 };
}

describe('planner invariants on random graphs', () => {
  for (const seed of [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]) {
    for (const policy of ['STRICT_INSIDE', 'ALLOW_NECESSARY_CONNECTORS'] as BoundaryPolicy[]) {
      it(`seed ${seed} ${policy}`, async () => {
        const { g, gi, size } = await randomGrid(seed);
        const zone = { type: 'polygon' as const, coordinates: rect(size * 0.15, size * 0.1, size * 0.85, size * 0.9) };
        const req = computeRequirements(gi, zone);
        const allow = (i: number) => edgeTraversable(gi, req.zoneInfo, policy, new Set(), i);
        const startIdx = gi.edges.findIndex((_, i) => allow(i) && req.zoneInfo[i].insideMeters > 0);
        if (startIdx < 0) return;
        const mk = () => planCoverage({
          gi, zoneInfo: req.zoneInfo, requirements: req.requirements, observed: new Map(), excluded: new Set(), policy,
          start: { edgeId: gi.edges[startIdx].edgeId, offsetMeters: 0, source: 'selected_start' },
          meta: { planId: 'p', graphHash: g.contentHash, zoneVersion: 1, progressRevision: 0, planningRevision: 1, createdAt: 'x' },
        });
        const plan = mk();
        const v = validatePlan(gi, plan, { zoneInfo: req.zoneInfo, policy, requirements: req.requirements, observed: new Map(), excluded: new Set() });
        expect(v.errors).toEqual([]);
        // determinism
        expect(mk().steps.map((s) => s.edgeId)).toEqual(plan.steps.map((s) => s.edgeId));
        // strict => no outside travel beyond tolerance
        if (policy === 'STRICT_INSIDE') for (const s of plan.steps) expect(s.outsideMeters).toBeLessThanOrEqual(STRICT_OUTSIDE_TOL_M);
        // every requirement accounted for exactly once
        const all = new Set(req.requirements.map((r) => r.requirementId));
        const claimed = new Set(plan.coveredRequirementIds);
        for (const u of plan.unreachable) expect(claimed.has(u.requirementId)).toBe(false);
        expect(new Set([...claimed, ...plan.unreachable.map((u) => u.requirementId)])).toEqual(all);
        // never traverse a non-eligible edge
        for (const s of plan.steps) expect(gi.edges[gi.idx.get(s.edgeId)!].eligibility).toBe('eligible');
      });
    }
  }
});

describe('interval and progress invariants', () => {
  it('union is monotone, idempotent, bounded', () => {
    const r = rng(42);
    let u: Interval[] = [];
    let prev = 0;
    for (let k = 0; k < 500; k++) {
      const a = r() * 300;
      const b = a + r() * 50;
      u = addInterval(u, [a, Math.min(300, b)]);
      const len = totalLength(u);
      expect(len).toBeGreaterThanOrEqual(prev - 1e-9);
      expect(len).toBeLessThanOrEqual(300 + 1e-9);
      prev = len;
      expect(unionIntervals([...u, ...u])).toEqual(u);
    }
  });

  it('opposite directions never alias and replays are idempotent', async () => {
    const o = new Osm().node(1, 0, 0).node(2, 250, 0).way(1, [1, 2], { highway: 'residential' });
    const g = await normalizeOverpass(o.json(), { fetchedAt: 'x', providerId: 'fixture' });
    const gi = new GraphIndex(g);
    let st = initialMatcherState();
    const events: SessionEvent[] = [];
    let seq = 0;
    for (const ob of track([[0, 0], [250, 0]], { idPrefix: 'o', speed: 12, accuracy: 6, noise: 3, seed: 7 })) {
      const res = processObservation(gi, st, ob, ob.timestamp);
      st = res.state;
      for (const t of res.traversals) events.push({ eventId: `e${++seq}`, seq, sessionId: 's', type: 'traversal_confirmed', timestamp: ob.timestamp, schemaVersion: '1.0.0', payload: { traversal: t } });
    }
    const ctx = { sessionId: 's', graphHash: g.contentHash, edgeLengths: new Map(g.directedEdges.map((e) => [e.edgeId, e.lengthMeters])) };
    const p1 = applyEvents(emptyProgress(), events, ctx);
    const p2 = applyEvents(applyEvents(emptyProgress(), events, ctx), events, ctx);
    expect([...p2.union.entries()]).toEqual([...p1.union.entries()]);
    expect(p1.union.get('osm:w1:n1-n2:b')).toBeUndefined();
    expect(totalLength(p1.union.get('osm:w1:n1-n2:f') ?? [])).toBeGreaterThan(200);
    // replay with duplicated traversal under a new event id does not double credit
    const dup = { ...events[0], eventId: 'dup', seq: seq + 1 };
    const p3 = applyEvents(emptyProgress(), [...events, dup], ctx);
    expect(totalLength(p3.union.get('osm:w1:n1-n2:f') ?? [])).toBeCloseTo(totalLength(p1.union.get('osm:w1:n1-n2:f') ?? []), 6);
    // blocked is never covered; confirmed meters bounded
    const req = computeRequirements(gi, { type: 'polygon', coordinates: rect(-20, -20, 270, 20) });
    const block: SessionEvent = { eventId: 'b', seq: seq + 2, sessionId: 's', type: 'requirement_excluded', timestamp: 0, schemaVersion: '1.0.0', payload: { requirementIds: ['osm:w1:n1-n2:b#0'], reason: 'blocked' } };
    const p4 = applyEvents(emptyProgress(), [...events, block], ctx);
    const { metrics, statuses } = computeMetrics(req.requirements, p4);
    expect(statuses.get('osm:w1:n1-n2:b#0')).toBe('blocked');
    expect(metrics.confirmedMeters).toBeLessThanOrEqual(metrics.totalRequiredMeters + 1e-6);
    expect(metrics.confirmedMeters + metrics.remainingMeters + metrics.blockedMeters + metrics.unreachableMeters + metrics.needsReviewMeters).toBeCloseTo(metrics.totalRequiredMeters, 6);
    // foreign session events rejected
    const foreign = { ...events[0], eventId: 'f', sessionId: 'other', seq: 999 };
    const p5 = applyEvents(emptyProgress(), [foreign], ctx);
    expect(p5.union.size).toBe(0);
    expect(p5.rejectedEvents[0].reason).toBe('foreign_session');
  });

  it('graph/revision mismatch cannot overwrite current plan', () => {
    const ctx = { graphHash: 'a', zoneVersion: 1, latestRequestedPlanningRevision: 3, acceptedPlanningRevision: 2, progressRevision: 5 };
    expect(decidePlan(ctx, { graphHash: 'b', zoneVersion: 1, planningRevision: 3, basedOnProgressRevision: 5, status: 'complete' }).accept).toBe(false);
    expect(decidePlan(ctx, { graphHash: 'a', zoneVersion: 1, planningRevision: 2, basedOnProgressRevision: 5, status: 'complete' }).accept).toBe(false);
  });
});

describe('zone geometry', () => {
  it('rejects self-intersecting polygons and tiny zones', () => {
    const bow = { type: 'polygon' as const, coordinates: [[44.5, 40.18], [44.502, 40.182], [44.502, 40.18], [44.5, 40.182]] as [number, number][] };
    expect(validateZone(bow).errors.map((e) => e.code)).toContain('self_intersection');
    expect(validateZone({ type: 'circle', center: [44.5, 40.18], radiusMeters: 5 }).ok).toBe(false);
  });
  it('circle approximation respects tolerance and keeps definition', () => {
    const c = circleApproximation([44.5, 40.18], 500, 1);
    expect(c.toleranceMeters).toBeLessThanOrEqual(1.0001);
    const v = validateZone({ type: 'circle', center: [44.5, 40.18], radiusMeters: 500, approximation: c });
    expect(v.ok).toBe(true);
    expect(v.areaSqMeters).toBeGreaterThan(Math.PI * 500 * 500 * 0.995);
    expect(v.areaSqMeters).toBeLessThan(Math.PI * 500 * 500);
  });
});
