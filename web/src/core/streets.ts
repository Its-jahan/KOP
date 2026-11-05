// Street sets: an explicit list of streets (e.g. traced from a client's assignment map) that
// restricts which roads inside a zone are required. The zone polygon still decides where the
// route may drive; only requirements lying on the listed streets are kept.

import type { GraphIndex } from './graph';
import { pointAtOffset } from './geo';
import type { LonLat, RequiredInterval } from './types';

export interface StreetSet {
  /** Stable id, e.g. "yerevan-client-2026-11-05". */
  id: string;
  name: { fa: string; en: string };
  /** Zone outline (closed ring not repeated) that contains every street. */
  ring: LonLat[];
  /** Street centre lines (OSM geometry). */
  lines: LonLat[][];
}

/** Max distance (m) from a street line for a sample to count as on the street. */
export const STREET_TOL_M = 8;
/** Share of a requirement's samples that must lie on listed streets. */
export const STREET_MIN_FRACTION = 0.75;
const SAMPLE_STEP_M = 5;
const CELL_M = 50;

/** Spatial index of line segments in a local equirectangular frame (metres). */
class SegIndex {
  private readonly lat0: number;
  private readonly kx: number;
  private readonly ky = 110_574;
  private readonly cells = new Map<string, number[]>();
  private readonly seg: number[] = []; // x1,y1,x2,y2 per segment

  constructor(lines: LonLat[][]) {
    let s = 0, n = 0;
    for (const l of lines) for (const p of l) { s += p[1]; n++; }
    this.lat0 = n ? s / n : 0;
    this.kx = 111_320 * Math.cos((this.lat0 * Math.PI) / 180);
    for (const l of lines) {
      for (let i = 0; i + 1 < l.length; i++) {
        const [x1, y1] = this.xy(l[i]);
        const [x2, y2] = this.xy(l[i + 1]);
        const k = this.seg.length / 4;
        this.seg.push(x1, y1, x2, y2);
        const c0x = Math.floor((Math.min(x1, x2) - STREET_TOL_M) / CELL_M), c1x = Math.floor((Math.max(x1, x2) + STREET_TOL_M) / CELL_M);
        const c0y = Math.floor((Math.min(y1, y2) - STREET_TOL_M) / CELL_M), c1y = Math.floor((Math.max(y1, y2) + STREET_TOL_M) / CELL_M);
        for (let cx = c0x; cx <= c1x; cx++) {
          for (let cy = c0y; cy <= c1y; cy++) {
            const key = `${cx},${cy}`;
            const a = this.cells.get(key);
            if (a) a.push(k);
            else this.cells.set(key, [k]);
          }
        }
      }
    }
  }

  private xy(p: LonLat): [number, number] {
    return [p[0] * this.kx, (p[1] - this.lat0) * this.ky];
  }

  near(p: LonLat, tol: number): boolean {
    const [x, y] = this.xy(p);
    const a = this.cells.get(`${Math.floor(x / CELL_M)},${Math.floor(y / CELL_M)}`);
    if (!a) return false;
    const t2 = tol * tol;
    for (const k of a) {
      const x1 = this.seg[4 * k], y1 = this.seg[4 * k + 1], x2 = this.seg[4 * k + 2], y2 = this.seg[4 * k + 3];
      const dx = x2 - x1, dy = y2 - y1;
      const L2 = dx * dx + dy * dy;
      const u = L2 > 0 ? Math.max(0, Math.min(1, ((x - x1) * dx + (y - y1) * dy) / L2)) : 0;
      const ex = x1 + u * dx - x, ey = y1 + u * dy - y;
      if (ex * ex + ey * ey <= t2) return true;
    }
    return false;
  }
}

/** Keep only requirements that run along the given streets. */
export function filterRequirementsByStreets(gi: GraphIndex, requirements: RequiredInterval[], lines: LonLat[][], tolM = STREET_TOL_M, minFraction = STREET_MIN_FRACTION): RequiredInterval[] {
  const idx = new SegIndex(lines);
  return requirements.filter((r) => {
    const i = gi.idx.get(r.edgeId);
    if (i === undefined) return false;
    const geom = gi.edges[i].geometry;
    const cum = gi.cum[i];
    const scale = cum[cum.length - 1] / Math.max(gi.length(i), 1e-9);
    const len = r.endOffsetMeters - r.startOffsetMeters;
    const n = Math.max(3, Math.ceil(len / SAMPLE_STEP_M) + 1);
    let hit = 0;
    for (let k = 0; k < n; k++) {
      const off = (r.startOffsetMeters + (len * k) / (n - 1)) * scale;
      if (idx.near(pointAtOffset(geom, cum, off), tolM)) hit++;
    }
    return hit / n >= minFraction;
  });
}

/** Parse a street-set file (our JSON format or a GeoJSON FeatureCollection). */
export function parseStreetSet(json: unknown, fallbackName: string): StreetSet {
  const o = json as Record<string, unknown>;
  if (o && Array.isArray(o.ring) && Array.isArray(o.lines)) {
    const name = (o.name ?? { fa: fallbackName, en: fallbackName }) as { fa: string; en: string };
    return { id: String(o.id ?? fallbackName), name, ring: o.ring as LonLat[], lines: o.lines as LonLat[][] };
  }
  if (o && o.type === 'FeatureCollection' && Array.isArray(o.features)) {
    let ring: LonLat[] | null = null;
    const lines: LonLat[][] = [];
    for (const f of o.features as { geometry?: { type: string; coordinates: unknown } }[]) {
      const g = f.geometry;
      if (!g) continue;
      if (g.type === 'Polygon' && !ring) ring = (g.coordinates as LonLat[][])[0].slice(0, -1);
      else if (g.type === 'LineString') lines.push(g.coordinates as LonLat[]);
      else if (g.type === 'MultiLineString') lines.push(...(g.coordinates as LonLat[][]));
    }
    if (!ring || !lines.length) throw new Error('street set needs one Polygon and at least one LineString');
    return { id: fallbackName, name: { fa: fallbackName, en: fallbackName }, ring, lines };
  }
  throw new Error('unrecognised street set file');
}
