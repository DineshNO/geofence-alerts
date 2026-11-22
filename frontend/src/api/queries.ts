import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useSignedIn } from '../state/session';
import { minutesBefore } from '../map/format';
import { apiFetch } from './client';
import type { AssetPosition, Page, TrackPoint, Zone } from './types';

/** The zones are static reference data: fetched once. */
export function useZones() {
  const { token } = useSignedIn();
  return useQuery({
    queryKey: ['zones'],
    queryFn: async ({ signal }) =>
      (await apiFetch<{ items: Zone[] }>('/api/v1/zones', { token, signal })).items,
    staleTime: Infinity,
  });
}

/**
 * The assets in the visible part of the map, asked again every second. A newer request cancels the
 * one before it when the map moves, and the previous answer stays on screen until the next arrives.
 */
export function useAssetsInView(bbox: string) {
  const { token } = useSignedIn();
  return useQuery({
    queryKey: ['assets', bbox],
    queryFn: ({ signal }) =>
      apiFetch<Page<AssetPosition>>(`/api/v1/assets?bbox=${encodeURIComponent(bbox)}&limit=5000`, {
        token,
        signal,
      }),
    refetchInterval: 1000,
    placeholderData: keepPreviousData,
  });
}

export function useAsset(assetId: string | null) {
  const { token } = useSignedIn();
  return useQuery({
    queryKey: ['asset', assetId],
    queryFn: ({ signal }) =>
      apiFetch<AssetPosition>(`/api/v1/assets/${encodeURIComponent(assetId ?? '')}`, { token, signal }),
    enabled: assetId !== null,
    refetchInterval: 1000,
  });
}

/** The last ten minutes before the asset's latest report (the feed's times are in the past). */
export function useRecentTrack(assetId: string | null, latestObservedAt: string | undefined) {
  const { token } = useSignedIn();
  const from = latestObservedAt ? minutesBefore(latestObservedAt, 10) : null;
  return useQuery({
    queryKey: ['track', assetId, from],
    queryFn: ({ signal }) =>
      apiFetch<Page<TrackPoint>>(
        `/api/v1/assets/${encodeURIComponent(assetId ?? '')}/track?from=${encodeURIComponent(from ?? '')}&limit=500`,
        { token, signal },
      ),
    enabled: assetId !== null && from !== null,
    refetchInterval: 3000,
  });
}
