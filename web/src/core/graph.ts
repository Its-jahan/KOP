// Runtime graph index: adjacency, legal transition automaton (turn restrictions incl.
// via-way), dead-end/turnaround rules and a spatial grid for snapping and matching.

import { LocalProjection, angleDiff, bearing, cumulativeLengths, projectOnPolyline, type Projection } from './geo';
import type { DirectedEdge, GraphNode, GraphSnapshot, LonLat } from './types';

export type Prefix = number[];

export interface Transition {
  next: number;
  prefix: Prefix;
  uturn: boolean;
  turnaround: 'verified' | 'unverified' | null;
  /** Signed heading change in degrees (positive = right turn). */
  turnAngle: number;
}

export class GraphIndex {
  readonly g: GraphSnapshot;
  readonly edges: DirectedEdge[];
  readonly idx = new Map<string, number>();
  readonly nodes = new Map<string, GraphNode>();
  readonly out = new Map<string, number[]>();
  readonly cum: number[][];
  readonly reciprocal: Int32Array;
  readonly physical = new Map<string, number[]>();
  readonly deadEnd: Uint8Array;
  readonly endBearing: Float64Array;
  readonly startBearing: Float64Array;
  private readonly forbidden = new Set<string>();
  private readonly prefixes = new Set<string>();
  private maxSeqLen = 1;
  readonly restrictionWarnings: string[] = [];
  // spatial grid
  private readonly proj: LocalProjection;
  private readonly cell = 50;
  private readonly grid = new Map<string, number[]>();

  constructor(g: GraphSnapshot) {
    this.g = g;
    this.edges = g.directedEdges.slice().sort((a, b) => (a.edgeId < b.edgeId ? -1 : a.edgeId > b.edgeId ? 1 : 0));
    this.edges.forEach((e, i) => this.idx.set(e.edgeId, i));
    for (const n of g.nodes) this.nodes.set(n.nodeId, n);
    this.cum = this.edges.map((e) => cumulativeLengths(e.geometry));
    this.reciprocal = new Int32Array(this.edges.length).fill(-1);
    this.endBearing = new Float64Array(this.edges.length);
    this.startBearing = new Float64Array(this.edges.length);
    this.edges.forEach((e, i) => {
      if (e.reciprocalEdgeId && this.idx.has(e.reciprocalEdgeId)) this.reciprocal[i] = this.idx.get(e.reciprocalEdgeId)!;
      const l = this.out.get(e.fromNodeId) ?? [];
      l.push(i);
      this.out.set(e.fromNodeId, l);
      const p = this.physical.get(e.physicalSegmentId) ?? [];
      p.push(i);
      this.physical.set(e.physicalSegmentId, p);
      const geo = e.geometry;
      this.startBearing[i] = bearing(geo[0], geo[1]);
      this.endBearing[i] = bearing(geo[geo.length - 2], geo[geo.length - 1]);
    });
    this.deadEnd = new Uint8Array(this.edges.length);
    this.edges.forEach((e, i) => {
      const node = this.nodes.get(e.toNodeId);
      if (!node || node.passage !== 'free') {
        this.deadEnd[i] = 1;
        return;
      }
      const cont = (this.out.get(e.toNodeId) ?? []).some(
        (j) => this.edges[j].physicalSegmentId !== e.physicalSegmentId && this.edges[j].eligibility === 'eligible',
      );
      this.deadEnd[i] = cont ? 0 : 1;
    });
    this.buildRestrictions();
    const ext = g.geographicExtent;
    this.proj = new LocalProjection([(ext.west + ext.east) / 2, (ext.south + ext.north) / 2]);
    this.edges.forEach((e, i) => {
      for (let k = 1; k < e.geometry.length; k++) {
        const a = this.proj.toXY(e.geometry[k - 1]);
        const b = this.proj.toXY(e.geometry[k]);
        const x0 = Math.floor(Math.min(a[0], b[0]) / this.cell), x1 = Math.floor(Math.max(a[0], b[0]) / this.cell);
        const y0 = Math.floor(Math.min(a[1], b[1]) / this.cell), y1 = Math.floor(Math.max(a[1], b[1]) / this.cell);
        for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) {
          const key = `${x}:${y}`;
          const l = this.grid.get(key) ?? [];
          if (l[l.length - 1] !== i) l.push(i);
          this.grid.set(key, l);
        }
      }
    });
  }

  private buildRestrictions() {
    const onlyGroups = new Map<string, { base: number[]; allowed: Set<number> }>();
    for (const r of this.g.turnRestrictions) {
      const ids = [r.fromEdgeId, ...r.viaEdgeIds, r.toEdgeId];
      if (!ids.every((id) => this.idx.has(id))) {
        this.restrictionWarnings.push(`restriction ${r.restrictionId} references unknown edges`);
        continue;
      }
      const seq = ids.map((id) => this.idx.get(id)!);
      if (r.kind === 'no') this.addForbidden(seq);
      else {
        const base = seq.slice(0, -1);
        const key = r.restrictionId.split('#')[0] + '|' + base.join(',');
        const grp = onlyGroups.get(key) ?? { base, allowed: new Set<number>() };
        grp.allowed.add(seq[seq.length - 1]);
        onlyGroups.set(key, grp);
      }
    }
    const keys = [...onlyGroups.keys()].sort();
    for (const k of keys) {
      const { base, allowed } = onlyGroups.get(k)!;
      const last = this.edges[base[base.length - 1]];
      for (const x of this.out.get(last.toNodeId) ?? []) if (!allowed.has(x)) this.addForbidden([...base, x]);
    }
  }

  private addForbidden(seq: number[]) {
    this.forbidden.add(seq.join(','));
    for (let l = 2; l < seq.length; l++) this.prefixes.add(seq.slice(0, l).join(','));
    if (seq.length > this.maxSeqLen) this.maxSeqLen = seq.length;
  }

  /** Apply automaton step. Returns null when the move completes a forbidden sequence. */
  step(prefix: Prefix, next: number): Prefix | null {
    const cand = [...prefix, next];
    const n = cand.length;
    for (let l = 2; l <= Math.min(n, this.maxSeqLen); l++) {
      if (this.forbidden.has(cand.slice(n - l).join(','))) return null;
    }
    for (let l = Math.min(n, this.maxSeqLen - 1); l >= 2; l--) {
      const s = cand.slice(n - l);
      if (this.prefixes.has(s.join(','))) return s;
    }
    return [next];
  }

  isUturn(a: number, b: number): boolean {
    return this.edges[a].physicalSegmentId === this.edges[b].physicalSegmentId;
  }

  turnaroundAt(edge: number): 'verified' | 'unverified' {
    const n = this.nodes.get(this.edges[edge].toNodeId);
    return n && n.turnaround !== 'none' ? 'verified' : 'unverified';
  }

  /**
   * Legal successors of the state (prefix ending at edge e), restricted to edges
   * accepted by `allow`. U-turns only at dead ends; never through a non-free node.
   */
  successors(prefix: Prefix, allow: (i: number) => boolean): Transition[] {
    const e = prefix[prefix.length - 1];
    const edge = this.edges[e];
    const node = this.nodes.get(edge.toNodeId);
    const out: Transition[] = [];
    const nodeFree = !!node && node.passage === 'free';
    for (const j of this.out.get(edge.toNodeId) ?? []) {
      if (!allow(j)) continue;
      const uturn = this.isUturn(e, j);
      if (uturn) {
        if (!this.deadEnd[e]) continue;
      } else if (!nodeFree) continue;
      const np = this.step(prefix, j);
      if (!np) continue;
      out.push({
        next: j,
        prefix: np,
        uturn,
        turnaround: uturn ? this.turnaroundAt(e) : null,
        turnAngle: angleDiff(this.endBearing[e], this.startBearing[j]),
      });
    }
    return out;
  }

  /** Whether the two-edge transition a->b is legal (ignores longer via-way context). */
  pairLegal(a: number, b: number): boolean {
    const ea = this.edges[a];
    if (ea.toNodeId !== this.edges[b].fromNodeId) return false;
    if (this.isUturn(a, b)) return !!this.deadEnd[a] && this.step([a], b) !== null;
    const node = this.nodes.get(ea.toNodeId);
    if (!node || node.passage !== 'free') return false;
    return this.step([a], b) !== null;
  }

  /** Candidate edges near a point (unique indices, sorted). */
  near(p: LonLat, radius: number): number[] {
    const xy = this.proj.toXY(p);
    const x0 = Math.floor((xy[0] - radius) / this.cell), x1 = Math.floor((xy[0] + radius) / this.cell);
    const y0 = Math.floor((xy[1] - radius) / this.cell), y1 = Math.floor((xy[1] + radius) / this.cell);
    const set = new Set<number>();
    for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) for (const i of this.grid.get(`${x}:${y}`) ?? []) set.add(i);
    return [...set].sort((a, b) => a - b);
  }

  project(i: number, p: LonLat): Projection {
    return projectOnPolyline(this.edges[i].geometry, this.cum[i], p);
  }

  /** Authoritative length (derived from hashed lengthMm, never from lengthMeters). */
  length(i: number): number {
    return this.edges[i].lengthMm / 1000;
  }
}
