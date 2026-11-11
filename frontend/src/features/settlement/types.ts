import type { Settlement } from '../../types';
export type SettlementState = { roomId: number; status: 'OPEN' | 'CONFIRMED'; currentSnapshotId: number | null };
export type Transfer = { fromMemberId: number; toMemberId: number; amount: number };
export type Preview = Settlement & { previewToken: string; transfers: Transfer[] };
export type ConfirmRequest = { requestId: string; previewToken: string };
export type Snapshot = {
  snapshotId: number; roomId: number; roomName: string; version: number; status: string;
  confirmedByUserId: number; confirmedAt: string; reopenedByUserId: number | null; reopenedAt: string | null;
  members: Array<{memberId: number; displayName: string; paid: number; burden: number; net: number}>;
  transfers: Array<Transfer & {transferId: number; status: string; updatedByUserId: number | null; updatedAt: string | null}>;
};
export type SettlementView = {
  mode: 'loading' | 'open' | 'confirmed' | 'legacy' | 'error'; state: SettlementState | null;
  preview: Preview | null; snapshot: Snapshot | null; busy: boolean; error: string;
  completionAttempt?: {snapshotId: number; transferId: number; completed: boolean} | null;
};

export type IssuedSnapshotShare = {token: string; expiresAt: string; shareUrl: string};
export type SnapshotShareState = {busy: boolean; share: IssuedSnapshotShare | null; error: string; copied: boolean};
