import type { RoomListItem, RoomListResult } from './types';

export type RoomListState = {
  userId: number | null;
  rooms: RoomListItem[];
  loading: boolean;
  error: string | null;
};

/** One login. A new object is created for every login, so an old login's reload can be told apart. */
export type RoomListSession = { token: string; userId: number };

type FetchRooms = (token: string, signal: AbortSignal) => Promise<RoomListResult>;
type Publish = (update: (previous: RoomListState) => RoomListState) => void;

export const emptyRoomListState = (userId: number | null, loading: boolean): RoomListState =>
  ({ userId, rooms: [], loading, error: null });

/**
 * Loads the room list for the current login only. A reload requested for an earlier login
 * (for example when a request started before logout finishes later) is ignored: it neither
 * cancels the current request nor touches the current state. Responses that arrive after a
 * newer request or a login change are dropped as well.
 */
export function createRoomListLoader(fetchRooms: FetchRooms, publish: Publish) {
  let current: RoomListSession | null = null;
  let generation = 0;
  let inFlight: AbortController | null = null;

  async function load(target: RoomListSession | null): Promise<void> {
    if (target !== current) return;
    inFlight?.abort();
    const requestGeneration = ++generation;
    if (target === null) {
      inFlight = null;
      publish(() => emptyRoomListState(null, false));
      return;
    }

    const controller = new AbortController();
    inFlight = controller;
    publish((previous) => ({
      userId: target.userId,
      rooms: previous.userId === target.userId ? previous.rooms : [],
      loading: true,
      error: null
    }));
    try {
      const result = await fetchRooms(target.token, controller.signal);
      if (requestGeneration !== generation) return;
      publish(() => ({ userId: target.userId, rooms: result.rooms, loading: false, error: null }));
    } catch (error) {
      if (requestGeneration !== generation) return;
      publish((previous) => ({
        userId: target.userId,
        rooms: previous.userId === target.userId ? previous.rooms : [],
        loading: false,
        error: error instanceof Error ? error.message : '방 목록을 불러오지 못했습니다.'
      }));
    }
  }

  return {
    /** Switches to a login (or to none) and loads its rooms. */
    setSession(session: RoomListSession | null) {
      current = session;
      return load(session);
    },
    /** Reloads for the login the caller belongs to; ignored once that login is no longer current. */
    reload(session: RoomListSession | null) {
      return load(session);
    },
    dispose() {
      inFlight?.abort();
      inFlight = null;
      current = null;
      generation += 1;
    }
  };
}
