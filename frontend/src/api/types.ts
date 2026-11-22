// API types come from the contract (schema.d.ts is generated from contract/openapi.yaml).
// The endpoints we added to the contract are described here by hand.
import type { MultiPolygon, Polygon } from 'geojson';
import type { components } from './schema';

export type Schemas = components['schemas'];
export type Role = Schemas['LoginResponse']['user']['role'];
export type LoginResponse = Schemas['LoginResponse'];
export type AssetPosition = Schemas['AssetPosition'];
export type TrackPoint = Schemas['TrackPoint'];
export type Alert = Schemas['Alert'];
export type AlertStatus = Schemas['AlertStatus'];
export type AuditEntry = Schemas['AuditEntry'];

export interface Page<T> {
  items: T[];
  truncated: boolean;
}

export interface AlertPage {
  items: Alert[];
  nextCursor: string | null;
}

/** Added to the contract: the zones with their shapes. */
export interface Zone {
  zoneId: string;
  name: string;
  zoneClass: 'RESTRICTED' | 'ADVISORY';
  crossesAntimeridian: boolean;
  geometry: Polygon | MultiPolygon;
}

export interface ZoneOccupancy {
  zoneId: string;
  assetCount: number;
}

export interface ZoneAssets {
  items: AssetPosition[];
  nextCursor: string | null;
}

/** A change pushed by the live stream; the console refetches the alert for its full state. */
export interface AlertEvent {
  type: 'OPENED' | 'CLAIMED' | 'RELEASED' | 'RESOLVED';
  alertId: string;
  status: AlertStatus;
  assetId: string;
  zoneId: string;
}
