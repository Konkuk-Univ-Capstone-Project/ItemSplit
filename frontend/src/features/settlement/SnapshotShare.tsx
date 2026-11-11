import { useEffect, useMemo, useRef, useSyncExternalStore } from 'react';
import { absoluteShareUrl, SnapshotShareController } from './shareController';
import type { SnapshotShareState } from './types';
export function SnapshotShare({token,roomId,snapshotId,disabled,onConflict}: {token:string;roomId:number;snapshotId:number;disabled:boolean;onConflict:()=>void}) {
  const conflict=useRef(onConflict);conflict.current=onConflict;
  const controller=useMemo(()=>new SnapshotShareController(token,roomId,snapshotId,()=>conflict.current()),[token,roomId,snapshotId]);
  const view=useSyncExternalStore(controller.subscribe,controller.getSnapshot,controller.getSnapshot);
  useEffect(()=>{controller.activate();return ()=>controller.dispose();},[controller]);
  return <SnapshotShareView view={view} disabled={disabled} origin={window.location.origin} onIssue={()=>void controller.issue()}
    onCopy={()=>void controller.copy(window.location.origin,text=>navigator.clipboard.writeText(text))}/>;
}
export function SnapshotShareView({view,disabled,origin,onIssue,onCopy}: {view:SnapshotShareState;disabled:boolean;origin:string;onIssue:()=>void;onCopy:()=>void}) {
  return <section className="snapshot-share" aria-label="확정본 공유">
    <h4>확정본 공유 링크</h4>
    <p>이 확정본의 이름·금액과 완료 상태를 로그인 없이 읽을 수 있는 링크입니다. 링크를 가진 사람은 누구나 볼 수 있습니다.</p>
    <p>기본 14일 동안 유효하며 다시 발급하면 이전 링크는 사용할 수 없습니다.</p>
    {view.error && <p role="alert">{view.error}</p>}
    <button type="button" className="button secondary" disabled={disabled || view.busy} onClick={onIssue}>{view.busy?'링크 발급 중…':view.share?'공유 링크 재발급':'공유 링크 발급'}</button>
    {!view.busy && view.share && <>
      <p>만료 {view.share.expiresAt.replace('T',' ')}</p>
      <label className="shared-link-label">공유 링크<input aria-label="확정본 공유 링크" readOnly value={absoluteShareUrl(view.share,origin)} onFocus={event=>event.currentTarget.select()}/></label>
      <button type="button" className="button secondary" disabled={disabled} onClick={onCopy}>링크 복사</button>
      {view.copied && <p role="status">링크를 복사했습니다.</p>}
    </>}
  </section>;
}
