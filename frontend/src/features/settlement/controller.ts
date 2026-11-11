import { ApiError, request } from '../../httpClient';
import * as api from './api';
import type { ConfirmRequest, SettlementView } from './types';

export function settlementConflict(error: unknown) {
  return error instanceof ApiError && error.status === 409 &&
    ['SETTLEMENT_LOCKED','SETTLEMENT_INPUT_CHANGED','SETTLEMENT_VERSION_CONFLICT','IDEMPOTENCY_KEY_REUSED'].includes(error.reason ?? '');
}
export function settlementMessage(error: unknown) {
  if (error instanceof ApiError) {
    if (error.reason === 'SETTLEMENT_LOCKED') return '정산이 확정되어 수정할 수 없습니다. 방장이 재개방한 뒤 다시 시도해주세요.';
    if (settlementConflict(error)) return '정산 입력 또는 확정 상태가 변경되었습니다. 최신 내용을 확인하고 다시 시도해주세요.';
  }
  return error instanceof Error ? error.message : '정산 상태를 확인하지 못했습니다.';
}
export const editingBlocked = (view: SettlementView) => view.busy || !['open','legacy'].includes(view.mode);

/** One controller per mounted room/account. Generations and aborts both fence stale reads. */
export class SettlementController {
  private view: SettlementView = {mode:'loading',state:null,preview:null,snapshot:null,busy:false,error:''};
  private listeners = new Set<() => void>();
  private generation = 0;
  private alive = true;
  private abort: AbortController | null = null;
  private attempt: ConfirmRequest | null = null;
  private knownLocked = false;
  constructor(private token: string, private roomId: number) {}
  getSnapshot = () => this.view;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  private publish(patch: Partial<SettlementView>) {
    if (!this.alive) return;
    this.view = {...this.view,...patch}; this.listeners.forEach(listener => listener());
  }
  dispose() { this.alive = false; this.generation++; this.abort?.abort(); }
  activate() { this.alive = true; }
  async refresh(preserveAttempt = false, operation = false) {
    if (!this.alive || (this.view.busy && !operation)) return;
    const generation = ++this.generation;
    this.abort?.abort(); const abort = this.abort = new AbortController();
    const current = () => this.alive && generation === this.generation;
    this.publish({mode:'loading',error:''});
    try {
      let state;
      try { state = await api.getState(this.token,this.roomId,abort.signal); }
      catch (error) {
        if (!(error instanceof ApiError && error.status === 404)) throw error;
        if (this.knownLocked) throw new Error('정산이 확정되어 수정할 수 없습니다. 최신 상태를 다시 확인해주세요.');
        // Endpoint absence alone does not establish membership or permit editing.
        await request(`/api/rooms/${this.roomId}/members`,{token:this.token,signal:abort.signal});
        if (current()) { this.attempt = null; this.publish({mode:'legacy',state:null,preview:null,snapshot:null}); }
        return;
      }
      if (!current()) return;
      if (state.roomId !== this.roomId || !['OPEN','CONFIRMED'].includes(state.status) ||
        (state.status === 'CONFIRMED' && state.currentSnapshotId == null) || (state.status === 'OPEN' && state.currentSnapshotId !== null)) {
        throw new Error('정산 상태를 확인하지 못했습니다. 다시 확인해주세요.');
      }
      this.knownLocked = state.status === 'CONFIRMED';
      if (state.status === 'CONFIRMED') {
        const snapshot = await api.getSnapshot(this.token,this.roomId,state.currentSnapshotId!,abort.signal);
        if (current()) { this.attempt = null; this.publish({mode:'confirmed',state,snapshot,preview:null}); }
      } else {
        const preview = preserveAttempt && this.attempt && this.view.preview ? this.view.preview : await api.getPreview(this.token,this.roomId,abort.signal);
        if (current()) {
          if (!preserveAttempt || !this.attempt) this.attempt = {requestId:crypto.randomUUID(),previewToken:preview.previewToken};
          this.publish({mode:'open',state,preview,snapshot:null});
        }
      }
    } catch (error) {
      if (current()) this.publish({mode:'error',error:settlementMessage(error)});
    }
  }
  reportError = (error: unknown) => {
    if (!this.alive || !settlementConflict(error)) return;
    if (error instanceof ApiError && error.reason === 'SETTLEMENT_LOCKED') this.knownLocked = true;
    this.publish({mode:'loading',error:settlementMessage(error)});
    void this.refresh().then(() => this.publish({error:settlementMessage(error)}));
  };
  confirm = async () => {
    if (!this.alive || this.view.busy || this.view.mode !== 'open' || !this.view.preview?.ready || !this.attempt) return;
    await this.mutate(() => api.confirm(this.token,this.roomId,this.attempt!));
  };
  reopen = async () => {
    if (!this.alive || this.view.busy || this.view.mode !== 'confirmed' || !this.view.state?.currentSnapshotId) return;
    const id = this.view.state.currentSnapshotId;
    await this.mutate(() => api.reopen(this.token,this.roomId,id));
  };
  private async mutate(action: () => Promise<unknown>) {
    this.generation++; this.abort?.abort(); this.publish({busy:true,error:''});
    try { await action(); if (this.alive) await this.refresh(false,true); }
    catch (error) {
      if (!this.alive) return;
      if (error instanceof ApiError && error.reason === 'SETTLEMENT_LOCKED') this.knownLocked = true;
      await this.refresh(!settlementConflict(error),true);
      this.publish({error:settlementMessage(error)});
    } finally { this.publish({busy:false}); }
  }
}
