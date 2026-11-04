import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { describe, expect, it } from 'vitest';
import { Store, StorageError, type SessionHeader } from '../../src/storage/db';
import type { SessionEvent } from '../../src/core/types';

const header = (id: string): SessionHeader => ({
  schemaVersion: '1.0.0', sessionId: id, graphId: 'g-0000000000000000', contentHash: '0'.repeat(64),
  zone: { zoneId: 'z', zoneVersion: 1, definition: { type: 'circle', center: [44.5, 40.18], radiusMeters: 100 } },
  vehicleProfile: 'passenger_car', boundaryPolicy: 'STRICT_INSIDE', returnToStart: false,
  startSelection: { requested: [44.5, 40.18], snapped: null, confirmed: false }, createdAt: 'x', updatedAt: 0, status: 'ready', lastSeq: 1, requirements: [], label: 't',
});
const ev = (sid: string, seq: number): SessionEvent => ({ eventId: `${sid}:${seq}`, seq, sessionId: sid, type: 'paused', timestamp: seq, schemaVersion: '1.0.0', payload: {} });

describe('IndexedDB store', () => {
  it('appends atomically, rejects sequence violations without partial writes, isolates sessions', async () => {
    const factory = new IDBFactory();
    const s = await Store.open(factory);
    await s.createSession(header('a'), [ev('a', 1)]);
    await s.createSession(header('b'), [ev('b', 1)]);
    await s.appendEvents('a', [ev('a', 2), ev('a', 3)], { status: 'active' });
    await expect(s.appendEvents('a', [ev('a', 4), ev('a', 6)])).rejects.toBeInstanceOf(StorageError);
    const a = await s.getSession('a');
    expect(a!.events.map((e) => e.seq)).toEqual([1, 2, 3]);
    expect(a!.header.lastSeq).toBe(3);
    expect(a!.header.status).toBe('active');
    const b = await s.getSession('b');
    expect(b!.events.map((e) => e.eventId)).toEqual(['b:1']);
    s.close();
    // reopen (simulated reload) keeps state
    const s2 = await Store.open(factory);
    expect((await s2.getSession('a'))!.events.length).toBe(3);
    await s2.deleteSession('a');
    expect(await s2.getSession('a')).toBeNull();
    expect((await s2.listSessions()).map((h) => h.sessionId)).toEqual(['b']);
  });

  it('recovers the longest valid prefix if stored events are corrupt', async () => {
    const factory = new IDBFactory();
    const s = await Store.open(factory);
    await s.createSession({ ...header('c'), lastSeq: 4 }, [ev('c', 1), ev('c', 2), { ...ev('c', 3), payload: null as unknown as Record<string, unknown> }, ev('c', 4)]);
    const c = await s.getSession('c');
    expect(c!.events.map((e) => e.seq)).toEqual([1, 2]);
    expect(c!.header.lastSeq).toBe(2);
    // repaired: appending continues from the recovered sequence
    await s.appendEvents('c', [ev('c', 3)]);
    expect((await s.getSession('c'))!.events.map((e) => e.seq)).toEqual([1, 2, 3]);
  });

  it('reports unavailable IndexedDB explicitly', async () => {
    await expect(Store.open(null)).rejects.toBeInstanceOf(StorageError);
  });

  it('maps quota exhaustion to an explicit storage error and keeps prior data', async () => {
    const factory = new IDBFactory();
    const s = await Store.open(factory);
    await s.createSession(header('q'), [ev('q', 1)]);
    const proto = Object.getPrototypeOf((globalThis as unknown as { IDBObjectStore: { prototype: object } }).IDBObjectStore.prototype) ? (globalThis as unknown as { IDBObjectStore: { prototype: { add: (...a: unknown[]) => unknown } } }).IDBObjectStore.prototype : null;
    const orig = proto!.add;
    proto!.add = function () {
      throw new DOMException('quota', 'QuotaExceededError');
    };
    try {
      const err = await s.appendEvents('q', [ev('q', 2)]).catch((e) => e);
      expect(err).toBeInstanceOf(StorageError);
      expect((err as StorageError).code).toBe('quota_exceeded');
    } finally {
      proto!.add = orig;
    }
    const q = await s.getSession('q');
    expect(q!.events.map((e) => e.seq)).toEqual([1]);
    expect(q!.header.lastSeq).toBe(1);
  });
});

