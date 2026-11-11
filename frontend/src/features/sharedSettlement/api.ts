import { request } from '../../httpClient';
import type { SharedSnapshot } from './types';
export const getSharedSnapshot = (token: string, signal?: AbortSignal) =>
  request<SharedSnapshot>(`/api/shared/settlements/${encodeURIComponent(token)}`, {signal,cache:'no-store',referrerPolicy:'no-referrer'});
