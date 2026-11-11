import { request } from '../../httpClient';
import type { RoomListResult } from './types';

export function getMyRooms(token: string, signal?: AbortSignal) {
  return request<RoomListResult>('/api/rooms', { token, signal });
}
