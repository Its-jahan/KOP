// Shared helpers for building contract fixtures (local meters -> WGS84).
import type { LocationObservation, LonLat } from '../src/core/types';
import type { OsmElement, OverpassResponse } from '../src/core/osm';

export const BASE: LonLat = [44.5, 40.18];
const M_LAT = 110_574;
const M_LON = 111_320 * Math.cos((40.18 * Math.PI) / 180);
export const T0 = 1_700_000_000_000;

export function ll(x: number, y: number): LonLat {
  return [Math.round((BASE[0] + x / M_LON) * 1e7) / 1e7, Math.round((BASE[1] + y / M_LAT) * 1e7) / 1e7];
}

export class Osm {
  elements: OsmElement[] = [];
  node(id: number, x: number, y: number, tags?: Record<string, string>) {
    const [lon, lat] = ll(x, y);
    this.elements.push({ type: 'node', id, lat, lon, ...(tags ? { tags } : {}) });
    return this;
  }
  way(id: number, nodes: number[], tags: Record<string, string>) {
    this.elements.push({ type: 'way', id, nodes, tags });
    return this;
  }
  rel(id: number, members: { type: 'node' | 'way'; ref: number; role: string }[], tags: Record<string, string>) {
    this.elements.push({ type: 'relation', id, members, tags });
    return this;
  }
  json(): OverpassResponse {
    return { osm3s: { timestamp_osm_base: 'fixture' }, elements: this.elements };
  }
}

export function rect(x0: number, y0: number, x1: number, y1: number): LonLat[] {
  return [ll(x0, y0), ll(x1, y0), ll(x1, y1), ll(x0, y1)];
}

function mulberry32(seed: number) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export interface TrackOpts {
  idPrefix: string;
  speed: number;
  startT?: number;
  accuracy?: number;
  noise?: number;
  seed?: number;
  intervalMs?: number;
  reportSpeed?: boolean;
  reportBearing?: boolean;
}

/** Fixes along a local-meter polyline at constant speed, 1 Hz by default. */
export function track(points: [number, number][], o: TrackOpts): LocationObservation[] {
  const rnd = mulberry32(o.seed ?? 1);
  const dtMs = o.intervalMs ?? 1000;
  const step = (o.speed * dtMs) / 1000;
  const segs: { a: [number, number]; b: [number, number]; len: number }[] = [];
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1], b = points[i];
    segs.push({ a, b, len: Math.hypot(b[0] - a[0], b[1] - a[1]) });
  }
  const total = segs.reduce((s, x) => s + x.len, 0);
  const out: LocationObservation[] = [];
  let t = o.startT ?? T0;
  for (let d = 0, k = 0; d <= total + 1e-9; d += step, k++) {
    let rem = d;
    let seg = segs[0];
    for (const s of segs) {
      seg = s;
      if (rem <= s.len) break;
      rem -= s.len;
    }
    const f = seg.len > 0 ? Math.min(1, rem / seg.len) : 0;
    const x = seg.a[0] + (seg.b[0] - seg.a[0]) * f + (o.noise ? (rnd() * 2 - 1) * o.noise : 0);
    const y = seg.a[1] + (seg.b[1] - seg.a[1]) * f + (o.noise ? (rnd() * 2 - 1) * o.noise : 0);
    const brg = ((Math.atan2(seg.b[0] - seg.a[0], seg.b[1] - seg.a[1]) * 180) / Math.PI + 360) % 360;
    const [lon, lat] = ll(x, y);
    out.push(obs(`${o.idPrefix}${k}`, t, lon, lat, {
      accuracy: o.accuracy ?? 5,
      speed: o.reportSpeed === false ? null : o.speed,
      bearing: o.reportBearing === false || o.speed < 0.5 ? null : Math.round(brg * 10) / 10,
    }));
    t += dtMs;
  }
  return out;
}

export function obs(
  id: string,
  t: number,
  lon: number,
  lat: number,
  o: { accuracy: number | null; speed: number | null; bearing: number | null; ageMs?: number },
): LocationObservation {
  return {
    observationId: id,
    timestamp: t,
    monotonicMs: null,
    lon,
    lat,
    horizontalAccuracyMeters: o.accuracy,
    speedMps: o.speed,
    speedAccuracyMps: null,
    bearingDeg: o.bearing,
    bearingAccuracyDeg: null,
    altitudeMeters: null,
    altitudeAccuracyMeters: null,
    source: 'simulated_test',
    ageMs: o.ageMs ?? 0,
  };
}
