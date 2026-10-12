import { useEffect, useRef, useState, type FormEvent } from 'react';
import { getLatestReceiptOcr, getReceiptOcr, startReceiptOcr, uploadReceiptImage } from './api';
import { ReceiptImageViewer, sourcePolygons, type ImageSource } from './ReceiptImageViewer';
import { OcrDraftEditor } from './OcrDraftEditor';
import type { ReceiptOcrJob, RoomMember } from './types';

const money = (value: number | null) => value === null ? '인식되지 않음' : `${value.toLocaleString('ko-KR')}원`;
const errorMessage = (error: unknown) => error instanceof Error ? error.message : '요청을 처리하지 못했습니다.';

export function ImageReceiptPanel({ token, roomId, onUploaded }: {
  token: string; roomId: number; onUploaded: (receiptId: number) => Promise<void>;
}) {
  const [file, setFile] = useState<File | null>(null);
  const [name, setName] = useState('사진 영수증');
  const [preview, setPreview] = useState('');
  const [busy, setBusy] = useState(false);
  const [uploaded, setUploaded] = useState(false);
  const [error, setError] = useState('');
  const inFlight = useRef(false);

  useEffect(() => {
    if (!file) { setPreview(''); return; }
    const url = URL.createObjectURL(file);
    setPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);

  async function upload(event: FormEvent) {
    event.preventDefault();
    if (!file || inFlight.current || uploaded) return;
    if (!['image/jpeg', 'image/png'].includes(file.type) || file.size === 0 || file.size > 10 * 1024 * 1024) {
      setError('10MB 이하의 JPEG 또는 PNG 사진을 선택해주세요.'); return;
    }
    inFlight.current = true;
    setBusy(true); setError('');
    try {
      const result = await uploadReceiptImage(token, roomId, file, name.trim() || '사진 영수증');
      setUploaded(true);
      await onUploaded(result.receiptId);
    } catch (e) { setError(errorMessage(e)); }
    finally { inFlight.current = false; setBusy(false); }
  }

  return <form className="panel image-upload-panel" onSubmit={upload}>
    <h3>영수증 사진 첨부</h3>
    <p>사진을 올린 뒤 인식을 시작하면 품목과 금액을 확인할 수 있습니다.</p>
    <fieldset className="receipt-fieldset" disabled={busy}>
      <label>사진 영수증 이름<input value={name} maxLength={100} onChange={e => setName(e.target.value)} /></label>
      <label>영수증 사진<input type="file" accept="image/jpeg,image/png" onChange={e => {
        setFile(e.target.files?.[0] ?? null); setUploaded(false); setError('');
      }} /></label>
      <small>JPEG·PNG, 최대 10MB, 한 번에 사진 한 장</small>
      {preview && <img className="receipt-photo-preview" src={preview} alt="첨부할 영수증 미리보기" />}
      <button className="button primary" type="submit" disabled={!file || uploaded}>
        {busy ? '사진 업로드 중…' : uploaded ? '사진 업로드 완료' : '사진 업로드'}
      </button>
    </fieldset>
    {error && <p role="alert">{error}</p>}
  </form>;
}

const statusLabels: Record<ReceiptOcrJob['status'], string> = {
  PENDING: '인식 대기 중', PROCESSING: '영수증 인식 중…', REVIEWABLE: '인식 완료',
  PARTIALLY_FAILED: '일부 사진 인식 실패', FAILED: '인식 실패'
};
const providerErrors: Record<string, string> = {
  CONFIG_ERROR: 'OCR 연결 설정을 확인해주세요.', TIMEOUT: '인식 응답 시간이 초과되었습니다.',
  RATE_LIMITED: 'OCR 호출 한도를 초과했습니다.', PROVIDER_ERROR: 'OCR 서비스에 연결하지 못했습니다.',
  EMPTY_RESULT: '사진에서 품목을 찾지 못했습니다.', RECOGNITION_FAILED: '영수증을 인식하지 못했습니다.',
  UNSUPPORTED_INPUT: '지원하지 않는 이미지입니다.', IMAGE_UNAVAILABLE: '저장된 사진을 읽지 못했습니다.',
  INTERRUPTED: '인식 작업이 중단되었습니다.'
};

export function ReceiptOcrPanel({ token, roomId, receiptId, name, members, currentUserId, onApplied, onAddManualItem }: {
  token: string; roomId: number; receiptId: number; name: string;
  members: RoomMember[]; currentUserId?: number; onApplied: () => Promise<void>;
  onAddManualItem?: () => void;
}) {
  const [mobilePane, setMobilePane] = useState<'photo' | 'items'>('items');
  const [source, setSource] = useState<ImageSource | null>(null);
  function selectSource(item: ReceiptOcrJob['draft']['items'][number]) {
    if (item.imageId == null) return;
    setSource({ imageId: item.imageId, name: item.name ?? '선택한 품목', polygons: sourcePolygons(item.ocr.detail) });
    setMobilePane('photo');
  }
  const [job, setJob] = useState<ReceiptOcrJob | null>(null);
  const [loading, setLoading] = useState(true);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState('');
  const requestId = useRef<string | null>(null);
  const inFlight = useRef(false);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true); setError('');
    getLatestReceiptOcr(token, roomId, receiptId)
      .then(result => { if (!cancelled) setJob(result); })
      .catch(e => { if (!cancelled) setError(errorMessage(e)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [token, roomId, receiptId, reload]);

  useEffect(() => {
    if (!job || !['PENDING', 'PROCESSING'].includes(job.status) || error || loading) return;
    let cancelled = false;
    const timer = window.setTimeout(() => {
      getReceiptOcr(token, roomId, receiptId, job.jobId)
        .then(result => { if (!cancelled) setJob(result); })
        .catch(e => { if (!cancelled) setError(errorMessage(e)); });
    }, 1500);
    return () => { cancelled = true; window.clearTimeout(timer); };
  }, [token, roomId, receiptId, job, error, loading]);

  async function start() {
    if (inFlight.current || job || loading || error) return;
    inFlight.current = true; setStarting(true); setError('');
    requestId.current ??= crypto.randomUUID();
    try { setJob(await startReceiptOcr(token, roomId, receiptId, requestId.current)); }
    catch (e) { setError(errorMessage(e)); }
    finally { inFlight.current = false; setStarting(false); }
  }

  return <section className="panel detail-panel ocr-panel" aria-label="영수증 인식 결과">
    <h3>{name} · 사진 인식</h3>
    <div className="receipt-mobile-tabs" role="group" aria-label="사진과 품목 전환">
      <button type="button" aria-pressed={mobilePane === 'photo'} onClick={() => setMobilePane('photo')}>사진 보기</button>
      <button type="button" aria-pressed={mobilePane === 'items'} onClick={() => setMobilePane('items')}>품목 보기</button>
    </div>
    <div className="ocr-review-layout" data-mobile-pane={mobilePane}>
    <ReceiptImageViewer token={token} roomId={roomId} receiptId={receiptId} job={job} source={source} />
    <div className="ocr-review-content">
    {loading ? <p role="status">인식 상태 확인 중…</p> : !job && !error && <>
      <p>업로드한 사진에서 품목과 금액을 읽습니다.</p>
      <button className="button primary" disabled={starting} onClick={start}>
        {starting ? '인식 요청 중…' : '영수증 인식 시작'}
      </button>
    </>}
    {error && <div role="alert"><p>{error}</p>
      <button className="button secondary" onClick={() => setReload(v => v + 1)} disabled={loading}>상태 다시 확인</button>
    </div>}
    {job && !loading && <>
      <button className="button secondary" onClick={() => {
        if (window.confirm('저장하지 않은 검토 내용은 사라집니다. 최신 상태를 불러올까요?')) setReload(v => v + 1);
      }}>상태 다시 확인</button>
      <ReceiptOcrResult job={job} onSelectSource={selectSource} />
      {job.draft.status === 'APPLIED' ? <>
        <p role="status">정산 품목에 반영되었습니다. 아래에서 결제자·참여자를 확인하고 누락된 품목을 추가할 수 있습니다.</p>
        {onAddManualItem && <button className="button secondary" type="button" onClick={onAddManualItem}>누락 품목 추가</button>}
      </> :
        !['PENDING', 'PROCESSING'].includes(job.status) && (currentUserId === job.requestedByUserId ?
          <OcrDraftEditor key={`${job.jobId}-${job.draft.revision}-${reload}`} token={token} roomId={roomId} job={job}
            members={members} onSaved={setJob} onApplied={onApplied} onSelectSource={selectSource} /> :
          <p>영수증을 올린 사용자만 초안을 검토하고 정산에 반영할 수 있습니다.</p>)}
    </>}
    </div></div>
  </section>;
}

export function ReceiptOcrResult({ job, onSelectSource }: { job: ReceiptOcrJob; onSelectSource?: (item: ReceiptOcrJob['draft']['items'][number]) => void }) {
  return <div className="ocr-result">
    <p role="status"><strong>{statusLabels[job.status]}</strong></p>
    {job.images.map(image => <div key={image.imageId}>
      {image.errorCode && <p role="alert">{providerErrors[image.errorCode] ?? '사진 인식 중 오류가 발생했습니다.'}</p>}
      {image.candidates && <p>{image.candidates.storeName ?? '상호 미인식'} · 영수증 인식 총액 {money(image.candidates.totalAmount)}</p>}
    </div>)}
    {job.draft.items.length > 0 && <>
      <div className="ocr-table-scroll"><table className="ocr-table">
        <caption>인식된 품목 (검토용 초안)</caption>
        <thead><tr><th>품목</th><th>단가</th><th>수량</th><th>인식 금액</th></tr></thead>
        <tbody>{job.draft.items.map(item => <tr key={item.draftItemId}>
          <td>{onSelectSource && item.imageId != null ? <button className="source-photo-link" type="button" onClick={() => onSelectSource(item)}>{item.name ?? '품목명 미인식'} · 원본 보기</button> : item.name ?? '품목명 미인식'}{(item.issues.length > 0 || item.warnings.length > 0) && <small> · 확인 필요</small>}</td>
          <td>{money(item.price)}</td><td>{item.quantity ?? '미인식'}</td><td>{money(item.ocr.lineAmount)}</td>
        </tr>)}</tbody>
      </table></div>
      <p>인식 품목 합계 <strong>{money(job.draft.itemsTotal)}</strong></p>
      {job.draft.warnings.includes('OCR_TOTAL_MISMATCH') && <p role="alert">품목 합계와 영수증의 총액이 다릅니다. 빠진 품목을 확인해주세요.</p>}
      {job.draft.status !== 'APPLIED' && <p className="draft-help">현재 결과는 검토용 초안입니다. 정산 품목에는 아직 반영되지 않았습니다.</p>}
    </>}
  </div>;
}
