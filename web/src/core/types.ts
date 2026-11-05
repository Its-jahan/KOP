// Canonical coverage-navigator data model, contract version 1.0.0.
// The JSON Schema in /contract/schema is authoritative; these types mirror it.

export const SCHEMA_VERSION = '1.0.0';
export const NORMALIZER_VERSION = 'osm-normalizer-1.1.0';
export const PLANNER_ALGORITHM = 'greedy-nearest-legal';
export const PLANNER_VERSION = '1.1.0';
export const MATCHER_VERSION = 'seq-matcher-1.1.0';

/** WGS84 [longitude, latitude], quantised to 1e-7 degrees. */
export type LonLat = [number, number];

export type VehicleProfileId = 'passenger_car';
export type BoundaryPolicy = 'STRICT_INSIDE' | 'ALLOW_NECESSARY_CONNECTORS';
export type Eligibility = 'eligible' | 'excluded' | 'needsReview';
export type NodePassage = 'free' | 'blocked' | 'unverified';
export type Turnaround = 'turning_circle' | 'turning_loop' | 'none';
export type DirectionIdentity = 'forward' | 'backward';

export type DataState =
  | 'not_configured'
  | 'credentials_missing'
  | 'entitlement_unverified'
  | 'area_unsupported'
  | 'fetching'
  | 'available'
  | 'stale'
  | 'incomplete'
  | 'failed'
  | 'synthetic_test_fixture';

export interface VehicleProfile {
  id: VehicleProfileId;
  widthMeters: number;
  heightMeters: number;
  weightTonnes: number;
}

export const PASSENGER_CAR: VehicleProfile = {
  id: 'passenger_car',
  widthMeters: 1.84,
  heightMeters: 1.5,
  weightTonnes: 2.0,
};

export interface GeographicExtent {
  west: number;
  south: number;
  east: number;
  north: number;
}

export interface GraphCapabilities {
  turnRestrictions: boolean;
  viaWayRestrictions: boolean;
  conditionalRestrictions: 'applied_conservatively';
  barriers: boolean;
  turnaroundData: boolean;
  liveClosures: boolean;
  traffic: boolean;
}

export interface GraphNode {
  nodeId: string;
  position: LonLat;
  passage: NodePassage;
  barrier: string | null;
  turnaround: Turnaround;
  sourceIds: string[];
}

export interface VehicleLimits {
  maxWidthCm: number | null;
  maxHeightCm: number | null;
  maxWeightKg: number | null;
}

export interface DirectedEdge {
  edgeId: string;
  physicalSegmentId: string;
  fromNodeId: string;
  toNodeId: string;
  geometry: LonLat[];
  lengthMeters: number;
  lengthMm: number;
  directionIdentity: DirectionIdentity;
  reciprocalEdgeId: string | null;
  sourceFeatureIds: string[];
  names: Record<string, string>;
  surface: string | null;
  roadClass: string;
  motorVehicleAccess: string;
  conditionalAccess: string | null;
  barriers: string[];
  vehicleLimits: VehicleLimits;
  eligibility: Eligibility;
  eligibilityReason: string | null;
  dataConfidence: 'high' | 'medium' | 'low';
  provenance: string;
  /** Lane turn indications in travel direction, left to right (OSM turn:lanes); absent if unmapped. */
  turnLanes?: string[][];
}

export interface TurnRestriction {
  restrictionId: string;
  kind: 'no' | 'only';
  sourceType: string;
  fromEdgeId: string;
  viaNodeId: string | null;
  viaEdgeIds: string[];
  toEdgeId: string;
  appliesTo: VehicleProfileId[];
  condition: string | null;
  provenance: string;
}

export interface GraphSnapshot {
  schemaVersion: string;
  graphId: string;
  contentHash: string;
  providerId: string;
  normalizerVersion: string;
  sourceDatasetVersion: string;
  fetchedAt: string;
  geographicExtent: GeographicExtent;
  coordinateReferenceSystem: 'EPSG:4326';
  attribution: string;
  capabilities: GraphCapabilities;
  freshness: { sourceTimestamp: string; note: string };
  completenessNotes: string[];
  dataState: DataState;
  vehicleProfile: VehicleProfileId;
  nodes: GraphNode[];
  directedEdges: DirectedEdge[];
  turnRestrictions: TurnRestriction[];
}

export type ZoneDefinition =
  | { type: 'polygon'; coordinates: LonLat[] }
  | {
      type: 'circle';
      center: LonLat;
      radiusMeters: number;
      /** Reproducible polygon approximation (closed ring not repeated). */
      approximation?: { vertices: number; toleranceMeters: number; coordinates: LonLat[] };
    };

export interface Zone {
  zoneId: string;
  zoneVersion: number;
  definition: ZoneDefinition;
}

export type RequirementStatus =
  | 'unvisited'
  | 'partial'
  | 'covered'
  | 'blocked'
  | 'unreachable'
  | 'needsReview';

export interface RequiredInterval {
  requirementId: string;
  edgeId: string;
  startOffsetMeters: number;
  endOffsetMeters: number;
  lengthMeters: number;
  status: RequirementStatus;
  exclusionReason: string | null;
}

export interface LocationObservation {
  observationId: string;
  timestamp: number; // wall clock, ms since epoch
  monotonicMs: number | null;
  lon: number;
  lat: number;
  horizontalAccuracyMeters: number | null;
  speedMps: number | null;
  speedAccuracyMps: number | null;
  bearingDeg: number | null;
  bearingAccuracyDeg: number | null;
  altitudeMeters: number | null;
  altitudeAccuracyMeters: number | null;
  source: 'gps' | 'network' | 'fused' | 'browser' | 'simulated_test';
  ageMs: number | null;
}

export interface MatchedTraversal {
  traversalId: string;
  observationIds: string[];
  edgeId: string;
  startOffsetMeters: number;
  endOffsetMeters: number;
  confidence: number;
  evidence: 'continuous' | 'junction_interpolated';
  timestampStart: number;
  timestampEnd: number;
  manuallyReviewed: boolean;
}

export type StepKind = 'required' | 'transit' | 'outside' | 'approach';

export interface PlanStep {
  edgeId: string;
  fromOffsetMeters: number;
  toOffsetMeters: number;
  kind: StepKind;
  newlyCoveredRequirementIds: string[];
  outsideMeters: number;
  turnaroundAtStart: 'verified' | 'unverified' | null;
}

export interface PlanLeg {
  kind: StepKind;
  firstStep: number;
  lastStep: number;
  lengthMeters: number;
}

export interface UnreachableRequirement {
  requirementId: string;
  cause:
    | 'outside_policy'
    | 'not_reachable_from_start'
    | 'needs_review'
    | 'blocked_by_user'
    | 'budget_exceeded';
}

export interface PlanTotals {
  totalMeters: number;
  newCoverageMeters: number;
  repeatMeters: number;
  outsideMeters: number;
  approachMeters: number;
}

export type PlanStatus = 'complete' | 'partial' | 'cancelled' | 'failed' | 'no_requirements';

export interface StartState {
  edgeId: string;
  offsetMeters: number;
  source: 'selected_start' | 'gps_position';
}

export interface RoutePlan {
  planId: string;
  graphHash: string;
  zoneVersion: number;
  basedOnProgressRevision: number;
  planningRevision: number;
  algorithm: string;
  algorithmVersion: string;
  createdAt: string;
  startState: StartState;
  steps: PlanStep[];
  legs: PlanLeg[];
  coveredRequirementIds: string[];
  unreachable: UnreachableRequirement[];
  totals: PlanTotals;
  limits: { maxIterations: number; timeBudgetMs: number; iterationsUsed: number };
  warnings: string[];
  status: PlanStatus;
}

export type SessionStatus = 'preparing' | 'ready' | 'active' | 'paused' | 'ended';

export interface ExclusionRecord {
  requirementId: string;
  reason: 'blocked' | 'inaccessible' | 'uncertain';
  note: string | null;
  at: number;
  active: boolean;
}

export interface StartSelection {
  requested: LonLat;
  snapped: { edgeId: string; offsetMeters: number; position: LonLat; distanceMeters: number } | null;
  confirmed: boolean;
}

export interface CoverageSession {
  schemaVersion: string;
  sessionId: string;
  graphId: string;
  contentHash: string;
  zone: Zone;
  vehicleProfile: VehicleProfileId;
  boundaryPolicy: BoundaryPolicy;
  returnToStart: boolean;
  startSelection: StartSelection;
  createdAt: string;
  status: SessionStatus;
  planningRevision: number;
  progressRevision: number;
  requiredIntervals: RequiredInterval[];
  confirmedTraversals: MatchedTraversal[];
  exclusions: ExclusionRecord[];
  routePlan: RoutePlan | null;
  eventLogPosition: number;
}

export type SessionEventType =
  | 'session_created'
  | 'start_confirmed'
  | 'plan_accepted'
  | 'observation'
  | 'traversal_confirmed'
  | 'gap_recorded'
  | 'requirement_excluded'
  | 'requirement_restored'
  | 'paused'
  | 'resumed'
  | 'ended';

export interface SessionEvent {
  eventId: string;
  seq: number;
  sessionId: string;
  type: SessionEventType;
  timestamp: number;
  schemaVersion: string;
  payload: Record<string, unknown>;
}
