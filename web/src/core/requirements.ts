import type { GraphIndex } from './graph';
import { coveredWithin, uncoveredParts, type Interval } from './intervals';
import { ZoneIndex } from './zone';
import type { BoundaryPolicy, RequiredInterval, ZoneDefinition } from './types';
import { bboxOf } from './geo';

export const MIN_REQUIRED_INTERVAL_M = 1.0;
/** Max outside length for an edge to count as inside under STRICT_INSIDE. */
export const STRICT_OUTSIDE_TOL_M = 2.0;
/** A requirement is complete when at most min(4 m, 25 % of it) is unconfirmed. */
export const COVERAGE_TOLERANCE_M = 4.0;

export interface EdgeZoneInfo {
  inside: Interval[];
  insideMeters: number;
  outsideMeters: number;
}

export interface RequirementsResult {
  zoneInfo: EdgeZoneInfo[];
  requirements: RequiredInterval[];
  ignoredSlivers: number;
  ignoredSliverMeters: number;
}

export function computeRequirements(gi: GraphIndex, zone: ZoneDefinition): RequirementsResult {
  const zi = new ZoneIndex(zone);
  const zb = bboxOf(zi.ring);
  const pad = 0.0005;
  const zoneInfo: EdgeZoneInfo[] = [];
  const requirements: RequiredInterval[] = [];
  let ignoredSlivers = 0;
  let ignoredSliverMeters = 0;
  gi.edges.forEach((e, i) => {
    const eb = bboxOf(e.geometry);
    let inside: Interval[] = [];
    if (!(eb.west > zb.east + pad || eb.east < zb.west - pad || eb.south > zb.north + pad || eb.north < zb.south - pad)) {
      // Clip in the physical segment's forward frame so both directions agree exactly.
      if (e.directionIdentity === 'backward' && e.reciprocalEdgeId && gi.idx.has(e.reciprocalEdgeId)) {
        const fwd = gi.edges[gi.idx.get(e.reciprocalEdgeId)!];
        const L = gi.length(i);
        const r = zi.clip(fwd.geometry).inside;
        inside = r.map(([a, b]) => [Math.max(0, L - b), Math.max(0, L - a)] as Interval).reverse();
      } else inside = zi.clip(e.geometry).inside;
    }
    // Rescale to stored length (clip uses recomputed haversine length; identical up to rounding).
    const L = gi.length(i);
    // Snap numeric noise (< 1 cm) at the edge ends.
    inside = inside
      .map(([a, b]) => [a < 0.01 ? 0 : Math.min(a, L), b > L - 0.01 ? L : Math.min(b, L)] as Interval)
      .filter(([a, b]) => b > a);
    const insideMeters = inside.reduce((s, [a, b]) => s + b - a, 0);
    zoneInfo.push({ inside, insideMeters, outsideMeters: Math.max(0, L - insideMeters) });
    if (e.eligibility === 'excluded') return;
    let k = 0;
    for (const [a, b] of inside) {
      if (b - a < MIN_REQUIRED_INTERVAL_M) {
        ignoredSlivers++;
        ignoredSliverMeters += b - a;
        continue;
      }
      requirements.push({
        requirementId: `${e.edgeId}#${k++}`,
        edgeId: e.edgeId,
        startOffsetMeters: a,
        endOffsetMeters: b,
        lengthMeters: b - a,
        status: e.eligibility === 'needsReview' ? 'needsReview' : 'unvisited',
        exclusionReason: e.eligibility === 'needsReview' ? e.eligibilityReason : null,
      });
    }
  });
  return { zoneInfo, requirements, ignoredSlivers, ignoredSliverMeters };
}

export function edgeTraversable(
  gi: GraphIndex,
  zoneInfo: EdgeZoneInfo[],
  policy: BoundaryPolicy,
  blockedEdges: Set<number>,
  i: number,
): boolean {
  const e = gi.edges[i];
  if (e.eligibility !== 'eligible') return false;
  if (blockedEdges.has(i)) return false;
  if (policy === 'STRICT_INSIDE' && zoneInfo[i].outsideMeters > STRICT_OUTSIDE_TOL_M) return false;
  return true;
}

export function requirementCovered(union: Interval[], r: RequiredInterval, tol = COVERAGE_TOLERANCE_M): boolean {
  const covered = coveredWithin(union, r.startOffsetMeters, r.endOffsetMeters);
  const uncovered = r.lengthMeters - covered;
  return uncovered <= Math.min(tol, 0.25 * r.lengthMeters);
}

export function requirementUncovered(union: Interval[], r: RequiredInterval): Interval[] {
  return uncoveredParts(union, r.startOffsetMeters, r.endOffsetMeters);
}
