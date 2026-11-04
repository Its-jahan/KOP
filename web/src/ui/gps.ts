// Browser geolocation source. Foreground only: browsers suspend JS/location when the
// tab is hidden or the screen locks; that is surfaced, never hidden.
import type { LocationObservation } from '../core/types';

export type GpsSourceState =
  | 'idle'
  | 'requesting'
  | 'active'
  | 'permission_denied'
  | 'location_unavailable'
  | 'timeout'
  | 'unsupported'
  | 'insecure_context';

export interface GpsCallbacks {
  onFix: (o: LocationObservation) => void;
  onState: (s: GpsSourceState, detail?: string) => void;
}

let counter = 0;

export class BrowserGps {
  private watchId: number | null = null;
  constructor(private readonly cb: GpsCallbacks) {}

  start() {
    if (!window.isSecureContext) return this.cb.onState('insecure_context');
    if (!('geolocation' in navigator)) return this.cb.onState('unsupported');
    this.stop();
    this.cb.onState('requesting');
    this.watchId = navigator.geolocation.watchPosition(
      (pos) => {
        const now = Date.now();
        const c = pos.coords;
        const heading = c.heading === null || Number.isNaN(c.heading) ? null : c.heading;
        const speed = c.speed === null || Number.isNaN(c.speed) ? null : Math.max(0, c.speed);
        this.cb.onState('active');
        this.cb.onFix({
          observationId: `web:${pos.timestamp}:${++counter}`,
          timestamp: Math.round(pos.timestamp),
          monotonicMs: typeof performance !== 'undefined' ? performance.now() : null,
          lon: c.longitude,
          lat: c.latitude,
          horizontalAccuracyMeters: Number.isFinite(c.accuracy) ? c.accuracy : null,
          speedMps: speed,
          speedAccuracyMps: null,
          bearingDeg: heading,
          bearingAccuracyDeg: null,
          altitudeMeters: c.altitude,
          altitudeAccuracyMeters: c.altitudeAccuracy,
          source: 'browser',
          ageMs: Math.max(0, now - pos.timestamp),
        });
      },
      (err) => {
        if (err.code === err.PERMISSION_DENIED) this.cb.onState('permission_denied', err.message);
        else if (err.code === err.POSITION_UNAVAILABLE) this.cb.onState('location_unavailable', err.message);
        else this.cb.onState('timeout', err.message);
      },
      { enableHighAccuracy: true, maximumAge: 0, timeout: 20_000 },
    );
  }

  stop() {
    if (this.watchId !== null) navigator.geolocation.clearWatch(this.watchId);
    this.watchId = null;
  }
}

export async function geolocationPermission(): Promise<PermissionState | 'unknown'> {
  try {
    const p = await navigator.permissions?.query({ name: 'geolocation' as PermissionName });
    return p?.state ?? 'unknown';
  } catch {
    return 'unknown';
  }
}

export class WakeLockKeeper {
  private sentinel: WakeLockSentinel | null = null;
  private wanted = false;
  constructor(private readonly onChange: (state: 'held' | 'released' | 'unsupported' | 'refused') => void) {
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible' && this.wanted && !this.sentinel) void this.acquire();
    });
  }
  async acquire() {
    this.wanted = true;
    if (!('wakeLock' in navigator)) return this.onChange('unsupported');
    try {
      this.sentinel = await navigator.wakeLock.request('screen');
      this.onChange('held');
      this.sentinel.addEventListener('release', () => {
        this.sentinel = null;
        this.onChange('released');
      });
    } catch {
      this.onChange('refused');
    }
  }
  async release() {
    this.wanted = false;
    await this.sentinel?.release().catch(() => undefined);
    this.sentinel = null;
  }
}
