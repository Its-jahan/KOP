// Measures normalisation, requirements, planning and validation on a real OSM extract.
import { readFileSync } from 'node:fs';
import { normalizeOverpass } from '../src/core/osm';
import { GraphIndex } from '../src/core/graph';
import { computeRequirements, edgeTraversable } from '../src/core/requirements';
import { edgeScc, planCoverage } from '../src/core/planner';
import { validatePlan } from '../src/core/validator';
import { snapStart, mainComponentPredicate } from '../src/core/snap';
import type { BoundaryPolicy } from '../src/core/types';

const resp = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const center: [number, number] = [Number(process.argv[3] ?? 44.51), Number(process.argv[4] ?? 40.184)];
let t = performance.now();
const g = await normalizeOverpass(resp, { fetchedAt: new Date().toISOString() });
const tNorm = performance.now() - t;
t = performance.now();
const gi = new GraphIndex(g);
const tIdx = performance.now() - t;
console.log(`graph nodes=${g.nodes.length} edges=${g.directedEdges.length} restrictions=${g.turnRestrictions.length} source=${g.sourceDatasetVersion} normalize=${tNorm.toFixed(0)}ms index=${tIdx.toFixed(0)}ms heapMB=${(process.memoryUsage().heapUsed / 1e6).toFixed(0)}`);
for (const r of [250, 500, 800]) {
  for (const policy of ['STRICT_INSIDE', 'ALLOW_NECESSARY_CONNECTORS'] as BoundaryPolicy[]) {
    t = performance.now();
    const req = computeRequirements(gi, { type: 'circle', center, radiusMeters: r });
    const tReq = performance.now() - t;
    const allow = (i: number) => edgeTraversable(gi, req.zoneInfo, policy, new Set(), i);
    const snap = snapStart(gi, center, allow, 150, mainComponentPredicate(gi, edgeScc(gi, allow)));
    if (!snap) { console.log(r, policy, 'no start'); continue; }
    t = performance.now();
    const plan = planCoverage({ gi, zoneInfo: req.zoneInfo, requirements: req.requirements, observed: new Map(), excluded: new Set(), policy,
      start: { edgeId: snap.edgeId, offsetMeters: snap.offsetMeters, source: 'selected_start' },
      meta: { planId: 'b', graphHash: g.contentHash, zoneVersion: 1, progressRevision: 0, planningRevision: 1, createdAt: 'x' } });
    const tPlan = performance.now() - t;
    t = performance.now();
    const v = validatePlan(gi, plan, { zoneInfo: req.zoneInfo, policy, requirements: req.requirements, observed: new Map(), excluded: new Set() });
    const tVal = performance.now() - t;
    const reqM = req.requirements.filter((x) => x.status !== 'needsReview').reduce((s, x) => s + x.lengthMeters, 0);
    const causes = plan.unreachable.reduce<Record<string, number>>((m, u) => ((m[u.cause] = (m[u.cause] ?? 0) + 1), m), {});
    console.log(`r=${r}m ${policy} reqs=${req.requirements.length} reqKm=${(reqM / 1000).toFixed(1)} status=${plan.status} valid=${v.ok} planKm=${(plan.totals.totalMeters / 1000).toFixed(1)} newKm=${(plan.totals.newCoverageMeters / 1000).toFixed(1)} repeatKm=${(plan.totals.repeatMeters / 1000).toFixed(1)} outsideKm=${(plan.totals.outsideMeters / 1000).toFixed(1)} unplanned=${JSON.stringify(causes)} req=${tReq.toFixed(0)}ms plan=${tPlan.toFixed(0)}ms validate=${tVal.toFixed(0)}ms`);
  }
}
