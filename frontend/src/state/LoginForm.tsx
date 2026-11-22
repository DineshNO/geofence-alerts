import { useState } from 'react';
import type { FormEvent } from 'react';
import { ApiError } from '../api/client';
import { useSession } from './session';

export function LoginForm() {
  const { signIn, endedReason } = useSession();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await signIn(username.trim(), password);
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 401
          ? 'The user name or password is wrong.'
          : e instanceof ApiError && e.status === 0
            ? 'The server cannot be reached.'
            : 'Signing in failed. Please try again.',
      );
    } finally {
      setBusy(false);
    }
  }

  const message = error ?? endedReason;
  return (
    <main className="login">
      <h1>Alert console</h1>
      <form onSubmit={(event) => void submit(event)} aria-describedby={message ? 'login-message' : undefined}>
        <label>
          User name
          <input
            name="username"
            autoComplete="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            required
          />
        </label>
        <label>
          Password
          <input
            name="password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>
        {message && (
          <p id="login-message" role="alert" className="error">
            {message}
          </p>
        )}
        <button type="submit" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </main>
  );
}
