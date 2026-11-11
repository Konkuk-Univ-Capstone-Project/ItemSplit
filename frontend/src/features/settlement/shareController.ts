import { ApiError } from '../../httpClient';
import { issueSnapshotShare } from './api';
import type { IssuedSnapshotShare, SnapshotShareState } from './types';

// Derive the copy URL from our browser origin and issued token, never from a supplied host.
export function absoluteShareUrl(share: IssuedSnapshotShare, origin: string) {
  return new URL(`/share/settlements/${encodeURIComponent(share.token)}`,origin).href;
}
export class SnapshotShareController {
  private view: SnapshotShareState = {busy:false,share:null,error:'',copied:false};
  private listeners = new Set<() => void>();
  private alive = true;
  private generation = 0;
  private abort: AbortController | null = null;
  constructor(private token: string, private roomId: number, private snapshotId: number, private onConflict:()=>void) {}
  getSnapshot=()=>this.view;
  subscribe=(listener:()=>void)=>{this.listeners.add(listener);return ()=>{this.listeners.delete(listener);};};
  private publish(patch: Partial<SnapshotShareState>) {if(this.alive){this.view={...this.view,...patch};this.listeners.forEach(listener=>listener());}}
  activate() {this.alive=true;}
  dispose() {this.alive=false;this.generation++;this.abort?.abort();this.view={busy:false,share:null,error:'',copied:false};}
  async issue() {
    if(!this.alive || this.view.busy) return;
    const generation=++this.generation;this.abort?.abort();const abort=this.abort=new AbortController();
    const current=()=>this.alive && generation===this.generation;
    this.publish({busy:true,share:null,error:'',copied:false});
    try {
      const share=await issueSnapshotShare(this.token,this.roomId,this.snapshotId,abort.signal);
      if(current()) this.publish({share});
    } catch(error) {
      if(current()) {
        this.publish({error:error instanceof ApiError && error.status===409?'확정 상태가 변경되었습니다. 최신 정산을 확인해주세요.':'공유 링크를 발급하지 못했습니다. 다시 시도해주세요.'});
        if(error instanceof ApiError && error.status===409) this.onConflict();
      }
    } finally {if(current()) this.publish({busy:false});}
  }
  async copy(origin: string, writeText: (text:string)=>Promise<void>) {
    if(!this.alive || this.view.busy || !this.view.share) return;
    const generation=this.generation;const share=this.view.share;
    try {
      await writeText(absoluteShareUrl(share,origin));
      if(this.alive && generation===this.generation) this.publish({copied:true,error:''});
    } catch {
      if(this.alive && generation===this.generation) this.publish({copied:false,error:'복사하지 못했습니다. 아래 링크를 직접 선택해 복사해주세요.'});
    }
  }
}
