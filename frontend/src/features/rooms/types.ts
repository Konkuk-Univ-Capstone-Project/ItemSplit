export type RoomListItem = {
  roomId: number;
  roomName: string;
  myMemberId: number;
  owner: boolean;
  createdAt: string;
};

export type RoomListResult = { rooms: RoomListItem[] };
