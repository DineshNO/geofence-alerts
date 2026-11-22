import type { Feature, FeatureCollection, LineString, Point } from 'geojson';
import type { AssetPosition } from '../api/types';

/** The whole world: what is asked for before the map has told its real viewport. */
export const WORLD_BBOX = '-180,-85,180,85';

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function round(value: number): number {
  return Math.round(value * 10_000) / 10_000;
}

/** Brings a longitude that a map reports beyond +-180 (the map repeats the world) back into range. */
export function wrapLongitude(lon: number): number {
  if (lon >= -180 && lon <= 180) {
    return lon;
  }
  return ((((lon + 180) % 360) + 360) % 360) - 180;
}

/**
 * The bounding box of the API (minLon,minLat,maxLon,maxLat) for the visible part of the map.
 * A view that shows the whole width of the world asks for everything; a view that crosses the 180
 * degree meridian gives minLon greater than maxLon, which the API reads as a box across it.
 */
export function bboxFromBounds(west: number, south: number, east: number, north: number): string {
  const minLat = round(clamp(south, -90, 90));
  const maxLat = round(clamp(north, -90, 90));
  if (east - west >= 360) {
    return `-180,${minLat},180,${maxLat}`;
  }
  return `${round(wrapLongitude(west))},${minLat},${round(wrapLongitude(east))},${maxLat}`;
}

export interface AssetProperties {
  assetId: string;
  heading: number;
  selected: boolean;
}

/** The assets as map points; the symbol is turned to the heading (north when it is unknown). */
export function assetsToGeoJson(
  assets: readonly AssetPosition[],
  selectedAssetId: string | null,
): FeatureCollection<Point, AssetProperties> {
  const features: Feature<Point, AssetProperties>[] = assets.map((asset) => ({
    type: 'Feature',
    geometry: { type: 'Point', coordinates: [asset.lon, asset.lat] },
    properties: {
      assetId: asset.assetId,
      heading: asset.headingDeg ?? 0,
      selected: asset.assetId === selectedAssetId,
    },
  }));
  return { type: 'FeatureCollection', features };
}

export function trackToGeoJson(points: readonly [number, number][]): FeatureCollection<LineString> {
  if (points.length < 2) {
    return { type: 'FeatureCollection', features: [] };
  }
  return {
    type: 'FeatureCollection',
    features: [{ type: 'Feature', geometry: { type: 'LineString', coordinates: [...points] }, properties: {} }],
  };
}

/**
 * Tells whether any asset moved or appeared or disappeared since the last drawing, so the map is
 * only given new data when something changed. Each asset's current report id changes whenever its
 * position moves.
 */
export function signature(assets: readonly AssetPosition[]): Map<string, string> {
  return new Map(assets.map((a) => [a.assetId, a.reportId]));
}

export function hasChanged(previous: Map<string, string> | null, next: Map<string, string>): boolean {
  if (previous === null || previous.size !== next.size) {
    return true;
  }
  for (const [assetId, reportId] of next) {
    if (previous.get(assetId) !== reportId) {
      return true;
    }
  }
  return false;
}

/**
 * The longitude of the same place that is drawn nearest to the middle of the map. The map repeats the world
 * to the east and west, so an asset at -177 may be on screen as 183.
 */
export function nearestLongitude(lon: number, centerLon: number): number {
  return lon + 360 * Math.round((centerLon - lon) / 360);
}

/** A rectangle on the map, in pixels from its top-left corner. */
export interface PixelBox {
  readonly left: number;
  readonly top: number;
  readonly right: number;
  readonly bottom: number;
}

/** True when a point is outside the map or under the box that is drawn over it (with a margin around the box). */
export function isHiddenOrOffscreen(
  point: { x: number; y: number },
  map: { width: number; height: number },
  overlay: PixelBox,
  margin = 24,
): boolean {
  const outside = point.x < 0 || point.y < 0 || point.x > map.width || point.y > map.height;
  const underOverlay =
    point.x >= overlay.left - margin &&
    point.x <= overlay.right + margin &&
    point.y >= overlay.top - margin &&
    point.y <= overlay.bottom + margin;
  return outside || underOverlay;
}
