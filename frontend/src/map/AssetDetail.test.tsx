import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { AssetPosition, TrackPoint } from '../api/types';
import { AssetDetail } from './AssetDetail';

afterEach(cleanup);

const asset: AssetPosition = {
  assetId: 'AST-1',
  reportId: 'r2',
  observedAt: '2026-11-02T06:00:10.000Z',
  lat: 1,
  lon: 2,
};

const point = (reportId: string, extra: Partial<TrackPoint> = {}): TrackPoint => ({
  reportId,
  observedAt: '2026-11-02T06:00:00.000Z',
  lat: 1,
  lon: 2,
  ...extra,
});

describe('AssetDetail and the source type (CR-1)', () => {
  it('shows the source type of the latest report in the facts list', () => {
    render(
      <AssetDetail
        assetId="AST-1"
        asset={{ ...asset, source: 'GND-4' }}
        track={[point('r1', { sourceType: 'SATELLITE' }), point('r2', { source: 'GND-4', sourceType: 'GROUND' })]}
        onClose={() => {}}
      />,
    );
    const label = screen.getAllByText('Source type').find((el) => el.tagName === 'DT');
    expect(label?.nextElementSibling?.textContent).toBe('GROUND');
  });

  it('shows the source type of each track point', () => {
    render(
      <AssetDetail
        assetId="AST-1"
        asset={asset}
        track={[point('r1', { sourceType: 'SATELLITE' }), point('r2', { sourceType: 'UNKNOWN' })]}
        onClose={() => {}}
      />,
    );
    expect(screen.getByRole('columnheader', { name: 'Source type' })).toBeTruthy();
    expect(screen.getAllByText('SATELLITE')).toHaveLength(1); // only in the track table
    expect(screen.getAllByText('UNKNOWN')).toHaveLength(2); // the latest report (facts) and the table
  });

  it('still works when the backend is version 1.0.0 and sends no type', () => {
    render(<AssetDetail assetId="AST-1" asset={asset} track={[point('r1')]} onClose={() => {}} />);
    expect(screen.getAllByText('–').length).toBeGreaterThan(0);
  });
});
