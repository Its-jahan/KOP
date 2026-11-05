import { describe, expect, it } from 'vitest';
import { Osm, rect } from '../../scripts/fixture-lib';
import { normalizeOverpass } from '../../src/core/osm';
import { GraphIndex } from '../../src/core/graph';
import { computeRequirements, requirementCovered } from '../../src/core/requirements';
import { confirmDay, newlyDriven, planCampaign, undoLastDay, type Campaign } from '../../src/core/campaign';
import type { Interval } from '../../src/core/intervals';

const n = 8;
const o = new Osm();
const id = (i: number, j: number) => i * n + j + 1;
for (let i = 0; i < n; i++) for (let j = 0; j < n; j++) o.node(id(i, j), j * 100, i * 100);
let w = 1000;
for (let i = 0; i < n; i++) for (let j = 0; j < n - 1; j++) o.way(w++, [id(i, j), id(i, j + 1)], { highway: 'residential' });
for (let j = 0; j < n; j++) for (let i = 0; i < n - 1; i++) o.way(w++, [id(i, j), id(i + 1, j)], { highway: 'residential' });
const g = await normalizeOverpass(o.json(), { fetchedAt: 'x', providerId: 'fixture' });
const gi = new GraphIndex(g);
const zone = { type: 'polygon' as const, coordinates: rect(-40, -40, (n - 1) * 100 + 40, (n - 1) * 100 + 40) };
const req = computeRequirements(gi, zone);
const base: Campaign = {
  campaignId: 'c1', name: 't', createdAt: 'x', graphId: g.graphId, contentHash: g.contentHash, zone, policy: 'STRICT_INSIDE',
  home: { requested: [0, 0], edgeId: 'osm:w1000:n1-n2:f', offsetMeters: 0 },
  settings: { hoursPerDay: 0.5, speedKmh: 14 }, confirmed: [], history: [],
};

describe('campaign (multi-day plan)', () => {
  const p = planCampaign(gi, req.zoneInfo, req.requirements, base, { planId: 'p', createdAt: 'x' });
  it('splits all plannable work into days within the budget', () => {
    expect(p.days.length).toBeGreaterThan(2);
    const budget = 0.5 * 14 * 1000;
    for (const d of p.days) {
      if (d.steps.length > 1) expect(d.totalMeters).toBeLessThanOrEqual(budget + 1);
      expect(d.targetIds.length).toBeGreaterThan(0);
    }
    const all = new Set(p.days.flatMap((d) => d.targetIds));
    expect(all).toEqual(new Set(p.fullPlan.coveredRequirementIds));
    expect(p.unplannable).toEqual([]);
  });
  it('day 1 starts at home; later days start with an approach from home', () => {
    expect(p.days[0].approach).toEqual([]);
    expect(p.days[0].steps[0].edgeId).toBe(base.home.edgeId);
    expect(p.days[1].approach.length).toBeGreaterThan(0);
    expect(p.days[1].approach[0].edgeId).toBe(base.home.edgeId);
  });
  it('confirming a driven day removes it from the remaining plan; undo restores it', () => {
    // simulate driving day 1 exactly
    const driven = new Map<string, Interval[]>();
    for (const s of p.days[0].steps) driven.set(s.edgeId, [...(driven.get(s.edgeId) ?? []), [s.fromOffsetMeters, s.toOffsetMeters]]);
    const done = newlyDriven(req.requirements, new Map(), driven);
    expect(done.length).toBe(p.days[0].targetIds.length);
    const c2 = confirmDay(base, done, driven, 's1', '2026-11-05');
    const p2 = planCampaign(gi, req.zoneInfo, req.requirements, c2, { planId: 'p2', createdAt: 'x' });
    const remainingTargets = new Set(p2.days.flatMap((d) => d.targetIds));
    for (const r of done) expect(remainingTargets.has(r.requirementId)).toBe(false);
    const conf = new Map(c2.confirmed);
    for (const r of done) expect(requirementCovered(conf.get(r.edgeId) ?? [], r)).toBe(true);
    expect(p2.remainingMeters).toBeLessThan(p.remainingMeters);
    const c3 = undoLastDay(c2, req.requirements);
    expect(c3.history.length).toBe(0);
    const p3 = planCampaign(gi, req.zoneInfo, req.requirements, c3, { planId: 'p3', createdAt: 'x' });
    expect(p3.remainingMeters).toBeCloseTo(p.remainingMeters, 3);
  });
  it('only what was confirmed counts: driving without confirming changes nothing', () => {
    const p4 = planCampaign(gi, req.zoneInfo, req.requirements, base, { planId: 'p4', createdAt: 'x' });
    expect(p4.remainingMeters).toBeCloseTo(p.remainingMeters, 3);
  });
});
