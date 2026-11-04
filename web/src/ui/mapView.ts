// Imperative MapLibre wrapper: zone editing handles, directed coverage styling,
// planned route, position and start markers.
import maplibregl, { type GeoJSONSource, type Map as MlMap, type Marker } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { osmRasterStyle } from '../providers/mapDisplay';
import type { LonLat } from '../core/types';

type FC = GeoJSON.FeatureCollection;
const empty = (): FC => ({ type: 'FeatureCollection', features: [] });

export const STATUS_COLORS: Record<string, string> = {
  unvisited: '#d6336c',
  partial: '#f08c00',
  covered: '#2b8a3e',
  blocked: '#495057',
  unreachable: '#7048e8',
  needsReview: '#e8b600',
};

export class MapView {
  readonly map: MlMap;
  private ready: Promise<void>;
  private vertexMarkers: Marker[] = [];
  private centerMarker: Marker | null = null;
  private startMarker: Marker | null = null;
  private clickHandler: ((p: LonLat) => void) | null = null;

  constructor(container: HTMLElement, center: LonLat = [44.5126, 40.1811], zoom = 12) {
    this.map = new maplibregl.Map({
      container,
      style: osmRasterStyle(),
      center,
      zoom,
      attributionControl: { compact: true },
      dragRotate: false,
      pitchWithRotate: false,
    });
    this.map.touchZoomRotate.disableRotation();
    this.map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'bottom-right');
    this.map.addControl(new maplibregl.ScaleControl({ unit: 'metric' }), 'bottom-left');
    this.map.on('click', (e) => this.clickHandler?.([e.lngLat.lng, e.lngLat.lat]));
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

    for (const id of ['zone', 'reqs', 'covered', 'route', 'pos', 'trail']) m.addSource(id, { type: 'geojson', data: empty() });
    m.addLayer({ id: 'zone-fill', type: 'fill', source: 'zone', paint: { 'fill-color': '#1c7ed6', 'fill-opacity': 0.08 } });
    m.addLayer({ id: 'zone-line', type: 'line', source: 'zone', paint: { 'line-color': '#1c7ed6', 'line-width': 2, 'line-dasharray': [2, 1] } });
    const offset: maplibregl.ExpressionSpecification = ['interpolate', ['linear'], ['zoom'], 13, 1, 16, 3, 19, 7];
    const width: maplibregl.ExpressionSpecification = ['interpolate', ['linear'], ['zoom'], 13, 1.5, 16, 3, 19, 6];
    m.addLayer({
      id: 'route-line', type: 'line', source: 'route',
      paint: {
        'line-color': ['match', ['get', 'kind'], 'outside', '#ae3ec9', 'approach', '#ae3ec9', '#1971c2'],
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
      paint: { 'line-color': STATUS_COLORS.covered, 'line-width': width, 'line-offset': offset },
    });
    m.addLayer({
      id: 'reqs-arrows', type: 'symbol', source: 'reqs', minzoom: 15.5,
      layout: {
        'symbol-placement': 'line', 'symbol-spacing': 70, 'icon-image': 'arrow', 'icon-size': 0.9,
        'icon-allow-overlap': true, 'icon-rotation-alignment': 'map', 'icon-offset': [0, 7],
      },
    });
    m.addLayer({ id: 'trail', type: 'line', source: 'trail', paint: { 'line-color': '#212529', 'line-width': 1, 'line-opacity': 0.5 } });
    m.addLayer({ id: 'pos-acc', type: 'fill', source: 'pos', filter: ['==', ['get', 'kind'], 'accuracy'], paint: { 'fill-color': '#1c7ed6', 'fill-opacity': 0.15 } });
    m.addLayer({
      id: 'pos-dot', type: 'circle', source: 'pos', filter: ['==', ['get', 'kind'], 'dot'],
      paint: {
        'circle-radius': 8,
        'circle-color': ['match', ['get', 'quality'], 'reliable', '#1c7ed6', '#868e96'],
        'circle-stroke-color': '#fff', 'circle-stroke-width': 3,
      },
    });
  }

  private src(id: string) {
    return this.map.getSource(id) as GeoJSONSource | undefined;
  }

  async setData(id: 'zone' | 'reqs' | 'covered' | 'route' | 'pos' | 'trail', fc: FC) {
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
