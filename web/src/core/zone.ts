import { EARTH_RADIUS_M, LocalProjection, cumulativeLengths, quantizeLonLat } from './geo';
import type { Interval } from './intervals';
import type { LonLat, ZoneDefinition } from './types';

export const DEFAULT_CIRCLE_TOLERANCE_M = 1.0;
/** Boundary is inclusive within this distance. */
export const BOUNDARY_INCLUSION_TOL_M = 0.01;

export interface ZoneValidation {
  ok: boolean;
  errors: { code: string; message: string; detail?: unknown }[];
  warnings: string[];
  areaSqMeters: number;
  perimeterMeters: number;
}

const D2R = Math.PI / 180;

function destination(center: LonLat, bearingDeg: number, dist: number): LonLat {
  const lat1 = center[1] * D2R;
  const lon1 = center[0] * D2R;
  const brng = bearingDeg * D2R;
  const dr = dist / EARTH_RADIUS_M;
  const lat2 = Math.asin(Math.sin(lat1) * Math.cos(dr) + Math.cos(lat1) * Math.sin(dr) * Math.cos(brng));
  const lon2 =
    lon1 + Math.atan2(Math.sin(brng) * Math.sin(dr) * Math.cos(lat1), Math.cos(dr) - Math.sin(lat1) * Math.sin(lat2));
  return [lon2 / D2R, lat2 / D2R];
}

/** Inscribed polygon whose maximum radial deviation (sagitta) <= tolerance. */
export function circleApproximation(center: LonLat, radiusMeters: number, toleranceMeters = DEFAULT_CIRCLE_TOLERANCE_M) {
  const tol = Math.min(toleranceMeters, radiusMeters * 0.5);
  let n = Math.ceil(Math.PI / Math.acos(1 - tol / radiusMeters));
  n = Math.max(16, Math.min(720, n));
  const coordinates: LonLat[] = [];
  for (let i = 0; i < n; i++) coordinates.push(quantizeLonLat(destination(center, (360 * i) / n, radiusMeters)));
  const sagitta = radiusMeters * (1 - Math.cos(Math.PI / n));
  return { vertices: n, toleranceMeters: sagitta, coordinates };
}

export function zoneRing(def: ZoneDefinition): LonLat[] {
  if (def.type === 'polygon') {
    const c = def.coordinates.slice();
    if (c.length > 1 && c[0][0] === c[c.length - 1][0] && c[0][1] === c[c.length - 1][1]) c.pop();
    return c;
  }
  return (def.approximation ?? circleApproximation(def.center, def.radiusMeters)).coordinates;
}

export function ringCentroid(ring: LonLat[]): LonLat {
  let x = 0, y = 0;
  for (const p of ring) {
    x += p[0];
    y += p[1];
  }
  return [x / ring.length, y / ring.length];
}

function segIntersect(
  a: [number, number], b: [number, number], c: [number, number], d: [number, number],
): { t: number; u: number } | null {
  const rx = b[0] - a[0], ry = b[1] - a[1];
  const sx = d[0] - c[0], sy = d[1] - c[1];
  const den = rx * sy - ry * sx;
  if (Math.abs(den) < 1e-12) return null;
  const qx = c[0] - a[0], qy = c[1] - a[1];
  const t = (qx * sy - qy * sx) / den;
  const u = (qx * ry - qy * rx) / den;
  if (t < -1e-12 || t > 1 + 1e-12 || u < -1e-12 || u > 1 + 1e-12) return null;
  return { t, u };
}

export function validateZone(def: ZoneDefinition): ZoneValidation {
  const errors: ZoneValidation['errors'] = [];
  const warnings: string[] = [];
  if (def.type === 'circle') {
    if (!(def.radiusMeters > 0) || !Number.isFinite(def.radiusMeters)) {
      errors.push({ code: 'invalid_radius', message: 'Circle radius must be a positive number of meters.' });
      return { ok: false, errors, warnings, areaSqMeters: 0, perimeterMeters: 0 };
    }
    if (def.radiusMeters < 20) errors.push({ code: 'too_small', message: 'Circle radius must be at least 20 m.' });
    if (def.radiusMeters > 5000) warnings.push('large_zone');
  }
  const ring = zoneRing(def);
  if (ring.length < 3) {
    errors.push({ code: 'too_few_vertices', message: 'A polygon needs at least 3 distinct points.' });
    return { ok: false, errors, warnings, areaSqMeters: 0, perimeterMeters: 0 };
  }
  for (const p of ring) {
    if (!Number.isFinite(p[0]) || !Number.isFinite(p[1]) || Math.abs(p[0]) > 180 || Math.abs(p[1]) > 85) {
      errors.push({ code: 'invalid_coordinate', message: 'Coordinate outside WGS84 range.' });
      return { ok: false, errors, warnings, areaSqMeters: 0, perimeterMeters: 0 };
    }
  }
  const proj = new LocalProjection(ringCentroid(ring));
  const xy = ring.map((p) => proj.toXY(p));
  const n = xy.length;
  for (let i = 0; i < n; i++) {
    const a = xy[i], b = xy[(i + 1) % n];
    if (Math.hypot(b[0] - a[0], b[1] - a[1]) < 0.01) {
      errors.push({ code: 'duplicate_vertex', message: `Points ${i + 1} and ${((i + 1) % n) + 1} coincide.`, detail: [i, (i + 1) % n] });
    }
  }
  // Self intersection (non-adjacent segments).
  if (def.type === 'polygon') {
    outer: for (let i = 0; i < n; i++) {
      for (let j = i + 1; j < n; j++) {
        if (j === i || (j + 1) % n === i || (i + 1) % n === j) continue;
        const r = segIntersect(xy[i], xy[(i + 1) % n], xy[j], xy[(j + 1) % n]);
        if (r) {
          errors.push({ code: 'self_intersection', message: `Edges ${i + 1} and ${j + 1} cross. Move a point to untangle the shape.`, detail: [i, j] });
          break outer;
        }
      }
    }
  }
  let area2 = 0;
  let perim = 0;
  for (let i = 0; i < n; i++) {
    const a = xy[i], b = xy[(i + 1) % n];
    area2 += a[0] * b[1] - b[0] * a[1];
    perim += Math.hypot(b[0] - a[0], b[1] - a[1]);
  }
  const area = Math.abs(area2) / 2;
  if (area < 100) errors.push({ code: 'too_small', message: 'Zone area must be at least 100 m².' });
  if (area > 25e6) warnings.push('large_zone');
  return { ok: errors.length === 0, errors, warnings, areaSqMeters: area, perimeterMeters: perim };
}

export class ZoneIndex {
  readonly ring: LonLat[];
  readonly proj: LocalProjection;
  private readonly xy: [number, number][];
  constructor(def: ZoneDefinition) {
    this.ring = zoneRing(def);
    this.proj = new LocalProjection(ringCentroid(this.ring));
    this.xy = this.ring.map((p) => this.proj.toXY(p));
  }

  private distToBoundaryXY(p: [number, number]): number {
    let best = Infinity;
    const n = this.xy.length;
    for (let i = 0; i < n; i++) {
      const a = this.xy[i], b = this.xy[(i + 1) % n];
      const dx = b[0] - a[0], dy = b[1] - a[1];
      const l2 = dx * dx + dy * dy;
      let t = l2 > 0 ? ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2 : 0;
      t = Math.max(0, Math.min(1, t));
      const d = Math.hypot(p[0] - (a[0] + dx * t), p[1] - (a[1] + dy * t));
      if (d < best) best = d;
    }
    return best;
  }

  containsXY(p: [number, number]): boolean {
    if (this.distToBoundaryXY(p) <= BOUNDARY_INCLUSION_TOL_M) return true;
    let inside = false;
    const n = this.xy.length;
    for (let i = 0, j = n - 1; i < n; j = i++) {
      const a = this.xy[i], b = this.xy[j];
      if (a[1] > p[1] !== b[1] > p[1] && p[0] < ((b[0] - a[0]) * (p[1] - a[1])) / (b[1] - a[1]) + a[0]) inside = !inside;
    }
    return inside;
  }

  contains(p: LonLat): boolean {
    return this.containsXY(this.proj.toXY(p));
  }

  /** In-zone offset intervals (meters along the haversine length) for a polyline. */
  clip(coords: LonLat[]): { inside: Interval[]; length: number } {
    const cum = cumulativeLengths(coords);
    const pts = coords.map((p) => this.proj.toXY(p));
    const pieces: Interval[] = [];
    const n = this.xy.length;
    for (let i = 1; i < pts.length; i++) {
      const a = pts[i - 1], b = pts[i];
      const ts = [0, 1];
      for (let k = 0; k < n; k++) {
        const r = segIntersect(a, b, this.xy[k], this.xy[(k + 1) % n]);
        if (r) ts.push(Math.max(0, Math.min(1, r.t)));
      }
      ts.sort((x, y) => x - y);
      const segLen = cum[i] - cum[i - 1];
      for (let k = 1; k < ts.length; k++) {
        const t0 = ts[k - 1], t1 = ts[k];
        if (t1 - t0 <= 1e-12) continue;
        const tm = (t0 + t1) / 2;
        const mid: [number, number] = [a[0] + (b[0] - a[0]) * tm, a[1] + (b[1] - a[1]) * tm];
        if (this.containsXY(mid)) pieces.push([cum[i - 1] + segLen * t0, cum[i - 1] + segLen * t1]);
      }
    }
    // Merge contiguous pieces.
    const merged: Interval[] = [];
    for (const p of pieces) {
      const last = merged[merged.length - 1];
      if (last && p[0] <= last[1] + 1e-6) last[1] = Math.max(last[1], p[1]);
      else merged.push([p[0], p[1]]);
    }
    return { inside: merged, length: cum[cum.length - 1] };
  }
}
