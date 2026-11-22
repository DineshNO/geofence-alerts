import { useState } from 'react';
import { useAssetsInView, useZones } from './api/queries';
import { Announcer, useAnnouncementQueue, useConnectionAnnouncements } from './a11y/Announcer';
import { AlertPanel } from './alerts/AlertPanel';
import { useLiveAlerts } from './alerts/useLiveAlerts';
import { AssetDetail, useSelectedAsset } from './map/AssetDetail';
import { MapView } from './map/MapView';
import { WORLD_BBOX } from './map/viewport';
import { ZonesPanel } from './zones/ZonesPanel';

/** The map, with the selected asset's details over its top-right corner, and beside it the panels. */
export function Workspace() {
  const [bbox, setBbox] = useState(WORLD_BBOX);
  const [selected, setSelected] = useState<string | null>(null);
  const zones = useZones();
  const assets = useAssetsInView(bbox);
  const { asset, track, trackLoading } = useSelectedAsset(selected);
  const { message, queue } = useAnnouncementQueue();
  const live = useLiveAlerts((events) => queue.addOpened(events));
  useConnectionAnnouncements(live.state, queue);

  const points: [number, number][] = track.map((p) => [p.lon, p.lat]);
  const truncated = assets.data?.truncated === true;

  return (
    <div className="workspace">
      <div className="map-wrap">
        <MapView
          zones={zones.data ?? []}
          assets={assets.data?.items ?? []}
          selectedAssetId={selected}
          selectedPosition={asset ? { lat: asset.lat, lon: asset.lon } : null}
          track={points}
          onSelect={setSelected}
          onBoundsChange={setBbox}
        />
        <p className="map-note" aria-live="polite">
          {assets.isError
            ? 'The positions could not be loaded.'
            : truncated
              ? 'Not every asset in view is shown. Zoom in to see them all.'
              : `${assets.data?.items.length ?? 0} assets in view`}
        </p>
        {selected && (
          <div className="map-overlay">
            <AssetDetail
              assetId={selected}
              asset={asset}
              track={track}
              trackLoading={trackLoading}
              onClose={() => setSelected(null)}
            />
          </div>
        )}
      </div>
      <aside className="side" aria-label="Alerts and details">
        <Announcer message={message} />
        <AlertPanel live={live} onShowAsset={setSelected} />
        <ZonesPanel onShowAsset={setSelected} />
      </aside>
    </div>
  );
}
