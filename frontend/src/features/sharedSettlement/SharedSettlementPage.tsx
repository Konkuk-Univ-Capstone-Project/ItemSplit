import { useEffect, useMemo, useSyncExternalStore } from 'react';
import { SharedSettlementController, parseSharedPath } from './controller';
import type { SharedView } from './types';
const money=(amount: number)=>`${amount.toLocaleString('ko-KR')}원`;
const date=(value: string)=>value.replace('T',' ');
export function SharedSettlementPage({path}: {path: string}) {
  const controller=useMemo(()=>new SharedSettlementController(parseSharedPath(path)),[path]);
  const view=useSyncExternalStore(controller.subscribe,controller.getSnapshot,controller.getSnapshot);
  useEffect(()=>{controller.activate();void controller.refresh();return ()=>controller.dispose();},[controller]);
  return <SharedSettlementView view={view} onRefresh={()=>void controller.refresh()}/>;
}
export function SharedSettlementView({view,onRefresh}: {view: SharedView; onRefresh:()=>void}) {
  const data=view.mode==='ready'?view.snapshot:null;
  const names=new Map(data?.members.map(member=>[member.memberId,member.displayName]));
  return <main className="shared-snapshot-page"><meta name="referrer" content="no-referrer"/>
    <header><span className="eyebrow">ItemSplit · 읽기 전용</span><h1>공유 정산</h1></header>
    <section className="panel settlement-panel" aria-label="공유 정산 내역">
      {view.mode==='loading' && <p role="status">공유 정산을 불러오는 중…</p>}
      {view.mode==='unavailable' && <div role="alert"><h2>이 공유 링크를 사용할 수 없습니다</h2><p>링크가 만료되었거나 새로 발급되었습니다. 방장에게 새 링크를 요청해주세요.</p></div>}
      {view.mode==='error' && <p role="alert">{view.error}</p>}
      {data && <>
        <h2>{data.roomName} · {data.version}차 확정본</h2>
        {data.status==='REOPENED' && <p className="shared-reopened" role="status">재개방된 과거 정산입니다. 이후 변경 내용은 포함되지 않습니다.{data.reopenedAt && ` · 재개방 ${date(data.reopenedAt)}`}</p>}
        <p>확정 {date(data.confirmedAt)}</p>
        <div className="settlement-list">{data.members.map(member=><div className="settlement-row" key={member.memberId}>
          <strong>{member.displayName}</strong><span>결제 {money(member.paid)} · 부담 {money(member.burden)}</span>
          <b>{member.net>=0?'받을 금액':'보낼 금액'} {money(Math.abs(member.net))}</b>
        </div>)}</div>
        <h3>송금 안내</h3>
        {data.transfers.map((transfer,index)=><div className="settlement-transfer" key={index}><p>{names.get(transfer.fromMemberId)} → {names.get(transfer.toMemberId)} <strong>{money(transfer.amount)}</strong> · {transfer.status==='COMPLETED'?'완료':'미완료'}</p></div>)}
        {data.transfers.length===0 && <p>송금할 금액이 없습니다.</p>}
        <p>확정 당시 이름과 금액, 이 확정본의 현재 완료 상태입니다.</p>
        <p>완료 표시는 수동 기록이며 실제 은행 입금 확인이 아닙니다.</p>
        <p>링크 만료 {date(data.expiresAt)}</p>
      </>}
      <button type="button" className="button secondary" disabled={view.mode==='loading'} onClick={onRefresh}>최신 상태 확인</button>
    </section>
  </main>;
}
