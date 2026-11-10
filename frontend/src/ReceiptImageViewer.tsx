import { useEffect, useRef, useState } from 'react';
import { getReceiptImages } from './api';
import type { OcrPoint, OcrSourceDetail, ReceiptImage, ReceiptOcrJob } from './types';

export function sourcePolygons(detail?: OcrSourceDetail | null): OcrPoint[][] {
  return Object.values(detail ?? {}).map(field => field?.box ?? []).filter(box => box.length >= 3 &&
    box.every(point => Number.isFinite(point.x) && Number.isFinite(point.y) && point.x >= 0 && point.y >= 0)).map(box => {
      // A field may concatenate several word boxes; bound them instead of drawing crossed edges.
      const left = Math.min(...box.map(p => p.x)), right = Math.max(...box.map(p => p.x));
      const top = Math.min(...box.map(p => p.y)), bottom = Math.max(...box.map(p => p.y));
      return [{ x: left, y: top }, { x: right, y: top }, { x: right, y: bottom }, { x: left, y: bottom }];
    });
}
export type ImageSource = { imageId: number; polygons: OcrPoint[][]; name: string };
const statuses: Record<string, string> = { PENDING: '인식 대기', PROCESSING: '인식 중', SUCCEEDED: '인식 완료', FAILED: '인식 실패' };

export function ReceiptImageViewer({ token, roomId, receiptId, job, source }: {
  token: string; roomId: number; receiptId: number; job: ReceiptOcrJob | null; source: ImageSource | null;
}) {
  const [images, setImages] = useState<ReceiptImage[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [zoom, setZoom] = useState(1);
  const [size, setSize] = useState({ width: 0, height: 0 });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [reload, setReload] = useState(0);
  const automaticUsed = useRef(false);
  const refreshing = useRef(false);
  const viewer = useRef<HTMLElement>(null);

  useEffect(() => {
    let cancelled = false;
    refreshing.current = true; setLoading(true); setError('');
    getReceiptImages(token, roomId, receiptId).then(result => {
      if (cancelled) return;
      setImages(result);
      setSelectedId(current => result.some(image => image.imageId === current) ? current : result[0]?.imageId ?? null);
    }).catch(e => {
      if (!cancelled) { setImages([]); setError(e instanceof Error ? e.message : '사진을 불러오지 못했습니다.'); }
    }).finally(() => { if (!cancelled) { refreshing.current = false; setLoading(false); } });
    return () => { cancelled = true; };
  }, [token, roomId, receiptId, reload]);

  // Refresh an expired URL once. Errors require a deliberate retry; editing state lives outside this component.
  function failedImage() {
    if (refreshing.current) return;
    if (!automaticUsed.current) {
      automaticUsed.current = true; refreshing.current = true; setReload(value => value + 1);
    } else setError('사진을 표시하지 못했습니다. 접근 권한이나 파일 상태를 확인한 뒤 다시 불러와주세요.');
  }
  useEffect(() => {
    if (images.length === 0 || error) return;
    const expires = Math.min(...images.map(image => Date.parse(image.expiresAt)));
    if (!Number.isFinite(expires)) return;
    const timer = window.setTimeout(() => {
      if (!refreshing.current) { refreshing.current = true; setReload(value => value + 1); }
    }, Math.max(1000, expires - Date.now() + 100));
    return () => window.clearTimeout(timer);
  }, [images, error]);

  useEffect(() => {
    if (!source) return;
    setSelectedId(source.imageId); setZoom(1);
    viewer.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }, [source]);
  useEffect(() => { setSize({ width: 0, height: 0 }); }, [selectedId]);
  const selected = images.find(image => image.imageId === selectedId);
  const polygons = source?.imageId === selectedId ? source.polygons : [];
  return <section ref={viewer} className="receipt-image-viewer" aria-label="업로드된 영수증 사진">
    <h4>영수증 원본</h4>
    {loading && <p role="status">사진 불러오는 중…</p>}
    {error && <p role="alert">{error}</p>}
    {!loading && !error && images.length === 0 && <p>저장된 사진이 없습니다.</p>}
    <div className="receipt-thumbnails">
      {images.map(image => <button type="button" className={image.imageId === selectedId ? 'selected' : ''}
        key={image.imageId} aria-pressed={image.imageId === selectedId} onClick={() => { setSelectedId(image.imageId); setZoom(1); }}>
        <img src={image.url} alt={`${image.order}번 영수증 썸네일`} referrerPolicy="no-referrer" onError={failedImage} />
        <span>사진 {image.order} · {statuses[job?.images.find(row => row.imageId === image.imageId)?.status ?? ''] ?? '인식 전'}</span>
      </button>)}
    </div>
    {selected && <>
      <div className="receipt-image-controls">
        <button type="button" aria-label="사진 축소" disabled={zoom <= 1} onClick={() => setZoom(v => Math.max(1, v - 0.5))}>−</button>
        <output aria-label="사진 확대 비율">{Math.round(zoom * 100)}%</output>
        <button type="button" aria-label="사진 확대" disabled={zoom >= 4} onClick={() => setZoom(v => Math.min(4, v + 0.5))}>＋</button>
        <button type="button" onClick={() => setZoom(1)}>화면에 맞추기</button>
      </div>
      <div className={`receipt-image-scroll${zoom > 1 ? ' is-zoomed' : ''}`}>
        <div className="receipt-image-canvas" style={{ width: `${zoom * 100}%` }}>
          <img key={selected.imageId} src={selected.url} alt={`업로드된 영수증 사진 ${selected.order}`} referrerPolicy="no-referrer"
            onError={failedImage} onLoad={event => setSize({ width: event.currentTarget.naturalWidth, height: event.currentTarget.naturalHeight })} />
          {size.width > 0 && polygons.length > 0 && <svg className="receipt-source-overlay" viewBox={`0 0 ${size.width} ${size.height}`}
            role="img" aria-label={`${source?.name} 인식 영역`}>
            {polygons.map((box, index) => <polygon key={index} points={box.map(p => `${p.x},${p.y}`).join(' ')} />)}
          </svg>}
        </div>
      </div>
      {source?.imageId === selectedId && <p className="draft-help">{source.name} · {polygons.length ? '인식 영역 표시' : '영역 좌표가 없어 사진 전체를 표시합니다.'}</p>}
    </>}
    <button className="button secondary" type="button" disabled={loading} onClick={() => {
      automaticUsed.current = false; setReload(value => value + 1);
    }}>사진 다시 불러오기</button>
  </section>;
}
