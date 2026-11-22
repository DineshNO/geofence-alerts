import { useState } from 'react';
import { useSignedIn } from '../state/session';
import { AlertRow } from './AlertRow';
import { ConnectionBadge } from './ConnectionBadge';
import type { LiveAlerts } from './useLiveAlerts';
import { useAlertList } from './queries';

const MAX_SHOWN = 200;

interface Props {
  live: LiveAlerts;
  onShowAsset(assetId: string): void;
}

/** The alerts that need attention (open and claimed), or the resolved ones, newest first. */
export function AlertPanel({ live, onShowAsset }: Props) {
  const me = useSignedIn();
  const [view, setView] = useState<'active' | 'resolved'>('active');
  const open = useAlertList('OPEN');
  const claimed = useAlertList('CLAIMED');
  const resolved = useAlertList('RESOLVED', view === 'resolved');

  const lists = view === 'active' ? [open, claimed] : [resolved];
  const loading = lists.some((q) => q.isPending);
  const failed = lists.some((q) => q.isError);
  const all = lists.flatMap((q) => q.data ?? []).sort((a, b) => Number(b.alertId) - Number(a.alertId));
  const shown = all.slice(0, MAX_SHOWN);

  return (
    <section aria-labelledby="alerts-heading" className="panel">
      <div className="panel-head">
        <h2 id="alerts-heading" tabIndex={-1}>
          Alerts
        </h2>
        <div role="group" aria-label="Show" className="segmented">
          <button type="button" aria-pressed={view === 'active'} onClick={() => setView('active')}>
            Active ({(open.data?.length ?? 0) + (claimed.data?.length ?? 0)})
          </button>
          <button type="button" aria-pressed={view === 'resolved'} onClick={() => setView('resolved')}>
            Resolved
          </button>
        </div>
      </div>
      <ConnectionBadge state={live.state} interruptedSince={live.interruptedSince} />
      {failed && (
        <p role="alert" className="message">
          The alerts could not be loaded.
        </p>
      )}
      {loading ? (
        <p>Loading…</p>
      ) : shown.length === 0 ? (
        <p>{view === 'active' ? 'No alerts need attention.' : 'No resolved alerts.'}</p>
      ) : (
        <>
          {all.length > shown.length && (
            <p className="note">
              Showing the newest {MAX_SHOWN} of {all.length}.
            </p>
          )}
          <ul className="alert-list">
            {shown.map((alert) => (
              <AlertRow key={alert.alertId} alert={alert} me={me} onShowAsset={onShowAsset} />
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
