// Recovery of a session from a persisted storage image after an interrupted write.
// Storage writes are transactional on both platforms; this pure function defines
// what the recovered state must be for any image (including torn/corrupt records).

import type { SessionEvent } from './types';

export interface StorageImage {
  checkpoint: { sessionId: string; seq: number; checksum: string } | null;
  /** Raw stored event records (may contain corrupt JSON or partial records). */
  eventRecords: string[];
}

export interface RecoveredLog {
  events: SessionEvent[];
  discarded: { index: number; reason: string }[];
  lastValidSeq: number;
}

function isEvent(x: unknown): x is SessionEvent {
  const e = x as SessionEvent;
  return !!e && typeof e.eventId === 'string' && typeof e.seq === 'number' && Number.isInteger(e.seq) && typeof e.sessionId === 'string' && typeof e.type === 'string' && typeof e.payload === 'object' && e.payload !== null;
}

/**
 * Keep the longest valid prefix of strictly increasing events for the session.
 * A corrupt record or a sequence break ends the valid log; later records are
 * discarded (never partially applied). Duplicate eventIds are dropped.
 */
export function recoverEventLog(sessionId: string, img: StorageImage): RecoveredLog {
  const events: SessionEvent[] = [];
  const discarded: RecoveredLog['discarded'] = [];
  const ids = new Set<string>();
  let last = 0;
  let broken = false;
  img.eventRecords.forEach((raw, index) => {
    if (broken) {
      discarded.push({ index, reason: 'after_corruption' });
      return;
    }
    let ev: unknown;
    try {
      ev = JSON.parse(raw);
    } catch {
      discarded.push({ index, reason: 'corrupt_record' });
      broken = true;
      return;
    }
    if (!isEvent(ev)) {
      discarded.push({ index, reason: 'invalid_record' });
      broken = true;
      return;
    }
    if (ev.sessionId !== sessionId) {
      discarded.push({ index, reason: 'foreign_session' });
      return;
    }
    if (ids.has(ev.eventId)) {
      discarded.push({ index, reason: 'duplicate_event' });
      return;
    }
    if (ev.seq !== last + 1) {
      discarded.push({ index, reason: 'sequence_gap' });
      broken = true;
      return;
    }
    ids.add(ev.eventId);
    events.push(ev);
    last = ev.seq;
  });
  return { events, discarded, lastValidSeq: last };
}
