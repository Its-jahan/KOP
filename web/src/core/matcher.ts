// Sequence-aware, accuracy-aware directed map matcher. Coverage is credited only
// for plausible, direction-consistent observed movement along graph geometry.

import type { GraphIndex } from './graph';
import { angleDiff, bearingAtOffset, haversine } from './geo';
import { MATCHER_VERSION, type LocationObservation, type LonLat, type MatchedTraversal } from './types';

export interface MatcherConfig {
  maxAccuracyM: number;
  maxFixAgeMs: number;
  maxGapMs: number;
  maxSpeedMps: number;
  searchRadiusMinM: number;
  searchRadiusMaxM: number;
  minAdvanceM: number;
  commitMinM: number;
  ambiguityMarginM: number;
  maxJunctionPathM: number;
  minMovingSpeedMps: number;
  headingMinSpeedMps: number;
  headingToleranceDeg: number;
}

export const DEFAULT_MATCHER_CONFIG: MatcherConfig = {
  maxAccuracyM: 30,
  maxFixAgeMs: 10_000,
  maxGapMs: 12_000,
  maxSpeedMps: 50,
  searchRadiusMinM: 12,
  searchRadiusMaxM: 45,
  minAdvanceM: 3,
  commitMinM: 12,
  ambiguityMarginM: 6,
  maxJunctionPathM: 80,
  minMovingSpeedMps: 1.0,
  headingMinSpeedMps: 3,
  headingToleranceDeg: 60,
};

export type GpsQuality = 'acquiring' | 'reliable' | 'weak_ambiguous' | 'stale' | 'off_graph';
export type GapReason = 'stale' | 'accuracy' | 'gap' | 'jump' | 'ambiguous' | 'off_graph';

interface Anchor {
  edge: number;
  offset: number;
  t: number;
  obsId: string;
}
interface Piece {
  edge: number;
  a: number;
  b: number;
  obsIds: string[];
  t0: number;
  t1: number;
  junction: boolean;
}

export interface MatcherState {
  version: string;
  anchor: Anchor | null;
  lastFix: { t: number; lon: number; lat: number; id: string } | null;
  lastGoodT: number | null;
  pending: Piece[];
  pendingLength: number;
  committed: boolean;
  recentIds: string[];
}

export function initialMatcherState(): MatcherState {
  return { version: MATCHER_VERSION, anchor: null, lastFix: null, lastGoodT: null, pending: [], pendingLength: 0, committed: false, recentIds: [] };
}

export interface MatchResult {
  state: MatcherState;
  traversals: MatchedTraversal[];
  quality: GpsQuality;
  ignored: 'duplicate' | 'out_of_order' | null;
  matched: { edgeId: string; offset: number; point: LonLat; committed: boolean } | null;
  gap: { reason: GapReason; fromT: number | null; toT: number } | null;
}

interface Cand {
  edge: number;
  offset: number;
  dist: number;
  point: LonLat;
}

function clone(s: MatcherState): MatcherState {
  return {
    ...s,
    anchor: s.anchor ? { ...s.anchor } : null,
    lastFix: s.lastFix ? { ...s.lastFix } : null,
    pending: s.pending.map((p) => ({ ...p, obsIds: [...p.obsIds] })),
    recentIds: [...s.recentIds],
  };
}

function flush(gi: GraphIndex, st: MatcherState): MatchedTraversal[] {
  const out: MatchedTraversal[] = [];
  // merge consecutive pieces on the same edge
  const merged: Piece[] = [];
  for (const p of st.pending) {
    const last = merged[merged.length - 1];
    if (last && last.edge === p.edge && Math.abs(last.b - p.a) < 1e-6) {
      last.b = p.b;
      last.t1 = p.t1;
      for (const id of p.obsIds) if (!last.obsIds.includes(id)) last.obsIds.push(id);
      last.junction = last.junction || p.junction;
    } else merged.push({ ...p, obsIds: [...p.obsIds] });
  }
  for (const p of merged) {
    if (p.b - p.a <= 1e-6) continue;
    const edgeId = gi.edges[p.edge].edgeId;
    out.push({
      traversalId: `tr:${p.obsIds[0]}:${p.obsIds[p.obsIds.length - 1]}:${edgeId}:${Math.round(p.a * 1000)}`,
      observationIds: p.obsIds,
      edgeId,
      startOffsetMeters: p.a,
      endOffsetMeters: p.b,
      confidence: p.junction ? 0.7 : 0.9,
      evidence: p.junction ? 'junction_interpolated' : 'continuous',
      timestampStart: p.t0,
      timestampEnd: p.t1,
      manuallyReviewed: false,
    });
  }
  st.pending = [];
  st.pendingLength = 0;
  return out;
}

/** Bounded legal path search from end of `from` edge to start of `to` edge. */
function junctionPath(gi: GraphIndex, from: number, to: number, maxLen: number): { mid: number[]; len: number }[] {
  const res: { mid: number[]; len: number }[] = [];
  const allow = (i: number) => gi.edges[i].eligibility !== 'excluded';
  const stack: { prefix: number[]; mid: number[]; len: number }[] = [{ prefix: [from], mid: [], len: 0 }];
  let guard = 0;
  while (stack.length && guard++ < 2000) {
    const cur = stack.pop()!;
    for (const t of gi.successors(cur.prefix, allow)) {
      if (t.uturn) continue;
      if (t.next === to) {
        res.push({ mid: cur.mid, len: cur.len });
        continue;
      }
      const nl = cur.len + gi.length(t.next);
      if (nl > maxLen || cur.mid.length >= 4) continue;
      stack.push({ prefix: t.prefix, mid: [...cur.mid, t.next], len: nl });
    }
  }
  return res;
}

export function processObservation(
  gi: GraphIndex,
  prev: MatcherState,
  obs: LocationObservation,
  nowMs: number,
  cfg: MatcherConfig = DEFAULT_MATCHER_CONFIG,
): MatchResult {
  const st = clone(prev);
  const traversals: MatchedTraversal[] = [];
  const result = (quality: GpsQuality, extra: Partial<MatchResult> = {}): MatchResult => ({
    state: st, traversals, quality, ignored: null, matched: null, gap: null, ...extra,
  });
  if (st.recentIds.includes(obs.observationId)) return { ...result('reliable'), ignored: 'duplicate', state: prev };
  if (st.lastFix && obs.timestamp <= st.lastFix.t) return { ...result('reliable'), ignored: 'out_of_order', state: prev };
  st.recentIds.push(obs.observationId);
  if (st.recentIds.length > 64) st.recentIds.shift();

  const breakAnchor = (reason: GapReason): MatchResult['gap'] => {
    const hadAnchor = st.anchor !== null;
    if (st.committed) traversals.push(...flush(gi, st));
    st.pending = [];
    st.pendingLength = 0;
    st.committed = false;
    st.anchor = null;
    return hadAnchor ? { reason, fromT: st.lastGoodT, toT: obs.timestamp } : null;
  };

  const age = obs.ageMs ?? nowMs - obs.timestamp;
  if (age > cfg.maxFixAgeMs) return result('stale', { gap: breakAnchor('stale') });
  const acc = obs.horizontalAccuracyMeters;
  if (acc === null || !(acc <= cfg.maxAccuracyM)) {
    return result('weak_ambiguous', { gap: breakAnchor('accuracy') });
  }
  const p: LonLat = [obs.lon, obs.lat];
  let gap: MatchResult['gap'] = null;
  if (st.lastFix) {
    const dt = obs.timestamp - st.lastFix.t;
    const d = haversine([st.lastFix.lon, st.lastFix.lat], p);
    if (dt > cfg.maxGapMs) gap = breakAnchor('gap');
    else if ((d / dt) * 1000 > cfg.maxSpeedMps) {
      st.lastFix = { t: obs.timestamp, lon: obs.lon, lat: obs.lat, id: obs.observationId };
      return result('weak_ambiguous', { gap: breakAnchor('jump') });
    }
  }
  st.lastFix = { t: obs.timestamp, lon: obs.lon, lat: obs.lat, id: obs.observationId };

  const radius = Math.max(cfg.searchRadiusMinM, Math.min(cfg.searchRadiusMaxM, acc * 1.5 + 5));
  const cands: Cand[] = [];
  for (const i of gi.near(p, radius)) {
    const pr = gi.project(i, p);
    if (pr.distance <= radius) cands.push({ edge: i, offset: pr.offset, dist: pr.distance, point: pr.point });
  }
  if (cands.length === 0) return result('off_graph', { gap: gap ?? breakAnchor('off_graph') });

  const moving = obs.speedMps === null ? null : obs.speedMps >= cfg.minMovingSpeedMps;
  const headingUsable = obs.speedMps !== null && obs.speedMps >= cfg.headingMinSpeedMps && obs.bearingDeg !== null;
  const headingPenalty = (c: Cand) => {
    if (!headingUsable) return 0;
    const b = bearingAtOffset(gi.edges[c.edge].geometry, gi.cum[c.edge], c.offset);
    return Math.abs(angleDiff(b, obs.bearingDeg!)) > cfg.headingToleranceDeg ? 15 : 0;
  };
  const minAdvance = Math.max(cfg.minAdvanceM, acc * 0.5);
  const physOf = (i: number) => gi.edges[i].physicalSegmentId;
  const matchedOut = (edge: number, offset: number, point: LonLat) => ({
    edgeId: gi.edges[edge].edgeId, offset, point, committed: st.committed,
  });

  if (st.anchor) {
    const an = st.anchor;
    const anLen = gi.length(an.edge);
    const anPoint = gi.edges[an.edge].geometry.length ? pointOn(gi, an.edge, an.offset) : p;
    const dtS = Math.max(0.001, (obs.timestamp - an.t) / 1000);
    const maxTravel = cfg.maxSpeedMps * dtS + 2 * acc + 10;
    type Option = { c: Cand; score: number; pieces: Omit<Piece, 'obsIds' | 't0' | 't1'>[]; advance: number; reverse: boolean };
    const options: Option[] = [];
    for (const c of cands) {
      if (c.edge === an.edge) {
        const adv = c.offset - an.offset;
        // Backward motion on a two-way segment is represented by the reciprocal-edge option.
        if (adv < 0 && gi.reciprocal[an.edge] >= 0) continue;
        options.push({ c, score: c.dist + headingPenalty(c), pieces: adv > 0 ? [{ edge: c.edge, a: an.offset, b: c.offset, junction: false }] : [], advance: adv, reverse: adv < 0 });
      } else if (physOf(c.edge) === physOf(an.edge)) {
        // reciprocal direction of the same physical segment: express in anchor frame
        const advInAnchor = anLen - c.offset - an.offset;
        if (advInAnchor < 0) {
          // moving backwards along anchor edge == forwards along reciprocal
          const from = anLen - an.offset;
          options.push({ c, score: c.dist + headingPenalty(c), pieces: [{ edge: c.edge, a: from, b: c.offset, junction: false }], advance: -advInAnchor, reverse: true });
        }
      } else {
        const paths = junctionPath(gi, an.edge, c.edge, cfg.maxJunctionPathM);
        if (paths.length === 0) continue;
        paths.sort((x, y) => x.len - y.len);
        const best = paths[0];
        const total = anLen - an.offset + best.len + c.offset;
        if (total > maxTravel) continue;
        const crow = haversine(anPoint, c.point);
        const detour = Math.max(0, total - crow);
        const unique = paths.length === 1 || paths[1].len - best.len > 10;
        const pieces: Option['pieces'] = [];
        if (anLen - an.offset > 0) pieces.push({ edge: an.edge, a: an.offset, b: anLen, junction: false });
        if (unique) for (const m of best.mid) pieces.push({ edge: m, a: 0, b: gi.length(m), junction: true });
        if (c.offset > 0) pieces.push({ edge: c.edge, a: 0, b: c.offset, junction: !unique });
        options.push({ c, score: c.dist + 0.5 * detour + headingPenalty(c), pieces: unique ? pieces : [], advance: total, reverse: false });
      }
    }
    if (options.length === 0) {
      gap = gap ?? breakAnchor('ambiguous');
      // fall through to re-anchoring below
    } else {
      options.sort((x, y) => x.score - y.score || x.c.edge - y.c.edge);
      const best = options[0];
      const rival = options.find((o) => physOf(o.c.edge) !== physOf(best.c.edge) && o.score - best.score < cfg.ambiguityMarginM);
      if (rival && best.c.edge !== an.edge && physOf(best.c.edge) !== physOf(an.edge)) {
        return result('weak_ambiguous', { gap: breakAnchor('ambiguous') });
      }
      const stationary = (moving === false) || Math.abs(best.advance) < minAdvance;
      if (stationary) {
        return result('reliable', { matched: matchedOut(an.edge, an.offset, pointOn(gi, an.edge, an.offset)), gap });
      }
      if (best.reverse && best.c.edge !== an.edge) {
        // direction flip on the same physical segment
        if (!st.committed) {
          st.pending = [];
          st.pendingLength = 0;
        } else {
          traversals.push(...flush(gi, st));
          st.committed = false;
        }
        st.anchor = { edge: best.c.edge, offset: best.c.offset, t: obs.timestamp, obsId: obs.observationId };
        st.lastGoodT = obs.timestamp;
        const pc = best.pieces[0];
        if (pc && pc.b > pc.a) {
          st.pending.push({ ...pc, obsIds: [an.obsId, obs.observationId], t0: an.t, t1: obs.timestamp });
          st.pendingLength += pc.b - pc.a;
        }
        if (st.pendingLength >= cfg.commitMinM) {
          st.committed = true;
          traversals.push(...flush(gi, st));
        }
        return result('reliable', { matched: matchedOut(best.c.edge, best.c.offset, best.c.point), gap });
      }
      if (best.reverse) {
        // backward movement on a one-way edge without reciprocal: treat as noise
        return result('weak_ambiguous', { matched: matchedOut(an.edge, an.offset, pointOn(gi, an.edge, an.offset)), gap });
      }
      for (const pc of best.pieces) {
        if (pc.b - pc.a <= 0) continue;
        st.pending.push({ ...pc, obsIds: [an.obsId, obs.observationId], t0: an.t, t1: obs.timestamp });
        st.pendingLength += pc.b - pc.a;
      }
      st.anchor = { edge: best.c.edge, offset: best.c.offset, t: obs.timestamp, obsId: obs.observationId };
      st.lastGoodT = obs.timestamp;
      if (!st.committed && st.pendingLength >= cfg.commitMinM) st.committed = true;
      if (st.committed) traversals.push(...flush(gi, st));
      return result('reliable', { matched: matchedOut(best.c.edge, best.c.offset, best.c.point), gap });
    }
  }

  // (Re)anchor
  const scored = cands.map((c) => ({ c, score: c.dist + headingPenalty(c) })).sort((x, y) => x.score - y.score || x.c.edge - y.c.edge);
  const best = scored[0];
  const rival = scored.find((o) => physOf(o.c.edge) !== physOf(best.c.edge) && o.score - best.score < cfg.ambiguityMarginM);
  if (rival) return result('weak_ambiguous', { gap });
  st.anchor = { edge: best.c.edge, offset: best.c.offset, t: obs.timestamp, obsId: obs.observationId };
  st.lastGoodT = obs.timestamp;
  st.committed = false;
  st.pending = [];
  st.pendingLength = 0;
  return result('reliable', { matched: matchedOut(best.c.edge, best.c.offset, best.c.point), gap });
}

function pointOn(gi: GraphIndex, edge: number, offset: number): LonLat {
  const e = gi.edges[edge];
  const cum = gi.cum[edge];
  let i = 1;
  while (i < cum.length - 1 && cum[i] < offset) i++;
  const segLen = cum[i] - cum[i - 1];
  const t = segLen > 0 ? Math.max(0, Math.min(1, (offset - cum[i - 1]) / segLen)) : 0;
  const a = e.geometry[i - 1], b = e.geometry[i];
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
}

/** Flush tentative pieces when the session pauses/ends (only if direction was committed). */
export function finalizeMatcher(gi: GraphIndex, prev: MatcherState): { state: MatcherState; traversals: MatchedTraversal[] } {
  const st = clone(prev);
  const traversals = st.committed ? flush(gi, st) : [];
  st.pending = [];
  st.pendingLength = 0;
  st.anchor = null;
  st.committed = false;
  return { state: st, traversals };
}
