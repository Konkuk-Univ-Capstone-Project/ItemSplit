export type ApiEnvelope<T> = {
  success: boolean;
  data: T | null;
  error: {
    code: string;
    message: string;
    details: Array<{ field: string; rejectedValue: string | null; reason: string }>;
  } | null;
};

export type AuthSession = {
  userId: number;
  email: string;
  nickname: string;
  accessToken: string;
  tokenType: string;
  expiresIn: number;
};

export type RecentRoom = {
  roomId: number;
  roomName: string;
  owner?: boolean;
};

export type RoomMember = {
  memberId: number;
  userId: number | null;
  email: string | null;
  nickname: string;
  linked: boolean;
  owner: boolean;
};

export type ReceiptSummary = {
  receiptId: number;
  roomId: number;
  name: string;
  sourceType: 'MANUAL' | 'IMAGE_UPLOAD';
  payerId: number | null;
  payerNickname: string | null;
  declaredTotal: number | null;
  purchasedAt: string | null;
  createdAt: string;
};

export type ReceiptItem = {
  itemId: number;
  name: string;
  price: number;
  quantity: number;
};

export type ReceiptDetail = ReceiptSummary & {
  items: ReceiptItem[];
  warning: string | null;
};

export type SettlementMember = {
  memberId: number;
  userId: number | null;
  nickname: string;
  linked: boolean;
  burden: number;
  paid: number;
  net: number;
};

export type Settlement = {
  roomId: number;
  roomName: string;
  members: SettlementMember[];
};

export type SharedSettlement = Settlement & {
  shareExpiresAt: string;
  readOnly: boolean;
};

export type Assignee = {
  memberId: number;
  userId: number | null;
  email: string | null;
  nickname: string;
  linked: boolean;
};

export type AssigneesResult = {
  roomId: number;
  receiptId: number;
  itemId: number;
  itemName: string;
  assignees: Assignee[];
};
