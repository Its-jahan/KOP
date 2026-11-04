import type { LonLat } from './types';

export const EARTH_RADIUS_M = 6371008.8;
const D2R = Math.PI / 180;

export function quantize(v: number): number {
  return Math.round(v * 1e7) / 1e7;
}

export function quantizeLonLat(p: LonLat): LonLat {
  return [quantize(p[0]), quantize(p[1])];
}

/** Great-circle distance in meters (haversine). */
export function haversine(a: LonLat, b: LonLat): number {
  const lat1 = a[1] * D2R;
  const lat2 = b[1] * D2R;
  const dLat = lat2 - lat1;
  const dLon = (b[0] - a[0]) * D2R;
  const s = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(s)));
}

/** Initial bearing a->b in degrees [0,360). */
export function bearing(a: LonLat, b: LonLat): number {
  const lat1 = a[1] * D2R;
  const lat2 = b[1] * D2R;
  const dLon = (b[0] - a[0]) * D2R;
  const y = Math.sin(dLon) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
  const deg = Math.atan2(y, x) / D2R;
  return (deg + 360) % 360;
}

/** Signed smallest difference b - a in degrees, range (-180, 180]. */
export function angleDiff(a: number, b: number): number {
  let d = ((b - a) % 360 + 360) % 360;
  if (d > 180) d -= 360;
  return d;
}

/**
 * Local tangent-plane projection (equirectangular about an origin). Suitable for
 * city-scale zones (error well below 0.1% within ~20 km of origin).
 */
export class LocalProjection {
  readonly lon0: number;
  readonly lat0: number;
  private readonly kx: number;
  private readonly ky: number;
  constructor(origin: LonLat) {
    this.lon0 = origin[0];
    this.lat0 = origin[1];
    this.kx = EARTH_RADIUS_M * Math.cos(this.lat0 * D2R) * D2R;
    this.ky = EARTH_RADIUS_M * D2R;
  }
  toXY(p: LonLat): [number, number] {
    return [(p[0] - this.lon0) * this.kx, (p[1] - this.lat0) * this.ky];
  }
  toLonLat(xy: [number, number]): LonLat {
    return [this.lon0 + xy[0] / this.kx, this.lat0 + xy[1] / this.ky];
  }
}

export function polylineLength(coords: LonLat[]): number {
  let s = 0;
  for (let i = 1; i < coords.length; i++) s += haversine(coords[i - 1], coords[i]);
  return s;
}

export function cumulativeLengths(coords: LonLat[]): number[] {
  const out = [0];
  for (let i = 1; i < coords.length; i++) out.push(out[i - 1] + haversine(coords[i - 1], coords[i]));
  return out;
}

/** Point at a given offset (meters) along polyline. */
export function pointAtOffset(coords: LonLat[], cum: number[], offset: number): LonLat {
  if (offset <= 0) return coords[0];
  const total = cum[cum.length - 1];
  if (offset >= total) return coords[coords.length - 1];
  let i = 1;
  while (i < cum.length && cum[i] < offset) i++;
  const segLen = cum[i] - cum[i - 1];
  const t = segLen > 0 ? (offset - cum[i - 1]) / segLen : 0;
  const a = coords[i - 1];
  const b = coords[i];
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
}

/** Bearing of the polyline at offset. */
export function bearingAtOffset(coords: LonLat[], cum: number[], offset: number): number {
  let i = 1;
  while (i < cum.length - 1 && cum[i] < offset) i++;
  return bearing(coords[i - 1], coords[i]);
}

export interface Projection {
  offset: number;
  distance: number;
  point: LonLat;
}

/** Project a point onto a polyline using a local plane around the point. */
export function projectOnPolyline(coords: LonLat[], cum: number[], p: LonLat): Projection {
  const proj = new LocalProjection(p);
  let best: Projection = { offset: 0, distance: Infinity, point: coords[0] };
  for (let i = 1; i < coords.length; i++) {
    const a = proj.toXY(coords[i - 1]);
    const b = proj.toXY(coords[i]);
    const dx = b[0] - a[0];
    const dy = b[1] - a[1];
    const len2 = dx * dx + dy * dy;
    let t = len2 > 0 ? -(a[0] * dx + a[1] * dy) / len2 : 0;
    t = Math.max(0, Math.min(1, t));
    const x = a[0] + dx * t;
    const y = a[1] + dy * t;
    const d = Math.hypot(x, y);
    if (d < best.distance) {
      const segLen = cum[i] - cum[i - 1];
      best = { offset: cum[i - 1] + segLen * t, distance: d, point: proj.toLonLat([x, y]) };
    }
  }
  return best;
}

/** Sub-polyline between two offsets (start < end). */
export function slicePolyline(coords: LonLat[], cum: number[], start: number, end: number): LonLat[] {
  const out: LonLat[] = [pointAtOffset(coords, cum, start)];
  for (let i = 1; i < coords.length - 1; i++) {
    if (cum[i] > start && cum[i] < end) out.push(coords[i]);
  }
  out.push(pointAtOffset(coords, cum, end));
  return out;
}

export function bboxOf(coords: LonLat[]): { west: number; south: number; east: number; north: number } {
  let west = Infinity, south = Infinity, east = -Infinity, north = -Infinity;
  for (const [x, y] of coords) {
    if (x < west) west = x;
    if (x > east) east = x;
    if (y < south) south = y;
    if (y > north) north = y;
  }
  return { west, south, east, north };
}

/** Expand bbox by meters. */
export function expandBbox(b: { west: number; south: number; east: number; north: number }, meters: number) {
  const latMid = (b.south + b.north) / 2;
  const dLat = meters / (EARTH_RADIUS_M * D2R);
  const dLon = meters / (EARTH_RADIUS_M * Math.cos(latMid * D2R) * D2R);
  return { west: b.west - dLon, south: b.south - dLat, east: b.east + dLon, north: b.north + dLat };
}
