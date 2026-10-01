import assert from 'node:assert/strict';
import { test } from 'node:test';
import { addItem, updateItem } from '../src/api';
import { renderToStaticMarkup } from 'react-dom/server';
import { SettlementBoard, ManualReceiptPanel } from '../src/App';
import { receiptItemsToDraftItems, normalizeReceiptDraft, getCreateAttempt, applyParticipants, setItemExcluded, calculateDraftTotal } from '../src/receiptDraft';

const receipt = {
  receiptId: 7, roomId: 1, name: 'Dinner', sourceType: 'MANUAL', payerMemberId: 12,
  payerNickname: 'A', declaredTotal: 8000, purchasedAt: null, createdAt: '', warning: null,
  items: [
    { itemId: 1, name: 'A', price: 1000, quantity: 2, memberIds: [12], excludedFromSettlement: false },
    { itemId: 2, name: 'B', price: 3000, quantity: 1, memberIds: [15], excludedFromSettlement: false },
    { itemId: 3, name: 'Personal', price: 3000, quantity: 1, memberIds: [], excludedFromSettlement: true }
  ]
};

test('opening an existing receipt preserves separate assignments and exclusion', () => {
  const draft = receiptItemsToDraftItems(receipt);
  assert.deepEqual(draft.map(item => item.memberIds), [[12], [15], []]);
  assert.deepEqual(draft.map(item => item.excludedFromSettlement), [false, false, true]);
});

test('blocked settlement shows reasons without transfers or an all-clear empty result', () => {
  const html = renderToStaticMarkup(<SettlementBoard settlement={{
    roomId: 1, roomName: 'Room', ready: false, members: [],
    issues: [{ code: 'TOTAL_MISSING', receiptId: 7, itemId: null, message: '원본 총액을 입력해주세요.' }]
  }} />);
  assert.match(html, /원본 총액을 입력해주세요/);
  assert.doesNotMatch(html, /송금할 내역이 없습니다/);
  assert.doesNotMatch(html, /transfer-section/);
});

const draft = () => ({ name: 'Dinner', payerMemberId: '12', declaredTotal: '8000', purchasedAt: '',
  items: receiptItemsToDraftItems(receipt) });

test('metadata and price changes retain each assignment and independent declared total', () => {
  const input = draft();
  input.name = 'Updated dinner';
  input.items[0].price = '2000';
  const payload = normalizeReceiptDraft(input);
  assert.equal(payload.declaredTotal, 8000);
  assert.deepEqual(payload.items.map(item => item.memberIds), [[12], [15], []]);
  assert.deepEqual(payload.items.map(item => item.itemId), [1, 2, 3]);
});

test('nullable total and unassigned participants are valid drafts', () => {
  const input = draft();
  input.declaredTotal = '';
  input.items[0].memberIds = [];
  const payload = normalizeReceiptDraft(input);
  assert.equal(payload.declaredTotal, null);
  assert.deepEqual(payload.items[0].memberIds, []);
});

for (const [field, value] of [['price', '1000001'], ['price', '1e3'], ['price', '9007199254740993'],
  ['quantity', '1000'], ['quantity', '1.2'], ['price', '0'], ['declaredTotal', '10000001'],
  ['declaredTotal', 'Infinity'], ['declaredTotal', '-1'], ['declaredTotal', '1.5']]) {
  test(`rejects invalid ${field}: ${value}`, () => {
    const input = draft();
    if (field === 'declaredTotal') input.declaredTotal = value;
    else input.items[0][field] = value;
    assert.throws(() => normalizeReceiptDraft(input));
  });
}

test('line and receipt caps include excluded items', () => {
  const input = draft();
  input.items[2] = { ...input.items[2], price: '1000000', quantity: '11' };
  assert.throws(() => normalizeReceiptDraft(input));
  input.items[2].quantity = '10';
  assert.throws(() => normalizeReceiptDraft(input));
  input.items = [input.items[2]];
  assert.equal(normalizeReceiptDraft(input).items[0].price, 1000000);
});

test('empty full snapshot is rejected and new rows use null identifiers', () => {
  const input = draft();
  input.items = [];
  assert.throws(() => normalizeReceiptDraft(input));
  input.items = [{ name: 'New', price: '50', quantity: '999', memberIds: [], excludedFromSettlement: false }];
  assert.equal(normalizeReceiptDraft(input).items[0].itemId, null);
});

test('create retries reuse the request identifier and edited payloads get a new identifier', () => {
  let id = 0;
  const newId = () => `attempt-${++id}`;
  const payload = normalizeReceiptDraft(draft());
  const first = getCreateAttempt(null, payload, newId);
  const retry = getCreateAttempt(first, payload, newId);
  assert.equal(retry.requestId, 'attempt-1');
  const edited = getCreateAttempt(retry, { ...payload, name: 'Changed' }, newId);
  assert.equal(edited.requestId, 'attempt-2');
  const newDraft = getCreateAttempt(null, payload, newId);
  assert.equal(newDraft.requestId, 'attempt-3');
});

test('bulk assignment is explicit and leaves excluded rows excluded with no participants', () => {
  const items = receiptItemsToDraftItems(receipt);
  const changed = applyParticipants(items, [12, 15]);
  assert.deepEqual(changed.map(item => item.memberIds), [[12, 15], [12, 15], []]);
  assert.deepEqual(items.map(item => item.memberIds), [[12], [15], []]);
});

test('excluding clears participants while reincluding requires fresh assignment', () => {
  const item = receiptItemsToDraftItems(receipt)[0];
  const excluded = setItemExcluded(item, true);
  assert.deepEqual(excluded.memberIds, []);
  assert.equal(excluded.excludedFromSettlement, true);
  const included = setItemExcluded(excluded, false);
  assert.deepEqual(included.memberIds, []);
  assert.equal(included.excludedFromSettlement, false);
});

test('preview rejects invalid or overflowing input instead of offering a misleading confirmed total', () => {
  const items = receiptItemsToDraftItems(receipt);
  assert.equal(calculateDraftTotal(items), 8000);
  assert.equal(calculateDraftTotal([{ ...items[0], price: '1e309' }]), null);
  assert.equal(calculateDraftTotal([{ ...items[0], quantity: '1.5' }]), null);
});

test('a valid shared settlement without readiness fields renders transfers', () => {
  const html = renderToStaticMarkup(<SettlementBoard settlement={{
    roomId: 1, roomName: 'Shared', shareExpiresAt: '2026-10-01', readOnly: true,
    members: [
      { memberId: 12, userId: null, nickname: 'Alice', linked: false, burden: 5000, paid: 10000, net: 5000 },
      { memberId: 15, userId: null, nickname: 'Bob', linked: false, burden: 5000, paid: 0, net: -5000 }
    ]
  }} />);
  assert.match(html, /transfer-route/);
  assert.match(html, /5,000원/);
  assert.doesNotMatch(html, /정산 준비가 필요합니다/);
});

for (const sourceType of ['MANUAL', 'IMAGE_UPLOAD', null]) {
  test(`sum confirmation availability for ${sourceType ?? 'new manual'} receipts`, () => {
    const html = renderToStaticMarkup(<ManualReceiptPanel
      token="token" roomId={1} members={[]} editReceipt={sourceType ? { ...receipt, sourceType } : null}
      newDraftVersion={0} setSelectedReceiptId={() => {}} refreshRoom={async () => {}}
      refreshReceipt={async () => {}} setNotice={() => {}} />);
    if (sourceType === 'IMAGE_UPLOAD') {
      assert.doesNotMatch(html, /품목 합계로 총액 확인/);
    } else {
      assert.match(html, /품목 합계로 총액 확인/);
    }
    assert.match(html, /원본 총액/);
  });
}

test('explicitly clearing payer preserves a nullable draft payer', () => {
  const input = draft();
  input.payerMemberId = '';
  assert.equal(normalizeReceiptDraft(input).payerMemberId, null);
});

test('legacy item writes send only editable item fields and return the legacy response', async (t) => {
  const sent = [];
  const response = { itemId: 9, receiptId: 7, name: 'Legacy', price: 1000, quantity: 1, excludedFromSettlement: true };
  t.mock.method(globalThis, 'fetch', async (_url, options) => {
    sent.push(JSON.parse(options.body));
    return new Response(JSON.stringify({ success: true, data: response, error: null }), { status: 200 });
  });
  const input = { name: 'Legacy', price: 1000, quantity: 1, memberIds: [12], excludedFromSettlement: false };
  const added = await addItem('token', 1, 7, input);
  const updated = await updateItem('token', 1, 7, 9, input);
  assert.deepEqual(sent, [
    { name: 'Legacy', price: 1000, quantity: 1 },
    { name: 'Legacy', price: 1000, quantity: 1 }
  ]);
  assert.deepEqual(added, response);
  assert.deepEqual(updated, response);
});
