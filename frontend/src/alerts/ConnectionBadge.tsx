import type { ConnectionState } from './liveStream';

interface Props {
  state: ConnectionState;
  interruptedSince: number | null;
}

function clock(time: number): string {
  return `${new Date(time).toISOString().slice(11, 19)} UTC`;
}

/** Shows whether live updates are working, in words and not only in colour. */
export function ConnectionBadge({ state, interruptedSince }: Props) {
  const label =
    state === 'live'
      ? 'Live updates on'
      : state === 'connecting'
        ? 'Connecting to live updates…'
        : `Live updates interrupted${interruptedSince ? ` since ${clock(interruptedSince)}` : ''}. Reconnecting…`;
  return (
    <p role="status" className={`connection ${state}`}>
      <span className="dot" aria-hidden="true" />
      {label}
    </p>
  );
}
