import { request } from '../../httpClient';
import type { ConfirmRequest, Preview, SettlementState, Snapshot } from './types';
const root = (roomId: number) => `/api/rooms/${roomId}`;
export const getState = (token: string, roomId: number, signal?: AbortSignal) => request<SettlementState>(`${root(roomId)}/settlement-state`, {token,signal});
export const getPreview = (token: string, roomId: number, signal?: AbortSignal) => request<Preview>(`${root(roomId)}/settlements/confirmation-preview`, {token,signal});
export const getSnapshot = (token: string, roomId: number, snapshotId: number, signal?: AbortSignal) => request<Snapshot>(`${root(roomId)}/settlement-snapshots/${snapshotId}`, {token,signal});
export const confirm = (token: string, roomId: number, body: ConfirmRequest, signal?: AbortSignal) => request<Snapshot>(`${root(roomId)}/settlement-snapshots`, {token,method:'POST',body:JSON.stringify(body),signal});
export const reopen = (token: string, roomId: number, snapshotId: number, signal?: AbortSignal) => request<SettlementState>(`${root(roomId)}/settlement-snapshots/${snapshotId}/reopen`, {token,method:'POST',signal});

export const setCompletion = (token: string, roomId: number, snapshotId: number, transferId: number, completed: boolean) => request<Snapshot['transfers'][number]>(`${root(roomId)}/settlement-snapshots/${snapshotId}/transfers/${transferId}/completion`, {token,method:'PUT',body:JSON.stringify({completed})});

export const issueSnapshotShare = (token: string, roomId: number, snapshotId: number, signal?: AbortSignal) => request<import('./types').IssuedSnapshotShare>(`${root(roomId)}/settlement-snapshots/${snapshotId}/share-token`, {token,method:'POST',signal,cache:'no-store',referrerPolicy:'no-referrer'});
