import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LoginForm } from './LoginForm';
import { SessionProvider, useSession } from './session';

function Probe() {
  const { session, endedReason } = useSession();
  return <p data-testid="state">{session ? `in:${session.username}:${session.role}` : `out:${endedReason ?? ''}`}</p>;
}

function renderApp() {
  render(
    <SessionProvider>
      <Probe />
      <LoginForm />
    </SessionProvider>,
  );
}

function loginAnswer(status: number, body: unknown) {
  return vi.fn().mockResolvedValue(
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }),
  );
}

function fillAndSubmit(user: string, password: string) {
  fireEvent.change(screen.getByLabelText('User name'), { target: { value: user } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } });
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}

beforeEach(() => vi.useRealTimers());
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('sign-in', () => {
  it('signs the user in and remembers the role', async () => {
    vi.stubGlobal(
      'fetch',
      loginAnswer(200, {
        accessToken: 'tok',
        tokenType: 'Bearer',
        expiresInSec: 1800,
        user: { username: 'op01', role: 'OPERATOR' },
      }),
    );
    renderApp();
    fillAndSubmit(' op01 ', 'secret');
    await waitFor(() => expect(screen.getByTestId('state').textContent).toBe('in:op01:OPERATOR'));
  });

  it('shows a clear message when the password is wrong', async () => {
    vi.stubGlobal('fetch', loginAnswer(401, { code: 'UNAUTHENTICATED', detail: 'no' }));
    renderApp();
    fillAndSubmit('op01', 'wrong');
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe('The user name or password is wrong.');
    expect(screen.getByTestId('state').textContent).toBe('out:');
  });

  it('says so when the server cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    renderApp();
    fillAndSubmit('op01', 'x');
    expect((await screen.findByRole('alert')).textContent).toBe('The server cannot be reached.');
  });

  it('ends the session when the token expires', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.stubGlobal(
      'fetch',
      loginAnswer(200, {
        accessToken: 'tok',
        tokenType: 'Bearer',
        expiresInSec: 60,
        user: { username: 'op01', role: 'OPERATOR' },
      }),
    );
    renderApp();
    fillAndSubmit('op01', 'secret');
    await waitFor(() => expect(screen.getByTestId('state').textContent).toBe('in:op01:OPERATOR'));

    act(() => {
      vi.advanceTimersByTime(61_000);
    });
    await waitFor(() => expect(screen.getByTestId('state').textContent).toContain('out:Your session expired'));
    expect((await screen.findByRole('alert')).textContent).toContain('session expired');
  });
});
