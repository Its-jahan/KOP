// DriveController regression tests for the independent-review findings (no browser).
import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { describe, expect, it } from 'vitest';
import { Osm, ll, obs, rect, T0 } from '../../scripts/fixture-lib';

const g0 = globalThis as unknown as Record<string, unknown>;
g0.document = { visibilityState: 'visible', addEventListener: () => undefined, removeEventListener: () => undefined };
g0.window = { isSecureContext: true };
let watchCb: ((p: unknown) => void) | null = null;
Object.defineProperty(globalThis, 'navigator', {
  value: { geolocation: { watchPosition: (cb: (p: unknown) => void) => ((watchCb = cb), 1), clearWatch: () => undefined } },
  configurable: true,
});

const { normalizeOverpass } = await import('../../src/core/osm');
const { GraphIndex } = await import('../../src/core/graph');
const { computeRequirements } = await import('../../src/core/requirements');
const { planCoverage } = await import('../../src/core/planner');
const { validatePlan } = await import('../../src/core/validator');
const { Store } = await import('../../src/storage/db');
const { DriveController } = await import('../../src/ui/driveController');

const o = new Osm();
const nid = (i: number, j: number) => i * 3 + j + 1;
for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) o.node(nid(i, j), j * 150, i * 150);
let w = 100;
for (let i = 0; i < 3; i++) for (let j = 0; j < 2; j++) o.way(w++, [nid(i, j), nid(i, j + 1)], { highway: 'residential' });
for (let j = 0; j < 3; j++) for (let i = 0; i < 2; i++) o.way(w++, [nid(i, j), nid(i + 1, j)], { highway: 'residential' });
const g = await normalizeOverpass(o.json(), { fetchedAt: 'x' });
const gi = new GraphIndex(g);
const zone = { type: 'polygon' as const, coordinates: rect(-20, -20, 320, 320) };
const req = computeRequirements(gi, zone);
let engineCalls = 0;
const engine = {
  call: async (r: { requirements: never; observed: never; excluded: string[]; policy: never; start: { edgeId: string }; meta: never }) => {
    engineCalls++;
    const plan = planCoverage({ gi, zoneInfo: req.zoneInfo, requirements: r.requirements, observed: new Map(r.observed), excluded: new Set(r.excluded), policy: r.policy, start: r.start as never, startPrefix: [gi.idx.get(r.start.edgeId)!], meta: r.meta });
    const validation = validatePlan(gi, plan, { zoneInfo: req.zoneInfo, policy: r.policy, requirements: r.requirements, observed: new Map(r.observed), excluded: new Set(r.excluded), startPrefix: [gi.idx.get(r.start.edgeId)!] });
    return { plan, validation, ms: 1 };
  },
} as never;

async function session(store: Awaited<ReturnType<typeof Store.open>>, sid: string) {
  const p = planCoverage({ gi, zoneInfo: req.zoneInfo, requirements: req.requirements, observed: new Map(), excluded: new Set(), policy: 'STRICT_INSIDE', start: { edgeId: 'osm:w100:n1-n2:f', offsetMeters: 0, source: 'selected_start' }, meta: { planId: 'p1', graphHash: g.contentHash, zoneVersion: 1, progressRevision: 0, planningRevision: 1, createdAt: 'x' } });
  const header = {
    schemaVersion: '1.0.0', sessionId: sid, graphId: g.graphId, contentHash: g.contentHash, zone: { zoneId: 'z', zoneVersion: 1, definition: zone },
    vehicleProfile: 'passenger_car' as const, boundaryPolicy: 'STRICT_INSIDE' as const, returnToStart: false,
    startSelection: { requested: ll(0, 0), snapped: null, confirmed: true }, createdAt: 'x', updatedAt: 0, status: 'ready' as const, lastSeq: 2, requirements: req.requirements, label: 't',
  };
  const events = [
    { eventId: `${sid}:1`, seq: 1, sessionId: sid, type: 'session_created' as const, timestamp: 0, schemaVersion: '1.0.0', payload: {} },
    { eventId: `${sid}:2`, seq: 2, sessionId: sid, type: 'plan_accepted' as const, timestamp: 0, schemaVersion: '1.0.0', payload: { plan: p } },
  ];
  await store.createSession(header, events);
  return { header, events };
}

function feed(xs: [number, number][], t0: number) {
  xs.forEach(([x, y], k) => {
    const [lon, lat] = ll(x, y);
    const o2 = obs(`f${t0}-${k}`, t0 + k * 1000, lon, lat, { accuracy: 5, speed: 10, bearing: 90 });
    watchCb!({ timestamp: o2.timestamp, coords: { latitude: lat, longitude: lon, accuracy: 5, speed: 10, heading: 90, altitude: null, altitudeAccuracy: null } });
  });
}
const settle = () => new Promise((r) => setTimeout(r, 30));

describe('DriveController (review regressions)', () => {
  it('a failed write is retried in order and nothing is lost', async () => {
    const store = await Store.open(new IDBFactory());
    const { header, events } = await session(store, 's1');
    const dc = new DriveController(store, engine, g, gi, req.zoneInfo, header, events);
    await dc.startOrResume();
    const orig = store.appendEvents.bind(store);
    let failOnce = true;
    store.appendEvents = (async (...a: Parameters<typeof orig>) => {
      if (failOnce) {
        failOnce = false;
        throw new Error('QuotaExceededError');
      }
      return orig(...a);
    }) as typeof store.appendEvents;
    const now = Date.now();
    feed(Array.from({ length: 12 }, (_, k) => [k * 10, 0] as [number, number]), now - 12_000);
    await settle();
    feed([[130, 0], [140, 0]], now - 300);
    await settle();
    await (dc as unknown as { flush: () => Promise<void> }).flush();
    const stored = await store.getSession('s1');
    const traversals = stored!.events.filter((e) => e.type === 'traversal_confirmed').length;
    expect(traversals).toBeGreaterThan(0);
    expect(stored!.events.length).toBe(stored!.header.lastSeq);
    dc.dispose();
  });

  it('reload while active is recorded as paused and resumes with a fresh plan from the real position', async () => {
    const store = await Store.open(new IDBFactory());
    const { header, events } = await session(store, 's2');
    const dc = new DriveController(store, engine, g, gi, req.zoneInfo, header, events);
    await dc.startOrResume();
    dc.dispose();
    const loaded = await store.getSession('s2');
    const dc2 = new DriveController(store, engine, g, gi, req.zoneInfo, loaded!.header, loaded!.events);
    let snap: { status: string; plan: unknown; notice: string } = { status: '', plan: null, notice: '' };
    dc2.subscribe((s) => (snap = s));
    expect(snap.plan).toBeNull();
    await dc2.recoverIfInterrupted();
    expect(snap.status).toBe('paused');
    expect(snap.notice).toBe('recovered');
    dc2.dispose();
  });

  it('report-blocked on the current road replans (start edge exempt) and never counts it as covered', async () => {
    const store = await Store.open(new IDBFactory());
    const { header, events } = await session(store, 's3');
    const dc = new DriveController(store, engine, g, gi, req.zoneInfo, header, events);
    let snap: { matched: { edgeId: string } | null; plan: { steps: { edgeId: string }[] } | null; statuses: Map<string, string>; notice: string } = { matched: null, plan: null, statuses: new Map(), notice: '' };
    dc.subscribe((s) => (snap = s as never));
    await dc.startOrResume();
    feed(Array.from({ length: 6 }, (_, k) => [k * 10, 0] as [number, number]), Date.now() - 6000);
    await settle();
    expect(snap.matched?.edgeId).toBe('osm:w100:n1-n2:f');
    const before = engineCalls;
    expect(await dc.blockCurrentRoad()).toBe(true);
    await settle();
    expect(engineCalls).toBeGreaterThan(before);
    expect(snap.notice).not.toBe('outside_policy');
    expect(snap.statuses.get('osm:w100:n1-n2:f#0')).toBe('blocked');
    expect(snap.statuses.get('osm:w100:n1-n2:b#0')).toBe('blocked');
    // the new plan may finish the current edge but never re-enters the blocked road later
    const later = snap.plan!.steps.slice(1).map((s) => s.edgeId);
    expect(later).not.toContain('osm:w100:n1-n2:f');
    expect(later).not.toContain('osm:w100:n1-n2:b');
    dc.dispose();
  });
});
void T0;
