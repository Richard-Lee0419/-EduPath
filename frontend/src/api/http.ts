import type { ApiResponse } from '@/types/api';

export const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api';

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    ...init,
    headers: buildAuthHeaders({
      'Content-Type': 'application/json',
      ...normalizeHeaders(init?.headers),
    }),
  });

  return unwrapApiResponse<T>(response);
}

export async function apiGet<T>(path: string, params?: Record<string, unknown>): Promise<T> {
  const url = new URL(`${apiBaseUrl}${path}`, window.location.origin);
  Object.entries(params || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      url.searchParams.set(key, String(value));
    }
  });

  const response = await fetch(url, {
    headers: buildAuthHeaders(),
  });
  return unwrapApiResponse<T>(response);
}

export async function apiPost<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    method: 'POST',
    headers: buildAuthHeaders({ 'Content-Type': 'application/json' }),
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return unwrapApiResponse<T>(response);
}

export async function apiPatch<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    method: 'PATCH',
    headers: buildAuthHeaders({ 'Content-Type': 'application/json' }),
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return unwrapApiResponse<T>(response);
}

export async function apiDelete<T>(path: string): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    method: 'DELETE',
    headers: buildAuthHeaders(),
  });
  return unwrapApiResponse<T>(response);
}

export async function apiUpload<T>(path: string, formData: FormData): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    method: 'POST',
    headers: buildAuthHeaders(),
    body: formData,
  });
  return unwrapApiResponse<T>(response);
}

function buildAuthHeaders(headers?: Record<string, string>) {
  const token = localStorage.getItem('edupath_token');
  return {
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...(headers || {}),
  };
}

function normalizeHeaders(headers?: HeadersInit): Record<string, string> {
  if (!headers) {
    return {};
  }
  if (headers instanceof Headers) {
    return Object.fromEntries(headers.entries());
  }
  if (Array.isArray(headers)) {
    return Object.fromEntries(headers);
  }
  return headers;
}

async function unwrapApiResponse<T>(response: Response): Promise<T> {
  const contentType = response.headers.get('content-type') ?? '';
  const body = contentType.includes('application/json')
    ? ((await response.json()) as Partial<ApiResponse<T>>)
    : ({ message: await response.text() } as Partial<ApiResponse<T>>);

  if (!response.ok) {
    throw new Error(normalizeApiErrorMessage(body.message, `Request failed: ${response.status} ${response.statusText}`));
  }

  if (typeof body.code === 'number' && body.code !== 0) {
    throw new Error(normalizeApiErrorMessage(body.message, 'API returned an error'));
  }

  return body.data as T;
}

function normalizeApiErrorMessage(message: string | undefined, fallback: string) {
  const rawMessage = message?.trim();
  if (!rawMessage) {
    return fallback;
  }

  if (/(authorization|bearer|jwt|refresh token|access token|\btoken\b)/i.test(rawMessage)) {
    return '登录状态已失效，请重新登录';
  }

  return rawMessage;
}
