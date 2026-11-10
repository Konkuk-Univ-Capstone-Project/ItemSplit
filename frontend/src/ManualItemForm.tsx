import { useRef, useState, type FormEvent, type RefObject } from 'react';
import { addItem } from './api';

export function ManualItemForm({ token, roomId, receiptId, formRef, onAdded }: {
  token: string; roomId: number; receiptId: number;
  formRef: RefObject<HTMLFormElement | null>; onAdded: () => Promise<void>;
}) {
  const [name, setName] = useState('');
  const [price, setPrice] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const inFlight = useRef(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (inFlight.current) return;
    if (!name.trim()) { setError('품목명을 입력해주세요.'); return; }
    inFlight.current = true;
    setBusy(true); setError('');
    let saved = false;
    try {
      await addItem(token, roomId, receiptId, { name: name.trim(), price: Number(price), quantity: Number(quantity) });
      saved = true;
      setName(''); setPrice(''); setQuantity('1');
      await onAdded();
    } catch (cause) {
      setError(saved ? '품목은 저장되었지만 목록을 갱신하지 못했습니다. 새로고침해주세요.' :
        cause instanceof Error ? cause.message : '품목을 추가하지 못했습니다.');
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  return <form ref={formRef} className="manual-item-form" aria-label="수동 품목 추가" onSubmit={submit}>
    <h4>수동 품목 추가</h4>
    <p>이 영수증에 빠진 품목을 추가합니다. 저장 후 아래 품목 목록에서 참여자를 지정해주세요.</p>
    <fieldset className="receipt-fieldset" disabled={busy}>
      <div className="ocr-edit-fields">
        <label>품목명<input aria-label="추가 품목명" value={name} onChange={event => setName(event.target.value)} maxLength={100} required /></label>
        <label>단가<input aria-label="추가 단가" type="number" min="1" max="1000000" step="1" value={price}
          onChange={event => setPrice(event.target.value)} required /></label>
        <label>수량<input aria-label="추가 수량" type="number" min="1" max="999" step="1" value={quantity}
          onChange={event => setQuantity(event.target.value)} required /></label>
      </div>
      <button className="button secondary" type="submit">{busy ? '추가 중…' : '이 영수증에 품목 추가'}</button>
    </fieldset>
    {error && <p role="alert">{error}</p>}
  </form>;
}
