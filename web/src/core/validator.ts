// Independent plan validator. It re-derives legality from the graph and must not
// trust any planner bookkeeping.

import type { GraphIndex, Prefix } from './graph';
import { haversine } from './geo';
import { addInterval, coveredWithin, type Interval } from './intervals';
import { STRICT_OUTSIDE_TOL_M, requirementCovered, type EdgeZoneInfo } from './requirements';
import type { BoundaryPolicy, RequiredInterval, RoutePlan } from './types';

export interface ValidationResult {
  ok: boolean;
  errors: string[];
}

export function validatePlan(
  gi: GraphIndex,
  plan: RoutePlan,
  ctx: {
    zoneInfo: EdgeZoneInfo[];
    policy: BoundaryPolicy;
    requirements: RequiredInterval[];
    observed: Map<string, Interval[]>;
    excluded: Set<string>;
    startPrefix?: Prefix;
  },
): ValidationResult {
  const errors: string[] = [];
  const eps = 0.01;
  if (plan.graphHash !== gi.g.contentHash) errors.push('graph_hash_mismatch');
  const steps = plan.steps;
  if (steps.length === 0) {
    if (plan.coveredRequirementIds.length) errors.push('empty_plan_claims_coverage');
    return { ok: errors.length === 0, errors };
  }
  const reqById = new Map(ctx.requirements.map((r) => [r.requirementId, r]));
  const blockedEdges = new Set<string>();
  for (const id of ctx.excluded) {
    const r = reqById.get(id);
    if (r) blockedEdges.add(r.edgeId);
  }
  if (steps[0].edgeId !== plan.startState.edgeId) errors.push('first_step_not_start_edge');
  if (Math.abs(steps[0].fromOffsetMeters - plan.startState.offsetMeters) > eps) errors.push('first_step_not_start_offset');

  let prefix: Prefix | null = null;
  let outside = 0;
  const planned = new Map<string, Interval[]>();
  for (let k = 0; k < steps.length; k++) {
    const s = steps[k];
    const ei = gi.idx.get(s.edgeId);
    if (ei === undefined) {
      errors.push(`step_${k}_unknown_edge`);
      continue;
    }
    const e = gi.edges[ei];
    if (e.eligibility !== 'eligible') errors.push(`step_${k}_ineligible_edge:${e.eligibility}`);
    if (blockedEdges.has(e.edgeId) && k > 0) errors.push(`step_${k}_user_blocked_edge`);
    if (s.fromOffsetMeters < -eps || s.toOffsetMeters > e.lengthMeters + eps || s.toOffsetMeters < s.fromOffsetMeters - eps) {
      errors.push(`step_${k}_bad_offsets`);
    }
    if (k > 0 && Math.abs(s.fromOffsetMeters) > eps) errors.push(`step_${k}_not_from_edge_start`);
    if (k < steps.length - 1 && Math.abs(s.toOffsetMeters - e.lengthMeters) > eps) errors.push(`step_${k}_not_to_edge_end`);
    const zi = ctx.zoneInfo[ei];
    const out = Math.max(0, s.toOffsetMeters - s.fromOffsetMeters - coveredWithin(zi.inside, s.fromOffsetMeters, s.toOffsetMeters));
    outside += out;
    if (ctx.policy === 'STRICT_INSIDE' && zi.outsideMeters > STRICT_OUTSIDE_TOL_M) errors.push(`step_${k}_outside_zone_under_strict`);
    if (k === 0) {
      prefix = ctx.startPrefix && ctx.startPrefix[ctx.startPrefix.length - 1] === ei ? ctx.startPrefix : [ei];
    } else if (prefix) {
      const prev = prefix[prefix.length - 1];
      const pe = gi.edges[prev];
      if (pe.toNodeId !== e.fromNodeId) errors.push(`step_${k}_disconnected`);
      const a = pe.geometry[pe.geometry.length - 1];
      const b = e.geometry[0];
      if (haversine(a, b) > 0.5) errors.push(`step_${k}_geometry_gap`);
      const uturn = gi.isUturn(prev, ei);
      if (uturn && !gi.deadEnd[prev]) errors.push(`step_${k}_illegal_uturn`);
      if (uturn && !s.turnaroundAtStart) errors.push(`step_${k}_unflagged_uturn`);
      const node = gi.nodes.get(pe.toNodeId);
      if (!uturn && (!node || node.passage !== 'free')) errors.push(`step_${k}_through_barrier`);
      const np = gi.step(prefix, ei);
      if (!np) errors.push(`step_${k}_turn_restriction_violated`);
      prefix = np ?? [ei];
    }
    planned.set(e.edgeId, addInterval(planned.get(e.edgeId) ?? [], [s.fromOffsetMeters, s.toOffsetMeters]));
  }
  if (ctx.policy === 'STRICT_INSIDE' && outside > STRICT_OUTSIDE_TOL_M * steps.length + eps) errors.push('strict_outside_travel');
  if (Math.abs(outside - plan.totals.outsideMeters) > 0.5) errors.push('outside_total_mismatch');
  for (const id of plan.coveredRequirementIds) {
    const r = reqById.get(id);
    if (!r) {
      errors.push(`claimed_unknown_requirement:${id}`);
      continue;
    }
    if (ctx.excluded.has(id)) errors.push(`claimed_excluded_requirement:${id}`);
    const u = [...(ctx.observed.get(r.edgeId) ?? []), ...(planned.get(r.edgeId) ?? [])];
    if (!requirementCovered(u.sort((x, y) => x[0] - y[0]), r)) errors.push(`claimed_but_not_traversed:${id}`);
  }
  const claimed = new Set(plan.coveredRequirementIds);
  const unreach = new Set(plan.unreachable.map((u) => u.requirementId));
  for (const r of ctx.requirements) {
    if (requirementCovered(ctx.observed.get(r.edgeId) ?? [], r)) continue;
    if (!claimed.has(r.requirementId) && !unreach.has(r.requirementId)) errors.push(`requirement_unaccounted:${r.requirementId}`);
  }
  if (plan.status === 'complete' && plan.unreachable.some((u) => u.cause === 'not_reachable_from_start' || u.cause === 'budget_exceeded')) {
    errors.push('complete_status_with_unplanned_remainder');
  }
  return { ok: errors.length === 0, errors };
}
