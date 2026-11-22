import type { AlertEvent } from '../api/types';

export const ANNOUNCE_INTERVAL_MS = 4_000;

/** One sentence for the new alerts of a window: the details of one, or the count of many. */
export function describeNewAlerts(events: readonly AlertEvent[]): string {
  const [only] = events;
  if (events.length === 0 || !only) {
    return '';
  }
  return events.length === 1
    ? `New alert: asset ${only.assetId} entered zone ${only.zoneId}.`
    : `${events.length} new alerts.`;
}

/**
 * Collects what is to be announced to a screen reader and says it at most once every few seconds,
 * all together, so a burst of alerts is one sentence ("37 new alerts") and not 37 interruptions.
 */
export class AnnouncementQueue {
  private opened: AlertEvent[] = [];
  private texts: string[] = [];
  private timer: ReturnType<typeof setTimeout> | null = null;
  private lastEmit = Number.NEGATIVE_INFINITY;

  constructor(
    private readonly emit: (text: string) => void,
    private readonly intervalMs = ANNOUNCE_INTERVAL_MS,
  ) {}

  addOpened(events: readonly AlertEvent[]) {
    this.opened.push(...events);
    this.schedule();
  }

  addText(text: string) {
    this.texts.push(text);
    this.schedule();
  }

  private schedule() {
    if (this.timer !== null) {
      return;
    }
    const wait = Math.max(0, this.lastEmit + this.intervalMs - Date.now());
    this.timer = setTimeout(() => this.flush(), wait);
  }

  private flush() {
    this.timer = null;
    const parts = [...this.texts, describeNewAlerts(this.opened)].filter((p) => p !== '');
    this.texts = [];
    this.opened = [];
    if (parts.length > 0) {
      this.lastEmit = Date.now();
      this.emit(parts.join(' '));
    }
  }

  stop() {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.texts = [];
    this.opened = [];
  }
}
