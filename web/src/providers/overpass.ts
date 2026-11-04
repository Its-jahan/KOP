// RoadGraphProvider: OpenStreetMap via public Overpass API instances (user-approved).
// Requests are made from the browser; no credentials are involved.
import { OVERPASS_HIGHWAY_REGEX, type OverpassResponse } from '../core/osm';
import type { FailureInput } from '../core/states';

export const OVERPASS_ENDPOINTS = [
  'https://overpass-api.de/api/interpreter',
  'https://maps.mail.ru/osm/tools/overpass/api/interpreter',
  'https://overpass.kumi.systems/api/interpreter',
];

export const ROAD_GRAPH_CAPABILITIES = {
  providerId: 'osm-overpass',
  fullRoutableGraph: true,
  turnRestrictions: true,
  liveClosures: false,
  traffic: false,
  license: 'ODbL 1.0',
};

export class GraphFetchError extends Error {
  constructor(public readonly failure: FailureInput, message: string) {
    super(message);
  }
}

export function buildOverpassQuery(b: { west: number; south: number; east: number; north: number }): string {
  const bb = `${b.south.toFixed(6)},${b.west.toFixed(6)},${b.north.toFixed(6)},${b.east.toFixed(6)}`;
  return `[out:json][timeout:120][maxsize:268435456];way["highway"~"${OVERPASS_HIGHWAY_REGEX}"](${bb})->.w;(.w;.w>;);out body;rel(bw.w)["type"~"^restriction"];out body;`;
}

export async function fetchRoadGraph(
  bbox: { west: number; south: number; east: number; north: number },
  signal: AbortSignal,
  onAttempt?: (endpoint: string, attempt: number) => void,
): Promise<{ data: OverpassResponse; endpoint: string }> {
  const query = buildOverpassQuery(bbox);
  let last: FailureInput = { kind: 'graph_network_error' };
  for (let round = 0; round < 2; round++) {
    for (const ep of OVERPASS_ENDPOINTS) {
      if (signal.aborted) throw new GraphFetchError({ kind: 'graph_network_error' }, 'aborted');
      onAttempt?.(ep, round + 1);
      const ctl = new AbortController();
      const onAbort = () => ctl.abort();
      signal.addEventListener('abort', onAbort);
      const timer = setTimeout(() => ctl.abort(), 150_000);
      try {
        const res = await fetch(ep, {
          method: 'POST',
          body: new URLSearchParams({ data: query }),
          signal: ctl.signal,
        });
        if (!res.ok) {
          last = { kind: 'graph_http', status: res.status };
          continue;
        }
        const data = (await res.json()) as OverpassResponse & { remark?: string };
        if (!data || !Array.isArray(data.elements)) {
          last = { kind: 'graph_http', status: 502 };
          continue;
        }
        // Overpass reports runtime errors (timeouts, memory) as HTTP 200 with a remark and a
        // truncated element list. Never treat that as a complete graph.
        if (typeof data.remark === 'string' && /error|timed out|out of memory/i.test(data.remark)) {
          last = { kind: 'graph_incomplete' };
          continue;
        }
        if (!data.elements.some((e) => e.type === 'way')) throw new GraphFetchError({ kind: 'graph_empty' }, 'no roads');
        return { data, endpoint: ep };
      } catch (e) {
        if (e instanceof GraphFetchError) throw e;
        last = ctl.signal.aborted && !signal.aborted ? { kind: 'graph_timeout' } : { kind: 'graph_network_error' };
      } finally {
        clearTimeout(timer);
        signal.removeEventListener('abort', onAbort);
      }
    }
    await new Promise((r) => setTimeout(r, 3000));
  }
  throw new GraphFetchError(last, 'all endpoints failed');
}
