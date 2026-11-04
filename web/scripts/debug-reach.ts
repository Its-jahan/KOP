import { readFileSync } from 'node:fs';
import { normalizeOverpass } from '../src/core/osm';
import { GraphIndex } from '../src/core/graph';
import { computeRequirements, edgeTraversable } from '../src/core/requirements';
import { snapStart } from '../src/core/snap';
import { edgeScc } from '../src/core/planner';
const resp = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const g = await normalizeOverpass(resp, { fetchedAt: 'x' });
const gi = new GraphIndex(g);
const req = computeRequirements(gi, { type: 'circle', center: [44.51, 40.184], radiusMeters: 300 });
const allow = (i: number) => edgeTraversable(gi, req.zoneInfo, 'ALLOW_NECESSARY_CONNECTORS', new Set(), i);
const snap = snapStart(gi, [44.51, 40.184], allow)!;
const s = gi.idx.get(snap.edgeId)!;
console.log('start', snap, gi.edges[s].roadClass, gi.edges[s].names);
// BFS reachability
const seen = new Set<string>([String(s)]);
const q = [[s]];
let count = 0;
while (q.length) { const p = q.shift()!; count++; for (const t of gi.successors(p, allow)) { const k = t.prefix.join(','); if (!seen.has(k)) { seen.add(k); q.push(t.prefix); } } }
console.log('reachable states', count);
const comp = edgeScc(gi, allow);
const sizes = new Map<number, number>();
comp.forEach((c) => c >= 0 && sizes.set(c, (sizes.get(c) ?? 0) + 1));
console.log('scc count', sizes.size, 'largest', [...sizes.values()].sort((a, b) => b - a).slice(0, 5), 'start comp size', sizes.get(comp[s]));
const passages = g.nodes.reduce((m, n) => ((m[n.passage] = (m[n.passage] ?? 0) + 1), m), {} as Record<string, number>);
console.log('passages', passages, 'deadEnds', [...gi.deadEnd].filter((x) => x).length);
// classes of eligible edges
const cls = g.directedEdges.filter((e) => e.eligibility === 'eligible').reduce((m, e) => ((m[e.roadClass] = (m[e.roadClass] ?? 0) + 1), m), {} as Record<string, number>);
console.log(cls);
// check a primary road edge successors
const prim = gi.edges.findIndex((e) => e.roadClass === 'primary' || e.roadClass === 'secondary');
console.log('sample', gi.edges[prim].edgeId, gi.edges[prim].roadClass, 'succ', gi.successors([prim], allow).map((t) => gi.edges[t.next].edgeId + ' ' + gi.edges[t.next].roadClass));
console.log('node', gi.nodes.get(gi.edges[prim].toNodeId), 'out', (gi.out.get(gi.edges[prim].toNodeId) ?? []).map((j) => gi.edges[j].edgeId + ' ' + gi.edges[j].eligibility));
