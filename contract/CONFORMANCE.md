# Conformance (contract 1.0.0)

Each `fixtures/*.json` has a `kind`:

- `graph`: run the pipeline on `input` and compare to `expected`:
  1. normalise `input.osm` (`fetchedAt` = `1970-01-01T00:00:00.000Z`, `providerId` = `fixture`,
     `dataState` = `synthetic_test_fixture`) → compare `expected.graph` (hash, counts, eligibility,
     reasons, non-free node passage, restrictions) exactly;
  2. compute requirements for `input.zone` → `expected.requirements` (offsets ±0.5 m);
  3. for each policy in `input.policies`, plan from `input.start` (planningRevision 1,
     progressRevision 0, no observations, no exclusions, `maxIterations` from
     `input.plannerLimits` or 200000, no time limit) → `expected.plans[policy]`:
     `status`, `edgeSequence`, `stepKinds`, `turnarounds`, `coveredRequirementIds`,
     `unreachable` must match **exactly**; `totals` ±1.0 m; `valid` must be true;
     `maneuvers` exactly when present;
  4. `pathQueries`: legal shortest path (eligible edges only, no zone penalty) → `expected.paths` exactly;
  5. `tracks`: feed observations in order to the matcher with `now = observation.timestamp`
     (or `nowMs`) and default matcher config; union confirmed traversals per edge (dedupe
     by traversalId) → `unionByEdge` (±0.5 m), `traversalCount`, `qualities`, `gaps`,
     `ignored` exactly. When `guidancePolicy` is set, run guidance on that policy's plan;
     on the first `deviation` replan from the matched edge/offset (startPrefix = [edge],
     planningRevision 2, progressRevision = observation index) → `deviationAt`, `replan` exactly.
  6. every entry in `assertions` must hold (semantic checks independent of the reference code).
- `decidePlan`, `recoverEventLog`, `mapFailure`: table cases; output must equal `expected`.
- `portable`: `referencePackage` must validate and import; derived progress must equal
  `expectedAfterImport`; each `invalidCases[i]` (JSON-pointer `replace` patches applied to
  the reference package, or `raw` text) must be rejected with first error `expectedFirstError`.

Cross-platform interoperability is UNVERIFIED until an Android-produced export imports on
web and a web-produced export imports on Android with identical hash, directions, partial
intervals and exclusions (see `F17_portable_roundtrip.json` for the reference package).

Reference runner: `web/src/core/conformance.ts`, test: `web/tests/unit/conformance.test.ts`.
