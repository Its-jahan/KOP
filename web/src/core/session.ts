// Session state derivation from an append-only event log. Replay is deterministic
// and idempotent: duplicate events (same eventId) and duplicate traversals (same
// traversalId) never double-credit progress.

import { addInterval, coveredWithin, type Interval } from './intervals';
import { COVERAGE_TOLERANCE_M, requirementCovered } from './requirements';
import type {
  CoverageSession,
  ExclusionRecord,
  MatchedTraversal,
  RequiredInterval,
  RequirementStatus,
  RoutePlan,
  SessionEvent,
  SessionStatus,
} from './types';

export interface DerivedProgress {
  union: Map<string, Interval[]>;
  traversals: MatchedTraversal[];
  exclusions: Map<string, ExclusionRecord>;
  plan: RoutePlan | null;
  status: SessionStatus;
  lastSeq: number;
  progressRevision: number;
  planningRevision: number;
  gaps: { reason: string; fromT: number | null; toT: number }[];
  appliedEventIds: Set<string>;
  rejectedEvents: { eventId: string; reason: string }[];
}

export function emptyProgress(): DerivedProgress {
  return {
    union: new Map(),
    traversals: [],
    exclusions: new Map(),
    plan: null,
    status: 'ready',
    lastSeq: 0,
    progressRevision: 0,
    planningRevision: 0,
    gaps: [],
    appliedEventIds: new Set(),
    rejectedEvents: [],
  };
}

export interface ApplyContext {
  sessionId: string;
  graphHash: string;
  edgeLengths: Map<string, number>;
}

/** Apply events in sequence order. Mutates and returns `p`. */
export function applyEvents(p: DerivedProgress, events: SessionEvent[], ctx: ApplyContext): DerivedProgress {
  const sorted = events.slice().sort((a, b) => a.seq - b.seq);
  const seenTraversal = new Set(p.traversals.map((t) => t.traversalId));
  for (const ev of sorted) {
    if (p.appliedEventIds.has(ev.eventId)) continue;
    if (ev.sessionId !== ctx.sessionId) {
      p.rejectedEvents.push({ eventId: ev.eventId, reason: 'foreign_session' });
      continue;
    }
    if (ev.seq <= p.lastSeq) {
      p.rejectedEvents.push({ eventId: ev.eventId, reason: 'non_monotonic_seq' });
      continue;
    }
    p.appliedEventIds.add(ev.eventId);
    p.lastSeq = ev.seq;
    switch (ev.type) {
      case 'traversal_confirmed': {
        const t = ev.payload.traversal as MatchedTraversal;
        const len = ctx.edgeLengths.get(t?.edgeId);
        if (!t || len === undefined || !(t.startOffsetMeters >= -0.01) || !(t.endOffsetMeters <= len + 0.01) || !(t.endOffsetMeters > t.startOffsetMeters)) {
          p.rejectedEvents.push({ eventId: ev.eventId, reason: 'invalid_traversal' });
          break;
        }
        if (seenTraversal.has(t.traversalId)) break;
        seenTraversal.add(t.traversalId);
        p.traversals.push(t);
        p.union.set(t.edgeId, addInterval(p.union.get(t.edgeId) ?? [], [Math.max(0, t.startOffsetMeters), Math.min(len, t.endOffsetMeters)]));
        p.progressRevision = ev.seq;
        break;
      }
      case 'plan_accepted': {
        const plan = ev.payload.plan as RoutePlan;
        if (plan && plan.graphHash === ctx.graphHash) {
          p.plan = plan;
          p.planningRevision = plan.planningRevision;
        } else p.rejectedEvents.push({ eventId: ev.eventId, reason: 'plan_graph_mismatch' });
        break;
      }
      case 'requirement_excluded': {
        const ids = ev.payload.requirementIds as string[];
        for (const id of ids ?? []) {
          p.exclusions.set(id, {
            requirementId: id,
            reason: (ev.payload.reason as ExclusionRecord['reason']) ?? 'blocked',
            note: (ev.payload.note as string) ?? null,
            at: ev.timestamp,
            active: true,
          });
        }
        p.progressRevision = ev.seq;
        break;
      }
      case 'requirement_restored': {
        for (const id of (ev.payload.requirementIds as string[]) ?? []) {
          const x = p.exclusions.get(id);
          if (x) x.active = false;
        }
        p.progressRevision = ev.seq;
        break;
      }
      case 'gap_recorded':
        p.gaps.push(ev.payload as DerivedProgress['gaps'][number]);
        break;
      case 'paused':
        p.status = 'paused';
        break;
      case 'resumed':
      case 'start_confirmed':
        p.status = 'active';
        break;
      case 'ended':
        p.status = 'ended';
        break;
      default:
        break;
    }
  }
  return p;
}

export function activeExclusionIds(p: DerivedProgress): Set<string> {
  return new Set([...p.exclusions.values()].filter((x) => x.active).map((x) => x.requirementId));
}

export function requirementStatus(
  r: RequiredInterval,
  union: Interval[],
  excluded: boolean,
  unreachableIds: Set<string>,
): RequirementStatus {
  if (excluded) return 'blocked';
  if (requirementCovered(union, r, COVERAGE_TOLERANCE_M)) return 'covered';
  const cov = coveredWithin(union, r.startOffsetMeters, r.endOffsetMeters);
  if (cov > 0.5) return 'partial';
  if (r.status === 'needsReview') return 'needsReview';
  if (unreachableIds.has(r.requirementId)) return 'unreachable';
  return 'unvisited';
}

export interface CoverageMetrics {
  totalRequiredMeters: number;
  confirmedMeters: number;
  remainingMeters: number;
  blockedMeters: number;
  unreachableMeters: number;
  needsReviewMeters: number;
  counts: Record<RequirementStatus, number>;
  percentConfirmed: number;
}

export function computeMetrics(
  requirements: RequiredInterval[],
  p: DerivedProgress,
): { metrics: CoverageMetrics; statuses: Map<string, RequirementStatus> } {
  const excluded = activeExclusionIds(p);
  const unreachable = new Set(
    (p.plan?.unreachable ?? []).filter((u) => u.cause === 'outside_policy' || u.cause === 'not_reachable_from_start').map((u) => u.requirementId),
  );
  const counts: Record<RequirementStatus, number> = { unvisited: 0, partial: 0, covered: 0, blocked: 0, unreachable: 0, needsReview: 0 };
  const statuses = new Map<string, RequirementStatus>();
  let total = 0, confirmed = 0, remaining = 0, blocked = 0, unr = 0, nr = 0;
  for (const r of requirements) {
    const u = p.union.get(r.edgeId) ?? [];
    const st = requirementStatus(r, u, excluded.has(r.requirementId), unreachable);
    statuses.set(r.requirementId, st);
    counts[st]++;
    total += r.lengthMeters;
    const cov = Math.min(r.lengthMeters, coveredWithin(u, r.startOffsetMeters, r.endOffsetMeters));
    confirmed += cov;
    const unc = r.lengthMeters - cov;
    if (st === 'blocked') blocked += unc;
    else if (st === 'unreachable') unr += unc;
    else if (st === 'needsReview') nr += unc;
    else remaining += unc;
  }
  return {
    metrics: {
      totalRequiredMeters: total,
      confirmedMeters: confirmed,
      remainingMeters: remaining,
      blockedMeters: blocked,
      unreachableMeters: unr,
      needsReviewMeters: nr,
      counts,
      percentConfirmed: total > 0 ? (100 * confirmed) / total : 0,
    },
    statuses,
  };
}

/** Build the canonical CoverageSession document for export/persistence. */
export function sessionDocument(base: Omit<CoverageSession, 'requiredIntervals' | 'confirmedTraversals' | 'exclusions' | 'routePlan' | 'status' | 'planningRevision' | 'progressRevision' | 'eventLogPosition'>, requirements: RequiredInterval[], p: DerivedProgress): CoverageSession {
  const { statuses } = computeMetrics(requirements, p);
  return {
    ...base,
    status: p.status,
    planningRevision: p.planningRevision,
    progressRevision: p.progressRevision,
    requiredIntervals: requirements.map((r) => ({ ...r, status: statuses.get(r.requirementId) ?? r.status })),
    confirmedTraversals: p.traversals.slice(),
    exclusions: [...p.exclusions.values()],
    routePlan: p.plan,
    eventLogPosition: p.lastSeq,
  };
}
