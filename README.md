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
