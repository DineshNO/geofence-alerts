import { useAsset, useRecentTrack } from '../api/queries';
import type { AssetPosition, TrackPoint } from '../api/types';
import { formatCoordinate, formatNumber, formatTime } from './format';

interface Props {
  assetId: string;
  onClose(): void;
  /** Passes the recent track up so the map can draw it. */
  asset: AssetPosition | undefined;
  track: readonly TrackPoint[];
}

/** The latest report of the selected asset and its recent track, as text. */
export function AssetDetail({ assetId, onClose, asset, track }: Props) {
  return (
    <section aria-labelledby="asset-heading" className="panel">
      <div className="panel-head">
        <h2 id="asset-heading">Asset {assetId}</h2>
        <button type="button" onClick={onClose}>
          Close
        </button>
      </div>
      {!asset ? (
        <p>Loading…</p>
      ) : (
        <>
          <h3>Latest report</h3>
          <dl className="facts">
            <dt>Observed</dt>
            <dd>{formatTime(asset.observedAt)}</dd>
            <dt>Position</dt>
            <dd>
              {formatCoordinate(asset.lat)}, {formatCoordinate(asset.lon)}
            </dd>
            <dt>Kind</dt>
            <dd>{asset.kind ?? '–'}</dd>
            <dt>Speed</dt>
            <dd>{formatNumber(asset.speedKn, 'kn')}</dd>
            <dt>Heading</dt>
            <dd>{formatNumber(asset.headingDeg, '°')}</dd>
            <dt>Source</dt>
            <dd>{asset.source ?? '–'}</dd>
            <dt>Report</dt>
            <dd>{asset.reportId}</dd>
          </dl>
          <h3>Recent track</h3>
          {track.length === 0 ? (
            <p>No earlier reports in the last ten minutes.</p>
          ) : (
            <table>
              <caption className="visually-hidden">Reports of the last ten minutes, oldest first</caption>
              <thead>
                <tr>
                  <th scope="col">Observed</th>
                  <th scope="col">Position</th>
                </tr>
              </thead>
              <tbody>
                {track.slice(-12).map((point) => (
                  <tr key={point.reportId}>
                    <td>{formatTime(point.observedAt)}</td>
                    <td>
                      {formatCoordinate(point.lat)}, {formatCoordinate(point.lon)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </section>
  );
}

/** Loads the selected asset and its track; used by the workspace. */
export function useSelectedAsset(assetId: string | null) {
  const asset = useAsset(assetId);
  const track = useRecentTrack(assetId, asset.data?.observedAt);
  return { asset: asset.data, track: track.data?.items ?? [] };
}
