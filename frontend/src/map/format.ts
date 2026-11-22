/** "2026-11-02T06:00:05.120Z" as "2026-11-02 06:00:05 UTC": all times are shown in UTC. */
export function formatTime(iso: string | undefined): string {
  if (!iso) {
    return '–';
  }
  const match = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2})/.exec(iso);
  return match ? `${match[1]} ${match[2]} UTC` : iso;
}

export function formatCoordinate(value: number): string {
  return value.toFixed(5);
}

export function formatNumber(value: number | null | undefined, unit: string): string {
  return value === null || value === undefined ? '–' : `${value} ${unit}`;
}

/** The start of the window of the recent track: a number of minutes before the latest report. */
export function minutesBefore(iso: string, minutes: number): string {
  const time = Date.parse(iso);
  // rounded down to the minute, so the request does not change with every report
  const start = Math.floor((time - minutes * 60_000) / 60_000) * 60_000;
  return new Date(start).toISOString();
}
