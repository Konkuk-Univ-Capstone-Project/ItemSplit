import type { SettlementView } from './types';
const money = (amount: number) => `${amount.toLocaleString('ko-KR')}원`;
export function SettlementPanel({view,owner,onRefresh,onConfirm,onReopen}: {
  view: SettlementView; owner: boolean; onRefresh: () => void; onConfirm: () => void; onReopen: () => void;
}) {
  const data = view.mode === 'confirmed' ? view.snapshot : view.mode === 'open' ? view.preview : null;
  const names = new Map(data?.members.map(member => [member.memberId,'displayName' in member ? member.displayName : member.nickname]));
  return <section className="panel settlement-panel" aria-label="정산 확정">
    <h3>{view.mode === 'confirmed' ? `확정된 정산 · ${view.snapshot?.version}차` : '정산 미리보기'}</h3>
    {view.mode === 'loading' && <p role="status">정산 상태를 확인하는 중…</p>}
    {view.error && <p role="alert">{view.error}</p>}
    {view.mode === 'error' && <p>상태를 확인할 때까지 영수증과 멤버 정보를 수정할 수 없습니다.</p>}
    {data && <>
      <p>{data.roomName}{view.mode === 'confirmed' && ` · 확정일 ${view.snapshot?.confirmedAt.replace('T',' ')}`}</p>
      {'issues' in data && !data.ready && <ul className="settlement-issues">{data.issues.map((issue,index)=><li key={index}>{issue.message}</li>)}</ul>}
      <div className="settlement-list">{data.members.map(member=><div className="settlement-row" key={member.memberId}>
        <strong>{names.get(member.memberId)}</strong>
        <span>결제 {money(member.paid)} · 부담 {money(member.burden)}</span>
        <b>{member.net >= 0 ? '받을 금액' : '보낼 금액'} {money(Math.abs(member.net))}</b>
      </div>)}</div>
      <h4>송금 안내</h4>
      {data.transfers.map((transfer,index)=><p key={index}>{names.get(transfer.fromMemberId)} → {names.get(transfer.toMemberId)} <strong>{money(transfer.amount)}</strong></p>)}
      {data.transfers.length === 0 && <p>송금할 금액이 없습니다.</p>}
      {view.mode === 'confirmed' ? <p>확정 당시 금액입니다. 입력을 수정하려면 방장이 정산을 재개방해야 합니다.</p> : <p>확정하면 영수증·품목·참여자 수정이 잠깁니다.</p>}
    </>}
    <div className="row-actions">
      <button type="button" className="button secondary" disabled={view.busy || view.mode === 'loading'} onClick={onRefresh}>최신 상태 확인</button>
      {owner && view.mode === 'open' && <button type="button" className="button primary" disabled={view.busy || !view.preview?.ready} onClick={onConfirm}>{view.busy ? '처리 중…' : '정산 확정'}</button>}
      {owner && view.mode === 'confirmed' && <button type="button" className="button primary" disabled={view.busy} onClick={onReopen}>재개방</button>}
      {!owner && data && <p>방장만 정산을 확정하거나 재개방할 수 있습니다.</p>}
    </div>
  </section>;
}
