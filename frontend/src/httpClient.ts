import type { ApiEnvelope } from './types';

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';

export type RequestOptions = RequestInit & {
  token?: string | null;
};

export class ApiError extends Error {
  constructor(message: string, public status: number, public reason?: string, public retryAfterMs?: number) {
    super(message);
    this.name = 'ApiError';
  }
}

function retryAfterMilliseconds(value: string | null): number | undefined {
  if (!value) return undefined;
  const seconds = Number(value);
  const delay = Number.isFinite(seconds) ? seconds * 1000 : Date.parse(value) - Date.now();
  return Number.isFinite(delay) && delay >= 0 ? delay : undefined;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers = new Headers(options.headers);
  const isFormData = options.body instanceof FormData;

  if (!isFormData && options.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  if (options.token) {
    headers.set('Authorization', `Bearer ${options.token}`);
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers
  });

  if (response.status === 204) {
    return undefined as T;
  }

  const envelope = await response.json().catch((error: unknown) => {
    // HTML/plain-text error pages still carry useful HTTP metadata. Transport failures must
    // keep their original type so polling can retry a connection lost after response headers.
    if (error instanceof SyntaxError) return null;
    throw error;
  }) as ApiEnvelope<T> | null;
  if (!response.ok || !envelope?.success) {
    throw new ApiError(envelope?.error?.message ?? '요청을 처리하지 못했습니다.', response.status,
      envelope?.error?.details?.[0]?.reason, retryAfterMilliseconds(response.headers.get('Retry-After')));
  }

  return envelope.data as T;
}
