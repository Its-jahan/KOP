import type { GraphIndex } from './graph';
import type { LonLat } from './types';

export interface SnapResult {
  edgeId: string;
  offsetMeters: number;
  position: LonLat;
  distanceMeters: number;
  alternatives: { edgeId: string; distanceMeters: number }[];
}

/**
 * Snap a requested start point to the nearest legal directed edge accepted by
 * `allow`. Both directions of a two-way road are returned as alternatives; the
 * caller must show the snapped point and direction for confirmation.
 */
export function snapStart(
  gi: GraphIndex,
  p: LonLat,
  allow: (i: number) => boolean,
  maxDistance = 150,
  /** Optional preference (e.g. edge belongs to the main connected component). */
  preferred?: (i: number) => boolean,
  preferenceSlackM = 30,
): SnapResult | null {
  const found: { i: number; d: number; offset: number; point: LonLat }[] = [];
  for (const i of gi.near(p, maxDistance)) {
    if (!allow(i)) continue;
    const pr = gi.project(i, p);
    if (pr.distance <= maxDistance) found.push({ i, d: pr.distance, offset: pr.offset, point: pr.point });
  }
  if (!found.length) return null;
  found.sort((a, b) => a.d - b.d || (gi.edges[a.i].edgeId < gi.edges[b.i].edgeId ? -1 : 1));
  let best = found[0];
  if (preferred && !preferred(best.i)) {
    const alt = found.find((f) => preferred(f.i) && f.d <= best.d + preferenceSlackM);
    if (alt) best = alt;
  }
  return {
    edgeId: gi.edges[best.i].edgeId,
    offsetMeters: best.offset,
    position: best.point,
    distanceMeters: best.d,
    alternatives: found.filter((f) => f !== best).slice(0, 5).map((f) => ({ edgeId: gi.edges[f.i].edgeId, distanceMeters: f.d })),
  };
}

/** Preference predicate: edge belongs to the strongly connected component holding the most traversable length. */
export function mainComponentPredicate(gi: GraphIndex, comp: Int32Array): (i: number) => boolean {
  const len = new Map<number, number>();
  comp.forEach((c, i) => {
    if (c >= 0) len.set(c, (len.get(c) ?? 0) + gi.edges[i].lengthMeters);
  });
  let best = -1, bestLen = -1;
  for (const [c, l] of [...len.entries()].sort((a, b) => a[0] - b[0])) if (l > bestLen) { best = c; bestLen = l; }
  return (i: number) => comp[i] === best;
}
