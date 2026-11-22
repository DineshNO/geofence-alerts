import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Alert } from '../api/types';
import { SessionContext } from '../state/session';
import type { Session } from '../state/session';
import { AlertRow } from './AlertRow';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const operator: Session = { token: 'tok', username: 'op01', role: 'OPERATOR', expiresAt: Date.now() + 1e6 };
const supervisor: Session = { token: 'tok', username: 'sup01', role: 'SUPERVISOR', expiresAt: Date.now() + 1e6 };

function alert(overrides: Partial<Alert> = {}): Alert {
  return {
    alertId: '5',
    assetId: 'AST-9',
    zoneId: 'Z-01',
    status: 'OPEN',
    openedAt: '2026-11-02T06:00:00.000Z',
    triggerReportId: 'r1',
    ...overrides,
  };
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function show(a: Alert, session: Session, onShowAsset = vi.fn()) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <SessionContext.Provider
        value={{ session, endedReason: null, signIn: async () => undefined, signOut: () => undefined, expire: () => undefined }}
      >
        <ul>
          <AlertRow alert={a} me={session} onShowAsset={onShowAsset} />
        </ul>
      </SessionContext.Provider>
    </QueryClientProvider>,
  );
  return onShowAsset;
}

describe('AlertRow', () => {
  it('claims an open alert with the user token', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(200, alert({ status: 'CLAIMED', claimedBy: 'op01' })));
    vi.stubGlobal('fetch', fetchMock);
    show(alert(), operator);

    fireEvent.click(screen.getByRole('button', { name: /claim alert 5, asset AST-9, zone Z-01/i }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/alerts/5/claim');
    expect(init.method).toBe('POST');
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer tok');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('explains a lost race instead of showing a generic error, and looks at the alert again', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(json(409, { code: 'ALERT_ALREADY_CLAIMED', detail: 'The alert is already claimed by op02.' }))
      .mockResolvedValueOnce(json(200, alert({ status: 'CLAIMED', claimedBy: 'op02' })));
    vi.stubGlobal('fetch', fetchMock);
    show(alert(), operator);

    fireEvent.click(screen.getByRole('button', { name: /^claim alert 5/i }));

    const message = await screen.findByRole('alert');
    expect(message.textContent).toBe('Not claimed: The alert is already claimed by op02.');
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect((fetchMock.mock.calls[1] as [string])[0]).toBe('/api/v1/alerts/5'); // the refetch
  });

  it('offers release and resolve to the owner, and asks for a note before resolving', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(200, alert({ status: 'RESOLVED', resolvedBy: 'op01' })));
    vi.stubGlobal('fetch', fetchMock);
    show(alert({ status: 'CLAIMED', claimedBy: 'op01' }), operator);

    expect(screen.getByRole('button', { name: /release alert 5/i })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /resolve alert 5/i }));

    const submit = screen.getByRole('button', { name: 'Resolve with this note' }) as HTMLButtonElement;
    expect(submit.disabled).toBe(true); // no note yet
    fireEvent.change(screen.getByLabelText(/note/i), { target: { value: '  Vessel left the zone  ' } });
    expect(submit.disabled).toBe(false);
    fireEvent.click(submit);

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/alerts/5/resolve');
    expect(init.body).toBe('{"note":"Vessel left the zone"}');
  });

  it('shows no commands to an operator for an alert somebody else owns, but does to a supervisor', () => {
    show(alert({ status: 'CLAIMED', claimedBy: 'op02' }), operator);
    expect(screen.queryByRole('button', { name: /release/i })).toBeNull();
    expect(screen.queryByRole('button', { name: /resolve/i })).toBeNull();
    expect(screen.getByText(/owner op02/)).toBeTruthy();
    cleanup();

    show(alert({ status: 'CLAIMED', claimedBy: 'op02' }), supervisor);
    expect(screen.getByRole('button', { name: /release alert 5/i })).toBeTruthy();
    expect(screen.getByRole('button', { name: /resolve alert 5/i })).toBeTruthy();
  });

  it('shows who resolved an alert and the note, with no commands', () => {
    show(alert({ status: 'RESOLVED', claimedBy: 'op01', resolvedBy: 'op01', resolutionNote: 'All clear' }), operator);
    expect(screen.getByText(/resolved by op01/)).toBeTruthy();
    expect(screen.getByText('“All clear”')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /claim|release|resolve/i })).toBeNull();
  });

  it('lets the operator show the asset on the map', () => {
    const onShowAsset = show(alert(), operator);
    fireEvent.click(screen.getByRole('button', { name: 'AST-9' }));
    expect(onShowAsset).toHaveBeenCalledWith('AST-9');
  });
});

describe('the resolve section expands and collapses', () => {
  it('opens and closes the note form with the Resolve button, and closes it with Escape', () => {
    show(alert({ status: 'CLAIMED', claimedBy: 'op01' }), operator);
    const toggle = screen.getByRole('button', { name: /resolve alert 5/i });
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByLabelText(/Note/)).toBeNull();
    fireEvent.click(toggle);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByLabelText(/Note/)).toBeTruthy();
    fireEvent.click(toggle);
    expect(screen.queryByLabelText(/Note/)).toBeNull();
    fireEvent.click(toggle);
    fireEvent.keyDown(screen.getByLabelText(/Note/), { key: 'Escape' });
    expect(screen.queryByLabelText(/Note/)).toBeNull();
    expect(document.activeElement).toBe(toggle);
  });
});
