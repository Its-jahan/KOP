# Della — Street Coverage Navigator

Two implementations of one frozen contract:

| Part | Path | Purpose |
|---|---|---|
| Contract 1.0.0 | `contract/` | JSON Schema, hash rule, algorithms, 18 golden fixtures, JCS vectors |
| Web fallback (Vercel) | `web/` | Phone browser, foreground only. Reference TypeScript core in `web/src/core` |
| Android (head unit) | `android/` | Kotlin app + JVM `core` port that must pass the same fixtures |

## Decisions recorded (user, 2026-11-04)
- Build both: web and Android.
- Road graph: **OpenStreetMap via Overpass API** (ODbL 1.0). Yandex is NOT used: no documented
  Yandex full road-graph export exists, and scraping/mass route probing is forbidden.
- Map display: OSM standard raster tiles (attribution shown, no bulk prefetch).
- UI: Persian (RTL, default) + English switch. Armenian street names shown as in OSM.
- Defaults kept: open route (no return to start unless ticked), boundary policy STRICT_INSIDE,
  passenger car profile (BYD Qin Plus: 1.84 m wide, 1.5 m high).

## How to use (both apps)
1. Search a country/city/place → the map moves there (nothing is selected automatically).
2. Draw the zone: Polygon (tap points, drag to edit, Undo/Clear) or Circle (tap center, set radius
   in meters). Self-intersecting or tiny zones are rejected with a reason.
3. Choose boundary policy. STRICT never leaves the zone; ALLOW shows every outside meter and needs
   explicit approval before driving.
4. Load the road network (OSM, zone + 600 m buffer). Check required km, needs-review km.
5. Choose the start: tap the map or "Use my location"; the app snaps to a legal road in the main
   connected network and shows direction; Flip if needed; Confirm.
6. Plan route → review total, new coverage, repeats, outside, not-plannable (with causes) and
   unverified dead-end turnarounds. Start is disabled with the reason until everything is valid.
7. Drive. Coverage is credited only from reliable GPS movement in the correct direction. Off-route
   driving keeps genuine progress and triggers a replan from the actual position. "Road blocked"
   marks both directions of the current road blocked (never counted as covered) and replans.
8. Pause/Resume; after reload or app kill, "Resume" restores progress from the event log and
   replans from the real position. Export/Import moves a session between phone and head unit
   (manual, no cloud sync).

## Safety and stop conditions
- Operate the screen only when parked or via a passenger; road signs and real conditions override
  the app. "Turn around" at a dead end without a mapped turning circle is shown as
  "only if safe and legal (unverified)".
- Stop the test if: GPS state is not "Reliable" for long periods, the app proposes a maneuver that
  contradicts signs, roads are missing/wrong compared with reality, or progress appears on roads
  you did not drive.

## Evidence (2026-11-05, this repository)
Revision: see `git log` (web/core fixes through planner 1.1.0, fixture revision 3).

| Check | Command | Result |
|---|---|---|
| Web unit + contract fixtures F01–F21, JCS vectors, invariants, IndexedDB, controller regressions, Android→web interop | `cd web && npm test` | 62 passed |
| Web lint / typecheck / production build | `npm run lint`, `npm run typecheck`, `npm run build` | pass |
| Web browser E2E (Playwright, Pixel 7 emulation, **simulated** GPS, mocked Overpass) | `npm run test:e2e` | 6 passed |
| Android Kotlin core (same fixtures F01–F21, hashes + plan sequences exact) | `cd android && ./gradlew :core:test` | 61 run, 0 failed, 1 skipped (optional real-data) |
| Android lint, unit tests, APK | `./gradlew lint testDebugUnitTest assembleDebug` | exit 0 (lint 0 errors, 16 warnings) |
| Independent review (separate agent, did not write the code) | review scripts + E2E | 14 confirmed defects; all fixed and re-verified (see git log 783c312) |

Real OSM data (central Yerevan extract, 4,588 directed edges, 147 turn restrictions, Node 22 on
the build machine — not a phone): normalise ≈1 s, plan ≤0.2 s, validate ≤25 ms for circles of
250–800 m. Typical result for a 300 m strict circle: 618 required directed intervals, 215 planned;
the rest are honestly reported as not plannable (courtyards behind gates/driveways tagged in OSM,
one-way pieces that can only be entered or left outside the zone, needs-review tracks).
Allowing outside connectors raises planned coverage at the cost of disclosed outside travel.

Not tested (no hardware available): any physical phone browser, the BYD head unit, real GPS,
the Android foreground service, MapLibre rendering on Android, screen-off behaviour.

## Android APK (debug, for the controlled test only)
`android/dist/della-coverage-navigator-0.1.0-fieldtest-debug.apk` (not committed; build with
`cd android && ./gradlew assembleDebug`). Package `com.della.coveragenavigator`, versionName
0.1.0-fieldtest, versionCode 1, minSdk 21, targetSdk 35, ABIs arm64-v8a/armeabi-v7a/x86/x86_64,
30,592,617 bytes, SHA-256 `7e4b208233c51b0c3ef486435ddb55583722b5f1691b9cfe7fd2b7a179c90eaa`,
debug-signed (a later release-signed build cannot update it in place: export sessions first,
uninstall, install). Install: copy to USB/phone storage and open it on the head unit if its
installer allows unknown apps, or `adb install -r <apk>` if USB debugging is enabled. Do not bypass
manufacturer security prompts or modify the vehicle.

## Field-test checklist (stop at the first failure)
1. Parked: install/open; grant location; confirm GPS state reaches "Reliable" with accuracy ≤ 15 m.
2. Draw a small familiar zone (2–4 blocks), load roads, confirm start and direction, plan.
3. Export the empty session once (proves export works) and re-import it on the other device.
4. Passenger-operated short drive: one street in each direction. Check the driven direction turns
   green and the opposite direction stays unvisited.
5. Deviate once on purpose (legally): the app must keep progress and replan from your position.
6. Force-close / reload: reopen, tap Resume, verify progress is intact and the route is recomputed.
7. Stop if positioning is unreliable, a maneuver contradicts signs, or roads differ from reality.
