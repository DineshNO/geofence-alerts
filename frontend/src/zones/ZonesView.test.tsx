import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AssetPosition, Zone } from '../api/types';
import { ZonesView } from './ZonesView';

afterEach(cleanup);

const square = { type: 'Polygon', coordinates: [[[0, 0], [1, 0], [1, 1], [0, 0]]] } as Zone['geometry'];
const zones: Zone[] = [
  { zoneId: 'Z-01', name: 'Alpha Range', zoneClass: 'RESTRICTED', crossesAntimeridian: false, geometry: square },
  { zoneId: 'Z-09', name: 'India Advisory', zoneClass: 'ADVISORY', crossesAntimeridian: false, geometry: square },
];
const asset: AssetPosition = {
  assetId: 'AST-7',
  reportId: 'r7',
  observedAt: '2026-11-02T06:00:07.000Z',
  lat: 38.2,
  lon: 168.4,
};

function view(overrides: Partial<Parameters<typeof ZonesView>[0]> = {}) {
  const props = {
    zones,
    occupancy: [
      { zoneId: 'Z-01', assetCount: 3 },
      { zoneId: 'Z-09', assetCount: 0 },
    ],
    selectedZoneId: null,
    assets: [] as AssetPosition[],
    assetsLoading: false,
    onSelectZone: vi.fn(),
    onShowAsset: vi.fn(),
    ...overrides,
  };
  render(<ZonesView {...props} />);
  return props;
}

describe('ZonesView', () => {
  it('lists every zone with its class and the number of assets inside, as a table', () => {
    view();
    const table = screen.getByRole('table', { name: /zones, their class/i });
    expect(within(table).getByRole('rowheader', { name: 'Z-01 Alpha Range' })).toBeTruthy();
    expect(within(table).getByText('Restricted')).toBeTruthy();
    expect(within(table).getByText('Advisory')).toBeTruthy();
    expect(screen.getByRole('button', { name: /3 assets inside Z-01 Alpha Range/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /0 assets inside Z-09 India Advisory/ })).toBeTruthy();
  });

  it('opens the list of a zone from the keyboard-operable count', () => {
    const props = view();
    fireEvent.click(screen.getByRole('button', { name: /3 assets inside Z-01/ }));
    expect(props.onSelectZone).toHaveBeenCalledWith('Z-01');
  });

  it('shows the assets inside the chosen zone and lets one be shown on the map', () => {
    const props = view({ selectedZoneId: 'Z-01', assets: [asset] });
    const list = screen.getByRole('table', { name: /assets inside alpha range/i });
    expect(within(list).getByText('38.20000, 168.40000')).toBeTruthy();
    fireEvent.click(within(list).getByRole('button', { name: 'AST-7' }));
    expect(props.onShowAsset).toHaveBeenCalledWith('AST-7');
  });

  it('says so when a zone is empty and while it is loading', () => {
    view({ selectedZoneId: 'Z-09' });
    expect(screen.getByText('No asset is inside this zone now.')).toBeTruthy();
    cleanup();
    view({ selectedZoneId: 'Z-09', assetsLoading: true });
    expect(screen.getByText('Loading…')).toBeTruthy();
  });

  it('gives every column a header, for screen readers', () => {
    view({ selectedZoneId: 'Z-01', assets: [asset] });
    expect(screen.getAllByRole('columnheader').length).toBeGreaterThanOrEqual(6);
  });
});
