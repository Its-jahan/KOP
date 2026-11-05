// Multi-day coverage campaign: one large zone driven over many days from a fixed home point.
//
// The whole remaining area is planned once from home (same legal planner), then the route is
// cut into consecutive daily chunks that fit the daily time budget including the drive from
// home to the chunk and back. After each day the user confirms what was actually driven (from
// GPS); confirmed coverage is removed and the remaining days are re-planned.

import type { GraphIndex } from './graph';
import { haversine, pointAtOffset } from './geo';
import { addInterval, type Interval } from './intervals';
import { legalShortestPath, planCoverage } from './planner';
import { requirementCovered, type EdgeZoneInfo } from './requirements';
import type { BoundaryPolicy, LonLat, PlanStep, RequiredInterval, RoutePlan, ZoneDefinition } from './types';

export interface CampaignSettings {
  hoursPerDay: number;
  /** Effective average speed while covering (stops, turns, lights). */
  speedKmh: number;
}

export const DEFAULT_CAMPAIGN_SETTINGS: CampaignSettings = { hoursPerDay: 4, speedKmh: 14 };

export interface CampaignDay {
  /** 1-based day number within the current plan. */
  day: number;
  /** Drive from home to where this day's coverage starts (not counted as new coverage). */
  approach: PlanStep[];
  /** This day's part of the coverage route. */
  steps: PlanStep[];
  targetIds: string[];
  coverMeters: number;
  approachMeters: number;
  /** Estimated drive back home (straight-line x 1.4; informative). */
  returnMeters: number;
  totalMeters: number;
  minutes: number;
  /** Bounding box for map preview [west, south, east, north]. */
  bbox: [number, number, number, number];
}

export interface CampaignPlan {
  days: CampaignDay[];
  remainingMeters: number;
  unplannable: string[];
  fullPlan: RoutePlan;
}

export interface Campaign {
  campaignId: string;
  name: string;
  createdAt: string;
  graphId: string;
  contentHash: string;
  zone: ZoneDefinition;
  policy: BoundaryPolicy;
  home: { requested: LonLat; edgeId: string; offsetMeters: number };
  settings: CampaignSettings;
  /** Confirmed coverage (only what the user confirmed), per directed edge. */
  confirmed: [string, Interval[]][];
  history: { date: string; sessionId: string | null; confirmedMeters: number; requirementIds: string[] }[];
}

const stepLen = (s: PlanStep) => s.toOffsetMeters - s.fromOffsetMeters;

function endPoint(gi: GraphIndex, s: PlanStep): LonLat {
  const i = gi.idx.get(s.edgeId)!;
  return pointAtOffset(gi.edges[i].geometry, gi.cum[i], s.toOffsetMeters);
}

/** Legal path from home to the start of `target` (any eligible road; may leave the zone). */
function approachSteps(gi: GraphIndex, homeEdge: number, homeOffset: number, target: PlanStep): PlanStep[] {
  const ti = gi.idx.get(target.edgeId)!;
  if (ti === homeEdge && target.fromOffsetMeters >= homeOffset) {
    return target.fromOffsetMeters - homeOffset > 0.5 ? [mk(gi, homeEdge, homeOffset, target.fromOffsetMeters)] : [];
  }
  const allow = (i: number) => gi.edges[i].eligibility === 'eligible';
  const r = legalShortestPath(gi, [homeEdge], (e) => e === ti, allow);
  if (!r.path) return [];
  const out = [mk(gi, homeEdge, homeOffset, gi.length(homeEdge))];
  r.path.forEach((p, k) => {
    const last = k === r.path!.length - 1;
    out.push({ ...mk(gi, p.edge, 0, last ? target.fromOffsetMeters : gi.length(p.edge)), turnaroundAtStart: p.t.uturn ? p.t.turnaround : null });
  });
  return out.filter((s) => stepLen(s) > 0.01 || s.edgeId === target.edgeId);
}

function mk(gi: GraphIndex, edge: number, from: number, to: number): PlanStep {
  return { edgeId: gi.edges[edge].edgeId, fromOffsetMeters: from, toOffsetMeters: to, kind: 'approach', newlyCoveredRequirementIds: [], outsideMeters: 0, turnaroundAtStart: null };
}

/** Cut a whole-area plan into daily chunks within the budget. */
export function splitIntoDays(gi: GraphIndex, plan: RoutePlan, home: { edgeId: string; offsetMeters: number }, settings: CampaignSettings): CampaignDay[] {
  const budget = settings.hoursPerDay * settings.speedKmh * 1000;
  const homeEdge = gi.idx.get(home.edgeId)!;
  const homePt = pointAtOffset(gi.edges[homeEdge].geometry, gi.cum[homeEdge], home.offsetMeters);
  const days: CampaignDay[] = [];
  let i = 0;
  // Leading steps that cover nothing (none expected: plan starts at home) are part of day 1.
  while (i < plan.steps.length) {
    const approach = days.length === 0 ? [] : approachSteps(gi, homeEdge, home.offsetMeters, plan.steps[i]);
    const approachMeters = approach.reduce((a, s) => a + stepLen(s), 0);
    const steps: PlanStep[] = [];
    let cover = 0;
    let j = i;
    for (; j < plan.steps.length; j++) {
      const s = plan.steps[j];
      const ret = haversine(endPoint(gi, s), homePt) * 1.4;
      if (steps.length > 0 && approachMeters + cover + stepLen(s) + ret > budget) break;
      steps.push(s);
      cover += stepLen(s);
    }
    // Do not end a day on steps that cover nothing new (they belong to the next day's drive).
    while (steps.length > 1 && steps[steps.length - 1].newlyCoveredRequirementIds.length === 0) {
      steps.pop();
      j--;
      cover -= stepLen(plan.steps[j]);
    }
    const last = steps[steps.length - 1];
    const returnMeters = haversine(endPoint(gi, last), homePt) * 1.4;
    const all = [...approach, ...steps];
    let w = Infinity, so = Infinity, e = -Infinity, n = -Infinity;
    for (const s of steps) {
      for (const p of gi.edges[gi.idx.get(s.edgeId)!].geometry) {
        w = Math.min(w, p[0]); e = Math.max(e, p[0]); so = Math.min(so, p[1]); n = Math.max(n, p[1]);
      }
    }
    const total = all.reduce((a, s) => a + stepLen(s), 0) + returnMeters;
    days.push({
      day: days.length + 1,
      approach,
      steps,
      targetIds: steps.flatMap((s) => s.newlyCoveredRequirementIds),
      coverMeters: cover,
      approachMeters,
      returnMeters,
      totalMeters: total,
      minutes: Math.round((total / 1000 / settings.speedKmh) * 60),
      bbox: [w, so, e, n],
    });
    i = j;
  }
  // Drop trailing days that cover nothing (pure transit at the end of the plan).
  while (days.length && days[days.length - 1].targetIds.length === 0) days.pop();
  return days;
}

/** Plan all remaining work from home and split it into days. */
export function planCampaign(
  gi: GraphIndex,
  zoneInfo: EdgeZoneInfo[],
  requirements: RequiredInterval[],
  c: Campaign,
  meta: { planId: string; createdAt: string },
  timeBudgetMs = 30_000,
): CampaignPlan {
  const confirmed = new Map(c.confirmed);
  const fullPlan = planCoverage({
    gi, zoneInfo, requirements, observed: confirmed, excluded: new Set(), policy: c.policy,
    start: { edgeId: c.home.edgeId, offsetMeters: c.home.offsetMeters, source: 'selected_start' },
    timeBudgetMs,
    meta: { planId: meta.planId, graphHash: c.contentHash, zoneVersion: 1, progressRevision: c.history.length, planningRevision: c.history.length + 1, createdAt: meta.createdAt },
  });
  const days = splitIntoDays(gi, fullPlan, c.home, c.settings);
  let remaining = 0;
  for (const r of requirements) {
    if (r.status === 'needsReview') continue;
    if (!requirementCovered(confirmed.get(r.edgeId) ?? [], r)) remaining += r.lengthMeters;
  }
  return { days, remainingMeters: remaining, unplannable: fullPlan.unreachable.map((u) => u.requirementId), fullPlan };
}

/** Requirements newly covered by `driven` (union from a day's session) beyond what is already confirmed. */
export function newlyDriven(requirements: RequiredInterval[], confirmed: Map<string, Interval[]>, driven: Map<string, Interval[]>): RequiredInterval[] {
  const out: RequiredInterval[] = [];
  for (const r of requirements) {
    const before = confirmed.get(r.edgeId) ?? [];
    if (requirementCovered(before, r)) continue;
    const merged = [...before, ...(driven.get(r.edgeId) ?? [])].reduce<Interval[]>((u, iv) => addInterval(u, iv), []);
    if (requirementCovered(merged, r)) out.push(r);
  }
  return out;
}

/** Apply a user-confirmed day: add the driven intervals of the confirmed requirements. */
export function confirmDay(c: Campaign, confirmedReqs: RequiredInterval[], driven: Map<string, Interval[]>, sessionId: string | null, date: string): Campaign {
  const conf = new Map(c.confirmed.map(([k, v]) => [k, v.slice()]));
  let meters = 0;
  for (const r of confirmedReqs) {
    let u = conf.get(r.edgeId) ?? [];
    for (const [a, b] of driven.get(r.edgeId) ?? []) {
      const lo = Math.max(a, r.startOffsetMeters), hi = Math.min(b, r.endOffsetMeters);
      if (hi > lo) u = addInterval(u, [lo, hi]);
    }
    conf.set(r.edgeId, u);
    meters += r.lengthMeters;
  }
  return {
    ...c,
    confirmed: [...conf.entries()],
    history: [...c.history, { date, sessionId, confirmedMeters: meters, requirementIds: confirmedReqs.map((r) => r.requirementId) }],
  };
}

/** Undo the last confirmed day (in case of a mistake). */
export function undoLastDay(c: Campaign, requirements: RequiredInterval[]): Campaign {
  if (!c.history.length) return c;
  const removed = new Set(c.history[c.history.length - 1].requirementIds);
  const reqById = new Map(requirements.map((r) => [r.requirementId, r]));
  const conf = new Map(c.confirmed.map(([k, v]) => [k, v.slice()]));
  for (const id of removed) {
    const r = reqById.get(id);
    if (!r) continue;
    const u = conf.get(r.edgeId) ?? [];
    const cut: Interval[] = [];
    for (const [a, b] of u) {
      if (b <= r.startOffsetMeters || a >= r.endOffsetMeters) cut.push([a, b]);
      else {
        if (a < r.startOffsetMeters) cut.push([a, r.startOffsetMeters]);
        if (b > r.endOffsetMeters) cut.push([r.endOffsetMeters, b]);
      }
    }
    conf.set(r.edgeId, cut);
  }
  return { ...c, confirmed: [...conf.entries()], history: c.history.slice(0, -1) };
}
