import { boot, t, url } from './boot';

/** Error answered by the JSON API: {"error": "<message key>", "fields": {"<field>": "<message key>"}}. */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly key: string,
    public readonly fields: Record<string, string> = {},
  ) {
    super(key);
  }
}

let csrfToken = boot.csrfToken;

export function setCsrfToken(token: string): void {
  csrfToken = token;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', 'X-CSRF-Token': csrfToken };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let res: Response;
  try {
    res = await fetch(url(path), {
      method,
      credentials: 'same-origin',
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, 'error.network');
  }
  let data: unknown = null;
  try {
    data = await res.json();
  } catch {
    data = null;
  }
  if (!res.ok) {
    const d = (data ?? {}) as { error?: string; fields?: Record<string, string> };
    if (res.status === 401 && boot.page !== 'login') {
      window.location.href = url('/login');
    }
    throw new ApiError(res.status, d.error ?? 'error.internal', d.fields ?? {});
  }
  return data as T;
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body ?? {}),
  del: <T>(path: string) => request<T>('DELETE', path),
};

/** User-facing text of an error. */
export function errorText(e: unknown): string {
  return e instanceof ApiError ? t(e.key) : t('error.internal');
}

/** Field error text for a form field, or undefined. */
export function fieldError(e: unknown, field: string): string | undefined {
  if (e instanceof ApiError && e.fields[field]) return t(e.fields[field]);
  return undefined;
}
