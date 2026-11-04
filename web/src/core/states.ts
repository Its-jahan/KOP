// Mapping of runtime failures to explicit, non-success user-visible states.

import type { DataState } from './types';

export type FailureInput =
  | { kind: 'location_permission_denied' }
  | { kind: 'location_disabled' }
  | { kind: 'location_unavailable' }
  | { kind: 'location_timeout' }
  | { kind: 'graph_http'; status: number }
  | { kind: 'graph_network_error' }
  | { kind: 'graph_timeout' }
  | { kind: 'graph_empty' }
  | { kind: 'provider_not_configured' }
  | { kind: 'credentials_missing' }
  | { kind: 'graph_too_large'; edges: number; limit: number };

export interface StateOutcome {
  gpsState: 'permission_denied' | 'location_disabled' | 'acquiring' | null;
  dataState: DataState | null;
  canStartNavigation: false;
  code: string;
}

export function mapFailure(f: FailureInput): StateOutcome {
  const base = { canStartNavigation: false as const };
  switch (f.kind) {
    case 'location_permission_denied':
      return { ...base, gpsState: 'permission_denied', dataState: null, code: 'gps_permission_denied' };
    case 'location_disabled':
      return { ...base, gpsState: 'location_disabled', dataState: null, code: 'gps_disabled' };
    case 'location_unavailable':
    case 'location_timeout':
      return { ...base, gpsState: 'acquiring', dataState: null, code: f.kind === 'location_timeout' ? 'gps_timeout' : 'gps_unavailable' };
    case 'graph_http':
      if (f.status === 429) return { ...base, gpsState: null, dataState: 'failed', code: 'provider_quota_exceeded' };
      if (f.status === 401 || f.status === 403) return { ...base, gpsState: null, dataState: 'credentials_missing', code: 'provider_auth' };
      if (f.status === 504) return { ...base, gpsState: null, dataState: 'failed', code: 'provider_timeout' };
      return { ...base, gpsState: null, dataState: 'failed', code: `provider_http_${f.status}` };
    case 'graph_network_error':
      return { ...base, gpsState: null, dataState: 'failed', code: 'network_unavailable' };
    case 'graph_timeout':
      return { ...base, gpsState: null, dataState: 'failed', code: 'provider_timeout' };
    case 'graph_empty':
      return { ...base, gpsState: null, dataState: 'area_unsupported', code: 'no_roads_in_area' };
    case 'provider_not_configured':
      return { ...base, gpsState: null, dataState: 'not_configured', code: 'provider_not_configured' };
    case 'credentials_missing':
      return { ...base, gpsState: null, dataState: 'credentials_missing', code: 'credentials_missing' };
    case 'graph_too_large':
      return { ...base, gpsState: null, dataState: 'incomplete', code: 'graph_exceeds_tested_limit' };
  }
}
