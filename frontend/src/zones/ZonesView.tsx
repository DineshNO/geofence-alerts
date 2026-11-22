import type { AssetPosition, Zone, ZoneOccupancy } from '../api/types';
import { formatCoordinate, formatTime } from '../map/format';

interface Props {
  zones: readonly Zone[];
  occupancy: readonly ZoneOccupancy[];
  selectedZoneId: string | null;
  assets: readonly AssetPosition[];
  assetsLoading: boolean;
  onSelectZone(zoneId: string | null): void;
  onShowAsset(assetId: string): void;
}

/**
 * Which assets are inside which zone, as real tables. This is the way to see it without the map:
 * everything here works with the keyboard and a screen reader.
 */
export function ZonesView({
  zones,
  occupancy,
  selectedZoneId,
  assets,
  assetsLoading,
  onSelectZone,
  onShowAsset,
}: Props) {
  const counts = new Map(occupancy.map((o) => [o.zoneId, o.assetCount]));
  const selected = zones.find((z) => z.zoneId === selectedZoneId);

  return (
    <section aria-labelledby="zones-heading" className="panel">
      <h2 id="zones-heading">Zones</h2>
      <table>
        <caption className="visually-hidden">Zones, their class and the number of assets inside each</caption>
        <thead>
          <tr>
            <th scope="col">Zone</th>
            <th scope="col">Class</th>
            <th scope="col">Assets inside</th>
          </tr>
        </thead>
        <tbody>
          {zones.map((zone) => (
            <tr key={zone.zoneId} aria-selected={zone.zoneId === selectedZoneId}>
              <th scope="row">
                {zone.zoneId} {zone.name}
              </th>
              <td>{zone.zoneClass === 'RESTRICTED' ? 'Restricted' : 'Advisory'}</td>
              <td>
                <button
                  type="button"
                  className="link"
                  aria-pressed={zone.zoneId === selectedZoneId}
                  aria-label={`${counts.get(zone.zoneId) ?? 0} assets inside ${zone.zoneId} ${zone.name}. Show the list`}
                  onClick={() => onSelectZone(zone.zoneId === selectedZoneId ? null : zone.zoneId)}
                >
                  {counts.get(zone.zoneId) ?? 0}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {selected && (
        <div aria-live="polite">
          <h3>
            Assets in {selected.zoneId} {selected.name}
          </h3>
          {assetsLoading ? (
            <p>Loading…</p>
          ) : assets.length === 0 ? (
            <p>No asset is inside this zone now.</p>
          ) : (
            <table>
              <caption className="visually-hidden">Assets inside {selected.name}, by asset id</caption>
              <thead>
                <tr>
                  <th scope="col">Asset</th>
                  <th scope="col">Position</th>
                  <th scope="col">Observed</th>
                </tr>
              </thead>
              <tbody>
                {assets.map((asset) => (
                  <tr key={asset.assetId}>
                    <th scope="row">
                      <button type="button" className="link" onClick={() => onShowAsset(asset.assetId)}>
                        {asset.assetId}
                      </button>
                    </th>
                    <td>
                      {formatCoordinate(asset.lat)}, {formatCoordinate(asset.lon)}
                    </td>
                    <td>{formatTime(asset.observedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </section>
  );
}
