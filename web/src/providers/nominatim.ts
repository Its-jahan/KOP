// Place search (positions the map only; never selects a coverage zone).
export interface PlaceResult {
  displayName: string;
  lon: number;
  lat: number;
  bbox: [number, number, number, number]; // west, south, east, north
  type: string;
}

export async function searchPlace(q: string, lang: string, signal?: AbortSignal): Promise<PlaceResult[]> {
  const url = new URL('https://nominatim.openstreetmap.org/search');
  url.searchParams.set('format', 'jsonv2');
  url.searchParams.set('q', q);
  url.searchParams.set('limit', '6');
  url.searchParams.set('accept-language', `${lang},en`);
  const res = await fetch(url, { signal, headers: { Accept: 'application/json' } });
  if (!res.ok) throw new Error(`search_http_${res.status}`);
  const arr = (await res.json()) as { display_name: string; lon: string; lat: string; boundingbox: string[]; type: string }[];
  return arr.map((r) => ({
    displayName: r.display_name,
    lon: Number(r.lon),
    lat: Number(r.lat),
    bbox: [Number(r.boundingbox[2]), Number(r.boundingbox[0]), Number(r.boundingbox[3]), Number(r.boundingbox[1])],
    type: r.type,
  }));
}
