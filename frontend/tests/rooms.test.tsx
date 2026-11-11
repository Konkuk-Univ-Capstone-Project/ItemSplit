import assert from 'node:assert/strict';
import { test } from 'node:test';
import { renderToStaticMarkup } from 'react-dom/server';
import { getMyRooms } from '../src/features/rooms/api';
import { RoomList } from '../src/features/rooms/RoomList';
import { chooseRoomId, clearLegacyRoomStorage, readLastRoomId, saveLastRoomId } from '../src/features/rooms/selection';
import type { RoomListItem } from '../src/features/rooms/types';

const rooms: RoomListItem[] = [
  { roomId: 9, roomName: '제주 여행', myMemberId: 31, owner: false, createdAt: '2026-11-10T10:00:00' },
  { roomId: 4, roomName: '팀 회식', myMemberId: 12, owner: true, createdAt: '2026-11-01T19:00:00' }
];

function memoryStorage(initial: Record<string, string> = {}) {
  const values = new Map(Object.entries(initial));
  return {
    values,
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); },
    removeItem: (key: string) => { values.delete(key); }
  };
}

test('room list is read from the server with the session token and abort signal', async t => {
  let sent: { url: unknown; options: RequestInit };
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    sent = { url, options };
    return new Response(JSON.stringify({ success: true, data: { rooms }, error: null }), { status: 200 });
  });
  const controller = new AbortController();

  const result = await getMyRooms('session-token', controller.signal);

  assert.deepEqual(result.rooms, rooms);
  assert.equal(sent!.url, '/api/rooms');
  assert.equal(sent!.options.method, undefined);
  assert.equal((sent!.options.headers as Headers).get('Authorization'), 'Bearer session-token');
  assert.equal(sent!.options.signal, controller.signal);
});

test('selection keeps a listed room, falls back to the first room, and is empty without rooms', () => {
  assert.equal(chooseRoomId(rooms, 4), 4);
  assert.equal(chooseRoomId(rooms, 77), 9);
  assert.equal(chooseRoomId(rooms, null), 9);
  assert.equal(chooseRoomId([], 4), null);
});

test('the last selected room is stored per account and legacy browser room lists are discarded', () => {
  const storage = memoryStorage({
    'itemsplit.rooms': '[{"roomId":3,"roomName":"old"}]',
    'itemsplit.activeRoomId': '3',
    'itemsplit.lastRoomId.2': 'not-a-number'
  });

  saveLastRoomId(storage, 1, 9);
  assert.equal(readLastRoomId(storage, 1), 9);
  assert.equal(readLastRoomId(storage, 2), null, 'another account and invalid values are ignored');
  saveLastRoomId(storage, 1, null);
  assert.equal(readLastRoomId(storage, 1), null);

  clearLegacyRoomStorage(storage);
  assert.deepEqual([...storage.values.keys()], ['itemsplit.lastRoomId.2']);
});

test('room list separates loading, empty, failed and loaded states', () => {
  const props = { selectedRoomId: null, onSelect: () => {}, onRetry: () => {} };

  assert.match(renderToStaticMarkup(<RoomList {...props} rooms={[]} loading error={null} />), /방 목록을 불러오는 중/);
  assert.match(renderToStaticMarkup(<RoomList {...props} rooms={[]} loading={false} error={null} />), /참여한 방이 없습니다/);

  const failed = renderToStaticMarkup(<RoomList {...props} rooms={[]} loading={false} error="네트워크 오류" />);
  assert.match(failed, /네트워크 오류/);
  assert.match(failed, /다시 시도/);
  assert.doesNotMatch(failed, /참여한 방이 없습니다/, 'a failed load is not shown as an empty list');

  const loaded = renderToStaticMarkup(<RoomList {...props} rooms={rooms} selectedRoomId={4} loading={false} error={null}
    onDelete={() => {}} />);
  assert.match(loaded, /제주 여행/);
  assert.match(loaded, /class="room-button active"[^>]*><span>팀 회식/);
  assert.equal(loaded.match(/title="방 삭제"/g)?.length, 1, 'only the owned room has a delete button');
});
