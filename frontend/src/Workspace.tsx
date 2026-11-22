import { useState } from 'react';
import { useAssetsInView, useZones } from './api/queries';
import { Announcer, useAnnouncementQueue, useConnectionAnnouncements } from './a11y/Announcer';
import { AlertPanel } from './alerts/AlertPanel';
import { useLiveAlerts } from './alerts/useLiveAlerts';
import { AssetDetail, useSelectedAsset } from './map/AssetDetail';
import { MapView } from './map/MapView';
import { WORLD_BBOX } from './map/viewport';
import { ZonesPanel } from './zones/ZonesPanel';

/** The map and, beside it, the panels. */
export function Workspace() {
  const [bbox, setBbox] = useState(WORLD_BBOX);
  const [selected, setSelected] = useState<string | null>(null);
  const zones = useZones();
  const assets = useAssetsInView(bbox);
  const { asset, track } = useSelectedAsset(selected);
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
      </div>
      <aside className="side" aria-label="Alerts and details">
        <Announcer message={message} />
        <AlertPanel live={live} onShowAsset={setSelected} />
        <ZonesPanel onShowAsset={setSelected} />
        {selected ? (
          <AssetDetail assetId={selected} asset={asset} track={track} onClose={() => setSelected(null)} />
        ) : (
          <section className="panel">
            <h2>Asset</h2>
            <p>Select an asset on the map to see its latest report and recent track.</p>
          </section>
        )}
      </aside>
    </div>
  );
}
