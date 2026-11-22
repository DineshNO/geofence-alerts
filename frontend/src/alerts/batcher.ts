import type { AlertEvent } from '../api/types';

export interface Flush {
  /** The distinct alerts that changed in the window, oldest first. */
  alertIds: string[];
  /** The events of the window, in order. */
  events: AlertEvent[];
  /** Too many alerts changed to refetch one by one: refetch the lists instead. */
  overflow: boolean;
}

export const BATCH_WINDOW_MS = 250;
export const MAX_ALERTS_PER_BATCH = 20;

/**
 * Collects the events of a short window, so a burst (many alerts at once, as when the feed is
 * replayed fast) becomes one refetch of each distinct alert, or one refetch of the lists when more
 * than twenty changed, and not one request per event.
 */
export class EventBatcher {
  private events: AlertEvent[] = [];
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private readonly onFlush: (flush: Flush) => void,
    private readonly windowMs = BATCH_WINDOW_MS,
    private readonly maxAlerts = MAX_ALERTS_PER_BATCH,
  ) {}

  add(event: AlertEvent) {
    this.events.push(event);
    if (this.timer === null) {
      this.timer = setTimeout(() => this.flush(), this.windowMs);
    }
  }

  flush() {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    if (this.events.length === 0) {
      return;
    }
    const events = this.events;
    this.events = [];
    const alertIds = [...new Set(events.map((e) => e.alertId))];
    this.onFlush({ alertIds, events, overflow: alertIds.length > this.maxAlerts });
  }

  stop() {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.events = [];
  }
}
