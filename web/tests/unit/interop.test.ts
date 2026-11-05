// Cross-platform interoperability: an export produced by the Android (Kotlin) core must import
// on web with identical graph hash, directions, partial intervals, exclusions and status.
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { validateImport } from '../../src/core/portable';
import { applyEvents, computeMetrics, emptyProgress } from '../../src/core/session';

const ANDROID = join(__dirname, '../../../android/dist/F17_export_android.json');
const REF = join(__dirname, '../../../contract/fixtures/F17_portable_roundtrip.json');

describe('Android export -> web import', () => {
  it.skipIf(!existsSync(ANDROID))('imports with identical progress semantics', async () => {
    const raw = readFileSync(ANDROID, 'utf8');
    const ref = JSON.parse(readFileSync(REF, 'utf8'));
    const r = await validateImport(raw, async () => null);
    expect(r.ok, r.errors.join(',')).toBe(true);
    expect(r.pkg!.exporter.platform).toBe('android');
    expect(r.graph!.contentHash).toBe(ref.expectedAfterImport.contentHash);
    const prog = applyEvents(emptyProgress(), r.pkg!.events, { sessionId: r.pkg!.session.sessionId, graphHash: r.graph!.contentHash, edgeLengths: new Map(r.graph!.directedEdges.map((e) => [e.edgeId, e.lengthMm / 1000])) });
    const fwd = (prog.union.get('osm:w80:n1-n2:f') ?? []).map(([a, b]) => [Math.round(a * 100) / 100, Math.round(b * 100) / 100]);
    expect(fwd.length).toBe(ref.expectedAfterImport.forwardUnion.length);
    fwd.forEach(([a, b], i) => {
      expect(Math.abs(a - ref.expectedAfterImport.forwardUnion[i][0])).toBeLessThan(0.5);
      expect(Math.abs(b - ref.expectedAfterImport.forwardUnion[i][1])).toBeLessThan(0.5);
    });
    expect(prog.union.get('osm:w80:n1-n2:b') ?? []).toEqual([]);
    expect([...prog.exclusions.values()].filter((x) => x.active).map((x) => x.requirementId)).toEqual(ref.expectedAfterImport.activeExclusions);
    expect(prog.status).toBe(ref.expectedAfterImport.status);
    expect(Object.fromEntries(computeMetrics(r.pkg!.session.requiredIntervals, prog).statuses)).toEqual(ref.expectedAfterImport.requirementStatuses);
  });
});
