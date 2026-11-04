# Graph content hash (contract 1.0.0)

`contentHash = lowercase_hex(SHA-256(UTF-8(JCS(hashDocument))))`
`graphId = "g-" + contentHash[0:16]`

JCS = RFC 8785 with one restriction: **every number in the hash document is a safe
integer** (|n| <= 2^53-1). Implementations MUST reject non-integers, so no float
formatting is involved. `-0` serialises as `0`.

String escaping follows ECMAScript `JSON.stringify` (well-formed variant): `\"`, `\\`,
`\b`, `\f`, `\n`, `\r`, `\t`; other code units < 0x20 as `\u00xx` (lowercase hex); lone
surrogates as `\udxxx` (lowercase); everything else literal (UTF-8 on the wire).
Object keys are sorted by UTF-16 code units (Kotlin `String.compareTo` / JS `<`).
`undefined` members are omitted; `null` is kept.

## hashDocument
```
{
  schemaVersion, providerId, normalizerVersion, sourceDatasetVersion,
  coordinateReferenceSystem, vehicleProfile,
  nodes: sorted by nodeId → { nodeId, lonE7, latE7, passage, barrier, turnaround, sourceIds },
  directedEdges: sorted by edgeId → {
    edgeId, physicalSegmentId, fromNodeId, toNodeId,
    geometryE7: [[lonE7, latE7], ...], lengthMm, directionIdentity, reciprocalEdgeId,
    sourceFeatureIds, names, surface, roadClass, motorVehicleAccess, conditionalAccess,
    barriers, vehicleLimits: { maxWidthCm, maxHeightCm, maxWeightKg },
    eligibility, eligibilityReason, dataConfidence
  },
  turnRestrictions: sorted by restrictionId → {
    restrictionId, kind, sourceType, fromEdgeId, viaNodeId, viaEdgeIds, toEdgeId, appliesTo, condition
  }
}
```
`lonE7 = round(lon * 1e7)` (round half up, i.e. `Math.round` / `java.lang.Math.round`).
Excluded (volatile) fields: `graphId`, `contentHash`, `fetchedAt`, `geographicExtent`,
`attribution`, `capabilities`, `freshness`, `completenessNotes`, `dataState`,
`provenance` (edge/restriction), `lengthMeters` (derived from `lengthMm`).

Vectors: `vectors/jcs-vectors.json` (input, canonical string, sha256). Every graph
fixture lists its expected `contentHash`; both platforms must reproduce it exactly.
