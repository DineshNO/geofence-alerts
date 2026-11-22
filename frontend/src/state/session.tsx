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

export interface SessionContextValue {
  session: Session | null;
  /** Why the last session ended, shown on the sign-in screen. */
  endedReason: string | null;
  signIn(username: string, password: string): Promise<void>;
  signOut(): void;
  /** Ends the session because the server no longer accepts the token. */
  expire(reason: string): void;
}

export const SessionContext = createContext<SessionContextValue | null>(null);

const STORAGE_KEY = 'geoalert.session';

/** The session saved by this tab, if it has not expired yet. Storage may be blocked: then none. */
function restore(): Session | null {
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const saved = JSON.parse(raw) as Partial<Session>;
    if (
      typeof saved.token === 'string' &&
      typeof saved.username === 'string' &&
      typeof saved.role === 'string' &&
      typeof saved.expiresAt === 'number' &&
      saved.expiresAt > Date.now()
    ) {
      return saved as Session;
    }
    window.sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // unreadable or blocked storage: start signed out
  }
  return null;
}

function save(session: Session | null): void {
  try {
    if (session) {
      window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    } else {
      window.sessionStorage.removeItem(STORAGE_KEY);
    }
  } catch {
    // blocked storage: the session simply lasts until the page is reloaded
  }
}

/**
 * The signed-in user. The session (token, user, expiry) is kept in this tab's sessionStorage, so a
 * reload keeps you signed in until the token expires (30 minutes) or you sign out; closing the tab
 * forgets it. When the token expires the session ends, as the server also closes the live stream at
 * that moment. Trade-off: a script running on the page could read the token.
 */
export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSessionState] = useState<Session | null>(restore);
  const setSession = useCallback((next: Session | null) => {
    save(next);
    setSessionState(next);
  }, []);
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
  }, [setSession]);

  const signOut = useCallback(() => {
    setSession(null);
    setEndedReason(null);
  }, [setSession]);

  const expire = useCallback((reason: string) => {
    setSession(null);
    setEndedReason(reason);
  }, [setSession]);

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
