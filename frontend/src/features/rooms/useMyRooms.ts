import { useCallback, useEffect, useRef, useState } from 'react';
import { getMyRooms } from './api';
import type { RoomListItem } from './types';

type RoomListState = {
  userId: number | null;
  rooms: RoomListItem[];
  loading: boolean;
  error: string | null;
};

/**
 * Loads the signed-in account's rooms from the server. A response that arrives after a newer request,
 * a logout or an account switch is dropped, so one account never sees another account's rooms.
 */
export function useMyRooms(token: string | null, userId: number | null) {
  const [state, setState] = useState<RoomListState>({ userId, rooms: [], loading: token !== null, error: null });
  const generation = useRef(0);
  const inFlight = useRef<AbortController | null>(null);

  const reload = useCallback(async (): Promise<void> => {
    inFlight.current?.abort();
    const current = ++generation.current;
    if (!token || userId === null) {
      setState({ userId, rooms: [], loading: false, error: null });
      return;
    }

    const controller = new AbortController();
    inFlight.current = controller;
    setState((previous) => ({
      userId,
      rooms: previous.userId === userId ? previous.rooms : [],
      loading: true,
      error: null
    }));
    try {
      const result = await getMyRooms(token, controller.signal);
      if (current !== generation.current) return;
      setState({ userId, rooms: result.rooms, loading: false, error: null });
    } catch (error) {
      if (current !== generation.current) return;
      setState((previous) => ({
        userId,
        rooms: previous.userId === userId ? previous.rooms : [],
        loading: false,
        error: error instanceof Error ? error.message : '방 목록을 불러오지 못했습니다.'
      }));
    }
  }, [token, userId]);

  useEffect(() => {
    void reload();
    return () => {
      inFlight.current?.abort();
      generation.current += 1;
    };
  }, [reload]);

  // Until the effect above starts the new account's request, show nothing from the previous account.
  const visible = state.userId === userId ? state : { userId, rooms: [], loading: token !== null, error: null };
  return { rooms: visible.rooms, loading: visible.loading, error: visible.error, ready: !visible.loading && !visible.error, reload };
}
