# Project rules

- **Web first.** Every feature or fix is implemented in the web app (`web/`) first, tested there,
  and only then ported to the Android app (`android/`). Keep both behaviourally identical.
- The shared contract in `contract/` (schema, fixtures) is authoritative for both apps; the
  reference implementation is `web/src/core`. Changes to core behaviour need regenerated
  fixtures (`cd web && npx tsx scripts/gen-fixtures.ts`) and the Kotlin core must pass them.
- UI language: Persian (RTL) default with English switch.
- Checks before pushing: `cd web && npm run lint && npm run typecheck && npm test && npm run build && npm run test:e2e`;
  `cd android && ./gradlew lint testDebugUnitTest :core:test assembleDebug`.
