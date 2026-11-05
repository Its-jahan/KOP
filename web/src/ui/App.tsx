import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { GraphIndex } from '../core/graph';
import { computeRequirements, edgeTraversable, type EdgeZoneInfo } from '../core/requirements';
import { snapStart } from '../core/snap';
import { circleApproximation, validateZone, type ZoneValidation } from '../core/zone';
import { bearingAtOffset, bboxOf, expandBbox } from '../core/geo';
import { verifyGraphHash } from '../core/hash';
import { mapFailure, type StateOutcome } from '../core/states';
import { applyEvents, computeMetrics, emptyProgress, sessionDocument } from '../core/session';
import { buildExport, validateImport } from '../core/portable';
import { SCHEMA_VERSION, type BoundaryPolicy, type GraphSnapshot, type LonLat, type RequiredInterval, type RoutePlan, type SessionEvent, type ZoneDefinition } from '../core/types';
import { fetchRoadGraph, GraphFetchError } from '../providers/overpass';
import { searchPlace, type PlaceResult } from '../providers/nominatim';
import { Store, storageEstimate, requestPersistence, type SessionHeader } from '../storage/db';
import { Engine } from '../worker/engine';
import { STRINGS, fmtMeters, fmtNum, type Lang, type Strings } from '../i18n/strings';
import { MapView, circlePolygon } from './mapView';
import { coveredFeatures, requirementFeatures, routeFeatures } from './geojson';
import { DriveController, type DriveSnapshot } from './driveController';
import { locationHub } from './locationHub';
import type { LaneHint, NavInstruction } from '../core/navigator';
import type { GpsSourceState } from './gps';
import type { LocationObservation } from '../core/types';

export const APP_VERSION = '0.1.0';
const MAX_EDGES = 60_000; // measured/tested ceiling for this build (see README)
const GRAPH_BUFFER_M = 600;

type Tab = 'prepare' | 'drive' | 'sessions' | 'settings';
type ZoneMode = 'polygon' | 'circle';
type GraphState =
  | { kind: 'none' }
  | { kind: 'fetching'; endpoint: string; attempt: number }
  | { kind: 'available'; graph: GraphSnapshot; endpoint: string; ms: number }
  | { kind: 'failed'; outcome: StateOutcome; message: string };

interface PreparedZone {
  graph: GraphSnapshot;
  gi: GraphIndex;
  zoneInfo: EdgeZoneInfo[];
  requirements: RequiredInterval[];
  mainIds: Set<string>;
}

interface StartChoice { requested: LonLat; edgeId: string; offset: number; position: LonLat; distance: number; confirmed: boolean }
interface PlanResult { plan: RoutePlan; validation: { ok: boolean; errors: string[] }; ms: number; inputKey: string }

function useStrings(lang: Lang) {
  return STRINGS[lang];
}

export function App() {
  const [lang, setLang] = useState<Lang>(() => (localStorageGet('lang') as Lang) || 'fa');
  const t = useStrings(lang);
  const [tab, setTab] = useState<Tab>('prepare');
  const mapEl = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapView | null>(null);
  const engineRef = useRef<Engine | null>(null);
  const [store, setStore] = useState<Store | null>(null);
  const [storeError, setStoreError] = useState<string | null>(null);

  // preparation state
  const [zoneMode, setZoneMode] = useState<ZoneMode>('polygon');
  const [poly, setPoly] = useState<LonLat[]>([]);
  const [circle, setCircle] = useState<{ center: LonLat | null; radius: number }>({ center: null, radius: 400 });
  const [policy, setPolicy] = useState<BoundaryPolicy>('STRICT_INSIDE');
  const [returnToStart, setReturnToStart] = useState(false);
  const [graphState, setGraphState] = useState<GraphState>({ kind: 'none' });
  const [prepared, setPrepared] = useState<PreparedZone | null>(null);
  const [pickStart, setPickStart] = useState(false);
  const [start, setStart] = useState<StartChoice | null>(null);
  const [startMsg, setStartMsg] = useState<string | null>(null);
  const [plan, setPlan] = useState<PlanResult | null>(null);
  const [planning, setPlanning] = useState(false);
  const [approveOutside, setApproveOutside] = useState(false);
  const [zoneVersion, setZoneVersion] = useState(1);
  const abortRef = useRef<AbortController | null>(null);
  const driveEngineRef = useRef<Engine | null>(null);
  const releaseLockRef = useRef<(() => void) | null>(null);

  // drive state
  const [drive, setDrive] = useState<DriveController | null>(null);
  const [driveSnap, setDriveSnap] = useState<DriveSnapshot | null>(null);
  const [driveGi, setDriveGi] = useState<GraphIndex | null>(null);
  const [sessions, setSessions] = useState<SessionHeader[]>([]);
  const [resumable, setResumable] = useState<SessionHeader | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  // Always-on "where am I" (every screen), independent of coverage crediting.
  const [myFix, setMyFix] = useState<LocationObservation | null>(null);
  const [myGps, setMyGps] = useState<GpsSourceState>('idle');
  const [follow, setFollow] = useState(true);
  const [menuOpen, setMenuOpen] = useState(false);
  const [legendOpen, setLegendOpen] = useState(false);
  const [sheetOpen, setSheetOpen] = useState(false);
  const centeredOnceRef = useRef(false);

  useEffect(() => {
    document.documentElement.lang = lang;
    document.documentElement.dir = lang === 'fa' ? 'rtl' : 'ltr';
    localStorageSet('lang', lang);
  }, [lang]);

  useEffect(() => {
    if (!mapEl.current || mapRef.current) return;
    mapRef.current = new MapView(mapEl.current);
    mapRef.current.onUserPan(() => setFollow(false));
    const offFix = locationHub.onFix(setMyFix);
    const offState = locationHub.onState(setMyGps);
    locationHub.start();
    // Diagnostics/E2E hook: exposes only the map widget (no data, no credentials).
    (window as unknown as { __coverageMap?: MapView }).__coverageMap = mapRef.current;
    engineRef.current = new Engine();
    Store.open()
      .then(async (s) => {
        setStore(s);
        void requestPersistence();
        const all = await s.listSessions();
        setSessions(all);
        setResumable(all.find((h) => h.status === 'active' || h.status === 'paused') ?? null);
      })
      .catch((e) => setStoreError(String(e.message ?? e)));
    return () => {
      offFix();
      offState();
      engineRef.current?.dispose();
    };
  }, []);

  // ---------- zone ----------
  const zoneDef: ZoneDefinition | null = useMemo(() => {
    if (zoneMode === 'polygon') return poly.length >= 3 ? { type: 'polygon', coordinates: poly } : null;
    if (!circle.center) return null;
    return { type: 'circle', center: circle.center, radiusMeters: circle.radius, approximation: circleApproximation(circle.center, circle.radius) };
  }, [zoneMode, poly, circle]);
  const zoneVal: ZoneValidation | null = useMemo(() => (zoneDef ? validateZone(zoneDef) : null), [zoneDef]);

  const invalidatePrepared = useCallback(() => {
    setPrepared(null);
    setPlan(null);
    setStart(null);
    setApproveOutside(false);
    setZoneVersion((v) => v + 1);
  }, []);

  useEffect(() => {
    const mv = mapRef.current;
    if (!mv || tab !== 'prepare') return;
    let ring: LonLat[] = [];
    if (zoneMode === 'polygon') ring = poly.length ? [...poly, poly[0]] : [];
    else if (circle.center) ring = circlePolygon(circle.center, circle.radius);
    void mv.setData('zone', {
      type: 'FeatureCollection',
      features: ring.length >= 4 ? [{ type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [ring] } }] : ring.length >= 2 ? [{ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: ring.slice(0, -1) } }] : [],
    });
    if (zoneMode === 'polygon') {
      mv.setCenterHandle(null, () => undefined);
      mv.setVertexHandles(poly, (i, p) => {
        setPoly((old) => old.map((q, k) => (k === i ? p : q)));
        invalidatePrepared();
      });
    } else {
      mv.setVertexHandles([], () => undefined);
      mv.setCenterHandle(circle.center, (p) => {
        setCircle((c) => ({ ...c, center: p }));
        invalidatePrepared();
      });
    }
  }, [poly, circle, zoneMode, tab, invalidatePrepared]);

  // map click routing
  useEffect(() => {
    const mv = mapRef.current;
    if (!mv) return;
    if (tab !== 'prepare') return mv.onClick(null);
    mv.onClick((p) => {
      if (planning) return;
      if (pickStart) {
        chooseStart(p);
        return;
      }
      if (prepared) return; // zone locked after loading roads; use Clear to edit
      if (zoneMode === 'polygon') setPoly((old) => [...old, p]);
      else setCircle((c) => ({ ...c, center: p }));
      invalidatePrepared();
    });
  });

  // ---------- road graph ----------
  async function loadRoads() {
    if (!zoneDef || !zoneVal?.ok || !engineRef.current) return;
    abortRef.current?.abort();
    const ctl = new AbortController();
    abortRef.current = ctl;
    setPrepared(null);
    setPlan(null);
    setStart(null);
    setGraphState({ kind: 'fetching', endpoint: '', attempt: 1 });
    const ring = zoneDef.type === 'polygon' ? zoneDef.coordinates : zoneDef.approximation!.coordinates;
    const bbox = expandBbox(bboxOf(ring), GRAPH_BUFFER_M);
    try {
      const t0 = performance.now();
      const { data, endpoint } = await fetchRoadGraph(bbox, ctl.signal, (endpoint, attempt) => setGraphState({ kind: 'fetching', endpoint, attempt }));
      const ways = data.elements.filter((e) => e.type === 'way').length;
      if (ways * 4 > MAX_EDGES) throw new GraphFetchError({ kind: 'graph_too_large', edges: ways * 4, limit: MAX_EDGES }, 'too large');
      const r = await engineRef.current.call<'normalize'>({ type: 'normalize', overpass: data, fetchedAt: new Date().toISOString() });
      engineRef.current.setGraph(r.graph);
      if (r.graph.directedEdges.length > MAX_EDGES) throw new GraphFetchError({ kind: 'graph_too_large', edges: r.graph.directedEdges.length, limit: MAX_EDGES }, 'too large');
      const gi = new GraphIndex(r.graph);
      const req = computeRequirements(gi, zoneDef);
      const rq = await engineRef.current.call<'requirements'>({ type: 'requirements', zone: zoneDef, policy });
      setPrepared({ graph: r.graph, gi, zoneInfo: req.zoneInfo, requirements: req.requirements, mainIds: new Set(rq.mainComponentEdgeIds) });
      setGraphState({ kind: 'available', graph: r.graph, endpoint, ms: performance.now() - t0 });
      const { statuses } = computeMetrics(req.requirements, emptyProgress());
      void mapRef.current?.setData('reqs', requirementFeatures(gi, req.requirements, statuses));
      void mapRef.current?.setData('covered', { type: 'FeatureCollection', features: [] });
      void mapRef.current?.setData('route', { type: 'FeatureCollection', features: [] });
      void store?.putGraph(r.graph).catch((e) => setToast(String(e.message)));
    } catch (e) {
      if (ctl.signal.aborted) return;
      const failure = e instanceof GraphFetchError ? e.failure : { kind: 'graph_network_error' as const };
      setGraphState({ kind: 'failed', outcome: mapFailure(failure), message: (e as Error).message });
    }
  }

  // re-request main component when policy changes
  useEffect(() => {
    if (!prepared || !zoneDef || !engineRef.current) return;
    setPlan(null);
    setApproveOutside(false);
    void engineRef.current.call<'requirements'>({ type: 'requirements', zone: zoneDef, policy }).then((rq) =>
      setPrepared((p) => (p ? { ...p, mainIds: new Set(rq.mainComponentEdgeIds) } : p)),
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [policy]);

  // ---------- start ----------
  function chooseStart(p: LonLat) {
    if (!prepared) return;
    const { gi, zoneInfo, mainIds } = prepared;
    const allow = (i: number) => edgeTraversable(gi, zoneInfo, policy, new Set(), i);
    const s = snapStart(gi, p, allow, 150, (i) => mainIds.has(gi.edges[i].edgeId));
    setPickStart(false);
    setPlan(null);
    if (!s) {
      setStart(null);
      setStartMsg(t.noLegalRoad);
      return;
    }
    setStartMsg(null);
    setStart({ requested: p, edgeId: s.edgeId, offset: s.offsetMeters, position: s.position, distance: s.distanceMeters, confirmed: false });
  }

  useEffect(() => {
    const mv = mapRef.current;
    if (!mv) return;
    if (tab !== 'prepare' || !start || !prepared) return mv.setStart(null, null);
    const i = prepared.gi.idx.get(start.edgeId)!;
    mv.setStart(start.position, bearingAtOffset(prepared.gi.edges[i].geometry, prepared.gi.cum[i], start.offset));
  }, [start, prepared, tab]);

  function flipStart() {
    if (!start || !prepared) return;
    const i = prepared.gi.idx.get(start.edgeId)!;
    const r = prepared.gi.reciprocal[i];
    if (r < 0) return;
    const allow = edgeTraversable(prepared.gi, prepared.zoneInfo, policy, new Set(), r);
    if (!allow) return;
    setPlan(null);
    setStart({ ...start, edgeId: prepared.gi.edges[r].edgeId, offset: Math.max(0, prepared.gi.length(r) - start.offset), confirmed: false });
  }

  function useMyLocation() {
    if (!navigator.geolocation) return setStartMsg(t.locationOff);
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        mapRef.current?.flyTo([pos.coords.longitude, pos.coords.latitude]);
        chooseStart([pos.coords.longitude, pos.coords.latitude]);
      },
      (err) => setStartMsg(err.code === err.PERMISSION_DENIED ? t.permissionDenied : t.locationOff),
      { enableHighAccuracy: true, timeout: 20000, maximumAge: 0 },
    );
  }

  // ---------- plan ----------
  const planInputKey = JSON.stringify([prepared?.graph.contentHash, zoneVersion, zoneDef, policy, returnToStart, start?.edgeId, start?.offset, start?.confirmed]);
  const planKeyRef = useRef(planInputKey);
  planKeyRef.current = planInputKey;

  async function makePlan() {
    if (!prepared || !start?.confirmed || !zoneDef || !engineRef.current) return;
    const requestKey = planInputKey;
    setPlanning(true);
    setPlan(null);
    setApproveOutside(false);
    try {
      const r = await engineRef.current.call<'plan'>({
        type: 'plan', requirements: prepared.requirements, zone: zoneDef, observed: [], excluded: [], policy,
        start: { edgeId: start.edgeId, offsetMeters: start.offset, source: 'selected_start' }, returnToStart, timeBudgetMs: 20_000,
        meta: { planId: crypto.randomUUID(), graphHash: prepared.graph.contentHash, zoneVersion, progressRevision: 0, planningRevision: 1, createdAt: new Date().toISOString() },
      });
      // Discard results for inputs that changed while the worker was computing.
      if (planKeyRef.current !== requestKey) return;
      setPlan({ plan: r.plan, validation: r.validation, ms: r.ms, inputKey: requestKey });
      void mapRef.current?.setData('route', routeFeatures(prepared.gi, r.plan));
    } catch (e) {
      if ((e as Error).message !== 'cancelled') setToast(String((e as Error).message));
    } finally {
      setPlanning(false);
    }
  }

  async function cancelPlanning() {
    await engineRef.current?.cancelAll();
    setPlanning(false);
  }

  const startBlockers: string[] = [];
  if (!prepared) startBlockers.push(t.reasonNoGraph);
  else if (prepared.graph.dataState === 'synthetic_test_fixture') startBlockers.push(t.reasonFixture);
  else if (prepared.graph.dataState !== 'available') startBlockers.push(`${t.graphFailed}: ${prepared.graph.dataState}`);
  if (planning) startBlockers.push(t.planning);
  if (!start?.confirmed) startBlockers.push(t.reasonNoStart);
  if (!plan) startBlockers.push(t.reasonNoPlan);
  else {
    if (!plan.validation.ok || plan.plan.status === 'failed') startBlockers.push(t.reasonPlanInvalid);
    if (plan.inputKey !== planInputKey) startBlockers.push(t.reasonNoPlan);
    if (plan.plan.totals.outsideMeters > 2 && !approveOutside) startBlockers.push(t.reasonOutside);
  }
  if (!store) startBlockers.push(storeError ?? 'storage');

  const startingRef = useRef(false);
  async function beginDriving() {
    if (startingRef.current) return; // ignore repeated taps while the session is being created
    if (startBlockers.length || !prepared || !start || !plan || !zoneDef || !store || !engineRef.current) return;
    startingRef.current = true;
    try {
      await createAndOpenSession(prepared, start, plan, zoneDef, store);
    } finally {
      startingRef.current = false;
    }
  }

  async function createAndOpenSession(
    prepared: PreparedZone,
    start: StartChoice,
    plan: PlanResult,
    zoneDef: ZoneDefinition,
    store: Store,
  ) {
    const sessionId = crypto.randomUUID();
    const header: SessionHeader = {
      schemaVersion: SCHEMA_VERSION, sessionId, graphId: prepared.graph.graphId, contentHash: prepared.graph.contentHash,
      zone: { zoneId: crypto.randomUUID(), zoneVersion, definition: zoneDef }, vehicleProfile: 'passenger_car', boundaryPolicy: policy, returnToStart,
      startSelection: { requested: start.requested, snapped: { edgeId: start.edgeId, offsetMeters: start.offset, position: start.position, distanceMeters: start.distance }, confirmed: true },
      createdAt: new Date().toISOString(), updatedAt: Date.now(), status: 'ready', lastSeq: 2, requirements: prepared.requirements,
      label: new Date().toLocaleString(lang === 'fa' ? 'fa-IR' : 'en-GB'),
    };
    const now = Date.now();
    const events: SessionEvent[] = [
      { eventId: `${sessionId}:1`, seq: 1, sessionId, type: 'session_created', timestamp: now, schemaVersion: SCHEMA_VERSION, payload: { appVersion: APP_VERSION, approvedOutsideMeters: approveOutside ? plan.plan.totals.outsideMeters : 0 } },
      { eventId: `${sessionId}:2`, seq: 2, sessionId, type: 'plan_accepted', timestamp: now, schemaVersion: SCHEMA_VERSION, payload: { plan: plan.plan, reason: 'preview' } },
    ];
    try {
      await store.putGraph(prepared.graph);
      await store.createSession(header, events);
    } catch (e) {
      setToast(String((e as Error).message));
      return;
    }
    await openDrive(header, events, prepared.graph, prepared.gi, prepared.zoneInfo, true);
  }

  async function openDrive(header: SessionHeader, events: SessionEvent[], graph: GraphSnapshot, gi: GraphIndex, zoneInfo: EdgeZoneInfo[], autoStart: boolean) {
    if (!store) return;
    drive?.dispose();
    releaseLockRef.current?.();
    releaseLockRef.current = null;
    // One tab per session: concurrent writers would break the event sequence.
    if (navigator.locks) {
      const got = await new Promise<boolean>((resolve) => {
        void navigator.locks.request(`session:${header.sessionId}`, { ifAvailable: true }, (lock) => {
          if (!lock) {
            resolve(false);
            return undefined;
          }
          resolve(true);
          return new Promise<void>((release) => (releaseLockRef.current = release));
        });
      });
      if (!got) {
        setToast(t.sessionOpenElsewhere);
        return;
      }
    }
    // A dedicated worker per driving session: preparing another zone can never swap its graph.
    driveEngineRef.current?.dispose();
    driveEngineRef.current = new Engine();
    await driveEngineRef.current.loadGraph(graph);
    const dc = new DriveController(store, driveEngineRef.current, graph, gi, zoneInfo, header, events);
    setDrive(dc);
    setDriveGi(gi);
    dc.subscribe(setDriveSnap);
    setTab('drive');
    mapRef.current?.setVertexHandles([], () => undefined);
    mapRef.current?.setCenterHandle(null, () => undefined);
    mapRef.current?.setStart(null, null);
    const ring = header.zone.definition.type === 'polygon' ? header.zone.definition.coordinates : header.zone.definition.approximation?.coordinates ?? circlePolygon(header.zone.definition.center, header.zone.definition.radiusMeters);
    void mapRef.current?.setData('zone', { type: 'FeatureCollection', features: [{ type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [[...ring, ring[0]]] } }] });
    const bb = bboxOf(ring);
    void mapRef.current?.fitBbox([bb.west, bb.south, bb.east, bb.north]);
    if (autoStart) await dc.startOrResume();
    else await dc.recoverIfInterrupted();
  }

  async function resumeSession(h: SessionHeader) {
    if (!store) return;
    const loaded = await store.getSession(h.sessionId);
    const graph = loaded ? await store.getGraph(loaded.header.graphId) : null;
    if (!loaded || !graph) return setToast(t.importFailed + ': graph_missing');
    if (!(await verifyGraphHash(graph))) return setToast(t.importFailed + ': graph_hash_mismatch');
    const gi = new GraphIndex(graph);
    const req = computeRequirements(gi, loaded.header.zone.definition);
    await openDrive(loaded.header, loaded.events, graph, gi, req.zoneInfo, false);
    setResumable(null);
  }

  // drive map rendering
  const lastRenderRef = useRef({ traversals: -1, planId: '', cursor: -1, statusKey: '' });
  useEffect(() => {
    const mv = mapRef.current;
    if (!mv || !driveSnap || !driveGi || !drive || tab !== 'drive') return;
    const lr = lastRenderRef.current;
    const statusKey = [...driveSnap.statuses.values()].join('');
    if (lr.statusKey !== statusKey) {
      void mv.setData('reqs', requirementFeatures(driveGi, drive.sessionHeader.requirements, driveSnap.statuses));
      lr.statusKey = statusKey;
    }
    if (lr.traversals !== driveSnap.progress.traversals.length) {
      void mv.setData('covered', coveredFeatures(driveGi, driveSnap.progress.union));
      lr.traversals = driveSnap.progress.traversals.length;
    }
    const pid = driveSnap.plan?.planId ?? '';
    if (lr.planId !== pid || lr.cursor !== driveSnap.cursor) {
      void mv.setData('route', routeFeatures(driveGi, driveSnap.plan, driveSnap.cursor));
      lr.planId = pid;
      lr.cursor = driveSnap.cursor;
    }
    void mv.setData('ahead', { type: 'FeatureCollection', features: (driveSnap.nav?.ahead ?? []).filter((l) => l.length > 1).map((l) => ({ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: l } })) });
    void mv.setData('trail', { type: 'FeatureCollection', features: driveSnap.trail.length > 1 ? [{ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: driveSnap.trail } }] : [] });
  }, [driveSnap, driveGi, drive, tab]);

  useEffect(() => {
    if (toast) {
      const id = setTimeout(() => setToast(null), 6000);
      return () => clearTimeout(id);
    }
  }, [toast]);

  useEffect(() => {
    setTimeout(() => mapRef.current?.resize(), 50);
  }, [tab]);

  // Position marker in every screen; follow camera while driving.
  const driving = tab === 'drive' && driveSnap?.status === 'active';
  useEffect(() => {
    const mv = mapRef.current;
    if (!mv) return;
    const f = myFix;
    const feats: GeoJSON.Feature[] = [];
    if (f) {
      const acc = Math.min(200, f.horizontalAccuracyMeters ?? 50);
      const heading = locationHub.heading;
      feats.push({ type: 'Feature', properties: { kind: 'accuracy' }, geometry: { type: 'Polygon', coordinates: [circlePolygon([f.lon, f.lat], acc, 32)] } });
      feats.push({ type: 'Feature', properties: { kind: 'dot', heading: heading ?? -1, quality: acc <= 25 ? 'reliable' : 'approx' }, geometry: { type: 'Point', coordinates: [f.lon, f.lat] } });
      if (follow && driving) mv.follow([f.lon, f.lat], heading, true);
      else if (!centeredOnceRef.current && tab === 'prepare' && poly.length === 0 && !circle.center) {
        centeredOnceRef.current = true;
        mv.follow([f.lon, f.lat], null, false);
      }
    }
    void mv.setData('pos', { type: 'FeatureCollection', features: feats });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [myFix, follow, driving, tab]);

  useEffect(() => {
    void mapRef.current?.whenReady().then(() => mapRef.current?.setDriving(driving));
    if (driving) setFollow(true);
  }, [driving]);

  function goToMyLocation() {
    locationHub.start();
    setFollow(true);
    const f = locationHub.last;
    if (f) mapRef.current?.follow([f.lon, f.lat], locationHub.heading, driving);
  }

  async function refreshSessions() {
    if (store) setSessions(await store.listSessions());
  }

  // ---------- export / import ----------
  async function exportSession(h: SessionHeader, includeObservations: boolean) {
    if (!store) return;
    const loaded = await store.getSession(h.sessionId);
    const graph = loaded ? await store.getGraph(h.graphId) : null;
    if (!loaded || !graph) return setToast('graph_missing');
    const prog = applyEvents(emptyProgress(), loaded.events, { sessionId: h.sessionId, graphHash: graph.contentHash, edgeLengths: new Map(graph.directedEdges.map((e) => [e.edgeId, e.lengthMeters])) });
    const doc = sessionDocument({
      schemaVersion: SCHEMA_VERSION, sessionId: h.sessionId, graphId: h.graphId, contentHash: h.contentHash, zone: h.zone, vehicleProfile: 'passenger_car',
      boundaryPolicy: h.boundaryPolicy, returnToStart: h.returnToStart, startSelection: h.startSelection, createdAt: h.createdAt,
    }, h.requirements, prog);
    const pkg = buildExport(graph, doc, loaded.events, { platform: 'web', appVersion: APP_VERSION, includeGraph: true, includeObservations, exportedAt: new Date().toISOString() });
    const blob = new Blob([JSON.stringify(pkg)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `coverage-session-${h.sessionId.slice(0, 8)}.json`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  }

  async function importFile(file: File) {
    if (!store) return;
    const raw = await file.text();
    const r = await validateImport(raw, (hash) => store.getGraphByHash(hash));
    if (!r.ok || !r.pkg || !r.graph) return setToast(`${t.importFailed}: ${r.errors.slice(0, 3).join(', ')}`);
    const pkg = r.pkg;
    const existing = await store.getSession(pkg.session.sessionId);
    const sessionId = existing ? crypto.randomUUID() : pkg.session.sessionId;
    // Re-sequence events for this device; append traversals/exclusions not present as events.
    const events: SessionEvent[] = [];
    let seq = 0;
    const seenTr = new Set<string>();
    for (const e of pkg.events) {
      if (e.type === 'traversal_confirmed') seenTr.add((e.payload.traversal as { traversalId: string }).traversalId);
      events.push({ ...e, sessionId, seq: ++seq, eventId: `${sessionId}:imp:${seq}` });
    }
    for (const tr of pkg.session.confirmedTraversals) {
      if (!seenTr.has(tr.traversalId)) events.push({ eventId: `${sessionId}:imp:${++seq}`, seq, sessionId, type: 'traversal_confirmed', timestamp: tr.timestampEnd, schemaVersion: SCHEMA_VERSION, payload: { traversal: tr } });
    }
    // Exclusions recorded only in the session document become explicit events.
    const replayed = applyEvents(emptyProgress(), events, { sessionId, graphHash: r.graph.contentHash, edgeLengths: new Map(r.graph.directedEdges.map((e) => [e.edgeId, e.lengthMm / 1000])) });
    const activeNow = new Set([...replayed.exclusions.values()].filter((x) => x.active).map((x) => x.requirementId));
    for (const x of pkg.session.exclusions) {
      if (x.active && !activeNow.has(x.requirementId)) {
        events.push({ eventId: `${sessionId}:imp:${++seq}`, seq, sessionId, type: 'requirement_excluded', timestamp: x.at, schemaVersion: SCHEMA_VERSION, payload: { requirementIds: [x.requirementId], reason: x.reason, note: x.note } });
      }
    }
    const header: SessionHeader = {
      schemaVersion: SCHEMA_VERSION, sessionId, graphId: r.graph.graphId, contentHash: r.graph.contentHash, zone: pkg.session.zone, vehicleProfile: 'passenger_car',
      boundaryPolicy: pkg.session.boundaryPolicy, returnToStart: pkg.session.returnToStart, startSelection: pkg.session.startSelection, createdAt: pkg.session.createdAt,
      updatedAt: Date.now(), status: pkg.session.status === 'active' ? 'paused' : pkg.session.status, lastSeq: seq,
      requirements: pkg.session.requiredIntervals.map((x) => ({ ...x, status: x.status === 'needsReview' ? 'needsReview' : 'unvisited' })),
      label: `${pkg.exporter.platform} ${pkg.exportedAt.slice(0, 16)}`,
    };
    const finalStatus = applyEvents(emptyProgress(), events, { sessionId, graphHash: r.graph.contentHash, edgeLengths: new Map(r.graph.directedEdges.map((e) => [e.edgeId, e.lengthMm / 1000])) }).status;
    if (finalStatus === 'active' || (header.status === 'paused' && finalStatus !== 'paused')) {
      events.push({ eventId: `${sessionId}:imp:${++seq}`, seq, sessionId, type: 'paused', timestamp: Date.now(), schemaVersion: SCHEMA_VERSION, payload: { reason: 'imported' } });
      header.lastSeq = seq;
    }
    await store.putGraph(r.graph);
    await store.createSession(header, events);
    setToast(t.importOk);
    await refreshSessions();
  }

  // ---------- render ----------
  const fixture = prepared?.graph.dataState === 'synthetic_test_fixture';
  const reqMeters = prepared ? prepared.requirements.filter((r) => r.status !== 'needsReview').reduce((s, r) => s + r.lengthMeters, 0) : 0;
  const nrMeters = prepared ? prepared.requirements.filter((r) => r.status === 'needsReview').reduce((s, r) => s + r.lengthMeters, 0) : 0;

  const step: 'area' | 'start' | 'route' = !prepared ? 'area' : !start?.confirmed ? 'start' : 'route';
  const sheetPeek = pickStart || (step === 'area' && !sheetOpen);
  const sheetMin = step !== 'area' && sheetOpen && !pickStart; // handle tapped: show only the essentials
  const inDrive = tab === 'drive' && !!drive && !!driveSnap;
  const pageOpen = tab === 'sessions' || tab === 'settings';

  return (
    <div className={`app ${inDrive ? 'mode-drive' : 'mode-prepare'}`}>
      <div className="map-wrap">
        <div className="map" ref={mapEl} data-testid="map" />
      </div>

      {/* top-left: menu + search (prepare) */}
      <div className="top-left">
        <button className="fab small" data-testid="menu" aria-label={t.menu} onClick={() => setMenuOpen(true)}>☰</button>
        {!inDrive && <PlaceSearch t={t} lang={lang} onPick={(r) => mapRef.current?.fitBbox(r.bbox, 15)} />}
        {fixture && <span className="badge lab">{t.labBadge}</span>}
      </div>
      {resumable && !inDrive && (
        <div className="float-card resume" role="status">
          <span>{t.resumeBanner}</span>
          <button className="primary" onClick={() => resumeSession(resumable)}>{t.resumeSession}</button>
          <button className="ghost" aria-label={t.cancel} onClick={() => setResumable(null)}>×</button>
        </div>
      )}

      {/* right-side map controls */}
      <div className="map-controls">
        <button className="fab" aria-label="+" onClick={() => mapRef.current?.map.zoomIn()}>+</button>
        <button className="fab" aria-label="−" onClick={() => mapRef.current?.map.zoomOut()}>−</button>
        <button className="fab" aria-label={t.legend} onClick={() => setLegendOpen((v) => !v)} data-testid="legend-btn">◧</button>
        {inDrive && driveSnap!.status === 'active' && (
          <button className="fab" onClick={() => drive!.setVoice(!driveSnap!.voiceOn)} aria-label={driveSnap!.voiceOn ? t.voiceOn : t.voiceOff}>{driveSnap!.voiceOn ? '🔊' : '🔇'}</button>
        )}
        <button className={`fab primary-fab ${follow ? 'on' : ''}`} onClick={goToMyLocation} aria-label={t.myLocation} title={t.myLocation} data-testid="my-location">➤</button>
      </div>
      {legendOpen && <Legend t={t} onClose={() => setLegendOpen(false)} />}

      <div className={`where-chip ${inDrive ? 'in-drive' : ''}`} data-testid="where-chip">
        {myFix ? `${t.youAreHere} · ±${fmtNum(myFix.horizontalAccuracyMeters ?? NaN, lang)} ${t.m}`
          : myGps === 'permission_denied' ? t.permissionDenied
          : myGps === 'insecure_context' ? t.insecure
          : myGps === 'location_unavailable' ? t.locationOff
          : myGps === 'idle' ? <button className="link" onClick={goToMyLocation}>{t.showMyLocation}</button>
          : t.locating}
      </div>

      {/* preparation: bottom sheet with 3 steps */}
      {!inDrive && (
        <aside className={`sheet ${sheetPeek ? 'peek' : ''} ${sheetMin ? 'min' : ''}`} data-testid="prepare-panel">
          <button className="sheet-handle" aria-label={t.more} onClick={() => setSheetOpen((v) => !v)}><span /></button>
          <ol className="stepper">
            {(['area', 'start', 'route'] as const).map((k, i) => {
              const done = (k === 'area' && !!prepared) || (k === 'start' && !!start?.confirmed) || (k === 'route' && !!plan);
              return <li key={k} className={`${step === k ? 'cur' : ''} ${done ? 'done' : ''}`}><span className="dot">{done ? '✓' : fmtNum(i + 1, lang)}</span>{t[`step_${k}` as keyof Strings] as string}</li>;
            })}
          </ol>
          {prepared && graphState.kind === 'available' && (
            <p className="summary ok" data-testid="graph-stats">{t.graphLoaded} · {t.required} {fmtMeters(reqMeters, t, lang)}{nrMeters > 0 ? ` · ${t.needsReview} ${fmtMeters(nrMeters, t, lang)}` : ''}
              <button className="link small" onClick={() => { invalidatePrepared(); setGraphState({ kind: 'none' }); }}>{t.editArea}</button></p>
          )}

          {step === 'area' && (
            <section>
              <div className="row">
                <div className="seg">
                  <button className={zoneMode === 'polygon' ? 'on' : ''} onClick={() => { setZoneMode('polygon'); invalidatePrepared(); setGraphState({ kind: 'none' }); }}>⬠ {t.polygon}</button>
                  <button className={zoneMode === 'circle' ? 'on' : ''} onClick={() => { setZoneMode('circle'); invalidatePrepared(); setGraphState({ kind: 'none' }); }}>◯ {t.circle}</button>
                </div>
                {zoneMode === 'polygon' ? (
                  <>
                    <button className="icon-btn" disabled={!poly.length} onClick={() => { setPoly((p) => p.slice(0, -1)); invalidatePrepared(); setGraphState({ kind: 'none' }); }} aria-label={t.undo} title={t.undo}>↶</button>
                    <button className="icon-btn" disabled={!poly.length} onClick={() => { setPoly([]); invalidatePrepared(); setGraphState({ kind: 'none' }); }} aria-label={t.clear} title={t.clear}>✕</button>
                  </>
                ) : (
                  <label className="radius">{t.radiusMeters}
                    <input type="number" inputMode="numeric" min={20} max={5000} step={10} value={circle.radius} data-testid="radius"
                      onChange={(e) => { setCircle((c) => ({ ...c, radius: Math.max(0, Number(e.target.value)) })); invalidatePrepared(); setGraphState({ kind: 'none' }); }} />
                  </label>
                )}
              </div>
              <p className={`status-line ${zoneVal ? (zoneVal.ok ? 'ok' : 'err') : 'hint'}`} data-testid="zone-status">
                {!zoneVal ? (zoneMode === 'polygon' ? t.tapToAddVertex : t.tapToSetCenter)
                  : zoneVal.ok ? `${t.area}: ${fmtNum(zoneVal.areaSqMeters / 1e6, lang, 3)} km² · ${t.perimeter}: ${fmtMeters(zoneVal.perimeterMeters, t, lang)}`
                  : `${t.zoneInvalid}: ${zoneVal.errors.map((e) => e.message).join(' ')}`}
              </p>
              <button className="primary big" disabled={!zoneVal?.ok || graphState.kind === 'fetching'} onClick={loadRoads} data-testid="load-roads">
                {graphState.kind === 'fetching' ? t.fetching : t.fetchRoads}
              </button>
              {graphState.kind === 'fetching' && (
                <p className="muted small">{graphState.endpoint && `${new URL(graphState.endpoint).host} #${graphState.attempt}`} <button className="link" onClick={() => { abortRef.current?.abort(); setGraphState({ kind: 'none' }); }}>{t.cancel}</button></p>
              )}
              {graphState.kind === 'failed' && <p className="err" data-testid="graph-error">{t.graphFailed}: {graphState.outcome.code}{graphState.outcome.dataState ? ` (${graphState.outcome.dataState})` : ''}</p>}
              {!sheetPeek && <p className="muted small">{t.dataSource}</p>}
            </section>
          )}

          {step === 'start' && prepared && (
            <section>
              <div className="row">
                <button className={pickStart ? 'on' : 'primary'} disabled={planning} onClick={() => setPickStart(true)} data-testid="pick-start">📍 {t.tapForStart}</button>
                <button onClick={useMyLocation}>➤ {t.useMyLocation}</button>
              </div>
              {startMsg && <p className="err">{startMsg}</p>}
              {start && (
                <>
                  <p className="status-line" data-testid="start-info">{t.startSnapped}: <bdi>{roadName(prepared.gi, start.edgeId) ?? start.edgeId}</bdi> · {fmtMeters(start.distance, t, lang)}</p>
                  <div className="row">
                    <button onClick={flipStart} disabled={prepared.gi.reciprocal[prepared.gi.idx.get(start.edgeId)!] < 0}>⇄ {t.flipDirection}</button>
                    <button className="primary" onClick={() => setStart({ ...start, confirmed: true })} data-testid="confirm-start">✓ {t.confirmStart}</button>
                  </div>
                </>
              )}
            </section>
          )}

          {step === 'route' && prepared && start && (
            <section>
              <p className="status-line">{t.startSnapped}: <bdi>{roadName(prepared.gi, start.edgeId) ?? '—'}</bdi> <button className="link small" onClick={() => setStart({ ...start, confirmed: false })}>{t.change}</button></p>
              <div className="row">
                <div className="seg">
                  <button className={policy === 'STRICT_INSIDE' ? 'on' : ''} disabled={planning} onClick={() => setPolicy('STRICT_INSIDE')} title={t.strictInside}>{t.policyStrictShort}</button>
                  <button className={policy === 'ALLOW_NECESSARY_CONNECTORS' ? 'on' : ''} disabled={planning} onClick={() => setPolicy('ALLOW_NECESSARY_CONNECTORS')} title={t.allowConnectors}>{t.policyAllowShort}</button>
                </div>
                <label className="check"><input type="checkbox" disabled={planning} checked={returnToStart} onChange={(e) => { setReturnToStart(e.target.checked); setPlan(null); }} /> {t.returnToStart}</label>
              </div>
              <div className="row">
                <button className={plan ? '' : 'primary'} onClick={makePlan} disabled={planning} data-testid="plan">{planning ? t.planning : plan ? t.replan : t.plan}</button>
                {planning && <button onClick={cancelPlanning}>{t.cancel}</button>}
              </div>
              {plan && <PlanSummary t={t} lang={lang} plan={plan} approve={approveOutside} setApprove={setApproveOutside} />}
              <div className="sticky-cta"><button className="go big" disabled={startBlockers.length > 0} onClick={beginDriving} data-testid="start-driving">▶ {t.startDriving}</button></div>
              {startBlockers.length > 0 && <p className="muted small" data-testid="start-blockers">{t.startDisabled}: {startBlockers.join(' · ')}</p>}
              <details className="small muted"><summary>{t.safetyTitle}</summary><p>{t.foregroundNote}</p><p>{t.safety}</p></details>
            </section>
          )}
        </aside>
      )}

      {/* driving HUD */}
      {inDrive && <DriveHud t={t} lang={lang} snap={driveSnap!} dc={drive!} onToast={setToast} onBackToPrepare={() => setTab('prepare')} onMenu={() => setMenuOpen(true)} />}

      {/* menu drawer */}
      {menuOpen && (
        <div className="scrim" onClick={() => setMenuOpen(false)}>
          <nav className="drawer" onClick={(e) => e.stopPropagation()}>
            <strong className="drawer-title">{t.appName}</strong>
            {drive && <button onClick={() => { setTab('drive'); setMenuOpen(false); }}>🚗 {t.drive}</button>}
            <button onClick={() => { setTab('prepare'); setMenuOpen(false); }}>🗺 {t.prepare}</button>
            <button onClick={() => { setTab('sessions'); void refreshSessions(); setMenuOpen(false); }}>🗂 {t.sessions}</button>
            <button onClick={() => { setTab('settings'); setMenuOpen(false); }}>⚙ {t.settings}</button>
            <div className="seg drawer-lang">
              <button className={lang === 'fa' ? 'on' : ''} onClick={() => setLang('fa')}>فارسی</button>
              <button className={lang === 'en' ? 'on' : ''} onClick={() => setLang('en')}>English</button>
            </div>
            <p className="small muted">{t.attribution}</p>
          </nav>
        </div>
      )}

      {/* full-screen pages */}
      {pageOpen && (
        <div className="page">
          <header className="page-head">
            <button className="icon-btn" data-testid="close-page" aria-label={t.back} onClick={() => setTab(drive ? 'drive' : 'prepare')}>←</button>
            <strong>{tab === 'sessions' ? t.sessions : t.settings}</strong>
          </header>
          <div className="page-body">
            {tab === 'sessions' && (
              <SessionsPanel t={t} lang={lang} sessions={sessions} onResume={resumeSession} onExport={exportSession} onImport={importFile}
                onDelete={async (h) => { if (store && confirm(t.confirmDelete)) { await store.deleteSession(h.sessionId); await refreshSessions(); } }} />
            )}
            {tab === 'settings' && <SettingsPanel t={t} lang={lang} setLang={setLang} graph={prepared?.graph ?? null} storeError={storeError} />}
          </div>
        </div>
      )}
      {toast && <div className="toast" role="status">{toast}</div>}
    </div>
  );
}

function roadName(gi: GraphIndex, edgeId: string): string | null {
  const i = gi.idx.get(edgeId);
  if (i === undefined) return null;
  const n = gi.edges[i].names;
  return n.default ?? n.en ?? n.ref ?? null;
}

function PlaceSearch({ t, lang, onPick }: { t: Strings; lang: Lang; onPick: (r: PlaceResult) => void }) {
  const [q, setQ] = useState('');
  const [busy, setBusy] = useState(false);
  const [res, setRes] = useState<PlaceResult[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  return (
    <div className="search">
      <form className="search-pill" onSubmit={async (e) => {
        e.preventDefault();
        if (!q.trim()) return;
        setBusy(true);
        setErr(null);
        try {
          setRes(await searchPlace(q.trim(), lang));
        } catch (x) {
          setErr(String((x as Error).message));
        } finally {
          setBusy(false);
        }
      }}>
        <span className="search-ico">⌕</span>
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t.searchPlaceholder} aria-label={t.search} enterKeyHint="search" />
        {busy && <span className="small muted">{t.searching}</span>}
      </form>
      {(err || (res && res.length === 0)) && <p className="search-msg">{err ?? t.noResults}</p>}
      {res && res.length > 0 && (
        <ul className="results">
          {res.map((r, i) => (
            <li key={i}><button onClick={() => { onPick(r); setRes(null); setQ(r.displayName.split(',')[0]); }}><strong>{r.displayName.split(',')[0]}</strong><span className="small muted">{r.displayName.split(',').slice(1, 3).join(',')}</span></button></li>
          ))}
        </ul>
      )}
    </div>
  );
}

function PlanSummary({ t, lang, plan, approve, setApprove }: { t: Strings; lang: Lang; plan: { plan: RoutePlan; validation: { ok: boolean; errors: string[] }; ms: number }; approve: boolean; setApprove: (b: boolean) => void }) {
  const p = plan.plan;
  const causes = p.unreachable.reduce<Record<string, number>>((m, u) => ((m[u.cause] = (m[u.cause] ?? 0) + 1), m), {});
  const unverified = p.steps.filter((s) => s.turnaroundAtStart === 'unverified').length;
  const unplanned = Object.values(causes).reduce((a, b) => a + b, 0);
  return (
    <div className="plan-card" data-testid="plan-summary">
      <div className="kpis">
        <div><b>{fmtMeters(p.totals.totalMeters, t, lang)}</b><span>{t.totalDistance}</span></div>
        <div><b>{fmtMeters(p.totals.newCoverageMeters, t, lang)}</b><span>{t.newCoverage}</span></div>
        <div><b>{fmtMeters(p.totals.repeatMeters, t, lang)}</b><span>{t.repeats}</span></div>
        {p.totals.outsideMeters > 2 && <div className="warn"><b>{fmtMeters(p.totals.outsideMeters, t, lang)}</b><span>{t.outside}</span></div>}
      </div>
      <p className="small">
        <span className={plan.validation.ok ? 'ok' : 'err'}>{(t as unknown as Record<string, string>)[`planStatus_${p.status}`] ?? p.status} · {plan.validation.ok ? '✓' : '✗'}</span>
        {unplanned > 0 && <> · {t.unreachable}: {fmtNum(unplanned, lang)}</>}
        {unverified > 0 && <> · {t.unverifiedTurnarounds}: {fmtNum(unverified, lang)}</>}
      </p>
      <details className="small muted"><summary>{t.details}</summary>
        <p>{Object.entries(causes).map(([k, v]) => `${k} ${fmtNum(v, lang)}`).join(' · ') || '—'}</p>
        <p>{p.algorithm} {p.algorithmVersion} · {Math.round(plan.ms)} ms</p>
        {!plan.validation.ok && <p className="err">{plan.validation.errors.slice(0, 5).join(', ')}</p>}
      </details>
      {p.totals.outsideMeters > 2 && (
        <label className="check warn"><input type="checkbox" checked={approve} onChange={(e) => setApprove(e.target.checked)} data-testid="approve-outside" /> {t.approveOutside} ({fmtMeters(p.totals.outsideMeters, t, lang)})</label>
      )}
    </div>
  );
}

const MANEUVER_ICON: Record<string, string> = {
  continue: '↑', slight_right: '↗', right: '→', sharp_right: '↘', slight_left: '↖', left: '←', sharp_left: '↙', turnaround: '⤸', arrive: '■', depart: '↑',
};

function navAction(t: Strings, n: NavInstruction): string {
  if (n.mode === 'to_road') return t.navHeadToRoad;
  if (n.type === 'join') return t.navJoinRoute;
  if (n.type === 'turnaround' && !n.turnaroundVerified) return t.turnaroundUnverified;
  return ((t as unknown as Record<string, string>)[n.type]) ?? t.continue;
}

/** Lane strip: one arrow per lane, usable lanes bright, others dimmed. */
function LaneStrip({ lanes }: { lanes: LaneHint[] }) {
  return (
    <div className="lanes" data-testid="lanes" dir="ltr">
      {lanes.map((l, i) => (
        <span key={i} className={`lane ${l.ok ? 'ok' : ''}`} data-ok={l.ok ? '1' : '0'}>
          {l.turns.map((x, k) => <span key={k} className="lane-arrow" style={{ transform: `rotate(${LANE_ROT[x] ?? 0}deg)` }}>{x === 'reverse' ? '↶' : '↑'}</span>)}
        </span>
      ))}
    </div>
  );
}

const LANE_ROT: Record<string, number> = {
  left: -90, slight_left: -45, sharp_left: -135, through: 0, none: 0, right: 90, slight_right: 45, sharp_right: 135, merge_to_left: -30, merge_to_right: 30, reverse: 0,
};

/** Maneuver card: what to do next, how far, which street, which lane. */
function ManeuverCard({ t, lang, n }: { t: Strings; lang: Lang; n: NavInstruction }) {
  const glyph = n.mode === 'to_road' ? '➤' : n.type === 'join' ? '⤴' : MANEUVER_ICON[n.type] ?? '↑';
  const rot = n.mode === 'to_road' ? (n.relativeBearing ?? n.headBearing ?? 0) - 90 : 0;
  const now = n.distanceMeters < 40 && n.mode !== 'to_road';
  return (
    <div className={`maneuver-card ${now ? 'now' : ''}`} data-testid="nav-banner" dir={lang === 'fa' ? 'rtl' : 'ltr'}>
      <div className="mc-main">
        <span className="mc-glyph" style={{ transform: `rotate(${rot}deg)` }}>{glyph}</span>
        <div className="mc-text">
          <div className="mc-dist">{fmtMeters(n.distanceMeters, t, lang)}</div>
          <div className="mc-act">{navAction(t, n)}{n.roadName && <> {t.onto} <bdi>{n.roadName}</bdi></>}</div>
        </div>
      </div>
      {n.lanes && <LaneStrip lanes={n.lanes} />}
      {(n.mode === 'approach' || (n.thenType && n.thenDistanceMeters !== null && n.thenDistanceMeters < 400) || n.approximate) && (
        <div className="mc-sub">
          {n.thenType && n.thenDistanceMeters !== null && n.thenDistanceMeters < 400 && (
            <span className="mc-then">{t.navThen} {n.thenType === 'join' ? '⤴' : MANEUVER_ICON[n.thenType] ?? ''}</span>
          )}
          {n.mode === 'approach' && n.rejoinMeters !== null && <span>{t.navGoToRoute} · {fmtMeters(n.rejoinMeters, t, lang)}</span>}
          {n.approximate && <span className="mc-approx">{t.navApproximate}</span>}
        </div>
      )}
    </div>
  );
}

function DriveHud({ t, lang, snap, dc, onToast, onBackToPrepare, onMenu }: { t: Strings; lang: Lang; snap: DriveSnapshot; dc: DriveController; onToast: (s: string) => void; onBackToPrepare: () => void; onMenu: () => void }) {
  const [, force] = useState(0);
  const [more, setMore] = useState(false);
  useEffect(() => {
    const id = setInterval(() => force((x) => x + 1), 1000);
    return () => clearInterval(id);
  }, []);
  const m = snap.metrics;
  const fixAge = snap.lastFix ? (Date.now() - snap.lastFix.timestamp) / 1000 : null;
  const gpsLabel = snap.status === 'paused' ? t.paused
    : snap.gpsSource === 'permission_denied' ? t.permissionDenied
    : snap.gpsSource === 'location_unavailable' ? t.locationOff
    : snap.gpsSource === 'insecure_context' ? t.insecure
    : fixAge !== null && (fixAge > 10 || fixAge < -5) ? t.stale
    : snap.quality === 'reliable' ? t.reliable
    : snap.quality === 'weak_ambiguous' ? t.weak
    : snap.quality === 'off_graph' ? t.offGraph
    : t.acquiring;
  const gpsOk = snap.quality === 'reliable' && fixAge !== null && fixAge <= 10 && fixAge >= -5 && snap.status === 'active';
  const nm = fixAge !== null && fixAge <= 5 && fixAge >= -5 && snap.status === 'active' ? snap.nextManeuver : null;
  const statusText = snap.replanning ? t.replanning
    : snap.notice === 'outside_policy' ? t.outsideZoneStop
    : snap.notice === 'finished' ? t.finished
    : snap.status === 'paused' ? t.paused
    : nm ? `${navActionType(t, nm.type, nm.turnaroundVerified)} · ${fmtMeters(nm.distanceMeters, t, lang)}`
    : t.noGuidance;
  const pct = Math.min(100, m.percentConfirmed);
  return (
    <>
      <div className="hud-top">
        {snap.status === 'active' && snap.nav && !snap.replanning ? <ManeuverCard t={t} lang={lang} n={snap.nav} /> : (
          <div className="maneuver-card muted-card" data-testid="maneuver">{statusText}</div>
        )}
        {snap.pendingOutsidePlan && (
          <div className="float-card warn">
            {t.outsideNeedsApproval}: {fmtMeters(snap.pendingOutsidePlan.outsideMeters, t, lang)}
            <button onClick={() => { if (confirm(`${t.outsideNeedsApproval}: ${fmtMeters(snap.pendingOutsidePlan!.outsideMeters, t, lang)}`)) void dc.approvePendingOutside(); }}>{t.approve}</button>
          </div>
        )}
      </div>
      <aside className="drive-bar" data-testid="drive-panel">
        <div className="db-row">
          <div className="ring" style={{ background: `conic-gradient(var(--done) ${pct * 3.6}deg, var(--track) 0)` }}><span>{fmtNum(pct, lang, 0)}%</span></div>
          <div className="db-info">
            <div className="db-progress" data-testid="progress">
              {fmtMeters(m.confirmedMeters, t, lang)} / {fmtMeters(m.totalRequiredMeters, t, lang)} {t.confirmedShort}
              {'\n'}<span className="small muted">{t.remaining} {fmtMeters(m.remainingMeters, t, lang)} · {t.blocked} {fmtMeters(m.blockedMeters, t, lang)} · {t.unreachable} {fmtMeters(m.unreachableMeters, t, lang)} · {t.needsReview} {fmtMeters(m.needsReviewMeters, t, lang)}</span>
            </div>
            <div className={`db-gps ${gpsOk ? 'ok' : 'warn'}`} data-testid="gps-state">
              <span className="gdot" />{t.gps}: {gpsLabel}
              {snap.lastFix && <span className="small"> · ±{fmtNum(snap.lastFix.horizontalAccuracyMeters ?? NaN, lang)} {t.m} · {fmtNum(fixAge ?? 0, lang)} s</span>}
            </div>
          </div>
          {snap.status === 'active' ? <button className="round-btn" onClick={() => dc.pause()} data-testid="pause" aria-label={t.pause}>⏸<span>{t.pause}</span></button>
            : snap.status !== 'ended' ? <button className="round-btn go" onClick={() => dc.startOrResume()} data-testid="resume" aria-label={t.resume}>▶<span>{t.resume}</span></button> : null}
          <button className="round-btn" onClick={() => setMore((v) => !v)} aria-label={t.more} data-testid="more">⋯<span>{t.more}</span></button>
        </div>
        {(snap.notice === 'hidden_gap' || snap.notice === 'recovered' || snap.notice === 'storage_error' || snap.unsavedEvents > 0 || snap.wakeLock !== 'held') && (
          <div className="db-notes small">
            {snap.notice === 'hidden_gap' && <p className="warn">{t.hiddenWarning}</p>}
            {snap.notice === 'recovered' && <p className="warn" data-testid="recovered">{t.recovered}</p>}
            {snap.notice === 'storage_error' && <p className="err">{t.storage}: {snap.storageError}</p>}
            {snap.unsavedEvents > 0 && <p className="err">{fmtNum(snap.unsavedEvents, lang)} {t.unsaved}</p>}
            {snap.wakeLock !== 'held' && snap.status === 'active' && <p className="muted">{t.wakeLockOff}</p>}
          </div>
        )}
        {more && (
          <div className="more-sheet">
            <button disabled={!snap.matched || snap.status !== 'active'} onClick={async () => { if (confirm(t.confirmBlocked)) { const ok = await dc.blockCurrentRoad(); if (!ok) onToast(t.offGraph); } setMore(false); }}>⛔ {t.reportBlocked}</button>
            <button onClick={() => { setMore(false); onBackToPrepare(); }}>🗺 {t.prepare}</button>
            <button data-testid="more-menu" onClick={() => { setMore(false); onMenu(); }}>☰ {t.menu}</button>
            {snap.status !== 'ended' && <button className="danger" onClick={() => { if (confirm(t.confirmEnd)) void dc.end(); setMore(false); }}>■ {t.end}</button>}
            <p className="small muted">{t.foregroundNote}</p>
          </div>
        )}
      </aside>
    </>
  );
}

function navActionType(t: Strings, type: string, verified: boolean | null): string {
  if (type === 'turnaround' && !verified) return t.turnaroundUnverified;
  return ((t as unknown as Record<string, string>)[type]) ?? t.continue;
}

function Legend({ t, onClose }: { t: Strings; onClose: () => void }) {
  const items: [string, string, string][] = [
    ['var(--todo)', t.unvisited, 'solid'], ['var(--done)', t.covered, 'solid'], ['var(--partial)', t.partial, 'solid'],
    ['var(--route)', t.routeAhead, 'thick'], ['var(--plan)', t.plannedRoute, 'faint'], ['var(--outside)', t.outsideConnector, 'faint'],
    ['var(--blocked)', t.blocked, 'dashed'], ['var(--unreach)', t.unreachable, 'dotted'], ['var(--review)', t.needsReview, 'dashed'],
  ];
  return (
    <div className="legend-pop" role="dialog" aria-label={t.legend} data-testid="legend">
      <div className="lp-head"><strong>{t.legend}</strong><button className="ghost" onClick={onClose} aria-label={t.cancel}>×</button></div>
      <ul>{items.map(([c, l, k]) => <li key={l}><span className={`sw ${k}`} style={{ ['--c' as string]: c }} />{l}</li>)}</ul>
      <p className="small muted">{t.legendDirections}</p>
    </div>
  );
}

function SessionsPanel({ t, lang, sessions, onResume, onExport, onImport, onDelete }: {
  t: Strings; lang: Lang; sessions: SessionHeader[];
  onResume: (h: SessionHeader) => void; onExport: (h: SessionHeader, obs: boolean) => void; onImport: (f: File) => void; onDelete: (h: SessionHeader) => void;
}) {
  const [obs, setObs] = useState(false);
  return (
    <section data-testid="sessions">
      <h3>{t.sessions}</h3>
      <label className="row"><input type="checkbox" checked={obs} onChange={(e) => setObs(e.target.checked)} /> {t.includeObservations}</label>
      {sessions.length === 0 && <p className="muted">{t.noSessions}</p>}
      <ul className="sessions">
        {sessions.map((h) => (
          <li key={h.sessionId}>
            <div><strong>{h.label}</strong> <span className="muted small">{h.status} · {fmtNum(h.requirements.length, lang)} req · {h.boundaryPolicy}</span></div>
            <div className="row">
              {h.status !== 'ended' && <button onClick={() => onResume(h)}>{t.resumeSession}</button>}
              <button onClick={() => onExport(h, obs)}>{t.exportSession}</button>
              <button className="danger" onClick={() => onDelete(h)}>{t.deleteSession}</button>
            </div>
          </li>
        ))}
      </ul>
      <label className="file">
        {t.importSession}
        <input type="file" accept="application/json,.json" data-testid="import-file" onChange={(e) => { const f = e.target.files?.[0]; if (f) onImport(f); e.target.value = ''; }} />
      </label>
    </section>
  );
}

function SettingsPanel({ t, lang, setLang, graph, storeError }: { t: Strings; lang: Lang; setLang: (l: Lang) => void; graph: GraphSnapshot | null; storeError: string | null }) {
  const [est, setEst] = useState<{ usage: number; quota: number; persisted: boolean } | null>(null);
  useEffect(() => { void storageEstimate().then(setEst); }, []);
  return (
    <section>
      <h3>{t.language}</h3>
      <div className="seg">
        <button className={lang === 'fa' ? 'on' : ''} onClick={() => setLang('fa')}>فارسی</button>
        <button className={lang === 'en' ? 'on' : ''} onClick={() => setLang('en')}>English</button>
      </div>
      <h3>{t.storage}</h3>
      {storeError ? <p className="err">{storeError}</p> : est ? <p>{fmtNum(est.usage / 1e6, lang, 1)} MB / {fmtNum(est.quota / 1e6, lang, 0)} MB · persisted: {String(est.persisted)}</p> : <p className="muted">n/a</p>}
      <h3>{t.diagnostics}</h3>
      {graph ? (
        <div className="small">
          <p>graphId {graph.graphId} · provider {graph.providerId} · {graph.normalizerVersion}</p>
          <p>source {graph.sourceDatasetVersion} · fetched {graph.fetchedAt}</p>
          <p>{fmtNum(graph.nodes.length, lang)} nodes · {fmtNum(graph.directedEdges.length, lang)} directed edges · {fmtNum(graph.turnRestrictions.length, lang)} restrictions</p>
          <ul>{graph.completenessNotes.map((n, i) => <li key={i}>{n}</li>)}</ul>
        </div>
      ) : <p className="muted small">—</p>}
      <p className="small">{t.attribution}</p>
      <p className="small">{t.privacy}</p>
      <p className="small">{t.safety}</p>
      <p className="small muted">{t.version} {APP_VERSION}</p>
    </section>
  );
}

function localStorageGet(k: string): string | null {
  try {
    return localStorage.getItem(k);
  } catch {
    return null;
  }
}
function localStorageSet(k: string, v: string) {
  try {
    localStorage.setItem(k, v);
  } catch {
    /* private mode */
  }
}
