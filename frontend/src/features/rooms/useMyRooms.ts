import { useCallback, useEffect, useMemo, useState } from 'react';
import { getMyRooms } from './api';
import { createRoomListLoader, emptyRoomListState, type RoomListSession, type RoomListState } from './roomListLoader';

/**
 * Loads the signed-in account's rooms from the server. `reload` is bound to the login it was
 * created for, so a callback still running from a previous login cannot change the current list.
 */
export function useMyRooms(token: string | null, userId: number | null) {
  const session = useMemo<RoomListSession | null>(
    () => (token && userId !== null ? { token, userId } : null),
    [token, userId]
  );
  const [state, setState] = useState<RoomListState>(() => emptyRoomListState(userId, session !== null));
  const loader = useMemo(() => createRoomListLoader(getMyRooms, setState), []);

  useEffect(() => {
    void loader.setSession(session);
    return () => loader.dispose();
  }, [loader, session]);

  const reload = useCallback(() => loader.reload(session), [loader, session]);

  // Until the effect above starts the new account's request, show nothing from the previous account.
  const visible = state.userId === userId ? state : emptyRoomListState(userId, session !== null);
  return { rooms: visible.rooms, loading: visible.loading, error: visible.error, ready: !visible.loading && !visible.error, reload };
}
