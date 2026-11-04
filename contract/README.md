# Coverage Navigator Shared Contract — v1.0.0 (FROZEN)

This package is the single interchange contract for the web (Vercel) and Android
implementations. The second implementation MUST adopt it unchanged. Any change is a
new contract version.

| Path | Purpose |
|---|---|
| `schema/coverage-contract-1.0.0.schema.json` | JSON Schema (draft 2020-12). `$defs` hold every type: `graphSnapshot`, `coverageSession`, `routePlan`, `sessionEvent`, `sessionExportPackage`, ... |
| `CANONICALIZATION.md` | Graph content-hash rule (RFC 8785 subset + SHA-256) |
| `ALGORITHMS.md` | Normative behaviour (normaliser, zone clipping, planner, matcher, guidance) |
| `CONFORMANCE.md` | How to run fixtures and compare outputs |
| `fixtures/F01..F20*.json` | 20 golden fixtures, revision 2 (inputs + expected outputs + semantic assertions) |
| `vectors/jcs-vectors.json` | Canonical JSON / SHA-256 vectors (incl. Armenian, Persian, escapes, surrogate pairs) |

Reference implementation: `web/src/core/*.ts` (pure TypeScript, no DOM). Where the
prose and the reference implementation differ, the fixtures decide.

Units and conventions
- Coordinates: WGS84 `[longitude, latitude]`, quantised to 1e-7 degrees.
- Distances: meters (float) in documents; integer millimetres (`lengthMm`) for costs and hashing.
- Time: milliseconds since Unix epoch (`timestamp`), ISO-8601 strings for `createdAt`/`fetchedAt`.
- IDs are ASCII and language independent:
  - node `osm:n{nodeId}`
  - physical segment `osm:w{wayId}:n{firstNode}-n{lastNode}` (suffix `~2`, `~3` on collision)
  - directed edge `{physicalSegmentId}:f` (way order) / `:b` (reverse)
  - requirement `{edgeId}#{k}` (k = 0-based order of in-zone interval along the edge)
  - restriction `osm:r{relationId}` (suffix `#k` when one relation maps to several edge sequences)
- Enums and nullability: exactly as in the schema (`additionalProperties: false` everywhere).
- Requirement status precedence: blocked > covered > partial > needsReview > unreachable > unvisited.

Data source (decided by the user on 2026-11-04): OpenStreetMap road graph via Overpass
API (ODbL 1.0, attribution "© OpenStreetMap contributors"), OSM-based raster tiles for
display. This is NOT Yandex data. `providerId` = `osm-overpass` for live data,
`fixture` for synthetic test graphs (`dataState` = `synthetic_test_fixture`).
