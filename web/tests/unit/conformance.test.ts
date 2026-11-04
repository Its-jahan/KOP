import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { runFixture } from '../../src/core/conformance';
import { decidePlan } from '../../src/core/revision';
import { recoverEventLog } from '../../src/core/recovery';
import { mapFailure } from '../../src/core/states';
import { canonicalize, sha256Hex } from '../../src/core/jcs';
import { validateImport, validateSchema } from '../../src/core/portable';
import { applyEvents, emptyProgress, computeMetrics } from '../../src/core/session';

const DIR = join(__dirname, '../../../contract/fixtures');
const files = readdirSync(DIR).filter((f) => f.endsWith('.json')).sort();

/** Deep compare with numeric tolerance. */
function close(actual: unknown, expected: unknown, tol: number, path = ''): string[] {
  if (typeof expected === 'number' && typeof actual === 'number') return Math.abs(actual - expected) <= tol ? [] : [`${path}: ${actual} != ${expected}`];
  if (Array.isArray(expected)) {
    if (!Array.isArray(actual) || actual.length !== expected.length) return [`${path}: array length ${Array.isArray(actual) ? actual.length : 'n/a'} != ${expected.length}`];
    return expected.flatMap((e, i) => close(actual[i], e, tol, `${path}[${i}]`));
  }
  if (expected && typeof expected === 'object') {
    if (!actual || typeof actual !== 'object') return [`${path}: not object`];
    const keys = new Set([...Object.keys(expected), ...Object.keys(actual as object)]);
    return [...keys].flatMap((k) => close((actual as Record<string, unknown>)[k], (expected as Record<string, unknown>)[k], tol, `${path}.${k}`));
  }
  return actual === expected ? [] : [`${path}: ${JSON.stringify(actual)} != ${JSON.stringify(expected)}`];
}

type Out = Awaited<ReturnType<typeof runFixture>>;
type PlanOut = { status: string; edgeSequence: string[]; turnarounds: string[]; coveredRequirementIds: string[]; unreachable: string[]; totals: Record<string, number>; valid: boolean };
type TrackOut = { unionByEdge: Record<string, [number, number][]>; gaps: string[]; ignored: string[]; deviationAt: number | null; replan: { valid: boolean; coveredRequirementIds: string[] } | null };

function covered(t: TrackOut, edgeId: string) {
  return (t.unionByEdge[edgeId] ?? []).reduce((s, [a, b]) => s + b - a, 0);
}
function containsSeq(seq: string[], sub: string[]) {
  for (let i = 0; i + sub.length <= seq.length; i++) if (sub.every((x, k) => seq[i + k] === x)) return true;
  return false;
}

function checkAssertion(a: Record<string, unknown>, out: Out) {
  const plan = a.policy ? (out.plans[a.policy as string] as PlanOut) : undefined;
  const tr = a.track ? (out.tracks[a.track as string] as TrackOut) : undefined;
  switch (a.kind) {
    case 'plan_covers_all':
      expect(plan!.unreachable).toEqual([]);
      expect(plan!.coveredRequirementIds.length).toBe(out.requirements.length);
      break;
    case 'plan_valid':
      expect(plan!.valid).toBe(true);
      break;
    case 'plan_status':
      expect(plan!.status).toBe(a.status);
      break;
    case 'plan_repeat_positive':
      expect(plan!.totals.repeatMeters).toBeGreaterThan(0);
      break;
    case 'plan_has_no_turnaround':
      expect(plan!.turnarounds).toEqual([]);
      break;
    case 'plan_has_unverified_turnaround':
      expect(plan!.turnarounds.some((t) => t.endsWith(':unverified'))).toBe(true);
      break;
    case 'plan_turnaround_verified_at_node': {
      const idx = plan!.turnarounds.filter((t) => t.endsWith(':verified')).map((t) => Number(t.split(':')[0]));
      const nodeNum = (a.nodeId as string).replace('osm:', '');
      expect(idx.some((i) => plan!.edgeSequence[i].includes(`${nodeNum}-`) || plan!.edgeSequence[i].includes(`-${nodeNum}:`))).toBe(true);
      break;
    }
    case 'plan_not_contains_sequence':
      expect(containsSeq(plan!.edgeSequence, a.sequence as string[])).toBe(false);
      break;
    case 'plan_never_uses_edge_prefix':
      expect(plan!.edgeSequence.some((e) => e.startsWith(a.prefix as string))).toBe(false);
      break;
    case 'plan_outside_at_least':
      expect(plan!.totals.outsideMeters).toBeGreaterThanOrEqual(a.meters as number);
      break;
    case 'plan_outside_at_most':
      expect(plan!.totals.outsideMeters).toBeLessThanOrEqual(a.meters as number);
      break;
    case 'unreachable_cause':
      expect(plan!.unreachable).toContain(`${a.requirementId}:${a.cause}`);
      break;
    case 'unreachable_cause_present':
      expect(plan!.unreachable.some((u) => u.endsWith(`:${a.cause}`))).toBe(true);
      break;
    case 'edge_count':
      expect(out.graph.edgeCount).toBe(a.count);
      break;
    case 'node_count':
      expect(out.graph.nodeCount).toBe(a.count);
      break;
    case 'requirement_count':
      expect(out.requirements.length).toBe(a.count);
      break;
    case 'requirement_interval': {
      const r = out.requirements.find((x) => x.requirementId === a.requirementId)!;
      expect(Math.abs(r.start - (a.start as number))).toBeLessThan(1.0);
      expect(Math.abs(r.end - (a.end as number))).toBeLessThan(1.0);
      break;
    }
    case 'no_edge_with_suffix':
      expect(out.graph.edgeIds.some((e) => e.endsWith(a.suffix as string))).toBe(false);
      break;
    case 'path_not_equal':
      expect(out.paths[a.path as string]).not.toEqual(a.forbidden);
      expect(out.paths[a.path as string]).not.toBeNull();
      break;
    case 'path_not_contains_sequence':
      expect(out.paths[a.path as string]).not.toBeNull();
      expect(containsSeq(out.paths[a.path as string]!, a.sequence as string[])).toBe(false);
      break;
    case 'edge_covered_at_least':
      expect(covered(tr!, a.edgeId as string)).toBeGreaterThanOrEqual(a.meters as number);
      break;
    case 'edge_covered_at_most':
      expect(covered(tr!, a.edgeId as string)).toBeLessThanOrEqual(a.meters as number);
      break;
    case 'edge_covered_between': {
      const c = covered(tr!, a.edgeId as string);
      expect(c).toBeGreaterThanOrEqual(a.min as number);
      expect(c).toBeLessThanOrEqual(a.max as number);
      break;
    }
    case 'edge_not_covered_in':
      for (const [s, e] of tr!.unionByEdge[a.edgeId as string] ?? []) {
        expect(e <= (a.start as number) || s >= (a.end as number), `interval [${s},${e}] overlaps forbidden [${a.start},${a.end}]`).toBe(true);
      }
      break;
    case 'track_total_at_most':
      expect(Object.keys(tr!.unionByEdge).reduce((s, k) => s + covered(tr!, k), 0)).toBeLessThanOrEqual(a.meters as number);
      break;
    case 'track_only_edges':
      expect(Object.keys(tr!.unionByEdge).sort()).toEqual((a.edgeIds as string[]).slice().sort());
      break;
    case 'ignored_count_at_least':
      expect(tr!.ignored.length).toBeGreaterThanOrEqual(a.count as number);
      break;
    case 'gap_reasons_include':
      for (const r of a.reasons as string[]) expect(tr!.gaps.some((g) => g.endsWith(`:${r}`)), r).toBe(true);
      break;
    case 'deviation_detected':
      expect(tr!.deviationAt).not.toBeNull();
      break;
    case 'replan_valid':
      expect(tr!.replan!.valid).toBe(true);
      break;
    case 'replan_does_not_claim':
      expect(tr!.replan!.coveredRequirementIds).not.toContain(a.requirementId);
      break;
    case 'eligibility':
      expect(out.graph.eligibility[a.edgeId as string]).toBe(a.value);
      if (a.reason) expect(out.graph.eligibilityReason[a.edgeId as string]).toBe(a.reason);
      break;
    case 'node_passage':
      expect((out.graph.nodePassage as Record<string, string>)[a.nodeId as string]).toBe(a.value);
      break;
    default:
      throw new Error(`unknown assertion ${String(a.kind)}`);
  }
}

describe('contract fixtures', () => {
  for (const f of files) {
    const doc = JSON.parse(readFileSync(join(DIR, f), 'utf8'));
    it(`${doc.fixtureId}: ${doc.title}`, async () => {
      if (doc.kind === 'graph') {
        const out = await runFixture(doc.input);
        const diffs = close(out, doc.expected, doc.tolerances.offsetMeters);
        expect(diffs, diffs.slice(0, 10).join('\n')).toEqual([]);
        for (const a of doc.assertions) checkAssertion(a, out);
        for (const pl of Object.values(out.plans) as PlanOut[]) expect(pl.valid).toBe(true);
      } else if (doc.kind === 'decidePlan') {
        for (const c of doc.cases) expect(decidePlan(c.ctx, c.result), c.name).toEqual(c.expected);
        expect(doc.cases.find((c: { name: string }) => c.name === 'arrives_after_newer_accepted').expected.accept).toBe(false);
      } else if (doc.kind === 'recoverEventLog') {
        for (const c of doc.cases) {
          const r = recoverEventLog(c.sessionId, { checkpoint: null, eventRecords: c.records });
          expect({ eventIds: r.events.map((e) => e.eventId), lastValidSeq: r.lastValidSeq, discarded: r.discarded }, c.name).toEqual(c.expected);
        }
      } else if (doc.kind === 'mapFailure') {
        for (const c of doc.cases) {
          expect(mapFailure(c.input)).toEqual(c.expected);
          expect(c.expected.canStartNavigation).toBe(false);
        }
      } else if (doc.kind === 'portable') {
        const raw = JSON.stringify(doc.referencePackage);
        expect(validateSchema('sessionExportPackage', doc.referencePackage).ok).toBe(true);
        const r = await validateImport(raw, async () => null);
        expect(r.ok, r.errors.join(',')).toBe(true);
        const pkg = r.pkg!;
        const g = r.graph!;
        expect(g.contentHash).toBe(doc.expectedAfterImport.contentHash);
        const prog = applyEvents(emptyProgress(), pkg.events, { sessionId: pkg.session.sessionId, graphHash: g.contentHash, edgeLengths: new Map(g.directedEdges.map((e) => [e.edgeId, e.lengthMeters])) });
        const fwd = (prog.union.get('osm:w80:n1-n2:f') ?? []).map(([a, b]) => [Math.round(a * 100) / 100, Math.round(b * 100) / 100]);
        expect(close(fwd, doc.expectedAfterImport.forwardUnion, 0.5)).toEqual([]);
        expect(prog.union.get('osm:w80:n1-n2:b') ?? []).toEqual([]);
        expect([...prog.exclusions.values()].filter((x) => x.active).map((x) => x.requirementId)).toEqual(doc.expectedAfterImport.activeExclusions);
        expect(prog.status).toBe(doc.expectedAfterImport.status);
        const { statuses } = computeMetrics(pkg.session.requiredIntervals, prog);
        expect(Object.fromEntries(statuses)).toEqual(doc.expectedAfterImport.requirementStatuses);
        for (const c of doc.invalidCases) {
          let text: string;
          if (c.raw !== undefined) text = c.raw;
          else {
            const d = JSON.parse(raw);
            for (const op of c.patch) {
              const parts = op.path.split('/').slice(1);
              let cur = d;
              for (const p of parts.slice(0, -1)) cur = cur[p];
              cur[parts[parts.length - 1]] = op.value;
            }
            text = JSON.stringify(d);
          }
          const res = await validateImport(text, async () => null);
          expect(res.ok, c.name).toBe(false);
          expect(res.errors[0], c.name).toBe(c.expectedFirstError);
        }
      }
    });
  }
});

describe('JCS vectors', () => {
  const vectors = JSON.parse(readFileSync(join(DIR, '../vectors/jcs-vectors.json'), 'utf8'));
  for (const [i, v] of vectors.entries()) {
    it(`vector ${i}`, async () => {
      expect(canonicalize(v.input)).toBe(v.canonical);
      expect(await sha256Hex(v.canonical)).toBe(v.sha256);
    });
  }
  it('rejects non-integer numbers', () => {
    expect(() => canonicalize({ a: 1.5 })).toThrow();
  });
});
