# Normative algorithms (contract 1.0.0)

The reference implementation is `web/src/core/`. Ports must reproduce it exactly where
fixtures compare exactly (graph, plans, matcher outputs). Constants:

| Constant | Value | Source |
|---|---|---|
| Earth radius (haversine) | 6371008.8 m | geo.ts |
| Circle approximation tolerance (sagitta) | 1.0 m, 16..720 vertices, inscribed | zone.ts |
| Boundary inclusion tolerance | 0.01 m (boundary counts as inside) | zone.ts |
| Minimum required interval | 1.0 m (shorter in-zone slivers ignored and counted) | requirements.ts |
| STRICT_INSIDE traversable edge outside length | <= 2.0 m | requirements.ts |
| Requirement covered | uncovered <= min(4.0 m, 25 % of its length) | requirements.ts |
| Outside cost factor | edge cost + 9 x outside mm | planner.ts |
| Turn penalties (mm) | straight(<30°) 0, right 5000, left 15000, sharp(>=150°) 30000, U-turn verified 60000, unverified 100000 | planner.ts |
| Planner budget | 200000 settled states, 20 s (web/Android UI) | planner.ts |
| Matcher | maxAccuracy 30 m, maxFixAge 10 s, maxFuture 5 s (clock skew → stale), maxGap 5 s (gap ends the run, no interpolation), jump speed 50 m/s, junction plausibility speed 30 m/s, search radius clamp(1.5*acc+5, 12, 45), minAdvance max(3, 0.5*acc) or max(3, acc) when the fix has no speed, commit 12 m, ambiguity margin 6 m, junction path <= 80 m and <= 4 intermediate edges, moving if (fix speed, else position speed over >= 3 s) >= 1.0 m/s, heading used if speed >= 3 m/s (tolerance 60°, penalty 15), a committed run flips direction only after 2 consecutive reverse fixes and never against a usable heading, an unconnected road closer by > 6 m for 2 consecutive fixes drops the anchor | matcher.ts |
| Guidance | window = current step + next + steps within 120 m (max 12), 3 committed off-route fixes, off-route distance 25 m, offset slack 10 m | guidance.ts |

## 1. OSM normalisation (osm.ts)
- Fetch highways matching `OVERPASS_HIGHWAY_REGEX` plus their nodes and `type=restriction*` relations.
- Graph nodes: way endpoints, nodes shared by >1 way (or repeated in a way), nodes with a `barrier` tag.
  Ways are split into physical segments between graph nodes. Closed loops without an inner graph
  node are split at the middle node.
- Directions (`wayDirections`): `oneway[:motorcar|:motor_vehicle|:vehicle]` yes/true/1 → forward only;
  -1/reverse → backward only; roundabout/circular junctions and motorway(_link) imply forward.
  No reverse edge is ever invented.
- Eligibility (`classifyWay`, first match wins): area=yes → excluded `highway_area`; lifecycle
  values → `not_in_service`; non-car classes (footway, path, pedestrian, steps, ...) excluded
  `not_car_road_{class}` unless motorcar/motor_vehicle/vehicle explicitly allowed; unknown class
  → `not_a_road_{class}`; access (motorcar > motor_vehicle > vehicle > access) no/private →
  excluded `access_{v}`; other non-allow values → needsReview `restricted_access_{v}`;
  reversible/alternating oneway → excluded; maxwidth/maxheight below_default → needsReview;
  vehicle limits below the passenger-car profile (1.84 m wide, 1.5 m high, 2.0 t) → excluded;
  service=parking_aisle|drive-through|emergency_access → excluded `service_{v}`;
  service=driveway without explicit yes/permissive/designated access → needsReview;
  track without explicit allow → needsReview `track_car_access_unverified`; highway=road →
  needsReview; any `*:conditional` access → needsReview `conditional_access`;
  `oneway:conditional` → needsReview. Unpaved surface alone never excludes.
- Node passage: barrier with node access no/private → blocked; explicit allow (not destination) → free;
  kerb, cattle_grid, height_restrictor, entrance, toll_booth, border_control → free; bollard, block,
  chain, jersey_barrier, ... → blocked; gates → unverified; other barrier values → unverified.
- Turn restrictions: `restriction:motorcar` > `restriction` > `restriction:motor_vehicle`, or
  `restriction:conditional` (applied always, condition kept). `except` containing motorcar or
  motor_vehicle → ignored. Via node: every from-way edge ending at the via node × every to-way
  edge starting there. Via way(s): BFS along via-way edges (max 20) from each from-edge end to a
  to-edge start. Unmappable relations are counted in `completenessNotes`. When an `only_right_turn`,
  `only_left_turn`, `only_straight_on` or `only_u_turn` relation yields several to-edges for the same
  from/via (to-way passing through the junction), only the to-edge whose turn angle is closest to
  +90/-90/0/180° is kept. `no_*` keeps all candidates (conservative).
- Missing node references split a way into separate runs; nodes are never joined across a gap,
  and the snapshot `dataState` becomes `incomplete`.

## 2. Legal transitions (graph.ts)
- State = directed edge plus the longest suffix of the driven edge sequence that is a proper
  prefix (length >= 2) of a forbidden sequence (automaton key = comma-joined edge indices of
  that suffix, indices = position in edgeId-sorted array).
- Forbidden sequences: `no` → [from, via..., to]; `only` → [from, via..., X] for every X != the
  allowed to-edge(s) of the same relation/from/via group.
- Transition e→j allowed iff j accepted by the caller's filter, the shared node passage is `free`
  (except U-turns), the move does not complete a forbidden sequence, and U-turns (same physical
  segment) only when e is a dead end: its end node is not free or has no other *eligible*
  outgoing edge on a different physical segment. Turnaround is `verified` iff the node is tagged
  turning_circle/turning_loop.

## 3. Requirements (requirements.ts)
Required intervals = in-zone parts (closed polygon, clipped in a local tangent plane; offsets
along haversine length) of every non-excluded directed edge; both directions use the forward
edge's clipping mirrored. Ends within 1 cm snap to 0/L. needsReview edges create requirements
with status `needsReview` (never planned). Boundary clipping never creates nodes or connections.

## 4. Planner (planner.ts)
Deterministic greedy nearest-feasible requirement with legal shortest-path connectors
(`legalShortestPath`: Dijkstra over states, integer mm costs, ties by cost → edgeId → automaton
key; equal-cost relaxations keep the first parent). Traversable edges: eligible, not user-blocked,
and (STRICT_INSIDE) outside length <= 2 m. Edge-level SCCs are computed; while the current SCC
still has remaining requirements only targets in that SCC are accepted. When it has none (leaving
is irreversible), `sccChainValues` scores every SCC as remaining requirement edges in it + the best
score among SCCs directly reachable from it (condensation DAG, sinks first); the target is the
nearest remaining edge whose SCC has the best score among those reachable from the current edge
(edge-level BFS). Fallback in every case: nearest remaining edge. Planned coverage merges observed
and planned intervals with `unionIntervals` before measuring (no double counting). Each
traversed edge marks all requirements it completes (incidental coverage counts). Start: the start
edge from the start offset to its end. Optional return-to-start. Unplanned requirements are listed
with causes; status `partial` when anything is `not_reachable_from_start` or the budget is hit,
`failed` when the start edge is not traversable. A user-blocked start edge may be used for the start
step only (the vehicle is already on it). Plans never claim optimality.

## 6. Graph verification and import
`verifyGraphHash` additionally requires `lengthMeters == lengthMm/1000` and edge geometry endpoints
equal to their node positions; all computations use `lengthMm`. Import recomputes the required
intervals from the session zone and graph and rejects any mismatch (`requirements_mismatch`), and
checks every traversal (session and events) and exclusion reference.

## 5. Matcher (matcher.ts) and guidance (guidance.ts)
See source; the fixtures F01, F08–F11 pin behaviour. Credit only after 12 m of consistent
movement; direction from movement along geometry (heading only as a penalty); gaps, stale,
inaccurate, jumping, ambiguous or off-graph fixes end the current run and are recorded as gaps;
pending (uncommitted) movement is discarded on a gap.
