import { RotateCw, Trash2 } from 'lucide-react';
import type { RoomListItem } from './types';

export type RoomListProps = {
  rooms: RoomListItem[];
  selectedRoomId: number | null;
  loading: boolean;
  error: string | null;
  onSelect: (roomId: number) => void;
  onRetry: () => void;
  // Optional: the rail shows a delete button on rooms the account owns.
  onDelete?: (room: RoomListItem) => void;
  deletingRoomId?: number | null;
  selectedRoomReadOnly?: boolean;
};

export function RoomList({ rooms, selectedRoomId, loading, error, onSelect, onRetry, onDelete, deletingRoomId = null, selectedRoomReadOnly = false }: RoomListProps) {
  // A failed load is shown as an error, never as an empty list.
  if (error) {
    return (
      <div className="room-list-status error" role="alert">
        <span>{error}</span>
        <button className="button secondary full-width" type="button" onClick={onRetry} disabled={loading}>
          <RotateCw size={15} />
          {loading ? '다시 불러오는 중' : '다시 시도'}
        </button>
      </div>
    );
  }

  if (loading && rooms.length === 0) {
    return <div className="room-list-status" role="status">방 목록을 불러오는 중</div>;
  }

  if (rooms.length === 0) {
    return <div className="room-list-status">참여한 방이 없습니다. 새 방을 만들거나 초대 토큰으로 참가하세요.</div>;
  }

  return (
    <div className="room-list" aria-busy={loading}>
      {rooms.map((room) => (
        <div className="room-row" key={room.roomId}>
          <button
            className={`room-button ${selectedRoomId === room.roomId ? 'active' : ''}`}
            type="button"
            onClick={() => onSelect(room.roomId)}
          >
            <span>{room.roomName}</span>
            <small>#{room.roomId}</small>
          </button>
          {onDelete && room.owner && (
            <button
              className="icon-action danger room-delete-button"
              type="button"
              title="방 삭제"
              disabled={deletingRoomId !== null || (room.roomId === selectedRoomId && selectedRoomReadOnly)}
              onClick={() => onDelete(room)}
            >
              <Trash2 size={15} />
            </button>
          )}
        </div>
      ))}
    </div>
  );
}
