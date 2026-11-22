import { useEffect, useMemo, useRef, useState } from 'react';
import type { ConnectionState } from '../alerts/liveStream';
import { AnnouncementQueue } from './announce';

/** A polite live region that only screen readers perceive. */
export function Announcer({ message }: { message: { id: number; text: string } }) {
  return (
    <div role="status" aria-live="polite" aria-atomic="true" className="visually-hidden">
      <span key={message.id}>{message.text}</span>
    </div>
  );
}

/** The queue of things to say to a screen reader, and the text currently in the live region. */
export function useAnnouncementQueue() {
  const [message, setMessage] = useState({ id: 0, text: '' });
  const counter = useRef(0);
  const queue = useMemo(
    () =>
      new AnnouncementQueue((text) => {
        counter.current += 1;
        setMessage({ id: counter.current, text });
      }),
    [],
  );
  useEffect(() => () => queue.stop(), [queue]);
  return { message, queue };
}

/** Says when live updates are lost and when they are back. The same is shown on screen. */
export function useConnectionAnnouncements(connection: ConnectionState, queue: AnnouncementQueue) {
  const previous = useRef<ConnectionState>('connecting');
  useEffect(() => {
    if (previous.current === 'live' && connection === 'reconnecting') {
      queue.addText('Live updates were interrupted. Reconnecting.');
    } else if (previous.current === 'reconnecting' && connection === 'live') {
      queue.addText('Live updates are back.');
    }
    previous.current = connection;
  }, [connection, queue]);
}
