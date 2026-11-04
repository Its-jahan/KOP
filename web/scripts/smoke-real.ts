import { readFileSync } from 'node:fs';
import { normalizeOverpass } from '../src/core/osm';
import { GraphIndex } from '../src/core/graph';
import { computeRequirements } from '../src/core/requirements';
import { planCoverage } from '../src/core/planner';
import { validatePlan } from '../src/core/validator';
import { snapStart, mainComponentPredicate } from '../src/core/snap';
import { edgeScc } from '../src/core/planner';
import { edgeTraversable } from '../src/core/requirements';
import type { BoundaryPolicy, ZoneDefinition } from '../src/core/types';

const file = process.argv[2];
const resp = JSON.parse(readFileSync(file, 'utf8'));
const t0 = performance.now();
const g = await normalizeOverpass(resp, { fetchedAt: new Date().toISOString() });
const t1 = performance.now();
const gi = new GraphIndex(g);
const elig = g.directedEdges.reduce((m, e) => ((m[e.eligibility] = (m[e.eligibility] ?? 0) + 1), m), {} as Record<string, number>);
console.log('graph', g.graphId, 'nodes', g.nodes.length, 'edges', g.directedEdges.length, 'restrictions', g.turnRestrictions.length, elig, 'normalize ms', (t1 - t0).toFixed(0));
const reasons = g.directedEdges.reduce((m, e) => (e.eligibilityReason ? ((m[e.eligibilityReason] = (m[e.eligibilityReason] ?? 0) + 1), m) : m), {} as Record<string, number>);
console.log('reasons', reasons);
const zone: ZoneDefinition = { type: 'circle', center: [44.5100, 40.1840], radiusMeters: Number(process.argv[3] ?? 300) };
for (const policy of ['STRICT_INSIDE', 'ALLOW_NECESSARY_CONNECTORS'] as BoundaryPolicy[]) {
  const req = computeRequirements(gi, zone);
  const allow = (i: number) => edgeTraversable(gi, req.zoneInfo, policy, new Set(), i);
  const snap = snapStart(gi, [44.5100, 40.1840], allow, 150, mainComponentPredicate(gi, edgeScc(gi, allow)))!;
  const t2 = performance.now();
  const plan = planCoverage({
    gi, zoneInfo: req.zoneInfo, requirements: req.requirements, observed: new Map(), excluded: new Set(), policy,
    start: { edgeId: snap.edgeId, offsetMeters: snap.offsetMeters, source: 'selected_start' },
    meta: { planId: 'p1', graphHash: g.contentHash, zoneVersion: 1, progressRevision: 0, planningRevision: 1, createdAt: 'x' },
  });
  const t3 = performance.now();
  const v = validatePlan(gi, plan, { zoneInfo: req.zoneInfo, policy, requirements: req.requirements, observed: new Map(), excluded: new Set() });
  const reqM = req.requirements.reduce((s, r) => s + r.lengthMeters, 0);
  const causes = plan.unreachable.reduce((m, u) => ((m[u.cause] = (m[u.cause] ?? 0) + 1), m), {} as Record<string, number>);
  console.log(policy, 'reqs', req.requirements.length, 'reqM', reqM.toFixed(0), 'status', plan.status, 'steps', plan.steps.length, 'covered', plan.coveredRequirementIds.length, 'unreach', causes,
    'totals', Object.fromEntries(Object.entries(plan.totals).map(([k, v]) => [k, Math.round(v)])), 'ms', (t3 - t2).toFixed(0), 'iters', plan.limits.iterationsUsed, 'valid', v.ok, v.errors.slice(0, 5), plan.warnings);
}
