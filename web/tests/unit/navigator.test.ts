import { describe, expect, it } from 'vitest';
import { Osm, ll, obs } from '../../scripts/fixture-lib';
import { normalizeOverpass } from '../../src/core/osm';
import { GraphIndex } from '../../src/core/graph';
import { computeRequirements } from '../../src/core/requirements';
import { planCoverage } from '../../src/core/planner';
import { buildManeuvers, stepStartDistances } from '../../src/core/maneuvers';
import { Navigator } from '../../src/core/navigator';

// A(0,0)-B(100,0)-C(200,0); B-D(100,100); C-E(200,100); D-E; spur D-F(0,100) outside the zone
const o = new Osm().node(1, 0, 0).node(2, 100, 0).node(3, 200, 0).node(4, 100, 100).node(5, 200, 100).node(6, 0, 100)
  .way(101, [1, 2, 3], { highway: 'residential', name: 'Main' }).way(102, [2, 4], { highway: 'residential', name: 'West' })
  .way(103, [3, 5], { highway: 'residential', name: 'East' }).way(104, [4, 5], { highway: 'residential', name: 'North' })
  .way(105, [6, 4], { highway: 'residential', name: 'Spur' });
const g = await normalizeOverpass(o.json(), { fetchedAt: 'x', providerId: 'fixture' });
const gi = new GraphIndex(g);
const zone = { type: 'polygon' as const, coordinates: [ll(-30, -30), ll(230, -30), ll(230, 130), ll(60, 130), ll(60, 40), ll(-30, 40)] };
const req = computeRequirements(gi, zone);
const plan = planCoverage({
  gi, zoneInfo: req.zoneInfo, requirements: req.requirements, observed: new Map(), excluded: new Set(), policy: 'STRICT_INSIDE',
  start: { edgeId: 'osm:w101:n1-n2:f', offsetMeters: 0, source: 'selected_start' },
  meta: { planId: 'p1', graphHash: g.contentHash, zoneVersion: 1, progressRevision: 0, planningRevision: 1, createdAt: 'x' },
});
const mans = buildManeuvers(gi, plan);
const starts = stepStartDistances(plan);
const fix = (x: number, y: number, bearing: number | null, speed: number | null, acc = 5) => {
  const [lon, lat] = ll(x, y);
  return obs('o', 0, lon, lat, { accuracy: acc, speed, bearing });
};

describe('Navigator (turn-by-turn)', () => {
  it('on the route: next turn with distance and the route ahead', () => {
    const n = new Navigator().compute(gi, plan, mans, starts, fix(50, 1, 90, 10), 500, 0)!;
    expect(n.mode).toBe('route');
    expect(['left', 'right', 'turnaround']).toContain(n.type);
    expect(n.distanceMeters).toBeGreaterThan(30);
    expect(n.distanceMeters).toBeLessThan(200);
    expect(n.ahead.length).toBeGreaterThan(0);
  });
  it('standing still at the start still gives instructions', () => {
    expect(new Navigator().compute(gi, plan, mans, starts, fix(2, 3, null, 0), 500, 0)!.mode).toBe('route');
  });
  it('away from the route: legal approach back to it', () => {
    const n = new Navigator().compute(gi, plan, mans, starts, fix(20, 100, 90, 8), 500, 0)!;
    expect(n.mode).toBe('approach');
    expect(n.rejoinMeters!).toBeGreaterThan(150);
    expect(n.distanceMeters).toBeGreaterThan(50);
    expect(n.distanceMeters).toBeLessThan(120);
  });
  it('off any road: points towards the route', () => {
    const n = new Navigator().compute(gi, plan, mans, starts, fix(-150, 300, null, null), 500, 0)!;
    expect(n.mode).toBe('to_road');
    expect(n.headBearing!).toBeGreaterThan(90);
    expect(n.headBearing!).toBeLessThan(270);
  });
  it('stale or inaccurate fix: no instruction', () => {
    const nav = new Navigator();
    expect(nav.compute(gi, plan, mans, starts, fix(50, 0, 90, 10), 20000, 0)).toBeNull();
    expect(nav.compute(gi, plan, mans, starts, fix(50, 0, 90, 10, 120), 500, 0)).toBeNull();
  });
});

import { laneHints } from '../../src/core/navigator';

describe('lane guidance', () => {
  it('right turn: only lanes that allow a right turn are marked', () => {
    const h = laneHints([['left'], ['through'], ['through', 'right']], 'right')!;
    expect(h.map((x) => x.ok)).toEqual([false, false, true]);
  });
  it('left turn and straight on', () => {
    expect(laneHints([['left'], ['left', 'through'], ['through', 'right']], 'left')!.map((x) => x.ok)).toEqual([true, true, false]);
    expect(laneHints([['left'], ['through'], ['right']], 'continue')!.map((x) => x.ok)).toEqual([false, true, false]);
  });
  it('no advice when every lane works, nothing matches, or lanes are unmapped', () => {
    expect(laneHints([['through', 'right'], ['through', 'right']], 'right')).toBeNull();
    expect(laneHints([['left'], ['through']], 'right')).toBeNull();
    expect(laneHints(undefined, 'right')).toBeNull();
  });
  it('navigator attaches lanes from the approaching road to the next maneuver', async () => {
    // Approach road A(0,0)->B(150,0) has lanes; at B the route turns right (south) to C(150,-100).
    const o2 = new Osm().node(1, 0, 0).node(2, 150, 0).node(3, 150, -100).node(4, 300, 0)
      .way(1, [1, 2], { highway: 'secondary', oneway: 'yes', 'turn:lanes': 'left|through|through;right' })
      .way(2, [2, 3], { highway: 'secondary', oneway: 'yes' }).way(3, [2, 4], { highway: 'secondary', oneway: 'yes' });
    const g2 = await normalizeOverpass(o2.json(), { fetchedAt: 'x', providerId: 'fixture' });
    const gi2 = new GraphIndex(g2);
    const z2 = { type: 'polygon' as const, coordinates: [ll(-30, 30), ll(170, 30), ll(170, -130), ll(-30, -130)] };
    const r2 = computeRequirements(gi2, z2);
    const p2 = planCoverage({
      gi: gi2, zoneInfo: r2.zoneInfo, requirements: r2.requirements, observed: new Map(), excluded: new Set(), policy: 'STRICT_INSIDE',
      start: { edgeId: 'osm:w1:n1-n2:f', offsetMeters: 0, source: 'selected_start' },
      meta: { planId: 'p2', graphHash: g2.contentHash, zoneVersion: 1, progressRevision: 0, planningRevision: 1, createdAt: 'x' },
    });
    expect(p2.steps.map((s) => s.edgeId)).toEqual(['osm:w1:n1-n2:f', 'osm:w2:n2-n3:f']);
    const n = new Navigator().compute(gi2, p2, buildManeuvers(gi2, p2), stepStartDistances(p2), fix(80, 1, 90, 10), 500, 0)!;
    expect(n.type).toBe('right');
    expect(n.lanes!.map((l) => l.ok)).toEqual([false, false, true]);
  });
});
