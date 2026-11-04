// Graph content hash: SHA-256 (hex, lowercase) of the RFC 8785 canonical JSON of the
// canonical hash document defined in /contract/CANONICALIZATION.md.

import { canonicalize, sha256Hex } from './jcs';
import type { GraphSnapshot } from './types';

const e7 = (v: number) => Math.round(v * 1e7);

export function canonicalHashDocument(g: GraphSnapshot): Record<string, unknown> {
  return {
    schemaVersion: g.schemaVersion,
    providerId: g.providerId,
    normalizerVersion: g.normalizerVersion,
    sourceDatasetVersion: g.sourceDatasetVersion,
    coordinateReferenceSystem: g.coordinateReferenceSystem,
    vehicleProfile: g.vehicleProfile,
    nodes: g.nodes
      .slice()
      .sort((a, b) => (a.nodeId < b.nodeId ? -1 : a.nodeId > b.nodeId ? 1 : 0))
      .map((n) => ({
        nodeId: n.nodeId,
        lonE7: e7(n.position[0]),
        latE7: e7(n.position[1]),
        passage: n.passage,
        barrier: n.barrier,
        turnaround: n.turnaround,
        sourceIds: n.sourceIds,
      })),
    directedEdges: g.directedEdges
      .slice()
      .sort((a, b) => (a.edgeId < b.edgeId ? -1 : a.edgeId > b.edgeId ? 1 : 0))
      .map((e) => ({
        edgeId: e.edgeId,
        physicalSegmentId: e.physicalSegmentId,
        fromNodeId: e.fromNodeId,
        toNodeId: e.toNodeId,
        geometryE7: e.geometry.map((p) => [e7(p[0]), e7(p[1])]),
        lengthMm: e.lengthMm,
        directionIdentity: e.directionIdentity,
        reciprocalEdgeId: e.reciprocalEdgeId,
        sourceFeatureIds: e.sourceFeatureIds,
        names: e.names,
        surface: e.surface,
        roadClass: e.roadClass,
        motorVehicleAccess: e.motorVehicleAccess,
        conditionalAccess: e.conditionalAccess,
        barriers: e.barriers,
        vehicleLimits: e.vehicleLimits,
        eligibility: e.eligibility,
        eligibilityReason: e.eligibilityReason,
        dataConfidence: e.dataConfidence,
      })),
    turnRestrictions: g.turnRestrictions
      .slice()
      .sort((a, b) => (a.restrictionId < b.restrictionId ? -1 : a.restrictionId > b.restrictionId ? 1 : 0))
      .map((r) => ({
        restrictionId: r.restrictionId,
        kind: r.kind,
        sourceType: r.sourceType,
        fromEdgeId: r.fromEdgeId,
        viaNodeId: r.viaNodeId,
        viaEdgeIds: r.viaEdgeIds,
        toEdgeId: r.toEdgeId,
        appliesTo: r.appliesTo,
        condition: r.condition,
      })),
  };
}

export function canonicalGraphBytes(g: GraphSnapshot): string {
  return canonicalize(canonicalHashDocument(g));
}

export async function computeContentHash(g: GraphSnapshot): Promise<string> {
  return sha256Hex(canonicalGraphBytes(g));
}

/** Hash check plus consistency of non-hashed derived fields (lengthMeters, geometry endpoints). */
export async function verifyGraphHash(g: GraphSnapshot): Promise<boolean> {
  if (g.graphId !== `g-${g.contentHash.slice(0, 16)}`) return false;
  const nodes = new Map(g.nodes.map((n) => [n.nodeId, n]));
  for (const e of g.directedEdges) {
    if (Math.abs(e.lengthMeters - e.lengthMm / 1000) > 1e-6) return false;
    const a = nodes.get(e.fromNodeId), b = nodes.get(e.toNodeId);
    if (!a || !b) return false;
    const s = e.geometry[0], t = e.geometry[e.geometry.length - 1];
    if (s[0] !== a.position[0] || s[1] !== a.position[1] || t[0] !== b.position[0] || t[1] !== b.position[1]) return false;
  }
  return (await computeContentHash(g)) === g.contentHash;
}
