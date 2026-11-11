export type SharedSnapshot = {
  snapshotId: number; roomName: string; version: number; status: string;
  confirmedAt: string; reopenedAt: string | null; expiresAt: string;
  members: Array<{memberId: number; displayName: string; paid: number; burden: number; net: number}>;
  transfers: Array<{fromMemberId: number; toMemberId: number; amount: number; status: string}>;
};
export type SharedView = {mode: 'loading' | 'ready' | 'unavailable' | 'error'; snapshot: SharedSnapshot | null; error: string};
