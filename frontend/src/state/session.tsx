import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { apiFetch } from '../api/client';
import type { LoginResponse, Role } from '../api/types';

export interface Session {
  token: string;
  username: string;
  role: Role;
  /** When the token stops being accepted, as a time in milliseconds. */
  expiresAt: number;
}

interface SessionContextValue {
  session: Session | null;
  /** Why the last session ended, shown on the sign-in screen. */
  endedReason: string | null;
  signIn(username: string, password: string): Promise<void>;
  signOut(): void;
  /** Ends the session because the server no longer accepts the token. */
  expire(reason: string): void;
}

const SessionContext = createContext<SessionContextValue | null>(null);

/**
 * The signed-in user. The token is kept in memory only: reloading the page means signing in again,
 * and nothing secret is left in the browser's storage. When the token expires the session ends, as
 * the server also closes the live stream at that moment.
 */
export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [endedReason, setEndedReason] = useState<string | null>(null);

  const signIn = useCallback(async (username: string, password: string) => {
    const response = await apiFetch<LoginResponse>('/api/v1/auth/login', {
      method: 'POST',
      body: { username, password },
    });
    setEndedReason(null);
    setSession({
      token: response.accessToken,
      username: response.user.username,
      role: response.user.role,
      expiresAt: Date.now() + response.expiresInSec * 1000,
    });
  }, []);

  const signOut = useCallback(() => {
    setSession(null);
    setEndedReason(null);
  }, []);

  const expire = useCallback((reason: string) => {
    setSession(null);
    setEndedReason(reason);
  }, []);

  useEffect(() => {
    if (!session) {
      return undefined;
    }
    const wait = Math.max(0, session.expiresAt - Date.now());
    const timer = window.setTimeout(() => expire('Your session expired. Please sign in again.'), wait);
    return () => window.clearTimeout(timer);
  }, [session, expire]);

  const value = useMemo(
    () => ({ session, endedReason, signIn, signOut, expire }),
    [session, endedReason, signIn, signOut, expire],
  );
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext);
  if (!value) {
    throw new Error('useSession must be used inside a SessionProvider.');
  }
  return value;
}

/** The session of a signed-in screen; those screens only render when there is one. */
export function useSignedIn(): Session {
  const { session } = useSession();
  if (!session) {
    throw new Error('This screen needs a signed-in user.');
  }
  return session;
}
