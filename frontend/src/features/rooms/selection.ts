import type { RoomListItem } from './types';

// Only the last selected room ID is kept locally, per account. The room list itself always comes from the server.
const LAST_ROOM_KEY_PREFIX = 'itemsplit.lastRoomId.';
// Older builds kept the room list and one shared selection in the browser. They are not imported into the server list.
const LEGACY_KEYS = ['itemsplit.rooms', 'itemsplit.activeRoomId'];

type KeyValueStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

export function readLastRoomId(storage: KeyValueStorage, userId: number): number | null {
  const value = Number(storage.getItem(LAST_ROOM_KEY_PREFIX + userId));
  return Number.isInteger(value) && value > 0 ? value : null;
}

export function saveLastRoomId(storage: KeyValueStorage, userId: number, roomId: number | null) {
  if (roomId === null) {
    storage.removeItem(LAST_ROOM_KEY_PREFIX + userId);
  } else {
    storage.setItem(LAST_ROOM_KEY_PREFIX + userId, String(roomId));
  }
}

export function clearLegacyRoomStorage(storage: KeyValueStorage) {
  LEGACY_KEYS.forEach((key) => storage.removeItem(key));
}

/** Keeps the preferred room when the server still lists it, otherwise falls back to the first room. */
export function chooseRoomId(rooms: RoomListItem[], preferredRoomId: number | null): number | null {
  if (preferredRoomId !== null && rooms.some((room) => room.roomId === preferredRoomId)) {
    return preferredRoomId;
  }
  return rooms[0]?.roomId ?? null;
}
