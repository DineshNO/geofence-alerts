import { useEffect, useRef } from 'react';
import { useAsset, useRecentTrack } from '../api/queries';
import type { AssetPosition, TrackPoint } from '../api/types';
import { formatCoordinate, formatNumber, formatTime } from './format';

interface Props {
  assetId: string;
  onClose(): void;
  /** Passes the recent track up so the map can draw it. */
  asset: AssetPosition | undefined;
  track: readonly TrackPoint[];
  /** True while the recent track is still being loaded, so an empty list is not shown as "no reports". */
  trackLoading?: boolean;
}

/** The latest report of the selected asset and its recent track, as text. */
export function AssetDetail({ assetId, onClose, asset, track, trackLoading = false }: Props) {
  const heading = useRef<HTMLHeadingElement>(null);

  // The details open over the map: tell keyboard and screen-reader users by moving focus to them.
  useEffect(() => {
    heading.current?.focus({ preventScroll: true });
  }, [assetId]);

  return (
    <section
      aria-labelledby="asset-heading"
      className="panel"
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          onClose();
        }
      }}
    >
      <div className="panel-head">
        <h2 id="asset-heading" ref={heading} tabIndex={-1}>
          Asset {assetId}
        </h2>
        <button type="button" onClick={onClose}>
          Close <span className="visually-hidden">asset details</span>
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
            <dt>Source type</dt>
            <dd>{track.find((point) => point.reportId === asset.reportId)?.sourceType ?? (trackLoading ? 'Loading…' : '–')}</dd>
            <dt>Report</dt>
            <dd>{asset.reportId}</dd>
          </dl>
          <h3>Recent track</h3>
          {trackLoading ? (
            <p>Loading the recent track…</p>
          ) : track.length === 0 ? (
            <p>No earlier reports in the last ten minutes.</p>
          ) : (
            <table>
              <caption className="visually-hidden">Reports of the last ten minutes, oldest first</caption>
              <thead>
                <tr>
                  <th scope="col">Observed</th>
                  <th scope="col">Position</th>
                  <th scope="col">Source type</th>
                </tr>
              </thead>
              <tbody>
                {track.slice(-12).map((point) => (
                  <tr key={point.reportId}>
                    <td>{formatTime(point.observedAt)}</td>
                    <td>
                      {formatCoordinate(point.lat)}, {formatCoordinate(point.lon)}
                    </td>
                    <td>{point.sourceType ?? '–'}</td>
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
  return { asset: asset.data, track: track.data?.items ?? [], trackLoading: track.isPending };
}
