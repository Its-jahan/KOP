# Coverage Navigator – Android

Native Kotlin app (Jetpack Compose + MapLibre Native) for the BYD Qin Plus head unit,
implementing the frozen shared contract in `../contract` (fixture revision 3, planner 1.1.0,
`osm-normalizer-1.1.0`, `seq-matcher-1.1.0`).

Status: installable debug build. **Not field-verified**: no emulator or device was available,
so nothing here has run on Android hardware, real GPS, or the BYD head unit.

## Modules
| Path | Contents |
|---|---|
| `core/` | Pure Kotlin/JVM port of `web/src/core` (no Android deps): OSM normaliser, JCS + SHA-256 graph hash, zone clipping, requirements, legal-transition automaton, planner (`legalShortestPath`, `edgeScc`), validator, matcher, guidance, session reducer, `decidePlan`, recovery, failure states, export/import (+ a minimal JSON-Schema validator over the contract schema, packaged from `../contract/schema`). |
| `core/src/test` | `ConformanceTest` (F01–F21 + JCS vectors, same comparisons as `web/tests/unit/conformance.test.ts`), `InvariantsTest` (random graphs, intervals, replay, zones), `PortableCrossPlatformTest` (Android-built F17 export, web package import/re-export), optional `RealDataSmokeTest` (`-PrealOsm=<overpass.json>`). |
| `app/` | Android UI and adapters: Overpass graph fetch (3 endpoints, retries, rejects truncated `remark` replies), Nominatim search (map positioning only), OSM raster tiles with a real User-Agent, SQLite event log + checkpoints, location foreground service (platform `LocationManager`, no Google Play Services), SAF export/import, fa (RTL, default) / en UI. |

## Build
```
./gradlew --version
./gradlew :core:test
./gradlew lint testDebugUnitTest assembleDebug
```
Requirements: JDK 17+ (built with 21), Android SDK platform 35 + build-tools 35.0.0
(`local.properties` → `sdk.dir`). Maven Central is resolved through Google's mirror first
(`settings.gradle.kts`) because `repo.maven.apache.org` rate-limits behind some proxies.

Output: `app/build/outputs/apk/debug/app-debug.apk` (copied to `dist/`).
Toolchain: Gradle 8.14.3 (wrapper), AGP 8.13.0, Kotlin 2.2.21, compileSdk/targetSdk 35,
**minSdk 21** (MapLibre 11.13.5 is the last line supporting API 21; Compose 1.7 / AndroidX
pins also support 21 — chosen because the head unit's Android version is unknown).
Native ABIs: arm64-v8a, armeabi-v7a, x86_64, x86.

## Install (user-controlled sideload)
Copy the APK to the head unit (USB stick or download) and open it with the system package
installer if the unit allows unknown sources, or with authorised USB debugging:
`adb install -r dist/<apk>`. This is a **debug-signed** build: a later release-signed build
cannot update it in place; uninstalling deletes local sessions, so **export sessions first**.

## Field workflow (parked)
1. Search country/city (moves the map only) → draw a polygon (tap corners, drag to adjust,
   undo) or a circle (centre + radius in metres).
2. Fetch road graph (OSM via Overpass; zone bbox + 600 m). Review required / needs-review /
   unreachable counts and the completeness notes in Diagnostics.
3. Pick the start (tap or current GPS), check the snapped road and direction, confirm.
4. Boundary policy: strict inside (default); outside connectors only after a dialog showing
   the outside metres. Preview the route (legs, repeats, unplanned causes).
5. Start navigation → location permission → foreground service. Driving view shows GPS
   state/accuracy/age, next manoeuvre (only with a valid plan and a fresh reliable fix),
   confirmed/total, Pause/Resume, Report blocked, End.
6. After a crash/reboot the app offers to resume: the session reopens **paused**; GPS and a
   fresh plan from the real position start only when you tap Resume.

## Privacy
Location history stays on the device (no backup, no analytics, no upload). Export excludes raw
observations by default. Overpass/Nominatim/OSM tile servers receive only the requested
area, search text, or tile coordinates. Attribution: © OpenStreetMap contributors (ODbL 1.0).

## Known limitations
- No device/emulator run: UI, MapLibre rendering, GPS, foreground service and SQLite paths
  are compiled and lint-checked only.
- The planner is the contract's greedy heuristic; on real central-Yerevan data it leaves a
  large share of a zone unplanned (e.g. 215 of 618 requirements planned for a 300 m strict zone in central Yerevan).
- Overpass public instances can be slow/rate-limited; the app retries and reports failure.
