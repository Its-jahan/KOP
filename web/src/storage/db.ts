// IndexedDB persistence: graphs, session headers, append-only event log.
// Every progress write is one readwrite transaction over `events` + `sessions`
// so a reload/kill leaves either the old or the new state, never a mix.

import type {
  BoundaryPolicy,
  GraphSnapshot,
  RequiredInterval,
  SessionEvent,
  SessionStatus,
  StartSelection,
  Zone,
} from '../core/types';

export const DB_NAME = 'della-coverage-navigator';
export const DB_VERSION = 1;

export interface SessionHeader {
  schemaVersion: string;
  sessionId: string;
  graphId: string;
  contentHash: string;
  zone: Zone;
  vehicleProfile: 'passenger_car';
  boundaryPolicy: BoundaryPolicy;
  returnToStart: boolean;
  startSelection: StartSelection;
  createdAt: string;
  updatedAt: number;
  status: SessionStatus;
  lastSeq: number;
  requirements: RequiredInterval[];
  label: string;
  /** Set when this session drives one day of a multi-day campaign. */
  campaignId?: string;
}

export class StorageError extends Error {
  constructor(public readonly code: 'quota_exceeded' | 'unavailable' | 'corrupt' | 'blocked' | 'unknown', msg: string) {
    super(msg);
  }
}

function wrap<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((res, rej) => {
    req.onsuccess = () => res(req.result);
    req.onerror = () => rej(req.error);
  });
}

function txDone(tx: IDBTransaction): Promise<void> {
  return new Promise((res, rej) => {
    tx.oncomplete = () => res();
    tx.onabort = () => rej(tx.error ?? new Error('transaction aborted'));
    tx.onerror = () => rej(tx.error);
  });
}

function mapError(e: unknown): StorageError {
  const name = (e as DOMException)?.name;
  if (name === 'QuotaExceededError') return new StorageError('quota_exceeded', 'Storage quota exceeded');
  if (name === 'InvalidStateError' || name === 'UnknownError') return new StorageError('unavailable', String((e as Error)?.message ?? e));
  return new StorageError('unknown', String((e as Error)?.message ?? e));
}

export class Store {
  private constructor(private readonly db: IDBDatabase) {}

  static async open(factory: IDBFactory | null = globalThis.indexedDB ?? null): Promise<Store> {
    if (!factory) throw new StorageError('unavailable', 'IndexedDB is not available (private mode or unsupported browser).');
    const req = factory.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = (ev) => {
      const db = req.result;
      const old = (ev as IDBVersionChangeEvent).oldVersion;
      if (old < 1) {
        db.createObjectStore('graphs', { keyPath: 'graphId' });
        db.createObjectStore('sessions', { keyPath: 'sessionId' });
        const events = db.createObjectStore('events', { keyPath: ['sessionId', 'seq'] });
        events.createIndex('bySession', 'sessionId');
        db.createObjectStore('settings');
      }
      // Future migrations: if (old < 2) { ... } — never drop user data silently.
    };
    try {
      const db = await new Promise<IDBDatabase>((res, rej) => {
        req.onsuccess = () => res(req.result);
        req.onerror = () => rej(req.error);
        req.onblocked = () => rej(new StorageError('blocked', 'Database upgrade blocked by another tab.'));
      });
      return new Store(db);
    } catch (e) {
      throw e instanceof StorageError ? e : mapError(e);
    }
  }

  close() {
    this.db.close();
  }

  async putGraph(g: GraphSnapshot): Promise<void> {
    try {
      const tx = this.db.transaction('graphs', 'readwrite');
      tx.objectStore('graphs').put(g);
      await txDone(tx);
    } catch (e) {
      throw mapError(e);
    }
  }

  async getGraph(graphId: string): Promise<GraphSnapshot | null> {
    const tx = this.db.transaction('graphs', 'readonly');
    return ((await wrap(tx.objectStore('graphs').get(graphId))) as GraphSnapshot | undefined) ?? null;
  }

  async getGraphByHash(hash: string): Promise<GraphSnapshot | null> {
    return this.getGraph(`g-${hash.slice(0, 16)}`).then((g) => (g && g.contentHash === hash ? g : null));
  }

  async createSession(h: SessionHeader, events: SessionEvent[]): Promise<void> {
    try {
      const tx = this.db.transaction(['sessions', 'events'], 'readwrite');
      tx.objectStore('sessions').put(h);
      for (const e of events) tx.objectStore('events').put(e);
      await txDone(tx);
    } catch (e) {
      throw mapError(e);
    }
  }

  /** Atomically append events (seq must continue the header's lastSeq) and patch the header. */
  async appendEvents(sessionId: string, events: SessionEvent[], patch: Partial<SessionHeader> = {}): Promise<SessionHeader> {
    try {
      const tx = this.db.transaction(['sessions', 'events'], 'readwrite');
      const sessions = tx.objectStore('sessions');
      const h = (await wrap(sessions.get(sessionId))) as SessionHeader | undefined;
      if (!h) {
        tx.abort();
        throw new StorageError('corrupt', `session ${sessionId} missing`);
      }
      let seq = h.lastSeq;
      for (const e of events) {
        if (e.sessionId !== sessionId || e.seq !== seq + 1) {
          tx.abort();
          throw new StorageError('corrupt', `event sequence violation at ${e.seq}`);
        }
        seq = e.seq;
        tx.objectStore('events').add(e);
      }
      const next: SessionHeader = { ...h, ...patch, lastSeq: seq, updatedAt: Date.now() };
      sessions.put(next);
      await txDone(tx);
      return next;
    } catch (e) {
      throw e instanceof StorageError ? e : mapError(e);
    }
  }

  async getSession(sessionId: string): Promise<{ header: SessionHeader; events: SessionEvent[] } | null> {
    const tx = this.db.transaction(['sessions', 'events'], 'readonly');
    const header = (await wrap(tx.objectStore('sessions').get(sessionId))) as SessionHeader | undefined;
    if (!header) return null;
    const range = IDBKeyRange.bound([sessionId, 0], [sessionId, Number.MAX_SAFE_INTEGER]);
    const raw = (await wrap(tx.objectStore('events').getAll(range))) as SessionEvent[];
    // Keep the longest valid contiguous prefix (same rule as core/recovery.ts).
    const events: SessionEvent[] = [];
    let last = 0;
    for (const e of raw.sort((a, b) => a.seq - b.seq)) {
      if (!e || typeof e.eventId !== 'string' || e.seq !== last + 1 || !e.payload || typeof e.payload !== 'object') break;
      events.push(e);
      last = e.seq;
    }
    if (last !== header.lastSeq || events.length !== raw.length) {
      // Repair: drop records after the valid prefix so later appends continue cleanly.
      const fixed = { ...header, lastSeq: last, updatedAt: Date.now() };
      const wtx = this.db.transaction(['sessions', 'events'], 'readwrite');
      wtx.objectStore('events').delete(IDBKeyRange.bound([sessionId, last + 1], [sessionId, Number.MAX_SAFE_INTEGER]));
      wtx.objectStore('sessions').put(fixed);
      await txDone(wtx);
      return { header: fixed, events };
    }
    return { header, events };
  }

  async listSessions(): Promise<SessionHeader[]> {
    const tx = this.db.transaction('sessions', 'readonly');
    const all = (await wrap(tx.objectStore('sessions').getAll())) as SessionHeader[];
    return all.sort((a, b) => b.updatedAt - a.updatedAt);
  }

  async deleteSession(sessionId: string): Promise<void> {
    const tx = this.db.transaction(['sessions', 'events'], 'readwrite');
    tx.objectStore('sessions').delete(sessionId);
    tx.objectStore('events').delete(IDBKeyRange.bound([sessionId, 0], [sessionId, Number.MAX_SAFE_INTEGER]));
    await txDone(tx);
  }

  async getSetting<T>(key: string): Promise<T | undefined> {
    const tx = this.db.transaction('settings', 'readonly');
    return (await wrap(tx.objectStore('settings').get(key))) as T | undefined;
  }

  async setSetting(key: string, value: unknown): Promise<void> {
    const tx = this.db.transaction('settings', 'readwrite');
    tx.objectStore('settings').put(value, key);
    await txDone(tx);
  }
}

export async function storageEstimate(): Promise<{ usage: number; quota: number; persisted: boolean } | null> {
  try {
    const est = await navigator.storage?.estimate?.();
    const persisted = (await navigator.storage?.persisted?.()) ?? false;
    if (!est) return null;
    return { usage: est.usage ?? 0, quota: est.quota ?? 0, persisted };
  } catch {
    return null;
  }
}

export async function requestPersistence(): Promise<boolean> {
  try {
    return (await navigator.storage?.persist?.()) ?? false;
  } catch {
    return false;
  }
}
