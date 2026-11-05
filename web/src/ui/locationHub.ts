// One always-on browser location source for the whole app: the map shows "where am I"
// in every screen (prepare, drive, paused), while coverage is credited only by an active
// driving session that subscribes to the same fixes.
import type { LocationObservation } from '../core/types';
import { BrowserGps, geolocationPermission, type GpsSourceState } from './gps';

type FixFn = (o: LocationObservation) => void;
type StateFn = (s: GpsSourceState) => void;

class LocationHub {
  state: GpsSourceState = 'idle';
  last: LocationObservation | null = null;
  /** Last trustworthy heading (only from fixes moving >= 2.5 m/s). */
  heading: number | null = null;
  private gps: BrowserGps | null = null;
  private fixFns = new Set<FixFn>();
  private stateFns = new Set<StateFn>();

  start() {
    if (this.gps) return;
    this.gps = new BrowserGps({
      onFix: (o) => {
        this.last = o;
        if ((o.speedMps ?? 0) >= 2.5 && o.bearingDeg !== null) this.heading = o.bearingDeg;
        for (const f of this.fixFns) f(o);
      },
      onState: (s) => {
        this.state = s;
        for (const f of this.stateFns) f(s);
        // A denied/failed watch is retried only by an explicit user action (start() again).
        if (s === 'permission_denied' || s === 'unsupported' || s === 'insecure_context') this.gps = null;
      },
    });
    this.gps.start();
  }

  /** Start automatically only if the user already granted location to this site. */
  async startIfPermitted() {
    if ((await geolocationPermission()) === 'granted') this.start();
  }

  onFix(f: FixFn) {
    this.fixFns.add(f);
    return () => this.fixFns.delete(f);
  }

  onState(f: StateFn) {
    this.stateFns.add(f);
    f(this.state);
    return () => this.stateFns.delete(f);
  }
}

export const locationHub = new LocationHub();
