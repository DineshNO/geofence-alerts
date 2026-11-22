import { describe, expect, it } from 'vitest';
import type { AssetPosition } from '../api/types';
import {
  assetsToGeoJson,
  bboxFromBounds,
  hasChanged,
  isHiddenOrOffscreen,
  nearestLongitude,
  signature,
  trackToGeoJson,
  wrapLongitude,
} from './viewport';

function asset(id: string, reportId: string, extra: Partial<AssetPosition> = {}): AssetPosition {
  return { assetId: id, reportId, observedAt: '2026-11-02T06:00:00.000Z', lat: 10, lon: 20, ...extra };
}

describe('wrapLongitude', () => {
  it('leaves a longitude in range alone', () => {
    expect(wrapLongitude(0)).toBe(0);
    expect(wrapLongitude(180)).toBe(180);
    expect(wrapLongitude(-180)).toBe(-180);
  });

  it('brings a longitude of the repeated world back into range', () => {
    expect(wrapLongitude(190)).toBe(-170);
    expect(wrapLongitude(-190)).toBe(170);
    expect(wrapLongitude(540)).toBe(-180);
  });
});

describe('bboxFromBounds', () => {
  it('writes a normal view as minLon,minLat,maxLon,maxLat', () => {
    expect(bboxFromBounds(167, 31, 182, 41)).toBe('167,31,-178,41'); // wraps east of 180
    expect(bboxFromBounds(100, -10, 120, 10)).toBe('100,-10,120,10');
  });

  it('gives minLon above maxLon for a view across the 180 degree meridian', () => {
    const [minLon, , maxLon] = bboxFromBounds(170, 30, 190, 40).split(',').map(Number);
    expect(minLon).toBe(170);
    expect(maxLon).toBe(-170);
    expect(minLon).toBeGreaterThan(maxLon as number);
  });

  it('asks for the whole width when the view shows the whole world or more', () => {
    expect(bboxFromBounds(-200, -50, 200, 50)).toBe('-180,-50,180,50');
    expect(bboxFromBounds(-180, -50, 180, 50)).toBe('-180,-50,180,50');
  });

  it('keeps the latitudes inside +-90', () => {
    expect(bboxFromBounds(0, -120, 10, 120)).toBe('0,-90,10,90');
  });

  it('rounds so tiny moves do not produce new addresses', () => {
    expect(bboxFromBounds(1.123456789, 2.987654321, 3.5, 4.5)).toBe('1.1235,2.9877,3.5,4.5');
  });
});

describe('assetsToGeoJson', () => {
  it('makes a point per asset with longitude first, the heading, and the selection', () => {
    const geo = assetsToGeoJson(
      [asset('A1', 'r1', { lat: 35.5, lon: 170.25, headingDeg: 271.4 }), asset('A2', 'r2')],
      'A2',
    );
    expect(geo.features).toHaveLength(2);
    expect(geo.features[0]?.geometry.coordinates).toEqual([170.25, 35.5]);
    expect(geo.features[0]?.properties).toEqual({ assetId: 'A1', heading: 271.4, selected: false });
    expect(geo.features[1]?.properties.selected).toBe(true);
  });

  it('points north when the heading is unknown', () => {
    const geo = assetsToGeoJson([asset('A1', 'r1', { headingDeg: null })], null);
    expect(geo.features[0]?.properties.heading).toBe(0);
  });
});

describe('trackToGeoJson', () => {
  it('draws a line from two or more points and nothing from fewer', () => {
    expect(trackToGeoJson([[1, 2]]).features).toHaveLength(0);
    expect(trackToGeoJson([[1, 2], [3, 4]]).features).toHaveLength(1);
  });
});

describe('hasChanged', () => {
  it('is true the first time and when an asset moved, appeared or disappeared', () => {
    const base = signature([asset('A1', 'r1'), asset('A2', 'r2')]);
    expect(hasChanged(null, base)).toBe(true);
    expect(hasChanged(base, signature([asset('A1', 'r1'), asset('A2', 'r2')]))).toBe(false);
    expect(hasChanged(base, signature([asset('A1', 'r1'), asset('A2', 'r9')]))).toBe(true);
    expect(hasChanged(base, signature([asset('A1', 'r1')]))).toBe(true);
    expect(hasChanged(base, signature([asset('A1', 'r1'), asset('A2', 'r2'), asset('A3', 'r3')]))).toBe(true);
  });
});

describe('nearestLongitude', () => {
  it('keeps a longitude that is already nearest', () => {
    expect(nearestLongitude(176, 174)).toBe(176);
  });
  it('shows a western longitude to the east of the date line when the map is centred there', () => {
    expect(nearestLongitude(-177.3, 174)).toBeCloseTo(182.7);
    expect(nearestLongitude(179.8, -175)).toBeCloseTo(-180.2);
  });
});

describe('isHiddenOrOffscreen', () => {
  const map = { width: 800, height: 600 };
  const overlay = { left: 450, top: 10, right: 790, bottom: 430 };
  it('is false for a point in the free part of the map', () => {
    expect(isHiddenOrOffscreen({ x: 200, y: 300 }, map, overlay)).toBe(false);
    expect(isHiddenOrOffscreen({ x: 700, y: 560 }, map, overlay)).toBe(false); // below the card
  });
  it('is true for a point under the card or close to its edge', () => {
    expect(isHiddenOrOffscreen({ x: 600, y: 200 }, map, overlay)).toBe(true);
    expect(isHiddenOrOffscreen({ x: 435, y: 200 }, map, overlay)).toBe(true);
  });
  it('is true for a point outside the map', () => {
    expect(isHiddenOrOffscreen({ x: -5, y: 300 }, map, overlay)).toBe(true);
    expect(isHiddenOrOffscreen({ x: 200, y: 700 }, map, overlay)).toBe(true);
  });
});
