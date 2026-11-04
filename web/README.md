# Coverage Navigator — phone web fallback (Vercel)

Mobile-first TypeScript/React app that plans a legal driving route covering every
eligible road direction inside a user-drawn polygon/circle, records GPS-confirmed
directed progress, and replans after deviations. It is the **foreground-only**
fallback for the Android head-unit app (`../android`). Both share the frozen contract
in `../contract` (schema, hash rule, 18 golden fixtures).

Data decision (made by the user): road graph = **OpenStreetMap via Overpass API**
(ODbL), map tiles = **OpenStreetMap standard raster tiles**. Not Yandex.

## Commands (npm, Node >= 20; tested with Node 22.23)
```
npm ci
npm run lint          # eslint
npm run typecheck     # tsc -b --noEmit
npm test              # vitest: contract fixtures F01–F18, JCS vectors, invariants, IndexedDB
npm run build         # tsc -b && vite build -> dist/
npm run test:e2e      # Playwright (mobile Chromium, simulated GPS, mocked Overpass) against `vite preview`
npm run dev           # local dev server
```

## Architecture
- `src/core/` pure, platform-independent routing core (reference implementation of the contract):
  OSM normaliser (`osm.ts`), canonical hash (`jcs.ts`, `hash.ts`), zone geometry (`zone.ts`),
  requirements/clipping (`requirements.ts`), legal transition automaton incl. via-way
  restrictions (`graph.ts`), coverage planner (`planner.ts`), independent validator
  (`validator.ts`), sequence-aware matcher (`matcher.ts`), guidance/deviation (`guidance.ts`),
  event-log progress (`session.ts`), revision-safe plan acceptance (`revision.ts`),
  recovery (`recovery.ts`), export/import (`portable.ts`).
- `src/worker/` Web Worker for normalisation, requirement clipping and planning. Cancel =
  terminate + recreate the worker.
- `src/storage/db.ts` IndexedDB: graphs, session headers, append-only events; every progress
  write is one transaction; corrupt tails are truncated to the last valid prefix on load.
- `src/providers/` MapDisplayProvider (OSM tiles), RoadGraphProvider (Overpass, 3 public
  endpoints with retry), place search (Nominatim). Capability declarations are explicit.
- `src/ui/` React UI: prepare (search, zone draw/edit, policy, load roads, start snap +
  direction, plan preview), drive (GPS state, next maneuver, progress, pause/resume,
  road-blocked, end), sessions (resume, export/import, delete), settings (FA/EN, storage,
  diagnostics). Persian RTL default, English switch, Armenian names rendered as-is.

## Vercel
- Framework preset: Other (static). Output: `dist`. Node 22.x.
- Git-based project settings: Root Directory `web`, "Include source files outside of the Root
  Directory" ON (the app imports `../contract/schema`), Install `npm ci`, Build `npm run build`.
- No server functions, no environment variables, no secrets. `vercel.json` sets security and
  cache headers. Location tracking runs only in the open browser tab.
- Preview deployments may be behind Vercel Authentication; production URL is public unless
  protection is enabled on the project.

## Browser limits (read before driving)
- HTTPS + an explicit Start tap are required for location. Keep the tab in the foreground and
  the screen on. Screen Wake Lock is requested when supported; otherwise keep the screen on
  manually. Locking the phone, switching apps or calls can stop location; the app records an
  unverified gap and never fills it with route geometry.
- Network is needed for tiles, place search and road data. Saved progress survives without
  network; a loaded graph allows continued matching, but tiles/search/new graphs do not.
- Measured limits: see the top-level README "Evidence" section.
