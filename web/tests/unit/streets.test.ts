import { describe, expect, it } from 'vitest';
import { Osm, ll, rect } from '../../scripts/fixture-lib';
import { normalizeOverpass } from '../../src/core/osm';
import { GraphIndex } from '../../src/core/graph';
import { computeRequirements } from '../../src/core/requirements';
import { filterRequirementsByStreets, parseStreetSet } from '../../src/core/streets';
import { planCampaign, type Campaign } from '../../src/core/campaign';

// 4x4 grid, 100 m blocks; the street set is the bottom row (y = 0) plus the left column (x = 0).
const n = 4;
const o = new Osm();
const id = (i: number, j: number) => i * n + j + 1;
for (let i = 0; i < n; i++) for (let j = 0; j < n; j++) o.node(id(i, j), j * 100, i * 100);
let w = 1000;
for (let i = 0; i < n; i++) for (let j = 0; j < n - 1; j++) o.way(w++, [id(i, j), id(i, j + 1)], { highway: 'residential' });
for (let j = 0; j < n; j++) for (let i = 0; i < n - 1; i++) o.way(w++, [id(i, j), id(i + 1, j)], { highway: 'residential' });
const g = await normalizeOverpass(o.json(), { fetchedAt: 'x', providerId: 'fixture' });
const gi = new GraphIndex(g);
const zone = { type: 'polygon' as const, coordinates: rect(-40, -40, 340, 340) };
const req = computeRequirements(gi, zone);
// traced lines are slightly offset (3 m) like a hand/photo trace
const lines = [[ll(0, 3), ll(300, 3)], [ll(-3, 0), ll(-3, 300)]];
const total = (a: { lengthMeters: number }[]) => a.reduce((s, r) => s + r.lengthMeters, 0);

describe('street sets', () => {
  it('keeps only requirements along the listed streets (both directions)', () => {
    const f = filterRequirementsByStreets(gi, req.requirements, lines);
    expect(total(f)).toBeCloseTo(1200, -1); // 2 streets x 300 m x 2 directions
    for (const r of f) {
      const e = gi.edges[gi.idx.get(r.edgeId)!];
      const ok = e.geometry.every(([lon, lat]) => Math.abs(lat - ll(0, 0)[1]) < 1e-6 || Math.abs(lon - ll(0, 0)[0]) < 1e-6);
      expect(ok).toBe(true);
    }
  });

  it('ignores crossing streets that only touch a listed street', () => {
    const f = filterRequirementsByStreets(gi, req.requirements, [[ll(0, 100), ll(300, 100)]]);
    expect(total(f)).toBeCloseTo(600, -1);
  });

  it('parses our format and GeoJSON', () => {
    const a = parseStreetSet({ id: 'x', name: { fa: 'x', en: 'x' }, ring: zone.coordinates, lines }, 'f');
    expect(a.lines.length).toBe(2);
    const b = parseStreetSet({ type: 'FeatureCollection', features: [
      { type: 'Feature', geometry: { type: 'Polygon', coordinates: [[...zone.coordinates, zone.coordinates[0]]] } },
      { type: 'Feature', geometry: { type: 'MultiLineString', coordinates: lines } },
    ] }, 'f');
    expect(b.ring.length).toBe(zone.coordinates.length);
    expect(b.lines.length).toBe(2);
    expect(() => parseStreetSet({ foo: 1 }, 'f')).toThrow();
  });

  it('a campaign with a street set plans only those streets but may drive through others', () => {
    const f = filterRequirementsByStreets(gi, req.requirements, lines);
    const start = gi.edges.find((e) => e.geometry[0][0] === ll(0, 0)[0] && e.geometry[0][1] === ll(0, 0)[1] && e.geometry[1][1] === ll(0, 0)[1])!;
    const c: Campaign = {
      campaignId: 'c', name: 'c', createdAt: 'x', graphId: g.graphId, contentHash: g.contentHash, zone, policy: 'STRICT_INSIDE',
      home: { requested: ll(0, 0), edgeId: start.edgeId, offsetMeters: 0 }, settings: { hoursPerDay: 4, speedKmh: 14 }, confirmed: [], history: [],
      streetSet: { id: 's', name: { fa: 's', en: 's' }, ring: zone.coordinates, lines },
    };
    const p = planCampaign(gi, req.zoneInfo, f, c, { planId: 'p', createdAt: 'x' });
    expect(p.remainingMeters).toBeCloseTo(1200, -1);
    expect(p.unplannable).toEqual([]);
    const targets = new Set(p.days.flatMap((d) => d.targetIds));
    expect(targets.size).toBe(f.length);
  });
});
