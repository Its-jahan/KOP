// Generates /contract/fixtures/*.json. Expected outputs come from the reference
// implementation and were reviewed against the hand-written `assertions` in each
// fixture (the assertions encode the contract semantics independently of the code).
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Osm, T0, ll, obs, rect, track } from './fixture-lib';
import { runFixture, type FixtureInput } from '../src/core/conformance';
import { decidePlan } from '../src/core/revision';
import { recoverEventLog } from '../src/core/recovery';
import { mapFailure, type FailureInput } from '../src/core/states';
import { canonicalize, sha256Hex } from '../src/core/jcs';

const here = dirname(fileURLToPath(import.meta.url));
const OUT = join(here, '../../contract/fixtures');
mkdirSync(OUT, { recursive: true });
const RES = { highway: 'residential' };

interface Assertion { kind: string; [k: string]: unknown }
async function emit(id: string, title: string, description: string, input: FixtureInput, assertions: Assertion[]) {
  const expected = await runFixture(input);
  const doc = { fixtureId: id, title, description, contractVersion: '1.0.0', kind: 'graph', input, expected, assertions, tolerances: { offsetMeters: 0.5, totalsMeters: 1.0 } };
  writeFileSync(join(OUT, `${id}.json`), JSON.stringify(doc, null, 1) + '\n');
  return expected;
}
function emitTable(id: string, title: string, description: string, kind: string, cases: unknown[]) {
  writeFileSync(join(OUT, `${id}.json`), JSON.stringify({ fixtureId: id, title, description, contractVersion: '1.0.0', kind, cases }, null, 1) + '\n');
}
const seg = (w: number, a: number, b: number, d: 'f' | 'b') => `osm:w${w}:n${a}-n${b}:${d}`;

// F01 two-way line
{
  const osm = new Osm().node(1, 0, 0).node(2, 200, 0).way(10, [1, 2], RES).json();
  await emit('F01_two_way_line', 'Two-way line', 'One pass credits exactly one direction; the return credits the other.', {
    osm, zone: { type: 'polygon', coordinates: rect(-50, -50, 250, 50) }, policies: ['STRICT_INSIDE'],
    start: { edgeId: seg(10, 1, 2, 'f'), offsetMeters: 0 },
    tracks: [
      { name: 'eastbound', observations: track([[0, 0], [200, 0]], { idPrefix: 'e', speed: 10, accuracy: 5 }) },
      { name: 'east_then_west', observations: track([[0, 0], [200, 0], [0, 0]], { idPrefix: 'r', speed: 10, accuracy: 5 }) },
      { name: 'outlier_reverse', observations: (() => {
        const a = track([[20, 0], [130, 0]], { idPrefix: 'o', speed: 10, accuracy: 5 });
        const [lon, lat] = ll(105, 0);
        const out = obs('o_out', a[a.length - 1].timestamp + 1000, lon, lat, { accuracy: 5, speed: 10, bearing: 90 });
        const b = track([[150, 0], [190, 0]], { idPrefix: 'o2', speed: 10, accuracy: 5, startT: out.timestamp + 1000 });
        return [...a, out, ...b];
      })() },
      { name: 'outlier_reverse_no_heading', observations: (() => {
        const a = track([[20, 0], [130, 0]], { idPrefix: 'q', speed: 10, accuracy: 5, reportBearing: false, reportSpeed: false });
        const [lon, lat] = ll(105, 0);
        const out = obs('q_out', a[a.length - 1].timestamp + 1000, lon, lat, { accuracy: 5, speed: null, bearing: null });
        const b = track([[150, 0], [190, 0]], { idPrefix: 'q2', speed: 10, accuracy: 5, startT: out.timestamp + 1000, reportBearing: false, reportSpeed: false });
        return [...a, out, ...b];
      })() },
    ],
  }, [
    { kind: 'edge_covered_at_most', track: 'outlier_reverse', edgeId: seg(10, 1, 2, 'b'), meters: 0 },
    { kind: 'edge_covered_at_least', track: 'outlier_reverse', edgeId: seg(10, 1, 2, 'f'), meters: 160 },
    { kind: 'edge_covered_at_most', track: 'outlier_reverse_no_heading', edgeId: seg(10, 1, 2, 'b'), meters: 0 },
    { kind: 'plan_covers_all', policy: 'STRICT_INSIDE' },
    { kind: 'edge_covered_at_least', track: 'eastbound', edgeId: seg(10, 1, 2, 'f'), meters: 190 },
    { kind: 'edge_covered_at_most', track: 'eastbound', edgeId: seg(10, 1, 2, 'b'), meters: 0 },
    { kind: 'edge_covered_at_least', track: 'east_then_west', edgeId: seg(10, 1, 2, 'b'), meters: 180 },
  ]);
}

// F02 one-way loop
{
  const osm = new Osm().node(1, 0, 0).node(2, 100, 0).node(3, 100, -100).node(4, 0, -100)
    .way(11, [1, 2], { ...RES, oneway: 'yes' }).way(12, [2, 3], { ...RES, oneway: 'yes' })
    .way(13, [3, 4], { ...RES, oneway: 'yes' }).way(14, [4, 1], { ...RES, oneway: 'yes' }).json();
  await emit('F02_one_way_loop', 'One-way loop', 'No reverse edge is invented and no wrong-way instruction is produced.', {
    osm, zone: { type: 'polygon', coordinates: rect(-50, -150, 150, 50) }, policies: ['STRICT_INSIDE'],
    start: { edgeId: seg(11, 1, 2, 'f'), offsetMeters: 0 },
  }, [
    { kind: 'edge_count', count: 4 },
    { kind: 'no_edge_with_suffix', suffix: ':b' },
    { kind: 'plan_covers_all', policy: 'STRICT_INSIDE' },
    { kind: 'plan_has_no_turnaround', policy: 'STRICT_INSIDE' },
  ]);
}

// F03 branch and dead end
{
  const osm = new Osm().node(1, 0, 0).node(2, 100, 0).node(3, 200, 0).node(4, 100, 80, { highway: 'turning_circle' })
    .way(20, [1, 2, 3], RES).way(21, [2, 4], RES).json();
  await emit('F03_branch_dead_end', 'Branch and dead end', 'Full feasible coverage with necessary repeated travel explicitly counted; turnarounds flagged.', {
    osm, zone: { type: 'polygon', coordinates: rect(-50, -50, 250, 130) }, policies: ['STRICT_INSIDE'],
    start: { edgeId: seg(20, 1, 2, 'f'), offsetMeters: 0 }, maneuvers: true,
  }, [
    { kind: 'plan_covers_all', policy: 'STRICT_INSIDE' },
    { kind: 'plan_repeat_positive', policy: 'STRICT_INSIDE' },
    { kind: 'plan_turnaround_verified_at_node', policy: 'STRICT_INSIDE', nodeId: 'osm:n4' },
  ]);
}

// F04 forbidden turn (via node) and via-way restriction
{
  const osm = new Osm()
    .node(1, 0, 0).node(2, 100, 0).node(3, 200, 0).node(4, 200, 100).node(5, 100, 100).node(6, 100, 200)
    .way(31, [1, 2], RES).way(32, [2, 3], RES).way(33, [2, 5], RES).way(34, [3, 4], RES).way(35, [4, 5], RES).way(36, [5, 6], RES)
    .rel(901, [{ type: 'way', ref: 31, role: 'from' }, { type: 'node', ref: 2, role: 'via' }, { type: 'way', ref: 33, role: 'to' }], { type: 'restriction', restriction: 'no_left_turn' })
    .rel(902, [{ type: 'way', ref: 34, role: 'from' }, { type: 'way', ref: 35, role: 'via' }, { type: 'way', ref: 33, role: 'to' }], { type: 'restriction', restriction: 'no_left_turn' })
    .json();
  await emit('F04_forbidden_turn_via_way', 'Forbidden turn and via-way restriction', 'The geometrically shortest path is rejected when it is illegal.', {
    osm, zone: { type: 'polygon', coordinates: rect(-50, -50, 250, 250) }, policies: ['STRICT_INSIDE'],
    start: { edgeId: seg(31, 1, 2, 'f'), offsetMeters: 0 },
    pathQueries: [
      { name: 'west_to_north', fromPrefix: [seg(31, 1, 2, 'f')], toEdgeId: seg(33, 2, 5, 'f') },
      { name: 'east_via_top_to_center', fromPrefix: [seg(34, 3, 4, 'f')], toEdgeId: seg(33, 2, 5, 'b') },
    ],
  }, [
    { kind: 'path_not_equal', path: 'west_to_north', forbidden: [seg(33, 2, 5, 'f')] },
    { kind: 'path_not_contains_sequence', path: 'east_via_top_to_center', sequence: [seg(35, 4, 5, 'f'), seg(33, 2, 5, 'b')] },
    { kind: 'plan_valid', policy: 'STRICT_INSIDE' },
    { kind: 'plan_not_contains_sequence', policy: 'STRICT_INSIDE', sequence: [seg(31, 1, 2, 'f'), seg(33, 2, 5, 'f')] },
    { kind: 'plan_not_contains_sequence', policy: 'STRICT_INSIDE', sequence: [seg(34, 3, 4, 'f'), seg(35, 4, 5, 'f'), seg(33, 2, 5, 'b')] },
  ]);
}

// F05 disconnected components
{
  const osm = new Osm().node(1, 0, 0).node(2, 150, 0).node(3, 0, 100).node(4, 150, 100)
    .way(40, [1, 2], RES).way(41, [3, 4], RES).json();
  await emit('F05_disconnected_components', 'Disconnected components', 'No fictional connecting line or false completion.', {
    osm, zone: { type: 'polygon', coordinates: rect(-50, -50, 200, 150) }, policies: ['STRICT_INSIDE', 'ALLOW_NECESSARY_CONNECTORS'],
    start: { edgeId: seg(40, 1, 2, 'f'), offsetMeters: 0 },
  }, [
    { kind: 'plan_status', policy: 'STRICT_INSIDE', status: 'partial' },
    { kind: 'plan_never_uses_edge_prefix', policy: 'STRICT_INSIDE', prefix: 'osm:w41:' },
    { kind: 'unreachable_cause', policy: 'STRICT_INSIDE', requirementId: `${seg(41, 3, 4, 'f')}#0`, cause: 'not_reachable_from_start' },
    { kind: 'plan_valid', policy: 'STRICT_INSIDE' },
  ]);
}

// F06 zone-crossing segment with multiple in-zone intervals
{
  const osm = new Osm().node(1, 0, 0).node(2, 300, 0).way(50, [1, 2], RES).json();
  const zone = [ll(-50, -50), ll(350, -50), ll(350, 50), ll(220, 50), ll(220, -20), ll(80, -20), ll(80, 50), ll(-50, 50)];
  await emit('F06_zone_crossing_segment', 'Zone-crossing segment', 'Clipping creates two in-zone intervals per direction and no artificial node, turn or connection.', {
    osm, zone: { type: 'polygon', coordinates: zone }, policies: ['STRICT_INSIDE', 'ALLOW_NECESSARY_CONNECTORS'],
    start: { edgeId: seg(50, 1, 2, 'f'), offsetMeters: 0 },
  }, [
    { kind: 'node_count', count: 2 },
    { kind: 'requirement_count', count: 4 },
    { kind: 'requirement_interval', requirementId: `${seg(50, 1, 2, 'f')}#0`, start: 0, end: 80 },
    { kind: 'requirement_interval', requirementId: `${seg(50, 1, 2, 'f')}#1`, start: 220, end: 300 },
    { kind: 'plan_status', policy: 'STRICT_INSIDE', status: 'failed' },
    { kind: 'plan_covers_all', policy: 'ALLOW_NECESSARY_CONNECTORS' },
    { kind: 'plan_outside_at_least', policy: 'ALLOW_NECESSARY_CONNECTORS', meters: 279 },
  ]);
}

// F07 strict infeasibility vs approved connectors (one-way loop half outside)
{
  const osm = new Osm().node(1, 0, 0).node(2, 200, 0).node(3, 200, -150).node(4, 0, -150)
    .way(61, [1, 2], { ...RES, oneway: 'yes' }).way(62, [2, 3], { ...RES, oneway: 'yes' })
    .way(63, [3, 4], { ...RES, oneway: 'yes' }).way(64, [4, 1], { ...RES, oneway: 'yes' }).json();
  await emit('F07_strict_vs_connectors', 'Strict-inside infeasibility and approved outside connector', 'Policy changes route feasibility, not legal access or direction.', {
    osm, zone: { type: 'polygon', coordinates: rect(-50, -50, 250, 50) }, policies: ['STRICT_INSIDE', 'ALLOW_NECESSARY_CONNECTORS'],
    start: { edgeId: seg(61, 1, 2, 'f'), offsetMeters: 0 },
  }, [
    { kind: 'plan_outside_at_most', policy: 'STRICT_INSIDE', meters: 0 },
    { kind: 'unreachable_cause', policy: 'STRICT_INSIDE', requirementId: `${seg(62, 2, 3, 'f')}#0`, cause: 'outside_policy' },
    { kind: 'plan_covers_all', policy: 'ALLOW_NECESSARY_CONNECTORS' },
    { kind: 'plan_outside_at_least', policy: 'ALLOW_NECESSARY_CONNECTORS', meters: 390 },
    { kind: 'no_edge_with_suffix', suffix: ':b' },
  ]);
}

// F08 parallel roads, overpass, stationary jitter
{
  const osm = new Osm()
    .node(1, -100, 0).node(2, 100, 0).node(3, -100, 12).node(4, 100, 12).node(5, 0, -100).node(6, 0, 100)
    .way(71, [1, 2], RES).way(72, [3, 4], RES).way(73, [5, 6], { ...RES, bridge: 'yes', layer: '1' }).json();
  const jitter: ReturnType<typeof obs>[] = [];
  const offs = [0, 3, -2, 4, -4, 1, -3, 2, 0, -1, 4, -2];
  offs.forEach((dx, k) => {
    const [lon, lat] = ll(-50 + dx, 0);
    jitter.push(obs(`j${k}`, T0 + k * 1000, lon, lat, { accuracy: 8, speed: 0, bearing: null }));
  });
  const jitterNoSpeed = offs.map((dx, k) => {
    const [lon, lat] = ll(-50 + dx, 0);
    return obs(`n${k}`, T0 + k * 1000, lon, lat, { accuracy: 8, speed: null, bearing: null });
  });
  await emit('F08_parallel_overpass_jitter', 'Parallel roads, overpass and stationary jitter', 'No false segment or direction completion.', {
    osm, zone: { type: 'polygon', coordinates: rect(-150, -150, 150, 150) }, policies: [], start: null,
    tracks: [
      { name: 'between_parallel', observations: track([[-90, 6], [90, 6]], { idPrefix: 'p', speed: 10, accuracy: 10 }) },
      { name: 'over_bridge', observations: track([[0, -90], [0, 90]], { idPrefix: 'b', speed: 10, accuracy: 5 }) },
      { name: 'stationary_jitter', observations: jitter },
      { name: 'stationary_jitter_no_speed', observations: jitterNoSpeed },
      { name: 'switch_to_parallel', observations: (() => {
        const [lon, lat] = ll(-90, 2);
        const first = obs('s0', T0, lon, lat, { accuracy: 10, speed: 10, bearing: 90 });
        return [first, ...track([[-80, 12], [90, 12]], { idPrefix: 's', speed: 10, accuracy: 10, startT: T0 + 1000 })];
      })() },
      { name: 'slow_drift_no_speed', observations: Array.from({ length: 40 }, (_, k) => {
        const [lon, lat] = ll(-60 + k * 0.55, (k % 3) - 1);
        return obs(`dr${k}`, T0 + k * 1000, lon, lat, { accuracy: 8, speed: null, bearing: null });
      }) },
    ],
  }, [
    { kind: 'edge_covered_at_least', track: 'switch_to_parallel', edgeId: seg(72, 3, 4, 'f'), meters: 120 },
    { kind: 'edge_covered_at_most', track: 'switch_to_parallel', edgeId: seg(71, 1, 2, 'f'), meters: 0 },
    { kind: 'track_total_at_most', track: 'slow_drift_no_speed', meters: 0 },
    { kind: 'track_total_at_most', track: 'between_parallel', meters: 0 },
    { kind: 'edge_covered_at_least', track: 'over_bridge', edgeId: seg(73, 5, 6, 'f'), meters: 170 },
    { kind: 'track_only_edges', track: 'over_bridge', edgeIds: [seg(73, 5, 6, 'f')] },
    { kind: 'track_total_at_most', track: 'stationary_jitter', meters: 0 },
    { kind: 'track_total_at_most', track: 'stationary_jitter_no_speed', meters: 0 },
  ]);
}

// F09 partial edge, overlaps, duplicates
{
  const osm = new Osm().node(1, 0, 0).node(2, 300, 0).way(80, [1, 2], RES).json();
  const a = track([[0, 0], [120, 0]], { idPrefix: 'a', speed: 10, accuracy: 5 });
  const b = track([[80, 0], [200, 0]], { idPrefix: 'b', speed: 10, accuracy: 5, startT: T0 + 60_000 });
  const dup = a.slice(3, 6).map((o) => ({ ...o }));
  await emit('F09_partial_overlap_duplicates', 'Partial edge, overlapping passes and duplicates', 'Union is correct and idempotent; partial traversal earns partial length only.', {
    osm, zone: { type: 'polygon', coordinates: rect(-50, -50, 350, 50) }, policies: [], start: null,
    tracks: [{ name: 'two_passes_with_duplicates', observations: [...a, ...dup, ...b, ...b.slice(0, 4)] }],
  }, [
    { kind: 'edge_covered_between', track: 'two_passes_with_duplicates', edgeId: seg(80, 1, 2, 'f'), min: 190, max: 201 },
    { kind: 'edge_covered_at_most', track: 'two_passes_with_duplicates', edgeId: seg(80, 1, 2, 'b'), meters: 0 },
    { kind: 'ignored_count_at_least', track: 'two_passes_with_duplicates', count: 7 },
  ]);
}

// F10 GPS outage, stale, jump, poor accuracy, delayed timestamps
{
  const osm = new Osm().node(1, 0, 0).node(2, 800, 0).way(90, [1, 2], RES).json();
  const at = (id: string, x: number, t: number, o: { accuracy?: number; ageMs?: number; y?: number } = {}) => {
    const [lon, lat] = ll(x, o.y ?? 0);
    return obs(id, T0 + t, lon, lat, { accuracy: o.accuracy ?? 5, speed: 10, bearing: 90, ageMs: o.ageMs });
  };
  const p1 = track([[0, 0], [100, 0]], { idPrefix: 'a', speed: 10, accuracy: 5 }); // t 0..10 s
  const p2 = track([[300, 0], [400, 0]], { idPrefix: 'c', speed: 10, accuracy: 5, startT: T0 + 31_000 }); // outage 10..31 s
  const delayed = at('late1', 350, 36_000); // timestamp older than last fix
  const poor = [at('poor0', 410, 42_000, { accuracy: 60 }), at('poor1', 420, 43_000, { accuracy: 60 }), at('poor2', 430, 44_000, { accuracy: 45 })];
  const p3 = track([[440, 0], [500, 0]], { idPrefix: 'g', speed: 10, accuracy: 5, startT: T0 + 45_000 }); // 45..51 s
  const stale = at('stale1', 510, 52_000, { ageMs: 20_000 });
  const p4 = track([[520, 0], [560, 0]], { idPrefix: 'h', speed: 10, accuracy: 5, startT: T0 + 53_000 }); // 53..57 s
  const jump = at('jump1', 570, 58_000, { y: 400 });
  await emit('F10_gps_failures', 'GPS outage, stale fix, jump, poor accuracy, delayed timestamps', 'No fabricated progress across gaps; every failure ends the current matched run.', {
    osm, zone: { type: 'polygon', coordinates: rect(-50, -50, 850, 50) }, policies: [], start: null,
    tracks: [
      { name: 'faulty', observations: [...p1, ...p2, delayed, ...poor, ...p3, stale, ...p4, jump] },
      { name: 'future_timestamp', observations: (() => {
        const a = track([[0, 0], [60, 0]], { idPrefix: 'fa', speed: 10, accuracy: 5 });
        const [lon, lat] = ll(65, 0);
        const fut = { ...obs('fut1', T0 + 60_000 + 7_000, lon, lat, { accuracy: 5, speed: 10, bearing: 90 }), ageMs: -60_000 };
        const b = track([[70, 0], [300, 0]], { idPrefix: 'fb', speed: 10, accuracy: 5, startT: T0 + 7_000 });
        return [...a, fut, ...b];
      })() },
    ],
  }, [
    { kind: 'edge_covered_at_least', track: 'future_timestamp', edgeId: seg(90, 1, 2, 'f'), meters: 250 },
    { kind: 'gap_reasons_include', track: 'future_timestamp', reasons: ['stale'] },
    { kind: 'edge_not_covered_in', track: 'faulty', edgeId: seg(90, 1, 2, 'f'), start: 105, end: 295 },
    { kind: 'edge_not_covered_in', track: 'faulty', edgeId: seg(90, 1, 2, 'f'), start: 402, end: 438 },
    { kind: 'edge_not_covered_in', track: 'faulty', edgeId: seg(90, 1, 2, 'f'), start: 502, end: 518 },
    { kind: 'edge_not_covered_in', track: 'faulty', edgeId: seg(90, 1, 2, 'f'), start: 562, end: 800 },
    { kind: 'gap_reasons_include', track: 'faulty', reasons: ['gap', 'accuracy', 'stale', 'jump'] },
    { kind: 'ignored_count_at_least', track: 'faulty', count: 1 },
  ]);
}

// F11 deviation over an unplanned road
{
  const osm = new Osm().node(1, 0, 0).node(2, 100, 0).node(3, 200, 0).node(4, 100, 100).node(5, 200, 100)
    .way(101, [1, 2, 3], RES).way(102, [2, 4], RES).way(103, [3, 5], RES).way(104, [4, 5], RES).json();
  const input: FixtureInput = {
    osm, zone: { type: 'polygon', coordinates: rect(-50, -50, 250, 150) }, policies: ['STRICT_INSIDE'],
    start: { edgeId: seg(101, 1, 2, 'f'), offsetMeters: 0 },
  };
  const probe = await runFixture(input);
  const seq = (probe.plans.STRICT_INSIDE as { edgeSequence: string[] }).edgeSequence;
  // The plan continues A->B->C first; the driver turns north at B instead.
  if (seq[1] !== seg(101, 2, 3, 'f')) throw new Error(`F11 premise changed: ${seq.slice(0, 3).join(' ')}`);
  input.tracks = [{ name: 'turn_north_at_B', guidancePolicy: 'STRICT_INSIDE', observations: track([[0, 0], [100, 0], [100, 100]], { idPrefix: 'd', speed: 8, accuracy: 5 }) }];
  await emit('F11_deviation_unplanned_road', 'Deviation over an unplanned road', 'Genuine progress is retained and the reroute excludes covered work.', input, [
    { kind: 'deviation_detected', track: 'turn_north_at_B' },
    { kind: 'edge_covered_at_least', track: 'turn_north_at_B', edgeId: seg(102, 2, 4, 'f'), meters: 40 },
    { kind: 'replan_valid', track: 'turn_north_at_B' },
    { kind: 'replan_does_not_claim', track: 'turn_north_at_B', requirementId: `${seg(101, 1, 2, 'f')}#0` },
  ]);
}

// F19 only_* restriction whose to-way passes through the via node
{
  const osm = new Osm().node(1, 0, 0).node(2, 100, 0).node(3, 200, 0).node(4, 100, -100).node(5, 0, -100).node(6, 200, -100)
    .way(401, [1, 2, 3], RES).way(402, [4, 2], RES).way(403, [5, 4, 6], RES).way(404, [1, 5], RES).way(405, [3, 6], RES)
    .rel(951, [{ type: 'way', ref: 402, role: 'from' }, { type: 'node', ref: 2, role: 'via' }, { type: 'way', ref: 401, role: 'to' }], { type: 'restriction', restriction: 'only_right_turn' })
    .json();
  await emit('F19_only_restriction_through_via', 'only_* restriction with a to-way passing through the junction', 'The branch matching the restriction direction stays the only legal exit.', {
    osm, zone: { type: 'polygon', coordinates: rect(-50, -150, 250, 50) }, policies: ['STRICT_INSIDE'],
    start: { edgeId: seg(402, 4, 2, 'f'), offsetMeters: 0 },
    pathQueries: [{ name: 'north_then_west', fromPrefix: [seg(402, 4, 2, 'f')], toEdgeId: seg(401, 1, 2, 'b') }],
  }, [
    { kind: 'path_not_equal', path: 'north_then_west', forbidden: [seg(401, 1, 2, 'b')] },
    { kind: 'plan_not_contains_sequence', policy: 'STRICT_INSIDE', sequence: [seg(402, 4, 2, 'f'), seg(401, 1, 2, 'b')] },
    { kind: 'plan_valid', policy: 'STRICT_INSIDE' },
  ]);
}

// F20 no interpolation across a long gap around a corner
{
  const osm = new Osm().node(1, 0, 0).node(2, 100, 0).node(3, 100, 70).node(4, 250, 70)
    .way(501, [1, 2], RES).way(502, [2, 3], RES).way(503, [3, 4], RES).json();
  const a = track([[0, 0], [90, 0]], { idPrefix: 'a', speed: 10, accuracy: 5 });
  const b = track([[110, 70], [240, 70]], { idPrefix: 'b', speed: 10, accuracy: 5, startT: a[a.length - 1].timestamp + 11_000 });
  await emit('F20_gap_corner', 'No guessed traversal through a long gap', 'An 11 s gap around a corner leaves the unobserved street uncredited.', {
    osm, zone: { type: 'polygon', coordinates: rect(-50, -50, 300, 120) }, policies: [], start: null,
    tracks: [{ name: 'gap_around_corner', observations: [...a, ...b] }],
  }, [
    { kind: 'edge_covered_at_most', track: 'gap_around_corner', edgeId: seg(502, 2, 3, 'f'), meters: 0 },
    { kind: 'gap_reasons_include', track: 'gap_around_corner', reasons: ['gap'] },
  ]);
}

// F21 irreversible exit: prefer the downstream block that keeps the most work reachable
{
  const osm = new Osm()
    .node(1, 0, 0).node(2, 100, 0)
    .node(3, 130, 30).node(4, 180, 30)
    .node(5, 200, -60).node(6, 300, -60).node(7, 400, -60)
    .way(601, [1, 2], RES)
    .way(602, [2, 3], { ...RES, oneway: 'yes' }).way(603, [3, 4], RES)
    .way(604, [2, 5], { ...RES, oneway: 'yes' }).way(605, [5, 6], RES).way(606, [6, 7], RES)
    .json();
  await emit('F21_irreversible_exit_choice', 'Irreversible exit choice between one-way branches', 'The nearer branch is a small sink; the planner must enter the branch whose downstream chain holds more remaining work.', {
    osm, zone: { type: 'polygon', coordinates: rect(-50, -120, 450, 80) }, policies: ['STRICT_INSIDE'],
    start: { edgeId: seg(601, 1, 2, 'b'), offsetMeters: 0 },
  }, [
    { kind: 'plan_status', policy: 'STRICT_INSIDE', status: 'partial' },
    { kind: 'plan_valid', policy: 'STRICT_INSIDE' },
    { kind: 'unreachable_cause', policy: 'STRICT_INSIDE', requirementId: `${seg(603, 3, 4, 'f')}#0`, cause: 'not_reachable_from_start' },
    { kind: 'plan_never_uses_edge_prefix', policy: 'STRICT_INSIDE', prefix: 'osm:w602:' },
  ]);
}

// F22 lane indications (turn:lanes) per travel direction
{
  const osm = new Osm().node(1, 0, 0).node(2, 150, 0).node(3, 150, 100).node(4, 300, 0).node(5, 150, -100)
    .way(701, [1, 2], { highway: 'secondary', lanes: '3', 'turn:lanes:forward': 'left|through|through;right', 'turn:lanes:backward': 'left;through|right' })
    .way(702, [2, 3], { highway: 'secondary', oneway: 'yes', 'turn:lanes': 'left|right' })
    .way(703, [4, 2], { highway: 'secondary', oneway: '-1', 'turn:lanes': 'through|weird_value' })
    .way(704, [2, 5], { highway: 'residential', 'turn:lanes': 'left|right' })
    .json();
  await emit('F22_turn_lanes', 'Lane indications per travel direction', 'turn:lanes:forward/backward map to the matching directed edge; plain turn:lanes only on one-way roads; unknown values become none.', {
    osm, zone: { type: 'polygon', coordinates: rect(-50, -150, 350, 150) }, policies: [], start: null,
  }, [
    { kind: 'turn_lanes', edgeId: seg(701, 1, 2, 'f'), value: 'left|through|through;right' },
    { kind: 'turn_lanes', edgeId: seg(701, 1, 2, 'b'), value: 'left;through|right' },
    { kind: 'turn_lanes', edgeId: seg(702, 2, 3, 'f'), value: 'left|right' },
    { kind: 'turn_lanes', edgeId: seg(703, 4, 2, 'b'), value: 'through|none' },
    { kind: 'turn_lanes', edgeId: seg(704, 2, 5, 'f'), value: null },
  ]);
}

// F12 stale planning response
emitTable('F12_stale_plan_response', 'Stale planning response', 'A slow older result never replaces a newer plan.', 'decidePlan',
  [
    { name: 'newest_request', ctx: { graphHash: 'h', zoneVersion: 1, latestRequestedPlanningRevision: 5, acceptedPlanningRevision: 4, progressRevision: 10 }, result: { graphHash: 'h', zoneVersion: 1, planningRevision: 5, basedOnProgressRevision: 10, status: 'complete' } },
    { name: 'superseded_by_newer_request', ctx: { graphHash: 'h', zoneVersion: 1, latestRequestedPlanningRevision: 6, acceptedPlanningRevision: 4, progressRevision: 10 }, result: { graphHash: 'h', zoneVersion: 1, planningRevision: 5, basedOnProgressRevision: 9, status: 'complete' } },
    { name: 'arrives_after_newer_accepted', ctx: { graphHash: 'h', zoneVersion: 1, latestRequestedPlanningRevision: 6, acceptedPlanningRevision: 6, progressRevision: 12 }, result: { graphHash: 'h', zoneVersion: 1, planningRevision: 5, basedOnProgressRevision: 9, status: 'complete' } },
    { name: 'progress_advanced_during_compute', ctx: { graphHash: 'h', zoneVersion: 1, latestRequestedPlanningRevision: 7, acceptedPlanningRevision: 6, progressRevision: 15 }, result: { graphHash: 'h', zoneVersion: 1, planningRevision: 7, basedOnProgressRevision: 12, status: 'partial' } },
    { name: 'graph_mismatch', ctx: { graphHash: 'h', zoneVersion: 1, latestRequestedPlanningRevision: 7, acceptedPlanningRevision: 6, progressRevision: 15 }, result: { graphHash: 'other', zoneVersion: 1, planningRevision: 7, basedOnProgressRevision: 15, status: 'complete' } },
    { name: 'zone_mismatch', ctx: { graphHash: 'h', zoneVersion: 2, latestRequestedPlanningRevision: 7, acceptedPlanningRevision: 6, progressRevision: 15 }, result: { graphHash: 'h', zoneVersion: 1, planningRevision: 7, basedOnProgressRevision: 15, status: 'complete' } },
    { name: 'cancelled', ctx: { graphHash: 'h', zoneVersion: 1, latestRequestedPlanningRevision: 7, acceptedPlanningRevision: 6, progressRevision: 15 }, result: { graphHash: 'h', zoneVersion: 1, planningRevision: 7, basedOnProgressRevision: 15, status: 'cancelled' } },
  ].map((c) => ({ ...c, expected: decidePlan(c.ctx, c.result) })));

// F13 process kill during write
{
  const ev = (seq: number, id = `e${seq}`, sid = 's1') => JSON.stringify({ eventId: id, seq, sessionId: sid, type: 'traversal_confirmed', timestamp: T0 + seq, schemaVersion: '1.0.0', payload: {} });
  const cases = [
    { name: 'clean_log', records: [ev(1), ev(2), ev(3)] },
    { name: 'torn_last_record', records: [ev(1), ev(2), ev(3).slice(0, 25)] },
    { name: 'duplicate_replayed_event', records: [ev(1), ev(2), ev(2), ev(3)] },
    { name: 'sequence_gap_then_more', records: [ev(1), ev(2), ev(4), ev(5)] },
    { name: 'foreign_session_record', records: [ev(1), ev(1, 'x1', 's2'), ev(2)] },
    { name: 'invalid_record_shape', records: [ev(1), '{"eventId":"e2"}', ev(3)] },
  ];
  emitTable('F13_process_kill_recovery', 'Process kill or reload while writing', 'Recover the last valid state without corrupting the session.', 'recoverEventLog',
    cases.map((c) => {
      const r = recoverEventLog('s1', { checkpoint: null, eventRecords: c.records });
      return { ...c, sessionId: 's1', expected: { eventIds: r.events.map((e) => e.eventId), lastValidSeq: r.lastValidSeq, discarded: r.discarded } };
    }));
}

// F14 unpaved legal road vs private / barrier / footpath
{
  const osm = new Osm()
    .node(1, 0, 0).node(2, 100, 0).node(3, 200, 0).node(4, 100, 100).node(5, 0, 100).node(6, 200, 100)
    .node(7, 300, 0, { barrier: 'bollard' }).node(8, 400, 0).node(9, 300, 100, { barrier: 'gate' }).node(10, 400, 100)
    .way(201, [1, 2], { highway: 'residential', surface: 'unpaved' })
    .way(202, [2, 4], { highway: 'service', access: 'private' })
    .way(203, [2, 3], { highway: 'footway' })
    .way(204, [4, 5], { highway: 'track' })
    .way(205, [4, 6], { highway: 'track', motor_vehicle: 'yes', surface: 'dirt' })
    .way(206, [3, 7, 8], { highway: 'service' })
    .way(207, [6, 9, 10], { highway: 'service' })
    .way(208, [1, 5], { highway: 'unclassified', maxwidth: '1.5' })
    .json();
  await emit('F14_eligibility', 'Unpaved legal road vs private access, barrier and footpath', 'Eligibility differs for the documented reason.', {
    osm, zone: { type: 'polygon', coordinates: rect(-50, -50, 450, 150) }, policies: [], start: null,
  }, [
    { kind: 'eligibility', edgeId: seg(201, 1, 2, 'f'), value: 'eligible' },
    { kind: 'eligibility', edgeId: seg(202, 2, 4, 'f'), value: 'excluded', reason: 'access_private' },
    { kind: 'eligibility', edgeId: seg(203, 2, 3, 'f'), value: 'excluded', reason: 'not_car_road_footway' },
    { kind: 'eligibility', edgeId: seg(204, 4, 5, 'f'), value: 'needsReview', reason: 'track_car_access_unverified' },
    { kind: 'eligibility', edgeId: seg(205, 4, 6, 'f'), value: 'eligible' },
    { kind: 'eligibility', edgeId: seg(208, 1, 5, 'f'), value: 'excluded', reason: 'vehicle_limit_width' },
    { kind: 'node_passage', nodeId: 'osm:n7', value: 'blocked' },
    { kind: 'node_passage', nodeId: 'osm:n9', value: 'unverified' },
  ]);
}

// F15 conditional access and unknown dead-end turnaround
{
  const osm = new Osm().node(1, 0, 0).node(2, 100, 0).node(3, 200, 0).node(4, 100, 100).node(5, 100, -80, { highway: 'turning_circle' })
    .way(301, [1, 2], RES).way(302, [2, 3], RES)
    .way(303, [2, 4], { highway: 'residential', 'motor_vehicle:conditional': 'no @ (Mo-Fr 07:00-19:00)' })
    .way(304, [2, 5], RES).json();
  await emit('F15_conditional_and_turnaround', 'Unknown/conditional access and unverified dead-end turnaround', 'No confident illegal maneuver; turnarounds are flagged verified/unverified.', {
    osm, zone: { type: 'polygon', coordinates: rect(-50, -130, 250, 150) }, policies: ['STRICT_INSIDE'],
    start: { edgeId: seg(301, 1, 2, 'f'), offsetMeters: 0 }, maneuvers: true,
  }, [
    { kind: 'eligibility', edgeId: seg(303, 2, 4, 'f'), value: 'needsReview', reason: 'conditional_access' },
    { kind: 'plan_never_uses_edge_prefix', policy: 'STRICT_INSIDE', prefix: 'osm:w303:' },
    { kind: 'plan_turnaround_verified_at_node', policy: 'STRICT_INSIDE', nodeId: 'osm:n5' },
    { kind: 'plan_has_unverified_turnaround', policy: 'STRICT_INSIDE' },
    { kind: 'plan_valid', policy: 'STRICT_INSIDE' },
  ]);
}

// F16 failure states
{
  const cases: FailureInput[] = [
    { kind: 'location_permission_denied' }, { kind: 'location_disabled' }, { kind: 'location_unavailable' }, { kind: 'location_timeout' },
    { kind: 'credentials_missing' }, { kind: 'provider_not_configured' }, { kind: 'graph_http', status: 429 }, { kind: 'graph_http', status: 403 },
    { kind: 'graph_http', status: 504 }, { kind: 'graph_network_error' }, { kind: 'graph_empty' }, { kind: 'graph_incomplete' }, { kind: 'graph_too_large', edges: 90000, limit: 60000 },
  ];
  emitTable('F16_failure_states', 'Permission, location, key, quota, area and network failures', 'Clear non-success states; navigation cannot start.', 'mapFailure',
    cases.map((input) => ({ input, expected: mapFailure(input) })));
}

// F18 large graph: budget + cancellation (generator, not stored geometry)
{
  const n = 30;
  const o = new Osm();
  let nid = 1;
  const id = (i: number, j: number) => i * n + j + 1;
  for (let i = 0; i < n; i++) for (let j = 0; j < n; j++) o.node(id(i, j), j * 80, i * 80);
  nid = 10000;
  for (let i = 0; i < n; i++) for (let j = 0; j < n - 1; j++) o.way(nid++, [id(i, j), id(i, j + 1)], RES);
  for (let j = 0; j < n; j++) for (let i = 0; i < n - 1; i++) o.way(nid++, [id(i, j), id(i + 1, j)], RES);
  const input: FixtureInput = {
    osm: o.json(), zone: { type: 'polygon', coordinates: rect(-40, -40, (n - 1) * 80 + 40, (n - 1) * 80 + 40) }, policies: ['STRICT_INSIDE'],
    start: { edgeId: `osm:w10000:n1-n2:f`, offsetMeters: 0 }, plannerLimits: { maxIterations: 2000 },
  };
  await emit('F18_large_grid_budget', 'Large graph budget and cancellation', 'Bounded computation returns an explicitly partial plan that identifies the unplanned remainder.', input, [
    { kind: 'plan_status', policy: 'STRICT_INSIDE', status: 'partial' },
    { kind: 'plan_valid', policy: 'STRICT_INSIDE' },
    { kind: 'unreachable_cause_present', policy: 'STRICT_INSIDE', cause: 'budget_exceeded' },
  ]);
}

// Hash vectors
{
  const docs: unknown[] = [
    { b: 1, a: [true, false, null], c: 'x' },
    { name: 'Աբովյան փողոց', fa: 'خیابان', esc: 'q"\\\n\t\u0001', emoji: '🚗' },
    { nested: { z: 1, y: { b: -2, a: 0 } }, big: 9007199254740991 },
  ];
  const vectors = [];
  for (const d of docs) {
    const c = canonicalize(d);
    vectors.push({ input: d, canonical: c, sha256: await sha256Hex(c) });
  }
  mkdirSync(join(OUT, '../vectors'), { recursive: true });
  writeFileSync(join(OUT, '../vectors/jcs-vectors.json'), JSON.stringify(vectors, null, 1) + '\n');
}
console.log('fixtures written to', OUT);

// F17 portable export/import (reference package produced by the web implementation)
{
  const { buildExport, validateImport } = await import('../src/core/portable');
  const { normalizeOverpass } = await import('../src/core/osm');
  const { GraphIndex } = await import('../src/core/graph');
  const { computeRequirements } = await import('../src/core/requirements');
  const { initialMatcherState, processObservation } = await import('../src/core/matcher');
  const { applyEvents, emptyProgress, sessionDocument } = await import('../src/core/session');
  const osm = new Osm().node(1, 0, 0).node(2, 300, 0).way(80, [1, 2], RES).json();
  const g = await normalizeOverpass(osm, { fetchedAt: '1970-01-01T00:00:00.000Z', dataState: 'synthetic_test_fixture', providerId: 'fixture' });
  const gi = new GraphIndex(g);
  const zone = { type: 'polygon' as const, coordinates: rect(-50, -50, 350, 50) };
  const req = computeRequirements(gi, zone);
  const sessionId = 'sess-fixture-17';
  const events: import('../src/core/types').SessionEvent[] = [];
  let seq = 0;
  const push = (type: import('../src/core/types').SessionEventType, payload: Record<string, unknown>, ts: number) =>
    events.push({ eventId: `${sessionId}:${++seq}`, seq, sessionId, type, timestamp: ts, schemaVersion: '1.0.0', payload });
  push('session_created', {}, T0);
  push('start_confirmed', {}, T0 + 1);
  let st = initialMatcherState();
  for (const o of track([[0, 0], [130, 0]], { idPrefix: 'x', speed: 10, accuracy: 5 })) {
    const r = processObservation(gi, st, o, o.timestamp);
    st = r.state;
    for (const t of r.traversals) push('traversal_confirmed', { traversal: t }, o.timestamp);
  }
  push('requirement_excluded', { requirementIds: ['osm:w80:n1-n2:b#0'], reason: 'blocked', note: 'construction' }, T0 + 20_000);
  push('paused', {}, T0 + 21_000);
  const prog = applyEvents(emptyProgress(), events, { sessionId, graphHash: g.contentHash, edgeLengths: new Map(g.directedEdges.map((e) => [e.edgeId, e.lengthMeters])) });
  const session = sessionDocument({
    schemaVersion: '1.0.0', sessionId, graphId: g.graphId, contentHash: g.contentHash, zone: { zoneId: 'zone-17', zoneVersion: 1, definition: zone },
    vehicleProfile: 'passenger_car', boundaryPolicy: 'STRICT_INSIDE', returnToStart: false,
    startSelection: { requested: ll(0, 0), snapped: { edgeId: 'osm:w80:n1-n2:f', offsetMeters: 0, position: ll(0, 0), distanceMeters: 0 }, confirmed: true },
    createdAt: '1970-01-01T00:00:00.000Z',
  }, req.requirements, prog);
  const pkg = buildExport(g, session, events, { platform: 'web', appVersion: '0.1.0', includeGraph: true, includeObservations: false, exportedAt: '1970-01-01T00:00:00.000Z' });
  const raw = JSON.stringify(pkg);
  const ok = await validateImport(raw, async () => null);
  if (!ok.ok) throw new Error('F17 reference export invalid: ' + ok.errors.join(','));
  const invalid = [
    { name: 'wrong_contract_version', patch: [{ op: 'replace', path: '/contractVersion', value: '2.0.0' }] },
    { name: 'graph_hash_tampered', patch: [{ op: 'replace', path: '/graph/directedEdges/0/lengthMm', value: 1 }] },
    { name: 'session_graph_mismatch', patch: [{ op: 'replace', path: '/session/contentHash', value: '0'.repeat(64) }] },
    { name: 'impossible_offset', patch: [{ op: 'replace', path: '/session/confirmedTraversals/0/endOffsetMeters', value: 5000 }] },
    { name: 'unexpected_crs', patch: [{ op: 'replace', path: '/graph/coordinateReferenceSystem', value: 'EPSG:3857' }] },
    { name: 'missing_graph_unknown_on_device', patch: [{ op: 'replace', path: '/graph', value: null }] },
    { name: 'non_monotonic_events', patch: [{ op: 'replace', path: '/events/1/seq', value: 1 }] },
    { name: 'length_meters_tampered', patch: [{ op: 'replace', path: '/graph/directedEdges/0/lengthMeters', value: 1234.5 }] },
    { name: 'requirements_tampered', patch: [{ op: 'replace', path: '/session/requiredIntervals/0/endOffsetMeters', value: 130 }] },
    { name: 'event_traversal_impossible_offset', patch: [{ op: 'replace', path: `/events/${events.findIndex((e) => e.type === 'traversal_confirmed')}/payload/traversal/endOffsetMeters`, value: 99999 }] },
  ];
  const applyPatch = (doc: unknown, ops: { op: string; path: string; value: unknown }[]) => {
    const d = JSON.parse(JSON.stringify(doc));
    for (const op of ops) {
      const parts = op.path.split('/').slice(1);
      let cur = d;
      for (const p of parts.slice(0, -1)) cur = cur[p];
      cur[parts[parts.length - 1]] = op.value;
    }
    return d;
  };
  const cases = [];
  for (const c of invalid) {
    const r = await validateImport(JSON.stringify(applyPatch(pkg, c.patch as { op: string; path: string; value: unknown }[])), async () => null);
    if (r.ok) throw new Error(`F17 invalid case accepted: ${c.name}`);
    cases.push({ ...c, expectedFirstError: r.errors[0] });
  }
  cases.push({ name: 'corrupt_json', raw: raw.slice(0, 100), expectedFirstError: 'corrupt_json' } as never);
  const coveredF = session.confirmedTraversals.filter((t) => t.edgeId === 'osm:w80:n1-n2:f');
  writeFileSync(join(OUT, 'F17_portable_roundtrip.json'), JSON.stringify({
    fixtureId: 'F17_portable_roundtrip', title: 'Portable Android/web import/export',
    description: 'Directions, partial intervals, exclusions, graph fingerprint and progress remain unchanged through export -> import -> re-export.',
    contractVersion: '1.0.0', kind: 'portable',
    referencePackage: pkg,
    expectedAfterImport: {
      contentHash: g.contentHash,
      forwardUnion: prog.union.get('osm:w80:n1-n2:f')?.map(([a, b]) => [Math.round(a * 100) / 100, Math.round(b * 100) / 100]) ?? [],
      backwardUnion: [],
      activeExclusions: ['osm:w80:n1-n2:b#0'],
      requirementStatuses: Object.fromEntries(session.requiredIntervals.map((r) => [r.requirementId, r.status])),
      traversalCount: coveredF.length,
      status: 'paused',
    },
    invalidCases: cases,
  }, null, 1) + '\n');
}
console.log('F17 written');
