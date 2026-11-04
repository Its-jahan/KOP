// OpenStreetMap (Overpass JSON) -> canonical GraphSnapshot normalisation.
// Data © OpenStreetMap contributors, ODbL 1.0. This module is pure and deterministic.

import { bboxOf, haversine, quantize } from './geo';
import { computeContentHash } from './hash';
import {
  NORMALIZER_VERSION,
  PASSENGER_CAR,
  SCHEMA_VERSION,
  type DataState,
  type DirectedEdge,
  type Eligibility,
  type GraphNode,
  type GraphSnapshot,
  type LonLat,
  type NodePassage,
  type TurnRestriction,
  type Turnaround,
  type VehicleLimits,
  type VehicleProfile,
} from './types';

export interface OsmElementNode { type: 'node'; id: number; lat: number; lon: number; tags?: Record<string, string> }
export interface OsmElementWay { type: 'way'; id: number; nodes: number[]; tags?: Record<string, string> }
export interface OsmElementRelation {
  type: 'relation';
  id: number;
  members: { type: 'node' | 'way' | 'relation'; ref: number; role: string }[];
  tags?: Record<string, string>;
}
export type OsmElement = OsmElementNode | OsmElementWay | OsmElementRelation;
export interface OverpassResponse { osm3s?: { timestamp_osm_base?: string }; elements: OsmElement[] }

export const OSM_ATTRIBUTION = '© OpenStreetMap contributors (ODbL 1.0)';

/** Highway values fetched from Overpass. Footways etc. are fetched only by fixtures. */
export const OVERPASS_HIGHWAY_REGEX =
  '^(motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|tertiary|tertiary_link|unclassified|residential|living_street|service|track|road|pedestrian|busway)$';

const CAR_CLASSES = new Set([
  'motorway', 'motorway_link', 'trunk', 'trunk_link', 'primary', 'primary_link', 'secondary', 'secondary_link',
  'tertiary', 'tertiary_link', 'unclassified', 'residential', 'living_street', 'service',
]);
const NON_CAR_CLASSES = new Set([
  'pedestrian', 'footway', 'path', 'cycleway', 'bridleway', 'steps', 'corridor', 'platform', 'busway',
  'bus_guideway', 'escape', 'raceway', 'elevator', 'via_ferrata', 'sidewalk', 'crossing',
]);
const LIFECYCLE = new Set(['construction', 'proposed', 'abandoned', 'disused', 'razed']);
const ALLOW_VALUES = new Set(['yes', 'permissive', 'designated', 'destination']);
const DENY_VALUES = new Set(['no', 'private']);
const BLOCKING_BARRIERS = new Set([
  'bollard', 'block', 'jersey_barrier', 'chain', 'cycle_barrier', 'kissing_gate', 'stile', 'turnstile',
  'full-height_turnstile', 'fence', 'wall', 'log', 'rope', 'motorcycle_barrier', 'bus_trap', 'sump_buster',
  'planter', 'debris', 'spikes', 'barrier_board', 'yes',
]);
const GATE_BARRIERS = new Set(['gate', 'lift_gate', 'swing_gate', 'sliding_gate', 'hampshire_gate', 'wicket_gate', 'bump_gate']);
const PASSABLE_BARRIERS = new Set(['kerb', 'cattle_grid', 'height_restrictor', 'entrance', 'toll_booth', 'border_control', 'speed_camera']);

function accessValue(tags: Record<string, string>): { value: string | null; key: string | null } {
  for (const k of ['motorcar', 'motor_vehicle', 'vehicle', 'access']) {
    if (tags[k] !== undefined) return { value: tags[k].split(';')[0].trim(), key: k };
  }
  return { value: null, key: null };
}

function explicitCarValue(tags: Record<string, string>): string | null {
  for (const k of ['motorcar', 'motor_vehicle', 'vehicle']) if (tags[k] !== undefined) return tags[k].split(';')[0].trim();
  return null;
}

function conditionalAccess(tags: Record<string, string>): string | null {
  for (const k of ['motorcar:conditional', 'motor_vehicle:conditional', 'vehicle:conditional', 'access:conditional']) {
    if (tags[k] !== undefined) return `${k}=${tags[k]}`;
  }
  return null;
}

/** Parse a length tag (maxwidth/maxheight) into centimetres. */
export function parseLengthCm(v: string | undefined): number | null | 'below_default' {
  if (v === undefined) return null;
  const s = v.trim().toLowerCase();
  if (s === 'none' || s === 'default' || s === 'unsigned') return null;
  if (s === 'below_default') return 'below_default';
  const ft = /^(\d+(?:\.\d+)?)\s*'\s*(?:(\d+(?:\.\d+)?)\s*")?$/.exec(s);
  if (ft) return Math.round((parseFloat(ft[1]) * 12 + (ft[2] ? parseFloat(ft[2]) : 0)) * 2.54);
  const m = /^(\d+(?:[.,]\d+)?)\s*(m|cm)?$/.exec(s);
  if (m) {
    const n = parseFloat(m[1].replace(',', '.'));
    return Math.round(m[2] === 'cm' ? n : n * 100);
  }
  return null;
}

export function parseWeightKg(v: string | undefined): number | null {
  if (v === undefined) return null;
  const m = /^(\d+(?:[.,]\d+)?)\s*(t|kg)?$/.exec(v.trim().toLowerCase());
  if (!m) return null;
  const n = parseFloat(m[1].replace(',', '.'));
  return Math.round(m[2] === 'kg' ? n : n * 1000);
}

export interface WayClassification {
  eligibility: Eligibility;
  reason: string | null;
  motorVehicleAccess: string;
  conditional: string | null;
  limits: VehicleLimits;
  confidence: 'high' | 'medium' | 'low';
}

export function classifyWay(tags: Record<string, string>, profile: VehicleProfile = PASSENGER_CAR): WayClassification {
  const hw = tags.highway ?? '';
  const acc = accessValue(tags);
  const explicitCar = explicitCarValue(tags);
  const conditional = conditionalAccess(tags);
  const w = parseLengthCm(tags.maxwidth);
  const h = parseLengthCm(tags.maxheight);
  const limits: VehicleLimits = {
    maxWidthCm: typeof w === 'number' ? w : null,
    maxHeightCm: typeof h === 'number' ? h : null,
    maxWeightKg: parseWeightKg(tags.maxweight),
  };
  let confidence: 'high' | 'medium' | 'low' = tags.fixme || tags.FIXME ? 'low' : 'high';
  const res = (eligibility: Eligibility, reason: string | null): WayClassification => ({
    eligibility, reason, motorVehicleAccess: acc.value ?? 'default', conditional, limits, confidence,
  });

  if (tags.area === 'yes') return res('excluded', 'highway_area');
  if (LIFECYCLE.has(hw)) return res('excluded', 'not_in_service');
  if (NON_CAR_CLASSES.has(hw)) {
    if (explicitCar !== null && ALLOW_VALUES.has(explicitCar)) {
      /* explicitly open to cars: continue checks below */
    } else return res('excluded', `not_car_road_${hw}`);
  } else if (!CAR_CLASSES.has(hw) && hw !== 'track' && hw !== 'road') {
    return res('excluded', `not_a_road_${hw || 'untagged'}`);
  }
  if (acc.value !== null) {
    if (DENY_VALUES.has(acc.value)) return res('excluded', `access_${acc.value}`);
    if (!ALLOW_VALUES.has(acc.value)) return res('needsReview', `restricted_access_${acc.value}`);
    if (acc.value === 'destination') confidence = 'medium';
  }
  if (tags.oneway === 'reversible' || tags.oneway === 'alternating') return res('excluded', 'reversible_oneway');
  if (w === 'below_default' || h === 'below_default') return res('needsReview', 'vehicle_limit_below_default');
  if (limits.maxWidthCm !== null && limits.maxWidthCm < profile.widthMeters * 100) return res('excluded', 'vehicle_limit_width');
  if (limits.maxHeightCm !== null && limits.maxHeightCm < profile.heightMeters * 100) return res('excluded', 'vehicle_limit_height');
  if (limits.maxWeightKg !== null && limits.maxWeightKg < profile.weightTonnes * 1000) return res('excluded', 'vehicle_limit_weight');
  if (hw === 'service') {
    const sub = tags.service;
    if (sub === 'parking_aisle' || sub === 'drive-through' || sub === 'emergency_access') return res('excluded', `service_${sub}`);
    if (sub === 'driveway' && !(acc.value !== null && ALLOW_VALUES.has(acc.value) && acc.value !== 'destination')) {
      return res('needsReview', 'driveway_access_unverified');
    }
  }
  if (hw === 'track' && !(acc.value !== null && ALLOW_VALUES.has(acc.value))) return res('needsReview', 'track_car_access_unverified');
  if (hw === 'road') return res('needsReview', 'road_type_unknown');
  if (conditional) return res('needsReview', 'conditional_access');
  if (tags['oneway:conditional'] || tags['oneway:motor_vehicle:conditional']) return res('needsReview', 'conditional_oneway');
  return res('eligible', null);
}

/** Directions permitted for motor vehicles: [forward, backward]. */
export function wayDirections(tags: Record<string, string>): [boolean, boolean] {
  const ow = tags['oneway:motorcar'] ?? tags['oneway:motor_vehicle'] ?? tags['oneway:vehicle'] ?? tags.oneway;
  if (ow === 'yes' || ow === 'true' || ow === '1') return [true, false];
  if (ow === '-1' || ow === 'reverse') return [false, true];
  if (ow === 'no' || ow === 'false' || ow === '0') return [true, true];
  if (ow === 'reversible' || ow === 'alternating') return [true, true]; // excluded by classification
  const hw = tags.highway;
  if (tags.junction === 'roundabout' || tags.junction === 'circular') return [true, false];
  if (hw === 'motorway' || hw === 'motorway_link') return [true, false];
  return [true, true];
}

function nodePassage(tags: Record<string, string> | undefined): { passage: NodePassage; barrier: string | null } {
  if (!tags || tags.barrier === undefined) return { passage: 'free', barrier: null };
  const b = tags.barrier;
  const acc = accessValue(tags).value;
  if (acc !== null && DENY_VALUES.has(acc)) return { passage: 'blocked', barrier: b };
  if (acc !== null && ALLOW_VALUES.has(acc) && acc !== 'destination') return { passage: 'free', barrier: b };
  if (PASSABLE_BARRIERS.has(b)) return { passage: 'free', barrier: b };
  if (BLOCKING_BARRIERS.has(b)) return { passage: 'blocked', barrier: b };
  if (GATE_BARRIERS.has(b)) return { passage: 'unverified', barrier: b };
  return { passage: 'unverified', barrier: b };
}

function nodeTurnaround(tags: Record<string, string> | undefined): Turnaround {
  if (!tags) return 'none';
  if (tags.highway === 'turning_circle') return 'turning_circle';
  if (tags.highway === 'turning_loop') return 'turning_loop';
  return 'none';
}

function names(tags: Record<string, string>): Record<string, string> {
  const out: Record<string, string> = {};
  if (tags.name) out.default = tags.name;
  for (const l of ['hy', 'en', 'ru', 'fa']) if (tags[`name:${l}`]) out[l] = tags[`name:${l}`];
  if (!tags.name && tags.ref) out.ref = tags.ref;
  return out;
}

export interface NormalizeOptions {
  providerId?: string;
  fetchedAt: string;
  dataState?: DataState;
  profile?: VehicleProfile;
  extraCompletenessNotes?: string[];
}

interface Segment {
  wayId: number;
  nodeIds: number[];
  physicalSegmentId: string;
}

export async function normalizeOverpass(resp: OverpassResponse, opts: NormalizeOptions): Promise<GraphSnapshot> {
  const profile = opts.profile ?? PASSENGER_CAR;
  const osmNodes = new Map<number, OsmElementNode>();
  const ways: OsmElementWay[] = [];
  const relations: OsmElementRelation[] = [];
  for (const el of resp.elements) {
    if (el.type === 'node') osmNodes.set(el.id, el);
    else if (el.type === 'way') ways.push(el);
    else if (el.type === 'relation') relations.push(el);
  }
  ways.sort((a, b) => a.id - b.id);
  const notes: string[] = [
    'OpenStreetMap data may be incomplete or outdated; field verification is required.',
    'Live closures and traffic are not available from this source.',
    'Conditional turn restrictions are applied at all times (conservative).',
    ...(opts.extraCompletenessNotes ?? []),
  ];
  let missingNodeRefs = 0;

  // Highway ways with resolvable nodes.
  const hwWays = ways.filter((w) => w.tags?.highway !== undefined);
  const usage = new Map<number, number>();
  for (const w of hwWays) {
    const seen = new Set<number>();
    for (const n of w.nodes) {
      if (!osmNodes.has(n)) {
        missingNodeRefs++;
        continue;
      }
      usage.set(n, (usage.get(n) ?? 0) + (seen.has(n) ? 2 : 1));
      seen.add(n);
    }
  }
  if (missingNodeRefs > 0) notes.push(`${missingNodeRefs} way node references were missing from the source extract and were skipped.`);

  const isGraphNode = (id: number, idx: number, len: number) =>
    idx === 0 || idx === len - 1 || (usage.get(id) ?? 0) > 1 || osmNodes.get(id)?.tags?.barrier !== undefined;

  const graphNodeIds = new Set<number>();
  const segmentsByWay = new Map<number, Segment[]>();
  const usedSegIds = new Set<string>();
  for (const w of hwWays) {
    const ids = w.nodes.filter((n) => osmNodes.has(n));
    if (ids.length < 2) continue;
    const segs: Segment[] = [];
    let start = 0;
    for (let i = 1; i < ids.length; i++) {
      if (isGraphNode(ids[i], i, ids.length)) {
        const nodeIds = ids.slice(start, i + 1);
        // drop zero-length duplicates
        const dedup = nodeIds.filter((n, k) => k === 0 || n !== nodeIds[k - 1]);
        if (dedup.length >= 2 && dedup[0] !== dedup[dedup.length - 1]) {
          let sid = `osm:w${w.id}:n${dedup[0]}-n${dedup[dedup.length - 1]}`;
          let k = 2;
          while (usedSegIds.has(sid)) sid = `osm:w${w.id}:n${dedup[0]}-n${dedup[dedup.length - 1]}~${k++}`;
          usedSegIds.add(sid);
          segs.push({ wayId: w.id, nodeIds: dedup, physicalSegmentId: sid });
          graphNodeIds.add(dedup[0]);
          graphNodeIds.add(dedup[dedup.length - 1]);
        } else if (dedup.length >= 2) {
          // closed loop segment without intermediate graph node: split at middle node
          const mid = Math.floor(dedup.length / 2);
          for (const part of [dedup.slice(0, mid + 1), dedup.slice(mid)]) {
            if (part.length < 2) continue;
            let sid = `osm:w${w.id}:n${part[0]}-n${part[part.length - 1]}`;
            let kk = 2;
            while (usedSegIds.has(sid)) sid = `osm:w${w.id}:n${part[0]}-n${part[part.length - 1]}~${kk++}`;
            usedSegIds.add(sid);
            segs.push({ wayId: w.id, nodeIds: part, physicalSegmentId: sid });
            graphNodeIds.add(part[0]);
            graphNodeIds.add(part[part.length - 1]);
          }
        }
        start = i;
      }
    }
    segmentsByWay.set(w.id, segs);
  }

  const pos = (id: number): LonLat => {
    const n = osmNodes.get(id)!;
    return [quantize(n.lon), quantize(n.lat)];
  };

  const nodes: GraphNode[] = [...graphNodeIds]
    .sort((a, b) => a - b)
    .map((id) => {
      const t = osmNodes.get(id)?.tags;
      const p = nodePassage(t);
      return {
        nodeId: `osm:n${id}`,
        position: pos(id),
        passage: p.passage,
        barrier: p.barrier,
        turnaround: nodeTurnaround(t),
        sourceIds: [`node/${id}`],
      };
    });
  const nodeById = new Map(nodes.map((n) => [n.nodeId, n]));

  const edges: DirectedEdge[] = [];
  for (const w of hwWays) {
    const tags = w.tags ?? {};
    const cls = classifyWay(tags, profile);
    const [fwd, bwd] = wayDirections(tags);
    for (const seg of segmentsByWay.get(w.id) ?? []) {
      const geomF = seg.nodeIds.map(pos);
      let len = 0;
      for (let i = 1; i < geomF.length; i++) len += haversine(geomF[i - 1], geomF[i]);
      const lengthMm = Math.round(len * 1000);
      if (lengthMm <= 0) continue;
      const a = `osm:n${seg.nodeIds[0]}`;
      const b = `osm:n${seg.nodeIds[seg.nodeIds.length - 1]}`;
      const endBarriers = [nodeById.get(a)?.barrier, nodeById.get(b)?.barrier].filter((x): x is string => !!x);
      const base = {
        physicalSegmentId: seg.physicalSegmentId,
        lengthMeters: lengthMm / 1000,
        lengthMm,
        sourceFeatureIds: [`way/${w.id}`],
        names: names(tags),
        surface: tags.surface ?? null,
        roadClass: tags.highway,
        motorVehicleAccess: cls.motorVehicleAccess,
        conditionalAccess: cls.conditional,
        barriers: endBarriers,
        vehicleLimits: cls.limits,
        eligibility: cls.eligibility,
        eligibilityReason: cls.reason,
        dataConfidence: cls.confidence,
        provenance: `osm way/${w.id}`,
      };
      const fId = `${seg.physicalSegmentId}:f`;
      const bId = `${seg.physicalSegmentId}:b`;
      if (fwd) {
        edges.push({
          ...base, edgeId: fId, fromNodeId: a, toNodeId: b, geometry: geomF,
          directionIdentity: 'forward', reciprocalEdgeId: bwd ? bId : null,
        });
      }
      if (bwd) {
        edges.push({
          ...base, edgeId: bId, fromNodeId: b, toNodeId: a, geometry: geomF.slice().reverse(),
          directionIdentity: 'backward', reciprocalEdgeId: fwd ? fId : null,
        });
      }
    }
  }
  edges.sort((x, y) => (x.edgeId < y.edgeId ? -1 : x.edgeId > y.edgeId ? 1 : 0));

  // Turn restrictions
  const edgesByWay = new Map<number, DirectedEdge[]>();
  for (const e of edges) {
    const wid = Number(e.sourceFeatureIds[0].slice(4));
    const l = edgesByWay.get(wid) ?? [];
    l.push(e);
    edgesByWay.set(wid, l);
  }
  const restrictions: TurnRestriction[] = [];
  let skippedRestrictions = 0;
  let exceptedRestrictions = 0;
  for (const r of relations.slice().sort((a, b) => a.id - b.id)) {
    const t = r.tags ?? {};
    if (t.type !== 'restriction' && !(t.type ?? '').startsWith('restriction')) continue;
    let value = t['restriction:motorcar'] ?? t.restriction ?? t['restriction:motor_vehicle'];
    let condition: string | null = null;
    if (!value && t['restriction:conditional']) {
      const m = /^\s*([a-z_]+)\s*@\s*(.+)$/.exec(t['restriction:conditional']);
      if (m) {
        value = m[1];
        condition = m[2].trim();
      }
    }
    if (!value) continue;
    const except = (t.except ?? '').split(';').map((s) => s.trim());
    if (except.includes('motorcar') || except.includes('motor_vehicle')) {
      exceptedRestrictions++;
      continue;
    }
    const kind = value.startsWith('no_') ? 'no' : value.startsWith('only_') ? 'only' : null;
    if (!kind) continue;
    const from = r.members.filter((m) => m.role === 'from' && m.type === 'way');
    const to = r.members.filter((m) => m.role === 'to' && m.type === 'way');
    const via = r.members.filter((m) => m.role === 'via');
    if (from.length !== 1 || to.length !== 1 || via.length === 0) {
      skippedRestrictions++;
      continue;
    }
    const fromEdges = edgesByWay.get(from[0].ref) ?? [];
    const toEdges = edgesByWay.get(to[0].ref) ?? [];
    const entries: { from: string; viaNode: string | null; viaEdges: string[]; to: string }[] = [];
    if (via.length === 1 && via[0].type === 'node') {
      const vn = `osm:n${via[0].ref}`;
      for (const fe of fromEdges.filter((e) => e.toNodeId === vn)) {
        for (const te of toEdges.filter((e) => e.fromNodeId === vn)) {
          entries.push({ from: fe.edgeId, viaNode: vn, viaEdges: [], to: te.edgeId });
        }
      }
    } else if (via.every((v) => v.type === 'way')) {
      const viaWayIds = new Set(via.map((v) => v.ref));
      const viaEdges = [...viaWayIds].flatMap((id) => edgesByWay.get(id) ?? []);
      const viaOut = new Map<string, DirectedEdge[]>();
      for (const e of viaEdges) {
        const l = viaOut.get(e.fromNodeId) ?? [];
        l.push(e);
        viaOut.set(e.fromNodeId, l);
      }
      for (const fe of fromEdges) {
        // BFS along via edges from fe.toNodeId to a node where a to-edge starts
        const queue: { node: string; path: string[] }[] = [{ node: fe.toNodeId, path: [] }];
        let guard = 0;
        while (queue.length && guard++ < 200) {
          const { node, path } = queue.shift()!;
          if (path.length > 0) {
            for (const te of toEdges.filter((e) => e.fromNodeId === node)) {
              entries.push({ from: fe.edgeId, viaNode: null, viaEdges: path, to: te.edgeId });
            }
          }
          if (path.length >= 20) continue;
          for (const ve of viaOut.get(node) ?? []) {
            if (path.includes(ve.edgeId)) continue;
            if (ve.reciprocalEdgeId && path.includes(ve.reciprocalEdgeId)) continue;
            queue.push({ node: ve.toNodeId, path: [...path, ve.edgeId] });
          }
        }
      }
    }
    if (entries.length === 0) {
      skippedRestrictions++;
      continue;
    }
    const entryKey = (x: { from: string; viaEdges: string[]; to: string }) => x.from + '|' + x.viaEdges.join(',') + '|' + x.to;
    entries.sort((a, b) => (entryKey(a) < entryKey(b) ? -1 : entryKey(a) > entryKey(b) ? 1 : 0));
    entries.forEach((en, i) => {
      restrictions.push({
        restrictionId: entries.length > 1 ? `osm:r${r.id}#${i + 1}` : `osm:r${r.id}`,
        kind,
        sourceType: value!,
        fromEdgeId: en.from,
        viaNodeId: en.viaNode,
        viaEdgeIds: en.viaEdges,
        toEdgeId: en.to,
        appliesTo: [profile.id],
        condition,
        provenance: `osm relation/${r.id}`,
      });
    });
  }
  restrictions.sort((a, b) => (a.restrictionId < b.restrictionId ? -1 : 1));
  if (skippedRestrictions > 0) notes.push(`${skippedRestrictions} turn restriction relations could not be mapped to the extracted graph (likely outside the fetched area) and were not applied.`);
  if (exceptedRestrictions > 0) notes.push(`${exceptedRestrictions} turn restrictions exempt passenger cars and were ignored.`);

  const allPos = nodes.map((n) => n.position);
  const bb = allPos.length ? bboxOf(allPos) : { west: 0, south: 0, east: 0, north: 0 };
  const sourceTs = resp.osm3s?.timestamp_osm_base ?? 'unknown';
  const snapshot: GraphSnapshot = {
    schemaVersion: SCHEMA_VERSION,
    graphId: '',
    contentHash: '',
    providerId: opts.providerId ?? 'osm-overpass',
    normalizerVersion: NORMALIZER_VERSION,
    sourceDatasetVersion: sourceTs,
    fetchedAt: opts.fetchedAt,
    geographicExtent: bb,
    coordinateReferenceSystem: 'EPSG:4326',
    attribution: OSM_ATTRIBUTION,
    capabilities: {
      turnRestrictions: true,
      viaWayRestrictions: true,
      conditionalRestrictions: 'applied_conservatively',
      barriers: true,
      turnaroundData: true,
      liveClosures: false,
      traffic: false,
    },
    freshness: { sourceTimestamp: sourceTs, note: 'OSM replication timestamp reported by the Overpass server.' },
    completenessNotes: notes,
    dataState: opts.dataState ?? 'available',
    vehicleProfile: profile.id,
    nodes,
    directedEdges: edges,
    turnRestrictions: restrictions,
  };
  snapshot.contentHash = await computeContentHash(snapshot);
  snapshot.graphId = `g-${snapshot.contentHash.slice(0, 16)}`;
  return snapshot;
}
