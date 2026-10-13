import { useRef, useState } from 'react';
import { applyReceiptOcrDraft, getReceiptOcr, retryReceiptOcrImage, saveReceiptOcrDraft } from './api';
import { calculatedUnitPrice, imageRetryAvailable } from './ocrReview';
import type { OcrDraftWrite, ReceiptOcrJob, RoomMember } from './types';

const messages: Record<string, string> = {
  NO_ITEMS: '반영할 품목이 하나 이상 필요합니다.', ITEM_ISSUES: '품목명·단가·수량을 확인해주세요.',
  RECEIPT_TOTAL_LIMIT: '품목 합계는 1,000만 원 이하여야 합니다.',
  IMAGE_FAILED_UNCOVERED: '실패한 사진의 품목을 수동 입력하고 확인해주세요.', JOB_IN_PROGRESS: '인식 완료를 기다려주세요.'
};
const errors: Record<string, string> = {
  MISSING_NAME: '품목명 미입력', NAME_TOO_LONG: '품목명은 100자 이하', MISSING_PRICE: '단가 미입력',
  PRICE_OUT_OF_RANGE: '단가 1~1,000,000원', MISSING_QUANTITY: '수량 미입력',
  QUANTITY_OUT_OF_RANGE: '수량 1~999', LINE_AMOUNT_OUT_OF_RANGE: '품목 금액 범위 확인'
};

export function OcrDraftEditor({ token, roomId, job, members, onSaved, onApplied, onSelectSource, canRetry = false }: {
  token: string; roomId: number; job: ReceiptOcrJob; members: RoomMember[];
  onSelectSource?: (item: ReceiptOcrJob['draft']['items'][number]) => void;
  onSaved: (job: ReceiptOcrJob) => void; onApplied: () => Promise<void>;
  canRetry?: boolean;
}) {
  const [lines, setLines] = useState<OcrDraftWrite['items']>(() => job.draft.items.map(line => ({
    draftItemId: line.draftItemId, name: line.name, price: line.price, quantity: line.quantity,
    memberIds: [...line.memberIds], excludedFromSettlement: line.excludedFromSettlement, removed: line.removed
  })));
  const [covered, setCovered] = useState(() => job.images.filter(i => i.manuallyCovered).map(i => i.imageId));
  const [dirty, setDirty] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const inFlight = useRef(false);
  function change(index: number, update: Partial<OcrDraftWrite['items'][number]>) {
    setLines(rows => rows.map((row, i) => i === index ? { ...row, ...update } : row));
    setDirty(true); setConfirmed(false);
  }
  async function action(apply: boolean) {
    if (inFlight.current || (apply && (dirty || !confirmed || job.draft.blockers.length > 0))) return;
    inFlight.current = true; setBusy(true); setError('');
    try {
      if (apply) {
        await applyReceiptOcrDraft(token, roomId, job.receiptId, job.jobId, job.draft.revision);
        const latest = await getReceiptOcr(token, roomId, job.receiptId, job.jobId);
        await onApplied();
        onSaved(latest);
      } else {
        onSaved(await saveReceiptOcrDraft(token, roomId, job.receiptId, job.jobId, {
          revision: job.draft.revision, coveredImageIds: covered, items: lines
        }));
      }
    } catch (e) { setError(e instanceof Error ? e.message : '저장하지 못했습니다.'); }
    finally { inFlight.current = false; setBusy(false); }
  }
  async function retry(imageId: number) {
    if (inFlight.current || !imageRetryAvailable(job, imageId, canRetry, dirty)) return;
    inFlight.current = true; setBusy(true); setError(''); setConfirmed(false);
    try { onSaved(await retryReceiptOcrImage(token, roomId, job.receiptId, job.jobId, imageId)); }
    catch (e) { setError(e instanceof Error ? e.message : '다시 인식하지 못했습니다.'); }
    finally { inFlight.current = false; setBusy(false); }
  }
  return <div className="ocr-review">
    <h4>정산 품목 검토</h4>
    <p>품목과 금액을 수정하고 함께 부담할 참여자를 선택하세요. 삭제 표시는 반영할 품목에서 빼고, 정산 제외는 품목을 남긴 채 부담 계산에서 뺍니다.</p>
    <fieldset className="receipt-fieldset" disabled={busy}>
      {lines.map((line, index) => {
        const original = job.draft.items.find(item => item.draftItemId === line.draftItemId);
        const candidate = calculatedUnitPrice(line, original);
        return <div className="ocr-edit-row" key={line.draftItemId ?? `new-${index}`}>
        {onSelectSource && job.draft.items.find(item => item.draftItemId === line.draftItemId)?.imageId != null &&
          <button type="button" className="source-photo-link" onClick={() => {
            const original = job.draft.items.find(item => item.draftItemId === line.draftItemId);
            if (original) onSelectSource(original);
          }}>이 품목의 원본 보기</button>}
        <label className="checkbox-label"><input type="checkbox" checked={line.removed}
          onChange={e => change(index, { removed: e.target.checked })} />{index + 1}번 품목 삭제 표시</label>
        <fieldset className="receipt-fieldset" disabled={line.removed}>
          <div className="ocr-edit-fields">
            <label>품목명<input aria-label={`${index + 1}번 품목명`} value={line.name ?? ''} maxLength={100}
              onChange={e => change(index, { name: e.target.value })} /></label>
            <label>단가<input aria-label={`${index + 1}번 단가`} type="number" min="1" max="1000000" step="1" value={line.price ?? ''}
              onChange={e => change(index, { price: e.target.value === '' ? null : Number(e.target.value) })} /></label>
            <label>수량<input aria-label={`${index + 1}번 수량`} type="number" min="1" max="999" step="1" value={line.quantity ?? ''}
              onChange={e => change(index, { quantity: e.target.value === '' ? null : Number(e.target.value) })} /></label>
          </div>
          {candidate !== null && <div className="calculated-price">
            <p>계산 단가 후보 <strong>{candidate.toLocaleString('ko-KR')}원</strong> (추정)
              {' — '}{original!.ocr.lineAmount!.toLocaleString('ko-KR')}원 ÷ {line.quantity}개</p>
            <button className="button secondary" type="button" onClick={() => change(index, { price: candidate })}>이 값 사용</button>
            <small>직접 인식한 단가가 아닙니다. 원본의 수량과 할인 내역을 확인해주세요.</small>
          </div>}
          {!dirty && job.draft.items.find(i => i.draftItemId === line.draftItemId)?.issues.map(issue =>
            <small className="field-error" key={issue}>{errors[issue] ?? '입력 확인 필요'}</small>)}
          <label className="checkbox-label"><input type="checkbox" checked={line.excludedFromSettlement}
            onChange={e => change(index, { excludedFromSettlement: e.target.checked, memberIds: e.target.checked ? [] : line.memberIds })} />정산 제외</label>
          {!line.excludedFromSettlement && <div className="ocr-participants" role="group" aria-label={`${index + 1}번 품목 참여자`}>
            {members.map(member => <label className="checkbox-label" key={member.memberId}>
              <input type="checkbox" checked={line.memberIds.includes(member.memberId)}
                onChange={e => change(index, { memberIds: e.target.checked ? [...line.memberIds, member.memberId] : line.memberIds.filter(id => id !== member.memberId) })} />{member.nickname}
            </label>)}
            {line.memberIds.length === 0 && <small>참여자를 지정해야 정산이 완료됩니다.</small>}
          </div>}
        </fieldset>
      </div>; })}
      <button className="button secondary" type="button" onClick={() => {
        setLines(rows => [...rows, { draftItemId: null, name: '', price: null, quantity: 1, memberIds: [], excludedFromSettlement: false, removed: false }]);
        setDirty(true); setConfirmed(false);
      }}>누락 품목 추가</button>
      {job.images.filter(i => i.status === 'FAILED').map(image => <div key={image.imageId}>
        {canRetry && <div>
          <p>실패한 사진 #{image.imageId} · {image.attemptCount >= 3 ? '사진별 최대 3회를 모두 사용했습니다.' : `남은 재시도 ${3 - image.attemptCount}회`}</p>
          <button className="button secondary" type="button" disabled={!imageRetryAvailable(job, image.imageId, canRetry, dirty)}
            onClick={() => void retry(image.imageId)}>다시 인식</button>
          {dirty && <p>변경한 검토 내용을 저장한 뒤 다시 인식할 수 있습니다.</p>}
          {(!image.retryable || image.attemptCount >= 3) && <p>이 사진은 다시 인식할 수 없습니다. 품목을 수동으로 보완해주세요.</p>}
          {covered.includes(image.imageId) && <p>수동 입력한 품목은 유지됩니다. 다시 인식한 뒤 중복 품목을 확인해주세요.</p>}
        </div>}
        <label className="checkbox-label">
        <input type="checkbox" checked={covered.includes(image.imageId)} onChange={e => {
          setCovered(ids => e.target.checked ? [...ids, image.imageId] : ids.filter(id => id !== image.imageId));
          setDirty(true); setConfirmed(false);
        }} />실패한 사진 #{image.imageId}의 품목을 모두 수동 입력했습니다.
      </label></div>)}
      <button className="button secondary" type="button" disabled={!dirty} onClick={() => void action(false)}>
        {busy ? '처리 중…' : '검토 내용 저장'}
      </button>
      {dirty ? <p>변경한 내용을 저장한 뒤 최종 확인해주세요.</p> : <>
        {job.draft.blockers.map(code => <p role="alert" key={code}>{messages[code] ?? '초안을 확인해주세요.'}</p>)}
        <label className="checkbox-label"><input type="checkbox" checked={confirmed} onChange={e => setConfirmed(e.target.checked)} />저장된 품목·금액·참여자를 검토했습니다.</label>
      </>}
      <button className="button primary" type="button" disabled={dirty || !confirmed || job.draft.blockers.length > 0}
        onClick={() => void action(true)}>정산에 반영</button>
    </fieldset>
    {error && <p role="alert">{error} 상단의 ‘상태 다시 확인’으로 최신 초안을 불러올 수 있습니다.</p>}
  </div>;
}
