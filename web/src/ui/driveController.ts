// Driving session controller (framework independent). Owns matcher state, the
// persisted event log, guidance, deviation handling and revision-safe replanning.

import type { GraphIndex } from '../core/graph';
import { finalizeMatcher, initialMatcherState, processObservation, type GpsQuality, type MatcherState } from '../core/matcher';
import { initialGuidance, updateGuidance, type GuidanceState } from '../core/guidance';
import { decidePlan } from '../core/revision';
import { applyEvents, activeExclusionIds, computeMetrics, emptyProgress, type CoverageMetrics, type DerivedProgress } from '../core/session';
import { buildManeuvers, stepStartDistances, type Maneuver } from '../core/maneuvers';
import { edgeTraversable } from '../core/requirements';
import type { EdgeZoneInfo } from '../core/requirements';
import { SCHEMA_VERSION, type GraphSnapshot, type LocationObservation, type LonLat, type RequirementStatus, type RoutePlan, type SessionEvent, type SessionEventType } from '../core/types';
import type { Engine } from '../worker/engine';
import type { SessionHeader, Store } from '../storage/db';
import { BrowserGps, WakeLockKeeper, type GpsSourceState } from './gps';

export interface DriveSnapshot {
  status: SessionHeader['status'];
  gpsSource: GpsSourceState;
  quality: GpsQuality | 'paused';
  lastFix: LocationObservation | null;
  matched: { edgeId: string; offset: number; point: LonLat; committed: boolean } | null;
  plan: RoutePlan | null;
  planValid: boolean;
  cursor: number;
  nextManeuver: (Maneuver & { distanceMeters: number }) | null;
  metrics: CoverageMetrics;
  statuses: Map<string, RequirementStatus>;
  progress: DerivedProgress;
  replanning: boolean;
  notice: 'none' | 'off_route' | 'outside_policy' | 'finished' | 'hidden_gap' | 'storage_error' | 'plan_failed' | 'outside_needs_approval' | 'recovered';
  /** A replanned route that adds outside travel beyond what the user approved. */
  pendingOutsidePlan: { outsideMeters: number; approvedMeters: number } | null;
  unsavedEvents: number;
  wakeLock: 'held' | 'released' | 'unsupported' | 'refused' | 'idle';
  storageError: string | null;
  trail: LonLat[];
}

const REPLAN_DEBOUNCE_MS = 8000;

export class DriveController {
  private matcher: MatcherState = initialMatcherState();
  private guidance: GuidanceState | null = null;
  private progress: DerivedProgress;
  private header: SessionHeader;
  private gps: BrowserGps;
  private wake: WakeLockKeeper;
  private listeners = new Set<(s: DriveSnapshot) => void>();
  private planValid = true;
  private maneuvers: Maneuver[] = [];
  private stepDist: number[] = [];
  private latestRequested = 0;
  private lastReplanAt = 0;
  private writeChain: Promise<unknown> = Promise.resolve();
  private snap: DriveSnapshot;
  private hiddenAt: number | null = null;
  /** Events assigned a sequence number but not yet stored (retried in order). */
  private unsaved: SessionEvent[] = [];
  private unsavedPatch: Partial<SessionHeader> = {};
  private timer: ReturnType<typeof setInterval>;
  private pendingPlan: RoutePlan | null = null;
  private approvedOutsideMeters: number;

  constructor(
    private readonly store: Store,
    private readonly engine: Engine,
    private readonly graph: GraphSnapshot,
    private readonly gi: GraphIndex,
    private readonly zoneInfo: EdgeZoneInfo[],
    header: SessionHeader,
    events: SessionEvent[],
  ) {
    this.header = header;
    this.progress = applyEvents(emptyProgress(), events, this.ctx());
    const created = events.find((e) => e.type === 'session_created');
    this.approvedOutsideMeters = Number(created?.payload.approvedOutsideMeters ?? 0);
    // Only a fresh session follows its preview plan. A resumed/recovered session never
    // continues an old route from an obsolete cursor: it replans from the real position.
    const fresh = this.progress.status === 'ready';
    if (this.progress.plan && fresh) this.adoptPlan(this.progress.plan, true);
    this.latestRequested = this.progress.planningRevision;
    const { metrics, statuses } = computeMetrics(header.requirements, this.progress);
    this.snap = {
      status: this.progress.status === 'ready' ? header.status : this.progress.status,
      gpsSource: 'idle', quality: 'acquiring', lastFix: null, matched: null, plan: fresh ? this.progress.plan : null, planValid: true,
      cursor: 0, nextManeuver: null, metrics, statuses, progress: this.progress, replanning: false, notice: 'none',
      wakeLock: 'idle', storageError: null, trail: [], pendingOutsidePlan: null, unsavedEvents: 0,
    };
    this.timer = setInterval(() => this.tick(), 1000);
    this.gps = new BrowserGps({ onFix: (o) => this.onFix(o), onState: (s) => this.update({ gpsSource: s }) });
    this.wake = new WakeLockKeeper((w) => this.update({ wakeLock: w }));
    document.addEventListener('visibilitychange', this.onVisibility);
  }

  private ctx() {
    return { sessionId: this.header.sessionId, graphHash: this.graph.contentHash, edgeLengths: new Map(this.graph.directedEdges.map((e) => [e.edgeId, e.lengthMeters])) };
  }

  subscribe(fn: (s: DriveSnapshot) => void) {
    this.listeners.add(fn);
    fn(this.snap);
    return () => this.listeners.delete(fn);
  }

  private update(p: Partial<DriveSnapshot>) {
    this.snap = { ...this.snap, ...p };
    for (const l of this.listeners) l(this.snap);
  }

  private recompute() {
    const { metrics, statuses } = computeMetrics(this.header.requirements, this.progress);
    this.update({ metrics, statuses, progress: this.progress, status: this.progress.status === 'ready' ? this.header.status : this.progress.status });
  }

  /**
   * After a reload/kill the log may end in 'active'. Record the interruption as a pause so
   * the UI offers an explicit Resume (GPS restarts only from a user action).
   */
  async recoverIfInterrupted() {
    if (this.progress.status !== 'active') return;
    await this.persist([{ type: 'paused', payload: { reason: 'interrupted' } }], { status: 'paused' });
    this.update({ notice: 'recovered', quality: 'paused' });
    this.recompute();
  }

  /** Persist events in order. Failed writes stay queued and are retried in order. */
  private persist(types: { type: SessionEventType; payload: Record<string, unknown>; timestamp?: number }[], patch: Partial<SessionHeader> = {}) {
    const events: SessionEvent[] = types.map((t) => ({
      eventId: `${this.header.sessionId}:${crypto.randomUUID()}`,
      seq: 0,
      sessionId: this.header.sessionId,
      type: t.type,
      timestamp: t.timestamp ?? Date.now(),
      schemaVersion: SCHEMA_VERSION,
      payload: t.payload,
    }));
    // Assign sequence numbers synchronously so in-memory state and storage agree.
    let seq = this.progress.lastSeq;
    for (const e of events) e.seq = ++seq;
    this.progress = applyEvents(this.progress, events, this.ctx());
    this.unsaved.push(...events);
    this.unsavedPatch = { ...this.unsavedPatch, ...patch };
    return this.flush();
  }

  private flush() {
    const job = this.writeChain.then(async () => {
      if (!this.unsaved.length) return;
      const batch = this.unsaved.slice();
      const patch = this.unsavedPatch;
      try {
        this.header = await this.store.appendEvents(this.header.sessionId, batch, patch);
        this.unsaved.splice(0, batch.length);
        this.unsavedPatch = {};
        if (this.snap.notice === 'storage_error') this.update({ notice: 'none', storageError: null });
      } catch (e) {
        this.update({ notice: 'storage_error', storageError: (e as Error).message });
      }
      this.update({ unsavedEvents: this.unsaved.length });
    });
    this.writeChain = job;
    return job;
  }

  /** 1 Hz housekeeping: retry unsaved writes and drop guidance that went stale. */
  private tick() {
    if (this.unsaved.length) void this.flush();
    const f = this.snap.lastFix;
    const age = f ? Date.now() - f.timestamp : Infinity;
    if (this.snap.nextManeuver && (age > 5000 || age < -5000 || this.progress.status !== 'active')) this.update({ nextManeuver: null });
  }

  async startOrResume() {
    const resuming = this.progress.status === 'paused';
    const type: SessionEventType = resuming ? 'resumed' : 'start_confirmed';
    await this.persist([{ type, payload: {} }], { status: 'active' });
    if (resuming) {
      // Replan from the reacquired real position on the first reliable committed fix.
      this.guidance = null;
      this.update({ plan: null, nextManeuver: null, cursor: 0, notice: 'none' });
    }
    this.matcher = initialMatcherState();
    this.gps.start();
    void this.wake.acquire();
    this.recompute();
  }

  async pause() {
    this.gps.stop();
    const fin = finalizeMatcher(this.gi, this.matcher);
    this.matcher = fin.state;
    await this.persist([...fin.traversals.map((t) => ({ type: 'traversal_confirmed' as const, payload: { traversal: t } })), { type: 'paused', payload: {} }], { status: 'paused' });
    await this.wake.release();
    this.update({ quality: 'paused' });
    this.recompute();
  }

  async end() {
    this.gps.stop();
    const fin = finalizeMatcher(this.gi, this.matcher);
    await this.persist([...fin.traversals.map((t) => ({ type: 'traversal_confirmed' as const, payload: { traversal: t } })), { type: 'ended', payload: {} }], { status: 'ended' });
    await this.wake.release();
    this.recompute();
  }

  dispose() {
    clearInterval(this.timer);
    this.gps.stop();
    void this.wake.release();
    document.removeEventListener('visibilitychange', this.onVisibility);
  }

  private onVisibility = () => {
    if (document.visibilityState === 'hidden') this.hiddenAt = Date.now();
    else if (this.hiddenAt !== null) {
      const away = Date.now() - this.hiddenAt;
      this.hiddenAt = null;
      if (away > 5000 && this.progress.status === 'active') {
        const fin = finalizeMatcher(this.gi, this.matcher);
        this.matcher = fin.state;
        void this.persist([
          ...fin.traversals.map((t) => ({ type: 'traversal_confirmed' as const, payload: { traversal: t } })),
          { type: 'gap_recorded', payload: { reason: 'app_hidden', fromT: Date.now() - away, toT: Date.now() } },
        ]);
        this.update({ notice: 'hidden_gap' });
      }
    }
  };

  private onFix(o: LocationObservation) {
    if (this.progress.status !== 'active') return;
    const res = processObservation(this.gi, this.matcher, o, Date.now());
    this.matcher = res.state;
    if (res.ignored) return;
    const evs: { type: SessionEventType; payload: Record<string, unknown>; timestamp?: number }[] = [
      { type: 'observation', payload: { observation: o }, timestamp: o.timestamp },
    ];
    for (const t of res.traversals) evs.push({ type: 'traversal_confirmed', payload: { traversal: t }, timestamp: t.timestampEnd });
    if (res.gap) evs.push({ type: 'gap_recorded', payload: res.gap, timestamp: o.timestamp });
    void this.persist(evs);
    const trail = [...this.snap.trail, [o.lon, o.lat] as LonLat].slice(-600);
    const reliable = res.quality === 'reliable';
    this.update({ lastFix: o, quality: res.quality, matched: res.matched, trail });
    if (res.traversals.length) this.recompute();
    this.guide(res.matched, reliable);
  }

  private guide(matched: DriveSnapshot['matched'], reliable: boolean) {
    const plan = this.snap.plan;
    if (!plan || !this.guidance) {
      if (matched?.committed && reliable) this.maybeReplan(matched, 'no_plan');
      this.update({ nextManeuver: null });
      return;
    }
    const g = updateGuidance(this.gi, plan, this.guidance, matched, reliable);
    this.guidance = g.state;
    const keep: DriveSnapshot['notice'][] = ['outside_policy', 'storage_error', 'outside_needs_approval', 'plan_failed'];
    let notice: DriveSnapshot['notice'] = keep.includes(this.snap.notice) ? this.snap.notice : 'none';
    if (g.event === 'deviation' && matched) {
      notice = 'off_route';
      this.maybeReplan(matched, 'deviation');
    } else if (g.event === 'finished') notice = 'finished';
    else if (g.event === 'on_route' && (notice === 'outside_policy' || notice === 'plan_failed')) notice = 'none';
    const fixAge = this.snap.lastFix ? Date.now() - this.snap.lastFix.timestamp : Infinity;
    const showManeuver = reliable && this.planValid && fixAge < 5000 && fixAge > -5000 && matched !== null && this.progress.status === 'active'
      && g.event !== 'off_route_pending' && g.event !== 'deviation' && notice !== 'plan_failed' && notice !== 'outside_needs_approval';
    let nextManeuver: DriveSnapshot['nextManeuver'] = null;
    if (showManeuver && matched) {
      const cursor = g.state.cursor;
      const step = plan.steps[cursor];
      const here = this.stepDist[cursor] + Math.max(0, (step.edgeId === matched.edgeId ? matched.offset : step.fromOffsetMeters) - step.fromOffsetMeters);
      const m = this.maneuvers.find((x) => x.stepIndex > cursor && x.type !== 'depart');
      if (m) nextManeuver = { ...m, distanceMeters: Math.max(0, m.distanceFromStartMeters - here) };
    }
    this.update({ cursor: g.state.cursor, nextManeuver, notice });
  }

  private maybeReplan(matched: NonNullable<DriveSnapshot['matched']>, why: 'deviation' | 'no_plan' | 'blocked') {
    const now = Date.now();
    if (why !== 'blocked' && (this.snap.replanning || now - this.lastReplanAt < REPLAN_DEBOUNCE_MS)) return;
    const mi = this.gi.idx.get(matched.edgeId);
    if (mi === undefined) return;
    const blocked = new Set<number>();
    const excl = activeExclusionIds(this.progress);
    for (const r of this.header.requirements) if (excl.has(r.requirementId)) blocked.add(this.gi.idx.get(r.edgeId)!);
    blocked.delete(mi); // the vehicle can still leave along its current edge
    if (!edgeTraversable(this.gi, this.zoneInfo, this.header.boundaryPolicy, blocked, mi)) {
      this.update({ notice: 'outside_policy', nextManeuver: null });
      return;
    }
    this.lastReplanAt = now;
    const revision = ++this.latestRequested;
    this.update({ replanning: true });
    this.engine
      .call<'plan'>({
        type: 'plan',
        requirements: this.header.requirements,
        zone: this.header.zone.definition,
        observed: [...this.progress.union.entries()],
        excluded: [...excl],
        policy: this.header.boundaryPolicy,
        start: { edgeId: matched.edgeId, offsetMeters: matched.offset, source: 'gps_position' },
        startPrefix: [matched.edgeId],
        returnToStart: this.header.returnToStart,
        timeBudgetMs: 15_000,
        meta: {
          planId: crypto.randomUUID(), graphHash: this.graph.contentHash, zoneVersion: this.header.zone.zoneVersion,
          progressRevision: this.progress.progressRevision, planningRevision: revision, createdAt: new Date().toISOString(),
        },
      })
      .then(async (r) => {
        const d = decidePlan(
          { graphHash: this.graph.contentHash, zoneVersion: this.header.zone.zoneVersion, latestRequestedPlanningRevision: this.latestRequested, acceptedPlanningRevision: this.progress.planningRevision, progressRevision: this.progress.progressRevision },
          { graphHash: r.plan.graphHash, zoneVersion: r.plan.zoneVersion, planningRevision: r.plan.planningRevision, basedOnProgressRevision: r.plan.basedOnProgressRevision, status: r.plan.status },
        );
        if (!d.accept) {
          if (d.reason === 'failed') this.update({ notice: 'plan_failed' });
          return;
        }
        if (!r.validation.ok) {
          this.update({ notice: 'plan_failed' });
          return;
        }
        if (r.plan.totals.outsideMeters > this.approvedOutsideMeters + 2) {
          // Never adopt more outside travel than the user approved without asking again.
          this.pendingPlan = r.plan;
          this.update({ notice: 'outside_needs_approval', nextManeuver: null, pendingOutsidePlan: { outsideMeters: r.plan.totals.outsideMeters, approvedMeters: this.approvedOutsideMeters } });
          return;
        }
        await this.persist([{ type: 'plan_accepted', payload: { plan: r.plan, reason: why } }]);
        this.adoptPlan(r.plan, r.validation.ok);
        this.recompute();
      })
      .catch(() => undefined)
      .finally(() => {
        if (revision === this.latestRequested) this.update({ replanning: false });
      });
  }

  private adoptPlan(plan: RoutePlan, valid: boolean) {
    this.planValid = valid;
    this.maneuvers = buildManeuvers(this.gi, plan);
    this.stepDist = stepStartDistances(plan);
    this.guidance = initialGuidance(plan);
    this.update({ plan, planValid: valid, cursor: 0, notice: 'none', pendingOutsidePlan: null });
  }

  async approvePendingOutside() {
    const plan = this.pendingPlan;
    if (!plan) return;
    this.pendingPlan = null;
    this.approvedOutsideMeters = plan.totals.outsideMeters;
    await this.persist([{ type: 'plan_accepted', payload: { plan, reason: 'outside_approved', approvedOutsideMeters: plan.totals.outsideMeters } }]);
    this.adoptPlan(plan, true);
    this.update({ pendingOutsidePlan: null });
    this.recompute();
  }

  /** Mark the physical road under the current matched position blocked (both directions). */
  async blockCurrentRoad() {
    const m = this.snap.matched;
    if (!m) return false;
    const mi = this.gi.idx.get(m.edgeId);
    if (mi === undefined) return false;
    const phys = this.gi.edges[mi].physicalSegmentId;
    const ids = this.header.requirements.filter((r) => this.gi.edges[this.gi.idx.get(r.edgeId)!].physicalSegmentId === phys).map((r) => r.requirementId);
    if (!ids.length) return false;
    await this.persist([{ type: 'requirement_excluded', payload: { requirementIds: ids, reason: 'blocked', note: null } }]);
    this.recompute();
    this.maybeReplan(m, 'blocked');
    return true;
  }

  get sessionHeader() {
    return this.header;
  }
}
