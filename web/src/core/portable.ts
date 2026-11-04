// Portable session export/import (Android <-> web). Validates against the frozen
// JSON Schema plus semantic checks the schema cannot express.

import Ajv2020 from 'ajv/dist/2020';
import schema from '../../../contract/schema/coverage-contract-1.0.0.schema.json';
import { verifyGraphHash } from './hash';
import { SCHEMA_VERSION, type CoverageSession, type GraphSnapshot, type MatchedTraversal, type SessionEvent } from './types';
import { GraphIndex } from './graph';
import { computeRequirements } from './requirements';

export interface SessionExportPackage {
  packageType: 'coverage-session-export';
  contractVersion: string;
  exportedAt: string;
  exporter: { platform: 'web' | 'android'; appVersion: string };
  graphRef: {
    graphId: string;
    contentHash: string;
    providerId: string;
    normalizerVersion: string;
    sourceDatasetVersion: string;
    attribution: string;
  };
  graph: GraphSnapshot | null;
  session: CoverageSession;
  events: SessionEvent[];
  diagnostics: { observationsIncluded: boolean; gapCount: number; notes: string[] };
}

const SCHEMA_ID = (schema as { $id: string }).$id;
let ajv: Ajv2020 | null = null;
function getAjv(): Ajv2020 {
  if (!ajv) {
    ajv = new Ajv2020({ strict: false, allErrors: true });
    ajv.addSchema(schema as object);
  }
  return ajv;
}

export function validateSchema(def: 'sessionExportPackage' | 'graphSnapshot' | 'coverageSession' | 'routePlan' | 'sessionEvent', value: unknown) {
  const v = getAjv().getSchema(`${SCHEMA_ID}#/$defs/${def}`);
  if (!v) throw new Error(`schema def ${def} missing`);
  const ok = v(value) as boolean;
  return { ok, errors: ok ? [] : (v.errors ?? []).slice(0, 20).map((e) => `${e.instancePath || '/'} ${e.message}`) };
}

export function buildExport(
  graph: GraphSnapshot,
  session: CoverageSession,
  events: SessionEvent[],
  opts: { platform: 'web' | 'android'; appVersion: string; includeGraph: boolean; includeObservations: boolean; exportedAt: string },
): SessionExportPackage {
  const ev = opts.includeObservations ? events : events.filter((e) => e.type !== 'observation');
  return {
    packageType: 'coverage-session-export',
    contractVersion: SCHEMA_VERSION,
    exportedAt: opts.exportedAt,
    exporter: { platform: opts.platform, appVersion: opts.appVersion },
    graphRef: {
      graphId: graph.graphId,
      contentHash: graph.contentHash,
      providerId: graph.providerId,
      normalizerVersion: graph.normalizerVersion,
      sourceDatasetVersion: graph.sourceDatasetVersion,
      attribution: graph.attribution,
    },
    graph: opts.includeGraph ? graph : null,
    session,
    events: ev,
    diagnostics: {
      observationsIncluded: opts.includeObservations,
      gapCount: events.filter((e) => e.type === 'gap_recorded').length,
      notes: opts.includeGraph ? ['Graph included under ODbL 1.0; keep attribution.'] : ['Graph not included: the importing device needs the identical graph (same contentHash).'],
    },
  };
}

export interface ImportResult {
  ok: boolean;
  errors: string[];
  pkg?: SessionExportPackage;
  graph?: GraphSnapshot;
}

/** Validate an import. Never mutates the input; callers keep the original file on rejection. */
export async function validateImport(raw: string, knownGraphs: (hash: string) => Promise<GraphSnapshot | null>): Promise<ImportResult> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return { ok: false, errors: ['corrupt_json'] };
  }
  const p = parsed as Partial<SessionExportPackage>;
  if (p?.packageType !== 'coverage-session-export') return { ok: false, errors: ['not_a_session_export'] };
  if (p.contractVersion !== SCHEMA_VERSION) return { ok: false, errors: [`unsupported_contract_version:${String(p.contractVersion)}`] };
  const sv = validateSchema('sessionExportPackage', parsed);
  if (!sv.ok) return { ok: false, errors: ['schema_invalid', ...sv.errors] };
  const pkg = parsed as SessionExportPackage;
  const errors: string[] = [];
  let graph = pkg.graph;
  if (graph) {
    if (!(await verifyGraphHash(graph))) errors.push('graph_hash_mismatch');
    if (graph.contentHash !== pkg.graphRef.contentHash) errors.push('graph_ref_mismatch');
    if (graph.coordinateReferenceSystem !== 'EPSG:4326') errors.push('unexpected_crs');
  } else {
    graph = await knownGraphs(pkg.graphRef.contentHash);
    if (!graph) errors.push('graph_unavailable_on_this_device');
  }
  if (pkg.session.contentHash !== pkg.graphRef.contentHash) errors.push('session_graph_mismatch');
  if (pkg.session.schemaVersion !== SCHEMA_VERSION) errors.push('session_schema_version');
  if (graph) {
    const len = new Map(graph.directedEdges.map((e) => [e.edgeId, e.lengthMm / 1000]));
    const eps = 0.01;
    const checkTraversal = (t: MatchedTraversal | undefined, where: string) => {
      const L = t ? len.get(t.edgeId) : undefined;
      if (!t || L === undefined) errors.push(`unknown_edge:${where}`);
      else if (!(t.startOffsetMeters >= -eps && t.endOffsetMeters <= L + eps && t.endOffsetMeters > t.startOffsetMeters)) errors.push(`impossible_offsets:${t.traversalId}`);
    };
    // Requirements must be exactly what this zone + graph produce (no silent denominator change).
    try {
      const expected = computeRequirements(new GraphIndex(graph), pkg.session.zone.definition).requirements;
      const got = new Map(pkg.session.requiredIntervals.map((r) => [r.requirementId, r]));
      if (expected.length !== got.size) errors.push('requirements_mismatch');
      else
        for (const r of expected) {
          const g = got.get(r.requirementId);
          if (!g || g.edgeId !== r.edgeId || Math.abs(g.startOffsetMeters - r.startOffsetMeters) > 0.05 || Math.abs(g.endOffsetMeters - r.endOffsetMeters) > 0.05) {
            errors.push('requirements_mismatch');
            break;
          }
        }
    } catch {
      errors.push('zone_invalid');
    }
    for (const t of pkg.session.confirmedTraversals) checkTraversal(t, t.edgeId);
    for (const e of pkg.events) if (e.type === 'traversal_confirmed') checkTraversal(e.payload?.traversal as MatchedTraversal | undefined, e.eventId);
    const reqIds = new Set(pkg.session.requiredIntervals.map((r) => r.requirementId));
    for (const x of pkg.session.exclusions) if (!reqIds.has(x.requirementId)) errors.push(`unknown_exclusion:${x.requirementId}`);
    for (const e of pkg.events) {
      if (e.type === 'requirement_excluded' || e.type === 'requirement_restored') {
        const ids = e.payload?.requirementIds;
        if (!Array.isArray(ids) || ids.some((id) => !reqIds.has(id as string))) errors.push(`unknown_exclusion:${e.eventId}`);
      }
    }
    if (pkg.session.graphId !== graph.graphId) errors.push('session_graph_mismatch');
  }
  let lastSeq = 0;
  for (const e of pkg.events) {
    if (e.sessionId !== pkg.session.sessionId) errors.push(`foreign_event:${e.eventId}`);
    if (e.seq <= lastSeq) errors.push(`non_monotonic_event:${e.eventId}`);
    lastSeq = e.seq;
  }
  if (errors.length) return { ok: false, errors: [...new Set(errors)].slice(0, 30) };
  return { ok: true, errors: [], pkg, graph: graph! };
}
