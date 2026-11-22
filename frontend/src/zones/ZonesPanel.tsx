import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { apiFetch } from '../api/client';
import { useZones } from '../api/queries';
import type { ZoneAssets, ZoneOccupancy } from '../api/types';
import { useSignedIn } from '../state/session';
import { ZonesView } from './ZonesView';

/** Loads the zones, the count in each, and the assets of the chosen zone (asked again every 2 s). */
export function ZonesPanel({ onShowAsset }: { onShowAsset(assetId: string): void }) {
  const { token } = useSignedIn();
  const [zoneId, setZoneId] = useState<string | null>(null);
  const zones = useZones();
  const occupancy = useQuery({
    queryKey: ['occupancy'],
    queryFn: async ({ signal }) =>
      (await apiFetch<{ items: ZoneOccupancy[] }>('/api/v1/zones/occupancy', { token, signal })).items,
    refetchInterval: 2000,
  });
  const assets = useQuery({
    queryKey: ['zone-assets', zoneId],
    queryFn: ({ signal }) =>
      apiFetch<ZoneAssets>(`/api/v1/zones/${encodeURIComponent(zoneId ?? '')}/assets?limit=500`, { token, signal }),
    enabled: zoneId !== null,
    refetchInterval: 2000,
  });

  return (
    <ZonesView
      zones={zones.data ?? []}
      occupancy={occupancy.data ?? []}
      selectedZoneId={zoneId}
      assets={assets.data?.items ?? []}
      assetsLoading={zoneId !== null && assets.isPending}
      onSelectZone={setZoneId}
      onShowAsset={onShowAsset}
    />
  );
}
