import { readFileSync } from 'node:fs';
import { normalizeOverpass } from '../src/core/osm';
import { GraphIndex } from '../src/core/graph';
import { computeRequirements, edgeTraversable } from '../src/core/requirements';
import { snapStart, mainComponentPredicate } from '../src/core/snap';
import { edgeScc, planCoverage } from '../src/core/planner';
import type { BoundaryPolicy } from '../src/core/types';
const resp = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const policy = (process.argv[4] ?? 'ALLOW_NECESSARY_CONNECTORS') as BoundaryPolicy;
const g = await normalizeOverpass(resp, { fetchedAt: 'x' });
const gi = new GraphIndex(g);
const req = computeRequirements(gi, { type: 'circle', center: [44.51, 40.184], radiusMeters: Number(process.argv[3]) });
const allow = (i: number) => edgeTraversable(gi, req.zoneInfo, policy, new Set(), i);
const comp = edgeScc(gi, allow);
const main = mainComponentPredicate(gi, comp);
const snap = snapStart(gi, [44.51, 40.184], allow, 150, main)!;
const plan = planCoverage({ gi, zoneInfo: req.zoneInfo, requirements: req.requirements, observed: new Map(), excluded: new Set(), policy,
  start: { edgeId: snap.edgeId, offsetMeters: snap.offsetMeters, source: 'selected_start' },
  meta: { planId: 'p', graphHash: g.contentHash, zoneVersion: 1, progressRevision: 0, planningRevision: 1, createdAt: 'x' } });
const reqById = new Map(req.requirements.map((r) => [r.requirementId, r]));
let inMain = 0, notMain = 0; const why: Record<string, number> = {};
const reasonsNotMain: Record<string, number> = {};
for (const u of plan.unreachable.filter((u) => u.cause === 'not_reachable_from_start')) {
  const r = reqById.get(u.requirementId)!; const i = gi.idx.get(r.edgeId)!;
  if (main(i)) { inMain++; } else { notMain++; const e = gi.edges[i]; const k = e.roadClass + (e.reciprocalEdgeId ? '' : ':oneway'); reasonsNotMain[k] = (reasonsNotMain[k] ?? 0) + 1;
    const fromN = gi.nodes.get(e.fromNodeId)!; const toN = gi.nodes.get(e.toNodeId)!; const w = `from:${fromN.passage} to:${toN.passage}`; why[w] = (why[w] ?? 0) + 1; }
}
console.log('start in main', main(gi.idx.get(snap.edgeId)!), 'unreachable in main SCC', inMain, 'not in main', notMain, reasonsNotMain, why);
// sizes of comps containing unreachable reqs
const compSizes = new Map<number, number>(); comp.forEach((c) => c >= 0 && compSizes.set(c, (compSizes.get(c) ?? 0) + 1));
const hist: Record<string, number> = {};
for (const u of plan.unreachable.filter((u) => u.cause === 'not_reachable_from_start')) { const i = gi.idx.get(reqById.get(u.requirementId)!.edgeId)!; const sz = compSizes.get(comp[i]) ?? 0; const b = sz <= 2 ? '<=2' : sz <= 10 ? '3-10' : sz <= 50 ? '11-50' : '>50'; hist[b] = (hist[b] ?? 0) + 1; }
console.log('comp size hist of unreachable', hist);
