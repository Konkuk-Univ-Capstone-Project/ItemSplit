import { ApiError } from '../../httpClient';
import { getSharedSnapshot } from './api';
import type { SharedView } from './types';
export const isSharedPath = (path: string) => path.startsWith('/share/settlements');
export const parseSharedPath = (path: string): string | null => /^\/share\/settlements\/([A-Za-z0-9_-]{43})\/?$/.exec(path)?.[1] ?? null;

export class SharedSettlementController {
  private view: SharedView = {mode:'loading',snapshot:null,error:''};
  private listeners = new Set<() => void>();
  private generation = 0;
  private alive = true;
  private abort: AbortController | null = null;
  constructor(private token: string | null) {}
  getSnapshot = () => this.view;
  subscribe = (listener: () => void) => {this.listeners.add(listener);return () => {this.listeners.delete(listener);};};
  private publish(view: SharedView) {if(this.alive){this.view=view;this.listeners.forEach(listener=>listener());}}
  activate() {this.alive=true;}
  dispose() {this.alive=false;this.generation++;this.abort?.abort();this.view={mode:'loading',snapshot:null,error:''};}
  async refresh() {
    if(!this.alive) return;
    const generation=++this.generation;
    this.abort?.abort();const abort=this.abort=new AbortController();
    const current=()=>this.alive && generation===this.generation;
    this.publish({mode:'loading',snapshot:null,error:''});
    if(!this.token) {this.publish({mode:'unavailable',snapshot:null,error:''});return;}
    try {
      const snapshot=await getSharedSnapshot(this.token,abort.signal);
      if(current()) this.publish({mode:'ready',snapshot,error:''});
    } catch(error) {
      if(current()) this.publish({mode:error instanceof ApiError && error.status===404?'unavailable':'error',snapshot:null,
        error:'공유 정산을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.'});
    }
  }
}
