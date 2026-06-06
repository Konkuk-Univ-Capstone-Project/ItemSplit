import type {
  ApiEnvelope,
  AssigneesResult,
  AuthSession,
  ReceiptDetail,
  ReceiptItem,
  ReceiptSummary,
  RoomMember,
  Settlement,
  SharedSettlement
} from './types';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';

type RequestOptions = RequestInit & {
  token?: string | null;
};

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
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

  const envelope = (await response.json()) as ApiEnvelope<T>;
  if (!response.ok || !envelope.success) {
    throw new Error(envelope.error?.message ?? '요청을 처리하지 못했습니다.');
  }

  return envelope.data as T;
}

export function signup(payload: { email: string; password: string; nickname: string }) {
  return request<{ userId: number; email: string; nickname: string }>('/api/auth/signup', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function login(payload: { email: string; password: string }) {
  return request<AuthSession>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function createRoom(token: string, name: string) {
  return request<{ roomId: number; roomName: string; ownerId: number; ownerNickname: string }>('/api/rooms', {
    method: 'POST',
    token,
    body: JSON.stringify({ name })
  });
}

export function deleteRoom(token: string, roomId: number) {
  return request<void>(`/api/rooms/${roomId}`, {
    method: 'DELETE',
    token
  });
}

export function joinRoom(token: string, inviteToken: string) {
  return joinRoomWithOptions(token, inviteToken, null, null);
}

export function getJoinOptions(token: string, inviteToken: string) {
  return request<{ roomId: number; roomName: string; members: RoomMember[] }>(
    `/api/rooms/join-options?token=${encodeURIComponent(inviteToken)}`,
    { token }
  );
}

export function joinRoomWithOptions(
  token: string,
  inviteToken: string,
  memberId: number | null,
  nickname: string | null
) {
  const params = new URLSearchParams({ token: inviteToken });
  if (memberId !== null) {
    params.set('memberId', String(memberId));
  }
  if (nickname?.trim()) {
    params.set('nickname', nickname.trim());
  }
  return request<{ roomId: number; roomName: string; joined: boolean }>(
    `/api/rooms/join?${params.toString()}`,
    { method: 'POST', token }
  );
}

export function getMembers(token: string, roomId: number) {
  return request<{ roomId: number; roomName: string; members: RoomMember[] }>(`/api/rooms/${roomId}/members`, {
    token
  });
}

export function addManualMember(token: string, roomId: number, nickname: string) {
  return request<RoomMember>(`/api/rooms/${roomId}/members/manual`, {
    method: 'POST',
    token,
    body: JSON.stringify({ nickname })
  });
}

export function updateMemberName(token: string, roomId: number, memberId: number, nickname: string) {
  return request<RoomMember>(`/api/rooms/${roomId}/members/${memberId}`, {
    method: 'PUT',
    token,
    body: JSON.stringify({ nickname })
  });
}

export function deleteMember(token: string, roomId: number, memberId: number) {
  return request<void>(`/api/rooms/${roomId}/members/${memberId}`, {
    method: 'DELETE',
    token
  });
}

export function issueInviteToken(token: string, roomId: number) {
  return request<{ roomId: number; roomName: string; token: string; expiresAt: string }>(
    `/api/rooms/${roomId}/invite-token`,
    { method: 'POST', token }
  );
}

export function issueShareToken(token: string, roomId: number) {
  return request<{ roomId: number; roomName: string; token: string; expiresAt: string; readOnly: boolean }>(
    `/api/rooms/${roomId}/share-token`,
    { method: 'POST', token }
  );
}

export function getReceipts(token: string, roomId: number) {
  return request<ReceiptSummary[]>(`/api/rooms/${roomId}/receipts`, { token });
}

export function getReceipt(token: string, roomId: number, receiptId: number) {
  return request<ReceiptDetail>(`/api/rooms/${roomId}/receipts/${receiptId}`, { token });
}

export function createManualReceipt(
  token: string,
  roomId: number,
  payload: {
    name: string;
    payerMemberId: number | null;
    declaredTotal: number | null;
    purchasedAt: string | null;
    items: Array<{ name: string; price: number; quantity: number }>;
  }
) {
  return request<ReceiptDetail>(`/api/rooms/${roomId}/receipts/manual`, {
    method: 'POST',
    token,
    body: JSON.stringify(payload)
  });
}

export function deleteReceipt(token: string, roomId: number, receiptId: number) {
  return request<void>(`/api/rooms/${roomId}/receipts/${receiptId}`, {
    method: 'DELETE',
    token
  });
}

export function updateReceipt(
  token: string,
  roomId: number,
  receiptId: number,
  payload: {
    name: string;
    payerMemberId: number | null;
    declaredTotal: number | null;
    purchasedAt: string | null;
  }
) {
  return request<ReceiptDetail>(`/api/rooms/${roomId}/receipts/${receiptId}`, {
    method: 'PUT',
    token,
    body: JSON.stringify(payload)
  });
}

export function addItem(token: string, roomId: number, receiptId: number, item: Omit<ReceiptItem, 'itemId'>) {
  return request<ReceiptItem>(`/api/rooms/${roomId}/receipts/${receiptId}/items`, {
    method: 'POST',
    token,
    body: JSON.stringify(item)
  });
}

export function updateItem(
  token: string,
  roomId: number,
  receiptId: number,
  itemId: number,
  item: Omit<ReceiptItem, 'itemId'>
) {
  return request<ReceiptItem>(`/api/rooms/${roomId}/receipts/${receiptId}/items/${itemId}`, {
    method: 'PUT',
    token,
    body: JSON.stringify(item)
  });
}

export function deleteItem(token: string, roomId: number, receiptId: number, itemId: number) {
  return request<void>(`/api/rooms/${roomId}/receipts/${receiptId}/items/${itemId}`, {
    method: 'DELETE',
    token
  });
}

export function getAssignees(token: string, roomId: number, receiptId: number, itemId: number) {
  return request<AssigneesResult>(`/api/rooms/${roomId}/receipts/${receiptId}/items/${itemId}/assignees`, {
    token
  });
}

export function replaceAssignees(token: string, roomId: number, receiptId: number, itemId: number, memberIds: number[]) {
  return request<AssigneesResult>(`/api/rooms/${roomId}/receipts/${receiptId}/items/${itemId}/assignees`, {
    method: 'PUT',
    token,
    body: JSON.stringify({ memberIds })
  });
}

export function getSettlement(token: string, roomId: number) {
  return request<Settlement>(`/api/rooms/${roomId}/settlements`, { token });
}

export function getSharedSettlement(shareToken: string) {
  return request<SharedSettlement>(`/api/shared/rooms/${encodeURIComponent(shareToken)}/settlements`);
}
