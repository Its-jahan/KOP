// Revision-safe acceptance of asynchronous planning results.

export interface PlanningContext {
  graphHash: string;
  zoneVersion: number;
  latestRequestedPlanningRevision: number;
  acceptedPlanningRevision: number;
  progressRevision: number;
}

export interface PlanResultMeta {
  graphHash: string;
  zoneVersion: number;
  planningRevision: number;
  basedOnProgressRevision: number;
  status: string;
}

export type PlanDecision =
  | { accept: true; reconcile: boolean }
  | { accept: false; reason: 'graph_mismatch' | 'zone_mismatch' | 'superseded' | 'older_than_accepted' | 'failed' | 'cancelled' };

/**
 * A result is accepted only if it answers the newest request for the same graph and
 * zone. If progress advanced while computing, it is accepted with `reconcile` so the
 * caller skips steps whose requirements are already confirmed (guidance follows the
 * actual position, never an obsolete cursor).
 */
export function decidePlan(ctx: PlanningContext, r: PlanResultMeta): PlanDecision {
  if (r.graphHash !== ctx.graphHash) return { accept: false, reason: 'graph_mismatch' };
  if (r.zoneVersion !== ctx.zoneVersion) return { accept: false, reason: 'zone_mismatch' };
  if (r.planningRevision <= ctx.acceptedPlanningRevision) return { accept: false, reason: 'older_than_accepted' };
  if (r.planningRevision < ctx.latestRequestedPlanningRevision) return { accept: false, reason: 'superseded' };
  if (r.status === 'failed') return { accept: false, reason: 'failed' };
  if (r.status === 'cancelled') return { accept: false, reason: 'cancelled' };
  return { accept: true, reconcile: r.basedOnProgressRevision < ctx.progressRevision };
}
