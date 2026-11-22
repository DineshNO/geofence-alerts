import { useState } from 'react';
import type { FormEvent } from 'react';
import type { Alert } from '../api/types';
import { formatTime } from '../map/format';
import type { Session } from '../state/session';
import { explainCommandError } from './explain';
import { useAlertCommands } from './queries';

interface Props {
  alert: Alert;
  me: Session;
  onShowAsset(assetId: string): void;
}

/** One alert, with the commands this user may give. A refused command is explained in place. */
export function AlertRow({ alert, me, onShowAsset }: Props) {
  const commands = useAlertCommands();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [resolving, setResolving] = useState(false);
  const [note, setNote] = useState('');

  const supervisor = me.role === 'SUPERVISOR';
  const mine = alert.claimedBy === me.username;
  const label = `alert ${alert.alertId}, asset ${alert.assetId}, zone ${alert.zoneId}`;

  async function give(command: 'claim' | 'release' | 'resolve', text?: string) {
    setBusy(true);
    setMessage(null);
    try {
      await commands.run(alert.alertId, command, text);
      setResolving(false);
      setNote('');
    } catch (error) {
      setMessage(explainCommandError(error));
      await commands.refresh(alert.alertId); // show the alert as it really is now
    } finally {
      setBusy(false);
    }
  }

  function submitResolve(event: FormEvent) {
    event.preventDefault();
    void give('resolve', note.trim());
  }

  return (
    <li className={`alert-row ${alert.status.toLowerCase()}`} aria-label={label}>
      <div className="alert-line">
        <span className={`status ${alert.status.toLowerCase()}`}>{alert.status}</span>
        <button type="button" className="link" onClick={() => onShowAsset(alert.assetId)}>
          {alert.assetId}
        </button>
        <span>in {alert.zoneId}</span>
      </div>
      <div className="alert-meta">
        Opened {formatTime(alert.openedAt)}
        {alert.claimedBy && alert.status !== 'OPEN' ? ` · owner ${alert.claimedBy}` : ''}
        {alert.status === 'RESOLVED' ? ` · resolved by ${alert.resolvedBy ?? '–'}` : ''}
      </div>
      {alert.status === 'RESOLVED' && alert.resolutionNote && <p className="note">“{alert.resolutionNote}”</p>}

      <div className="alert-actions">
        {alert.status === 'OPEN' && (
          <button type="button" className="primary" disabled={busy} onClick={() => void give('claim')} aria-label={`Claim ${label}`}>
            Claim
          </button>
        )}
        {alert.status === 'CLAIMED' && (mine || supervisor) && !resolving && (
          <>
            <button type="button" disabled={busy} onClick={() => void give('release')} aria-label={`Release ${label}`}>
              Release
            </button>
            <button type="button" className="primary" disabled={busy} onClick={() => setResolving(true)} aria-label={`Resolve ${label}`}>
              Resolve…
            </button>
          </>
        )}
      </div>

      {resolving && (
        <form className="resolve" onSubmit={submitResolve}>
          <label>
            Note (1 to 500 characters)
            <textarea value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} rows={3} required autoFocus />
          </label>
          <div className="alert-actions">
            <button type="submit" className="primary" disabled={busy || note.trim() === ''}>
              Resolve with this note
            </button>
            <button type="button" onClick={() => setResolving(false)}>
              Cancel
            </button>
          </div>
        </form>
      )}

      {message && (
        <p role="alert" className="message">
          {message}
        </p>
      )}
    </li>
  );
}
