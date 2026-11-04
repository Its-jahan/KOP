// MapDisplayProvider: OpenStreetMap standard raster tiles (light interactive use only,
// no prefetch, attribution visible) per https://operations.osmfoundation.org/policies/tiles/
import type { StyleSpecification } from 'maplibre-gl';

export const MAP_DISPLAY_CAPABILITIES = {
  providerId: 'osm-standard-tiles',
  routableGraph: false,
  navigationGuidance: false,
  offline: false,
};

export function osmRasterStyle(): StyleSpecification {
  return {
    version: 8,
    sources: {
      osm: {
        type: 'raster',
        tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
        tileSize: 256,
        maxzoom: 19,
        attribution: '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors',
      },
    },
    layers: [{ id: 'osm', type: 'raster', source: 'osm', paint: { 'raster-saturation': -0.35, 'raster-contrast': 0.05 } }],
  };
}
