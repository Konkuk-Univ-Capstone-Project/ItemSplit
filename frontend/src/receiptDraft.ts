import type { ReceiptDetail } from './types';

export type DraftItem = {
  itemId?: number;
  name: string;
  price: string;
  quantity: string;
  memberIds: number[];
  excludedFromSettlement: boolean;
};

export type ReceiptDraft = {
  name: string;
  payerMemberId: string;
  declaredTotal: string;
  purchasedAt: string;
  items: DraftItem[];
};

export function blankDraftItem(): DraftItem {
  return { name: '', price: '', quantity: '1', memberIds: [], excludedFromSettlement: false };
}

export function receiptItemsToDraftItems(receipt: ReceiptDetail): DraftItem[] {
  return receipt.items.map((item) => ({
    itemId: item.itemId, name: item.name, price: String(item.price), quantity: String(item.quantity),
    memberIds: [...item.memberIds], excludedFromSettlement: item.excludedFromSettlement
  }));
}

function boundedInteger(value: string, maximum: number, label: string): number {
  const number = Number(value);
  if (!/^[0-9]+$/.test(value.trim()) || !Number.isSafeInteger(number) || number < 1 || number > maximum) {
    throw new Error(`${label}은(는) 1~${maximum.toLocaleString('ko-KR')} 사이의 정수로 입력해주세요.`);
  }
  return number;
}

export function normalizeReceiptDraft(draft: ReceiptDraft) {
  if (draft.items.length === 0) throw new Error('품목을 하나 이상 입력해주세요.');
  if (!draft.name.trim() || draft.items.some(item => !item.name.trim())) {
    throw new Error('상호명과 품목명을 입력해주세요.');
  }
  let total = 0;
  const items = draft.items.map(item => {
    const price = boundedInteger(item.price, 1_000_000, '단가');
    const quantity = boundedInteger(item.quantity, 999, '수량');
    const lineTotal = price * quantity;
    total += lineTotal;
    if (!Number.isSafeInteger(lineTotal) || lineTotal > 10_000_000 ||
        !Number.isSafeInteger(total) || total > 10_000_000) {
      throw new Error('품목 금액과 영수증 품목 합계는 1천만 원 이하여야 합니다. 정산 제외 품목도 포함됩니다.');
    }
    return { itemId: item.itemId ?? null, name: item.name.trim(), price, quantity,
      memberIds: [...new Set(item.memberIds)], excludedFromSettlement: item.excludedFromSettlement };
  });
  return { name: draft.name.trim(), payerMemberId: draft.payerMemberId ? Number(draft.payerMemberId) : null,
    declaredTotal: draft.declaredTotal.trim() ? boundedInteger(draft.declaredTotal, 10_000_000, '원본 총액') : null,
    purchasedAt: draft.purchasedAt || null, items };
}

export function getCreateAttempt(previous: { requestId: string; fingerprint: string } | null, payload: unknown,
  newId: () => string = () => crypto.randomUUID()) {
  const fingerprint = JSON.stringify(payload);
  return previous?.fingerprint === fingerprint ? previous : { requestId: newId(), fingerprint };
}

export function applyParticipants(items: DraftItem[], memberIds: number[]) {
  return items.map(item => item.excludedFromSettlement ? item : { ...item, memberIds: [...memberIds] });
}

export function setItemExcluded(item: DraftItem, excludedFromSettlement: boolean): DraftItem {
  return { ...item, excludedFromSettlement, memberIds: [] };
}

export function calculateDraftTotal(items: DraftItem[]): number | null {
  let total = 0;
  try {
    for (const item of items) {
      const line = boundedInteger(item.price, 1_000_000, '단가') * boundedInteger(item.quantity, 999, '수량');
      total += line;
      if (!Number.isSafeInteger(total) || line > 10_000_000 || total > 10_000_000) return null;
    }
    return total;
  } catch {
    return null;
  }
}
