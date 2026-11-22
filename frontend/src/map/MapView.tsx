import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import type { ExpressionSpecification, GeoJSONSource, StyleSpecification } from 'maplibre-gl';
import { useEffect, useRef } from 'react';
import type { AssetPosition, Zone } from '../api/types';
import { assetsToGeoJson, bboxFromBounds, hasChanged, signature, trackToGeoJson } from './viewport';

interface Props {
  zones: readonly Zone[];
  assets: readonly AssetPosition[];
  selectedAssetId: string | null;
  track: readonly [number, number][];
  onSelect(assetId: string): void;
  onBoundsChange(bbox: string): void;
}

const dark = () => window.matchMedia('(prefers-color-scheme: dark)').matches;

/**
 * A blank map: only our own layers (zones, assets, the selected track), no tiles, no fonts and no
 * sprites, so nothing is requested from another host. Zone names are HTML labels for that reason.
 */
function blankStyle(): StyleSpecification {
  return {
    version: 8,
    sources: {},
    layers: [{ id: 'background', type: 'background', paint: { 'background-color': dark() ? '#0e2233' : '#dbe7f2' } }],
  };
}

/** An arrow pointing north, drawn once; the symbol layer turns it to each asset's heading. */
function arrowImage(): ImageData {
  const size = 40;
  const canvas = document.createElement('canvas');
  canvas.width = size;
  canvas.height = size;
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    throw new Error('A canvas is needed to draw the asset symbols.');
  }
  ctx.beginPath();
  ctx.moveTo(size / 2, 3);
  ctx.lineTo(size - 8, size - 6);
  ctx.lineTo(size / 2, size - 13);
  ctx.lineTo(8, size - 6);
  ctx.closePath();
  ctx.fillStyle = '#1d5fd1';
  ctx.fill();
  ctx.lineWidth = 3;
  ctx.strokeStyle = '#ffffff';
  ctx.stroke();
  return ctx.getImageData(0, 0, size, size);
}

function zoneFeatures(zones: readonly Zone[]) {
  return {
    type: 'FeatureCollection' as const,
    features: zones.map((z) => ({
      type: 'Feature' as const,
      geometry: z.geometry,
      properties: { zoneId: z.zoneId, name: z.name, zoneClass: z.zoneClass },
    })),
  };
}

/** The middle of a zone's first part, for its label. */
function labelPosition(zone: Zone): [number, number] {
  const ring =
    zone.geometry.type === 'Polygon' ? zone.geometry.coordinates[0] : zone.geometry.coordinates[0]?.[0];
  const points = ring ?? [];
  const lons = points.map((p) => p[0] ?? 0);
  const lats = points.map((p) => p[1] ?? 0);
  return [(Math.min(...lons) + Math.max(...lons)) / 2, (Math.min(...lats) + Math.max(...lats)) / 2];
}

export function MapView({ zones, assets, selectedAssetId, track, onSelect, onBoundsChange }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const ready = useRef(false);
  const labels = useRef<maplibregl.Marker[]>([]);
  const lastSignature = useRef<Map<string, string> | null>(null);
  const frame = useRef<number | null>(null);

  // the latest data, read when the map draws
  const latest = useRef({ zones, assets, selectedAssetId, track });
  latest.current = { zones, assets, selectedAssetId, track };
  const callbacks = useRef({ onSelect, onBoundsChange });
  callbacks.current = { onSelect, onBoundsChange };

  /** Gives the map its data, at most once per animation frame, and the assets only if they changed. */
  function draw(force = false) {
    if (frame.current !== null) {
      return;
    }
    frame.current = requestAnimationFrame(() => {
      frame.current = null;
      const map = mapRef.current;
      if (!map || !ready.current) {
        return;
      }
      const { assets: current, selectedAssetId: selected, track: line } = latest.current;
      const next = signature(current);
      if (force || hasChanged(lastSignature.current, next)) {
        lastSignature.current = next;
        map.getSource<GeoJSONSource>('assets')?.setData(assetsToGeoJson(current, selected));
      }
      map.getSource<GeoJSONSource>('track')?.setData(trackToGeoJson(line));
    });
  }

  useEffect(() => {
    const element = container.current;
    if (!element) {
      return undefined;
    }
    const map = new maplibregl.Map({
      container: element,
      style: blankStyle(),
      center: [174, 36],
      zoom: 4.2,
      attributionControl: false,
    });
    mapRef.current = map;
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-left');

    const emitBounds = () => {
      const b = map.getBounds();
      callbacks.current.onBoundsChange(bboxFromBounds(b.getWest(), b.getSouth(), b.getEast(), b.getNorth()));
    };

    map.on('load', () => {
      map.addImage('arrow', arrowImage());
      map.addSource('zones', { type: 'geojson', data: zoneFeatures(latest.current.zones) });
      map.addSource('track', { type: 'geojson', data: trackToGeoJson([]) });
      map.addSource('assets', { type: 'geojson', data: assetsToGeoJson([], null) });
      const restricted: ExpressionSpecification = ['==', ['get', 'zoneClass'], 'RESTRICTED'];
      map.addLayer({
        id: 'zones-fill',
        type: 'fill',
        source: 'zones',
        paint: { 'fill-color': ['case', restricted, '#c62828', '#6b7280'], 'fill-opacity': 0.22 },
      });
      map.addLayer({
        id: 'zones-line',
        type: 'line',
        source: 'zones',
        filter: restricted,
        paint: { 'line-color': '#c62828', 'line-width': 2 },
      });
      map.addLayer({
        id: 'zones-line-advisory',
        type: 'line',
        source: 'zones',
        filter: ['!', restricted],
        paint: { 'line-color': '#6b7280', 'line-width': 2, 'line-dasharray': [3, 2] },
      });
      map.addLayer({
        id: 'track-line',
        type: 'line',
        source: 'track',
        paint: { 'line-color': '#f59e0b', 'line-width': 3 },
      });
      map.addLayer({
        id: 'assets',
        type: 'symbol',
        source: 'assets',
        layout: {
          'icon-image': 'arrow',
          'icon-rotate': ['get', 'heading'],
          'icon-rotation-alignment': 'map',
          'icon-allow-overlap': true,
          'icon-ignore-placement': true,
          'icon-size': ['case', ['get', 'selected'], 0.9, 0.32],
        },
      });
      map.on('click', 'assets', (event) => {
        const id = event.features?.[0]?.properties?.assetId as string | undefined;
        if (id) {
          callbacks.current.onSelect(id);
        }
      });
      map.on('mouseenter', 'assets', () => (map.getCanvas().style.cursor = 'pointer'));
      map.on('mouseleave', 'assets', () => (map.getCanvas().style.cursor = ''));
      ready.current = true;
      emitBounds();
      draw(true);
    });
    map.on('moveend', emitBounds);

    return () => {
      if (frame.current !== null) {
        cancelAnimationFrame(frame.current);
        frame.current = null;
      }
      labels.current.forEach((m) => m.remove());
      labels.current = [];
      ready.current = false;
      map.remove();
      mapRef.current = null;
      lastSignature.current = null;
    };
    // the map is created once; later data arrives through the effects below
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // zones: the shapes and a name label for each
  useEffect(() => {
    const map = mapRef.current;
    if (!map || zones.length === 0) {
      return;
    }
    const add = () => {
      map.getSource<GeoJSONSource>('zones')?.setData(zoneFeatures(zones));
      labels.current.forEach((m) => m.remove());
      labels.current = zones.map((zone) => {
        const element = document.createElement('div');
        element.className = `zone-label ${zone.zoneClass === 'RESTRICTED' ? 'restricted' : 'advisory'}`;
        element.textContent = `${zone.zoneId} ${zone.name}`;
        element.setAttribute('aria-hidden', 'true'); // the zones table is the accessible version
        return new maplibregl.Marker({ element }).setLngLat(labelPosition(zone)).addTo(map);
      });
    };
    if (ready.current) {
      add();
    } else {
      map.once('load', add);
    }
  }, [zones]);

  useEffect(() => draw(), [assets, track]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => draw(true), [selectedAssetId]); // eslint-disable-line react-hooks/exhaustive-deps

  return <div ref={container} className="map" role="application" aria-label="Map of assets and zones" />;
}
