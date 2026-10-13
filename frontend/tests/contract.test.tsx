import assert from 'node:assert/strict';
import { test } from 'node:test';
import { addItem, updateItem, uploadReceiptImage, startReceiptOcr, getLatestReceiptOcr, getReceiptOcr, saveReceiptOcrDraft, applyReceiptOcrDraft } from '../src/api';
import { renderToStaticMarkup } from 'react-dom/server';
import { SettlementBoard, ManualReceiptPanel, ReceiptsView } from '../src/App';
import { OcrDraftEditor } from '../src/OcrDraftEditor';
import { ReceiptOcrResult } from '../src/ImageReceiptPanel';
import { receiptItemsToDraftItems, normalizeReceiptDraft, getCreateAttempt, applyParticipants, setItemExcluded, calculateDraftTotal } from '../src/receiptDraft';

test('image upload sends the actual file as multipart without a JSON content type', async (t) => {
  let sent;
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    sent = { url, options };
    return new Response(JSON.stringify({ success: true, data: { receiptId: 27 } }), { status: 201 });
  });
  const file = new File(['image-content'], 'receipt.png', { type: 'image/png' });
  const result = await uploadReceiptImage('token', 8, file, '사진 영수증');
  assert.equal(result.receiptId, 27);
  assert.equal(sent.url, '/api/rooms/8/receipts/image');
  assert.equal(sent.options.method, 'POST');
  assert.equal(sent.options.headers.get('Authorization'), 'Bearer token');
  assert.equal(sent.options.headers.has('Content-Type'), false);
  assert.equal(await sent.options.body.get('file').text(), 'image-content');
  assert.equal(sent.options.body.get('name'), '사진 영수증');
});

test('OCR start reuses its request ID and status reads never POST another job', async (t) => {
  const calls = [];
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push({ url, method: options.method ?? 'GET', body: options.body });
    return new Response(JSON.stringify({ success: true, data: null }), { status: 200 });
  });
  await startReceiptOcr('token', 8, 27, 'stable-request');
  assert.equal(await getLatestReceiptOcr('token', 8, 27), null);
  await getReceiptOcr('token', 8, 27, 6);
  assert.deepEqual(calls, [
    { url: '/api/rooms/8/receipts/27/ocr/jobs', method: 'POST', body: '{"requestId":"stable-request"}' },
    { url: '/api/rooms/8/receipts/27/ocr/jobs/latest', method: 'GET', body: undefined },
    { url: '/api/rooms/8/receipts/27/ocr/jobs/6', method: 'GET', body: undefined }
  ]);
});

const receipt = {
  receiptId: 7, roomId: 1, name: 'Dinner', sourceType: 'MANUAL', payerMemberId: 12,
  payerNickname: 'A', itemsTotal: 8000, purchasedAt: null, createdAt: '',
  items: [
    { itemId: 1, name: 'A', price: 1000, quantity: 2, memberIds: [12], excludedFromSettlement: false },
    { itemId: 2, name: 'B', price: 3000, quantity: 1, memberIds: [15], excludedFromSettlement: false },
    { itemId: 3, name: 'Personal', price: 3000, quantity: 1, memberIds: [], excludedFromSettlement: true }
  ]
};

test('switching receipt selection hides the previous image OCR panel until details match', () => {
  const html = renderToStaticMarkup(<ReceiptsView token="token" roomId={1} members={[]}
    receipts={[]} selectedReceiptId={8} setSelectedReceiptId={() => {}}
    selectedReceipt={{ ...receipt, sourceType: 'IMAGE_UPLOAD', name: 'Previous photo' }}
    assignees={{}} setAssignees={() => {}} refreshRoom={async () => {}}
    refreshReceipt={async () => {}} setNotice={() => {}} />);
  assert.doesNotMatch(html, /Previous photo/);
  assert.match(html, /선택한 영수증을 불러오는 중/);
});

test('image receipt list shows calculated item total', () => {
  const photo = { ...receipt, sourceType: 'IMAGE_UPLOAD' };
  const html = renderToStaticMarkup(<ReceiptsView token="token" roomId={1} members={[]}
    receipts={[photo]} selectedReceiptId={7} setSelectedReceiptId={() => {}}
    selectedReceipt={photo} assignees={{}} setAssignees={() => {}}
    refreshRoom={async () => {}} refreshReceipt={async () => {}} setNotice={() => {}} />);
  assert.match(html, /<b>8,000원<\/b>/);
  assert.doesNotMatch(html, /미확정/);
  assert.doesNotMatch(html, />0원</);
});

test('an existing image receipt offers manual item entry in its own detail panel', () => {
  const photo = { ...receipt, sourceType: 'IMAGE_UPLOAD' };
  const html = renderToStaticMarkup(<ReceiptsView token="token" roomId={1} members={[]}
    receipts={[photo]} selectedReceiptId={7} setSelectedReceiptId={() => {}}
    selectedReceipt={photo} assignees={{}} setAssignees={() => {}}
    refreshRoom={async () => {}} refreshReceipt={async () => {}} setNotice={() => {}} />);
  assert.match(html, /aria-label="수동 품목 추가"/);
  assert.match(html, /aria-label="추가 품목명"/);
  assert.match(html, /aria-label="추가 단가"/);
  assert.match(html, /aria-label="추가 수량"/);
  assert.doesNotMatch(html, />영수증을 선택하세요\.</);
});

test('creating a new receipt shows the entry form without an empty detail panel', () => {
  const html = renderToStaticMarkup(<ReceiptsView token="token" roomId={1} members={[]}
    receipts={[receipt]} selectedReceiptId={null} setSelectedReceiptId={() => {}}
    selectedReceipt={receipt} assignees={{}} setAssignees={() => {}}
    refreshRoom={async () => {}} refreshReceipt={async () => {}} setNotice={() => {}} />);
  assert.match(html, /aria-label="새 영수증 입력 방식"/);
  assert.match(html, /aria-pressed="true"[^>]*>영수증 사진 첨부/);
  assert.match(html, /사진 업로드/);
  assert.match(html, /class="receipt-entry-pane" hidden=""><form class="panel manual-panel"/);
  assert.doesNotMatch(html, /detail-panel/);
  assert.doesNotMatch(html, />영수증을 선택하세요\.</);
  assert.ok(html.indexOf('receipt-list-panel') < html.indexOf('image-upload-panel'), '목록이 입력 폼보다 먼저 표시되어야 함');
});

test('selecting an existing image receipt keeps the list above review and hides new receipt upload', () => {
  const photo = { ...receipt, sourceType: 'IMAGE_UPLOAD' };
  const html = renderToStaticMarkup(<ReceiptsView token="token" roomId={1} members={[]}
    receipts={[photo]} selectedReceiptId={7} setSelectedReceiptId={() => {}}
    selectedReceipt={photo} assignees={{}} setAssignees={() => {}}
    refreshRoom={async () => {}} refreshReceipt={async () => {}} setNotice={() => {}} />);
  assert.ok(html.indexOf('receipt-list-panel') < html.indexOf('ocr-panel'));
  assert.doesNotMatch(html, /image-upload-panel/);
  assert.doesNotMatch(html, /aria-label="새 영수증 입력 방식"/);
});

test('OCR results preserve missing values and identify results as uncommitted drafts', () => {
  const html = renderToStaticMarkup(<ReceiptOcrResult job={{
    jobId: 1, receiptId: 7, status: 'REVIEWABLE', provider: 'CLOVA_RECEIPT', images: [],
    draft: { itemsTotal: null, warnings: ['OCR_TOTAL_MISMATCH'], blockers: ['ITEM_ISSUES'], items: [
      { draftItemId: 1, name: 'Soup', price: null, quantity: null, ocr: { lineAmount: 16000 },
        issues: ['MISSING_PRICE'], warnings: [] }
    ] }
  }} />);
  assert.match(html, /인식되지 않음/);
  assert.match(html, /16,000원/);
  assert.match(html, /정산 품목에는 아직 반영되지 않았습니다/);
  assert.match(html, /품목 합계와 영수증의 총액이 다릅니다/);
  assert.doesNotMatch(html, />0원</);
});

test('opening an existing receipt preserves separate assignments and exclusion', () => {
  const draft = receiptItemsToDraftItems(receipt);
  assert.deepEqual(draft.map(item => item.memberIds), [[12], [15], []]);
  assert.deepEqual(draft.map(item => item.excludedFromSettlement), [false, false, true]);
});

test('blocked settlement shows reasons without transfers or an all-clear empty result', () => {
  const html = renderToStaticMarkup(<SettlementBoard settlement={{
    roomId: 1, roomName: 'Room', ready: false, members: [],
    issues: [{ code: 'PAYER_MISSING', receiptId: 7, receiptName: '팀회식', itemId: null, message: '결제자를 지정해주세요.' }]
  }} />);
  assert.match(html, /결제자를 지정해주세요/);
  assert.match(html, /팀회식/);
  assert.doesNotMatch(html, /영수증 #7/);
  assert.doesNotMatch(html, /송금할 내역이 없습니다/);
  assert.doesNotMatch(html, /transfer-section/);
});

const draft = () => ({ name: 'Dinner', payerMemberId: '12', purchasedAt: '',
  items: receiptItemsToDraftItems(receipt) });

test('metadata and price changes retain assignments without sending a manual total', () => {
  const input = draft();
  input.name = 'Updated dinner';
  input.items[0].price = '2000';
  const payload = normalizeReceiptDraft(input);
  assert.equal(Object.hasOwn(payload, 'declaredTotal'), false);
  assert.equal(payload.items[0].price, 2000);
  assert.deepEqual(payload.items.map(item => item.memberIds), [[12], [15], []]);
  assert.deepEqual(payload.items.map(item => item.itemId), [1, 2, 3]);
});

test('item-only receipts and unassigned participants are valid drafts', () => {
  const input = draft();
  input.items[0].memberIds = [];
  const payload = normalizeReceiptDraft(input);
  assert.equal(Object.hasOwn(payload, 'declaredTotal'), false);
  assert.deepEqual(payload.items[0].memberIds, []);
});

for (const [field, value] of [['price', '1000001'], ['price', '1e3'], ['price', '9007199254740993'],
  ['quantity', '1000'], ['quantity', '1.2'], ['price', '0']]) {
  test(`rejects invalid ${field}: ${value}`, () => {
    const input = draft();
    input.items[0][field] = value;
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
  test(`item-only total display for ${sourceType ?? 'new manual'} receipts`, () => {
    const html = renderToStaticMarkup(<ManualReceiptPanel
      token="token" roomId={1} members={[]} editReceipt={sourceType ? { ...receipt, sourceType } : null}
      newDraftVersion={0} setSelectedReceiptId={() => {}} refreshRoom={async () => {}}
      refreshReceipt={async () => {}} setNotice={() => {}} />);
    assert.doesNotMatch(html, /품목 합계로 총액 확인|원본 총액|직접 입력한 총액/);
    assert.match(html, /전체 품목 합계/);
    assert.match(html, /제외 품목도 합계에 포함됩니다/);
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


test('review saves the draft version and apply sends only the reviewed server revision', async (t) => {
  const calls = [];
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push({ url, method: options.method, body: JSON.parse(options.body) });
    return new Response(JSON.stringify({ success: true, data: {} }), { status: 200 });
  });
  const payload = { revision: 2, coveredImageIds: [], items: [] };
  await saveReceiptOcrDraft('token', 8, 27, 6, payload);
  await applyReceiptOcrDraft('token', 8, 27, 6, 3);
  assert.deepEqual(calls, [
    { url: '/api/rooms/8/receipts/27/ocr/jobs/6/draft', method: 'PUT', body: payload },
    { url: '/api/rooms/8/receipts/27/ocr/jobs/6/draft/apply', method: 'POST', body: { revision: 3, reviewConfirmed: true } }
  ]);
});


test('OCR review offers editing and prevents apply until review confirmation', () => {
  const job = { jobId: 1, receiptId: 7, requestedByUserId: 1, status: 'REVIEWABLE', provider: 'CLOVA_RECEIPT', images: [],
    draft: { status: 'EDITING', revision: 2, itemsTotal: 18000, warnings: [], blockers: [], items: [
      { draftItemId: 1, imageId: 1, name: 'Soup', price: 9000, quantity: 2, memberIds: [12],
        excludedFromSettlement: false, removed: false, ocr: { lineAmount: 18000 }, issues: [], warnings: [] }
    ] } };
  const html = renderToStaticMarkup(<OcrDraftEditor token="token" roomId={1} job={job}
    members={[{ memberId: 12, nickname: 'A' }]} onSaved={() => {}} onApplied={async () => {}} />);
  assert.match(html, /1번 품목명/);
  assert.match(html, /1번 품목 참여자/);
  assert.match(html, /검토했습니다/);
  assert.match(html, /disabled="">정산에 반영/);
  const applied = renderToStaticMarkup(<ReceiptOcrResult job={{ ...job, draft: { ...job.draft, status: 'APPLIED' } }} />);
  assert.doesNotMatch(applied, /정산 품목에는 아직 반영되지 않았습니다/);
});

test('image list requires member auth and OCR fields retain their source polygons', async (t) => {
  const { getReceiptImages } = await import('../src/api');
  const { sourcePolygons } = await import('../src/ReceiptImageViewer');
  let sent;
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    sent = { url, options };
    return new Response(JSON.stringify({ success: true, data: [{ imageId: 41, order: 1, url: '/signed-photo', expiresAt: '2026-10-12T12:05:00Z', contentType: 'image/png' }] }));
  });
  const images = await getReceiptImages('token', 8, 27);
  assert.equal(sent.url, '/api/rooms/8/receipts/27/images');
  assert.equal(sent.options.headers.get('Authorization'), 'Bearer token');
  assert.equal(images[0].imageId, 41);
  const box = [{ x: 10, y: 20 }, { x: 30, y: 20 }, { x: 30, y: 40 }];
  assert.deepEqual(sourcePolygons({ name: { box }, quantity: { box: [] } }), [[{ x: 10, y: 20 }, { x: 30, y: 20 }, { x: 30, y: 40 }, { x: 10, y: 40 }]]);
  assert.deepEqual(sourcePolygons(null), []);
  assert.deepEqual(sourcePolygons({ name: { box: [{ x: -1, y: 1 }] } }), []);
});

test('OCR errors expose HTTP status, reason and Retry-After even for a non-JSON response', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => new Response('Too many requests', {
    status: 429, headers: { 'Retry-After': '12' }
  }));
  await assert.rejects(getReceiptOcr('token', 1, 7, 1), error => {
    assert.equal(error.status, 429);
    assert.equal(error.retryAfterMs, 12000);
    return true;
  });
});

test('failed image retry only posts to that image and preserves structured quota reasons', async (t) => {
  const api = await import('../src/api');
  assert.equal(typeof api.retryReceiptOcrImage, 'function');
  let sent;
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    sent = { url, method: options.method };
    return new Response(JSON.stringify({ success: false, data: null, error: {
      code: 'TOO_MANY_REQUESTS', message: '오늘 한도 초과',
      details: [{ field: 'ocr', reason: 'OCR_USER_DAILY_LIMIT', rejectedValue: null }]
    } }), { status: 429 });
  });
  await assert.rejects(api.retryReceiptOcrImage('token', 1, 7, 2, 41), error => {
    assert.equal(error.reason, 'OCR_USER_DAILY_LIMIT');
    assert.equal(error.status, 429);
    return true;
  });
  assert.deepEqual(sent, { url: '/api/rooms/1/receipts/7/ocr/jobs/2/images/41/retry', method: 'POST' });
});

function reviewJob() {
  return { jobId: 1, receiptId: 7, requestedByUserId: 1, status: 'PARTIALLY_FAILED', provider: 'CLOVA_RECEIPT',
    images: [{ imageId: 41, status: 'FAILED', errorCode: 'PROVIDER_TIMEOUT', manuallyCovered: false,
      retryable: true, attemptCount: 1, candidates: null }],
    draft: { status: 'EDITING', revision: 2, itemsTotal: 0, warnings: [], blockers: ['IMAGE_FAILED_UNCOVERED'], items: [
      { draftItemId: 1, imageId: 40, name: 'Soup', price: null, quantity: 2, memberIds: [12],
        excludedFromSettlement: false, removed: false,
        ocr: { unitPrice: null, lineAmount: 16000, derivedUnitPrice: 8000 }, issues: ['MISSING_PRICE'], warnings: [] }
    ] } };
}

test('review shows an opt-in calculated price with its basis without filling the price', () => {
  const html = renderToStaticMarkup(<OcrDraftEditor token="token" roomId={1} job={reviewJob()}
    members={[]} onSaved={() => {}} onApplied={async () => {}} />);
  assert.match(html, /계산 단가 후보/);
  assert.match(html, /8,000원/);
  assert.match(html, /16,000원 ÷ 2개/);
  assert.match(html, /이 값 사용/);
  assert.match(html, /aria-label="1번 단가"[^>]*value=""/);
});

test('retry control exposes remaining attempts and cannot retry exhausted photos', () => {
  const job = reviewJob();
  const render = () => renderToStaticMarkup(<OcrDraftEditor token="token" roomId={1} job={job}
    members={[]} canRetry={true} onSaved={() => {}} onApplied={async () => {}} />);
  assert.match(render(), /다시 인식/);
  assert.match(render(), /남은 재시도 2회/);
  job.images[0].attemptCount = 3;
  assert.match(render(), /disabled=""[^>]*>다시 인식/);
  assert.match(render(), /3회.*사용/);
});

import './ocrPolling.test';

test('calculated price follows edited quantity and rejects rounding, invalid amounts and already entered prices', async () => {
  const { calculatedUnitPrice } = await import('../src/ocrReview');
  const original = reviewJob().draft.items[0];
  const line = { ...original };
  assert.equal(calculatedUnitPrice(line, original), 8000);
  assert.equal(calculatedUnitPrice({ ...line, quantity: 4 }, original), 4000);
  for(const quantity of [0, -1, 3, 1.5, 1000, null])
    assert.equal(calculatedUnitPrice({ ...line, quantity }, original), null);
  assert.equal(calculatedUnitPrice({ ...line, price: 7000 }, original), null);
  assert.equal(calculatedUnitPrice({ ...line, removed: true }, original), null);
  assert.equal(calculatedUnitPrice(line, { ...original, ocr: { unitPrice: 8000, lineAmount: 16000 } }), null);
  assert.equal(calculatedUnitPrice(line, { ...original, ocr: { unitPrice: null, lineAmount: 3000000 } }), null);
  assert.equal(calculatedUnitPrice(line, undefined), null);
});

test('retry eligibility protects unsaved edits, other viewers, completed drafts and successful images', async () => {
  const { imageRetryAvailable } = await import('../src/ocrReview');
  const job = reviewJob();
  assert.equal(imageRetryAvailable(job,41,true,false),true);
  assert.equal(imageRetryAvailable(job,41,true,true),false);
  assert.equal(imageRetryAvailable(job,41,false,false),false);
  assert.equal(imageRetryAvailable({...job,status:'PROCESSING'},41,true,false),false);
  assert.equal(imageRetryAvailable({...job,draft:{...job.draft,status:'APPLIED'}},41,true,false),false);
  job.images[0].retryable=false;
  assert.equal(imageRetryAvailable(job,41,true,false),false);
  job.images[0].retryable=true;job.images[0].status='SUCCEEDED';
  assert.equal(imageRetryAvailable(job,41,true,false),false);
});

test('Retry-After HTTP dates are exposed as remaining milliseconds', async t => {
  t.mock.timers.enable({apis:['Date'],now:Date.parse('2026-10-12T12:00:00Z')});
  t.mock.method(globalThis,'fetch',async()=>new Response('',{status:429,headers:{'Retry-After':'Mon, 12 Oct 2026 12:00:20 GMT'}}));
  await assert.rejects(getReceiptOcr('token',1,7,1),e=>{assert.equal(e.retryAfterMs,20000);return true;});
});

test('network failure while reading an HTTP 200 body remains retryable', async t => {
  t.mock.method(globalThis,'fetch',async()=>new Response(new ReadableStream({
    start(controller){controller.error(new TypeError('connection lost while reading body'));}
  }),{status:200}));
  await assert.rejects(getReceiptOcr('token',1,7,1),TypeError);
});
