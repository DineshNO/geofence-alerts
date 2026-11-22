/** An error answered by the API (problem+json), or a network failure (status 0). */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly detail: string,
  ) {
    super(detail);
    this.name = 'ApiError';
  }
}

interface Problem {
  status?: number;
  code?: string;
  detail?: string;
  title?: string;
}

export interface RequestOptions {
  method?: 'GET' | 'POST';
  token?: string;
  body?: unknown;
  signal?: AbortSignal;
}

/** Builds the request headers: a bearer token when there is one, JSON when there is a body. */
export function headers(token: string | undefined, hasBody: boolean): Record<string, string> {
  const result: Record<string, string> = { Accept: 'application/json' };
  if (token) {
    result.Authorization = `Bearer ${token}`;
  }
  if (hasBody) {
    result['Content-Type'] = 'application/json';
  }
  return result;
}

/** Calls the API on the same origin (relative address) and returns the parsed JSON. */
export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  let response: Response;
  try {
    response = await fetch(path, {
      method: options.method ?? 'GET',
      headers: headers(options.token, options.body !== undefined),
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal ?? null,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error;
    }
    throw new ApiError(0, 'NETWORK', 'The server cannot be reached.');
  }
  if (!response.ok) {
    throw await toApiError(response);
  }
  return (await response.json()) as T;
}

async function toApiError(response: Response): Promise<ApiError> {
  let problem: Problem = {};
  try {
    problem = (await response.json()) as Problem;
  } catch {
    // not a JSON body: fall through to a generic message
  }
  return new ApiError(
    response.status,
    problem.code ?? 'UNKNOWN',
    problem.detail ?? problem.title ?? `The request failed (${response.status}).`,
  );
}
