import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiFetch, headers } from './client';

function respond(status: number, body: unknown, contentType = 'application/json'): Response {
  return new Response(typeof body === 'string' ? body : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': contentType },
  });
}

afterEach(() => vi.unstubAllGlobals());

describe('headers', () => {
  it('adds the bearer token only when there is one', () => {
    expect(headers(undefined, false)).toEqual({ Accept: 'application/json' });
    expect(headers('abc', false).Authorization).toBe('Bearer abc');
  });

  it('declares JSON only when there is a body', () => {
    expect(headers('abc', true)['Content-Type']).toBe('application/json');
    expect(headers('abc', false)['Content-Type']).toBeUndefined();
  });
});

describe('apiFetch', () => {
  it('sends the token and returns the parsed answer', async () => {
    const fetchMock = vi.fn().mockResolvedValue(respond(200, { ok: true }));
    vi.stubGlobal('fetch', fetchMock);

    const result = await apiFetch<{ ok: boolean }>('/api/v1/stats', { token: 't0k3n' });

    expect(result).toEqual({ ok: true });
    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/v1/stats'); // a relative address: never another host
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer t0k3n');
  });

  it('turns a problem+json answer into an ApiError with the code of the contract', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        respond(
          409,
          { code: 'ALERT_ALREADY_CLAIMED', detail: 'The alert is already claimed by op02.' },
          'application/problem+json',
        ),
      ),
    );

    const error = await apiFetch('/api/v1/alerts/1/claim', { method: 'POST' }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(409);
    expect(apiError.code).toBe('ALERT_ALREADY_CLAIMED');
    expect(apiError.detail).toBe('The alert is already claimed by op02.');
  });

  it('gives a generic message when the error has no JSON body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond(502, '<html>bad gateway</html>', 'text/html')));
    const error = (await apiFetch('/x').catch((e: unknown) => e)) as ApiError;
    expect(error.status).toBe(502);
    expect(error.code).toBe('UNKNOWN');
    expect(error.detail).toContain('502');
  });

  it('reports a network failure as status 0', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    const error = (await apiFetch('/x').catch((e: unknown) => e)) as ApiError;
    expect(error.status).toBe(0);
    expect(error.code).toBe('NETWORK');
  });

  it('sends a JSON body for a POST', async () => {
    const fetchMock = vi.fn().mockResolvedValue(respond(200, {}));
    vi.stubGlobal('fetch', fetchMock);
    await apiFetch('/api/v1/auth/login', { method: 'POST', body: { username: 'a', password: 'b' } });
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe('POST');
    expect(init.body).toBe('{"username":"a","password":"b"}');
  });
});
