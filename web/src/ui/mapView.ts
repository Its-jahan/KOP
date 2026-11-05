// Imperative MapLibre wrapper: zone editing handles, directed coverage styling,
// planned route, position and start markers.
import maplibregl, { type GeoJSONSource, type Map as MlMap, type Marker } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { osmRasterStyle } from '../providers/mapDisplay';
import type { LonLat } from '../core/types';

type FC = GeoJSON.FeatureCollection;
const empty = (): FC => ({ type: 'FeatureCollection', features: [] });

/** Shared palette (keep in sync with the CSS tokens in styles.css). */
export const PALETTE = {
  todo: '#ff5a1f',
  done: '#12a150',
  partial: '#f4b400',
  blocked: '#6b7280',
  unreach: '#8e5cc4',
  review: '#a0785a',
  route: '#1a73e8',
  routeCasing: '#0b3d91',
  plan: '#1a73e8',
  outside: '#c2185b',
  zone: '#1a73e8',
  me: '#1a73e8',
};

export const STATUS_COLORS: Record<string, string> = {
  unvisited: PALETTE.todo,
  partial: PALETTE.partial,
  covered: PALETTE.done,
  blocked: PALETTE.blocked,
  unreachable: PALETTE.unreach,
  needsReview: PALETTE.review,
};

export class MapView {
  readonly map: MlMap;
  private ready: Promise<void>;
  private vertexMarkers: Marker[] = [];
  private centerMarker: Marker | null = null;
  private startMarker: Marker | null = null;
  private clickHandler: ((p: LonLat) => void) | null = null;
  private userPanHandler: (() => void) | null = null;

  constructor(container: HTMLElement) {
    // Start from the last viewed area (per device) or a neutral world view; never a fixed city.
    let center: LonLat = [20, 30];
    let zoom = 1.5;
    try {
      const v = JSON.parse(localStorage.getItem('mapView') ?? 'null');
      if (v && Array.isArray(v.c) && typeof v.z === 'number') {
        center = v.c;
        zoom = v.z;
      }
    } catch {
      /* storage unavailable */
    }
    this.map = new maplibregl.Map({
      container,
      style: osmRasterStyle(),
      center,
      zoom,
      attributionControl: { compact: true },
      fadeDuration: 0,
      dragRotate: false,
      pitchWithRotate: false,
    });
    this.map.touchZoomRotate.disableRotation();
    this.map.addControl(new maplibregl.ScaleControl({ unit: 'metric' }), 'bottom-left');
    this.map.on('click', (e) => this.clickHandler?.([e.lngLat.lng, e.lngLat.lat]));
    this.map.on('dragstart', () => this.userPanHandler?.());
    this.map.on('moveend', () => {
      try {
        const c = this.map.getCenter();
        localStorage.setItem('mapView', JSON.stringify({ c: [c.lng, c.lat], z: this.map.getZoom() }));
      } catch {
        /* ignore */
      }
    });
    this.ready = new Promise((res) => this.map.on('load', () => {
      this.setup();
      res();
    }));
  }

  whenReady() {
    return this.ready;
  }

  onClick(h: ((p: LonLat) => void) | null) {
    this.clickHandler = h;
  }

  onUserPan(h: (() => void) | null) {
    this.userPanHandler = h;
  }

  /** Driving camera: heading-up, tilted, vehicle in the lower part of the screen. */
  follow(p: LonLat, heading: number | null, driving: boolean) {
    const h = this.map.getContainer().clientHeight;
    this.map.easeTo({
      center: p,
      zoom: driving ? 17.3 : Math.max(this.map.getZoom(), 16),
      bearing: driving && heading !== null ? heading : this.map.getBearing(),
      pitch: driving ? 45 : 0,
      padding: driving ? { top: h * 0.4, bottom: 0, left: 0, right: 0 } : { top: 0, bottom: 0, left: 0, right: 0 },
      duration: 800,
    });
  }

  /** De-emphasise the coverage network while driving so the guided route stands out. */
  setDriving(driving: boolean) {
    const m = this.map;
    if (!m.getLayer('reqs-line')) return;
    for (const id of ['reqs-line', 'reqs-line-dashed', 'covered-line']) m.setPaintProperty(id, 'line-opacity', driving ? 0.45 : 1);
    m.setLayoutProperty('reqs-arrows', 'visibility', driving ? 'none' : 'visible');
    m.setPaintProperty('route-line', 'line-opacity', driving ? 0.15 : 0.35);
    if (!driving && (this.map.getPitch() !== 0 || this.map.getBearing() !== 0)) {
      this.map.easeTo({ pitch: 0, bearing: 0, padding: { top: 0, bottom: 0, left: 0, right: 0 }, duration: 400 });
    }
  }

  private setup() {
    const m = this.map;
    // arrow icon for direction
    const size = 24;
    const c = document.createElement('canvas');
    c.width = size;
    c.height = size;
    const ctx = c.getContext('2d')!;
    ctx.fillStyle = '#ffffff';
    ctx.strokeStyle = '#212529';
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(4, 6);
    ctx.lineTo(20, 12);
    ctx.lineTo(4, 18);
    ctx.lineTo(8, 12);
    ctx.closePath();
    ctx.fill();
    ctx.stroke();
    m.addImage('arrow', ctx.getImageData(0, 0, size, size), { pixelRatio: 2 });
    // vehicle / heading marker
    const vs = 64;
    const vc = document.createElement('canvas');
    vc.width = vs;
    vc.height = vs;
    const v = vc.getContext('2d')!;
    v.beginPath();
    v.moveTo(vs * 0.5, vs * 0.06);
    v.lineTo(vs * 0.88, vs * 0.9);
    v.lineTo(vs * 0.5, vs * 0.7);
    v.lineTo(vs * 0.12, vs * 0.9);
    v.closePath();
    v.fillStyle = PALETTE.me;
    v.fill();
    v.lineWidth = 5;
    v.strokeStyle = '#ffffff';
    v.stroke();
    m.addImage('vehicle', v.getImageData(0, 0, vs, vs), { pixelRatio: 2 });

    for (const id of ['zone', 'reqs', 'covered', 'route', 'pos', 'trail', 'ahead']) m.addSource(id, { type: 'geojson', data: empty() });
    m.addLayer({ id: 'zone-fill', type: 'fill', source: 'zone', paint: { 'fill-color': PALETTE.zone, 'fill-opacity': 0.06 } });
    m.addLayer({ id: 'zone-line', type: 'line', source: 'zone', paint: { 'line-color': PALETTE.zone, 'line-width': 2.5, 'line-dasharray': [3, 2] } });
    const offset: maplibregl.ExpressionSpecification = ['interpolate', ['linear'], ['zoom'], 13, 1, 16, 3, 19, 7];
    const width: maplibregl.ExpressionSpecification = ['interpolate', ['linear'], ['zoom'], 13, 1.5, 16, 3, 19, 6];
    m.addLayer({
      id: 'route-line', type: 'line', source: 'route',
      paint: {
        'line-color': ['match', ['get', 'kind'], 'outside', PALETTE.outside, 'approach', PALETTE.outside, PALETTE.plan],
        'line-width': ['interpolate', ['linear'], ['zoom'], 13, 4, 18, 12],
        'line-opacity': 0.35,
      },
      layout: { 'line-cap': 'round', 'line-join': 'round' },
    });
    const statusColor = ['match', ['get', 'status'], ...Object.entries(STATUS_COLORS).flat(), '#868e96'] as unknown as maplibregl.ExpressionSpecification;
    m.addLayer({
      id: 'reqs-line', type: 'line', source: 'reqs', filter: ['!', ['in', ['get', 'status'], ['literal', ['blocked', 'needsReview']]]],
      paint: { 'line-color': statusColor, 'line-width': width, 'line-offset': offset },
    });
    m.addLayer({
      id: 'reqs-line-dashed', type: 'line', source: 'reqs', filter: ['in', ['get', 'status'], ['literal', ['blocked', 'needsReview']]],
      paint: { 'line-color': statusColor, 'line-width': width, 'line-offset': offset, 'line-dasharray': [1.5, 1] },
    });
    m.addLayer({
      id: 'covered-line', type: 'line', source: 'covered',
      paint: { 'line-color': PALETTE.done, 'line-width': width, 'line-offset': offset },
    });
    m.addLayer({
      id: 'reqs-arrows', type: 'symbol', source: 'reqs', minzoom: 15.5,
      layout: {
        'symbol-placement': 'line', 'symbol-spacing': 70, 'icon-image': 'arrow', 'icon-size': 0.9,
        'icon-allow-overlap': true, 'icon-rotation-alignment': 'map', 'icon-offset': [0, 7],
      },
    });
    m.addLayer({ id: 'trail', type: 'line', source: 'trail', paint: { 'line-color': '#212529', 'line-width': 1, 'line-opacity': 0.5 } });
    m.addLayer({ id: 'ahead-casing', type: 'line', source: 'ahead', layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': PALETTE.routeCasing, 'line-width': ['interpolate', ['linear'], ['zoom'], 13, 8, 18, 22] } });
    m.addLayer({ id: 'ahead-line', type: 'line', source: 'ahead', layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': PALETTE.route, 'line-width': ['interpolate', ['linear'], ['zoom'], 13, 5, 18, 16] } });
    m.addLayer({
      id: 'ahead-arrows', type: 'symbol', source: 'ahead',
      layout: { 'symbol-placement': 'line', 'symbol-spacing': 80, 'icon-image': 'arrow', 'icon-size': 1.1, 'icon-allow-overlap': true, 'icon-ignore-placement': true, 'icon-rotation-alignment': 'map' },
    });
    m.addLayer({ id: 'pos-acc', type: 'fill', source: 'pos', filter: ['==', ['get', 'kind'], 'accuracy'], paint: { 'fill-color': PALETTE.me, 'fill-opacity': 0.12 } });
    m.addLayer({
      id: 'pos-heading', type: 'symbol', source: 'pos', filter: ['all', ['==', ['get', 'kind'], 'dot'], ['>=', ['get', 'heading'], 0]],
      layout: { 'icon-image': 'vehicle', 'icon-size': 1, 'icon-rotate': ['get', 'heading'], 'icon-rotation-alignment': 'map', 'icon-allow-overlap': true, 'icon-ignore-placement': true },
    });
    m.addLayer({
      id: 'pos-dot', type: 'circle', source: 'pos', filter: ['all', ['==', ['get', 'kind'], 'dot'], ['<', ['get', 'heading'], 0]],
      paint: {
        'circle-radius': 8,
        'circle-color': ['match', ['get', 'quality'], 'reliable', PALETTE.me, '#f59f00'],
        'circle-stroke-color': '#fff', 'circle-stroke-width': 3,
      },
    });
  }

  private src(id: string) {
    return this.map.getSource(id) as GeoJSONSource | undefined;
  }

  async setData(id: 'zone' | 'reqs' | 'covered' | 'route' | 'pos' | 'trail' | 'ahead', fc: FC) {
    await this.ready;
    this.src(id)?.setData(fc);
  }

  async fitBbox(b: [number, number, number, number], maxZoom = 16) {
    await this.ready;
    this.map.fitBounds([[b[0], b[1]], [b[2], b[3]]], { padding: 40, maxZoom, duration: 600 });
  }

  flyTo(p: LonLat, zoom?: number) {
    this.map.easeTo({ center: p, zoom: zoom ?? Math.max(this.map.getZoom(), 16), duration: 500 });
  }

  setVertexHandles(points: LonLat[], onMove: (i: number, p: LonLat) => void) {
    this.vertexMarkers.forEach((mk) => mk.remove());
    this.vertexMarkers = points.map((p, i) => {
      const el = document.createElement('div');
      el.className = 'vertex-handle';
      el.textContent = String(i + 1);
      const mk = new maplibregl.Marker({ element: el, draggable: true }).setLngLat(p).addTo(this.map);
      mk.on('dragend', () => {
        const ll = mk.getLngLat();
        onMove(i, [ll.lng, ll.lat]);
      });
      return mk;
    });
  }

  setCenterHandle(p: LonLat | null, onMove: (p: LonLat) => void) {
    this.centerMarker?.remove();
    this.centerMarker = null;
    if (!p) return;
    const el = document.createElement('div');
    el.className = 'vertex-handle center';
    el.textContent = '●';
    const mk = new maplibregl.Marker({ element: el, draggable: true }).setLngLat(p).addTo(this.map);
    mk.on('dragend', () => {
      const ll = mk.getLngLat();
      onMove([ll.lng, ll.lat]);
    });
    this.centerMarker = mk;
  }

  setStart(p: LonLat | null, bearing: number | null) {
    this.startMarker?.remove();
    this.startMarker = null;
    if (!p) return;
    const el = document.createElement('div');
    el.className = 'start-marker';
    el.innerHTML = '<span>▲</span>';
    if (bearing !== null) (el.firstChild as HTMLElement).style.transform = `rotate(${bearing}deg)`;
    this.startMarker = new maplibregl.Marker({ element: el }).setLngLat(p).addTo(this.map);
  }

  resize() {
    this.map.resize();
  }
}

export function circlePolygon(center: LonLat, radius: number, n = 48): LonLat[] {
  const out: LonLat[] = [];
  const kLat = 1 / 110_574;
  const kLon = 1 / (111_320 * Math.cos((center[1] * Math.PI) / 180));
  for (let i = 0; i <= n; i++) {
    const a = (2 * Math.PI * i) / n;
    out.push([center[0] + Math.sin(a) * radius * kLon, center[1] + Math.cos(a) * radius * kLat]);
  }
  return out;
}
