import type { GraphIndex } from '../core/graph';
import { slicePolyline } from '../core/geo';
import type { Interval } from '../core/intervals';
import type { RequiredInterval, RequirementStatus, RoutePlan } from '../core/types';

type FC = GeoJSON.FeatureCollection;

export function requirementFeatures(gi: GraphIndex, reqs: RequiredInterval[], statuses: Map<string, RequirementStatus>): FC {
  const features: GeoJSON.Feature[] = [];
  for (const r of reqs) {
    const i = gi.idx.get(r.edgeId);
    if (i === undefined) continue;
    const coords = slicePolyline(gi.edges[i].geometry, gi.cum[i], r.startOffsetMeters, r.endOffsetMeters);
    features.push({
      type: 'Feature',
      properties: { id: r.requirementId, status: statuses.get(r.requirementId) ?? r.status, edgeId: r.edgeId },
      geometry: { type: 'LineString', coordinates: coords },
    });
  }
  return { type: 'FeatureCollection', features };
}

export function coveredFeatures(gi: GraphIndex, union: Map<string, Interval[]>): FC {
  const features: GeoJSON.Feature[] = [];
  for (const [edgeId, ivs] of union) {
    const i = gi.idx.get(edgeId);
    if (i === undefined) continue;
    for (const [a, b] of ivs) {
      if (b - a < 0.5) continue;
      features.push({ type: 'Feature', properties: { edgeId }, geometry: { type: 'LineString', coordinates: slicePolyline(gi.edges[i].geometry, gi.cum[i], a, b) } });
    }
  }
  return { type: 'FeatureCollection', features };
}

export function routeFeatures(gi: GraphIndex, plan: RoutePlan | null, fromStep = 0): FC {
  if (!plan) return { type: 'FeatureCollection', features: [] };
  const features: GeoJSON.Feature[] = [];
  let cur: { kind: string; coords: number[][] } | null = null;
  for (let k = fromStep; k < plan.steps.length; k++) {
    const s = plan.steps[k];
    const i = gi.idx.get(s.edgeId);
    if (i === undefined) continue;
    const coords = slicePolyline(gi.edges[i].geometry, gi.cum[i], s.fromOffsetMeters, s.toOffsetMeters);
    const kind = s.outsideMeters > 2 ? 'outside' : s.kind;
    if (cur && cur.kind === kind) cur.coords.push(...coords.slice(1));
    else {
      if (cur) features.push({ type: 'Feature', properties: { kind: cur.kind }, geometry: { type: 'LineString', coordinates: cur.coords } });
      cur = { kind, coords: coords.slice() };
    }
  }
  if (cur) features.push({ type: 'Feature', properties: { kind: cur.kind }, geometry: { type: 'LineString', coordinates: cur.coords } });
  return { type: 'FeatureCollection', features };
}
