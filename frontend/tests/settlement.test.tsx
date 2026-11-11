import assert from 'node:assert/strict';
import { test } from 'node:test';
import { renderToStaticMarkup } from 'react-dom/server';
import { SettlementController } from '../src/features/settlement/controller';
import { SettlementPanel } from '../src/features/settlement/SettlementPanel';
import { RoomEditingContext } from '../src/features/settlement/RoomEditingContext';
import { ReceiptsView } from '../src/App';
const open = { roomId: 1, status: 'OPEN', currentSnapshotId: null };
const preview = { roomId: 1, roomName: 'Dinner', members: [], transfers: [], ready: true, issues: [], previewToken: 'preview-a' };
const snapshot = { snapshotId: 9, roomId: 1, roomName: 'Saved dinner', version: 2, status: 'CONFIRMED', confirmedAt: '2026-11-11T12:00:00', confirmedByUserId: 1, reopenedAt: null, reopenedByUserId: null,
  members: [{ memberId: 1, displayName: 'Saved Alice', paid: 999, burden: 500, net: 499 }, { memberId: 2, displayName: 'Saved Bob', paid: 1, burden: 500, net: -499 }],
  transfers: [{ transferId: 2, fromMemberId: 2, toMemberId: 1, amount: 499, status: 'PENDING', updatedAt: null, updatedByUserId: null }] };
const response = (data: unknown, status = 200) => new Response(JSON.stringify({success: status < 400, data, error: status >= 400 ? {message:'failed', details:[]} : null}), {status});
test('confirmation retries preserve the request ID and preview after uncertain response; reopen is bodyless', async t => {
  let confirmed = false; const posts: RequestInit[] = [];
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    if (String(url).endsWith('/reopen')) { posts.push(options); confirmed = false; return response(open); }
    if (options.method === 'POST') { posts.push(options); if (posts.length === 1) throw new TypeError('offline'); confirmed = true; return response(snapshot, 201); }
    if (String(url).endsWith('/settlement-state')) return response(confirmed ? {...open,status:'CONFIRMED',currentSnapshotId:9} : open);
    if (String(url).endsWith('/confirmation-preview')) return response(preview);
    return response(snapshot);
  });
  const controller = new SettlementController('token', 1);
  await controller.refresh(); await controller.confirm();
  assert.equal(controller.getSnapshot().mode, 'open');
  await controller.confirm();
  assert.equal(posts[0].body, posts[1].body);
  assert.equal(JSON.parse(posts[0].body as string).previewToken, 'preview-a');
  assert.equal(controller.getSnapshot().mode, 'confirmed');
  await controller.reopen(); assert.equal(posts[2].body, undefined);
  assert.equal(controller.getSnapshot().mode, 'open');
});
test('404 fallback requires successful room membership read; all other state failures block edits', async t => {
  let memberStatus = 403; let stateStatus = 404;
  t.mock.method(globalThis,'fetch',async url => response(String(url).endsWith('/members') ? {members:[]} : null, String(url).endsWith('/members') ? memberStatus : stateStatus));
  const c = new SettlementController('token',1);
  await c.refresh(); assert.equal(c.getSnapshot().mode,'error');
  memberStatus = 200; await c.refresh(); assert.equal(c.getSnapshot().mode,'legacy');
  stateStatus = 500; await c.refresh(); assert.equal(c.getSnapshot().mode,'error');
});
test('late state response after disposal never publishes a previous room', async t => {
  let resolve!: (value: Response) => void;
  t.mock.method(globalThis,'fetch',() => new Promise<Response>(r => {resolve = r;}));
  const c = new SettlementController('token',1); const pending = c.refresh(); c.dispose(); resolve(response(open)); await pending;
  assert.equal(c.getSnapshot().mode,'loading');
});
test('confirmed panel renders stored names amounts transfers and owner-only reopen', () => {
  const view = {mode:'confirmed' as const,state:{...open,status:'CONFIRMED' as const,currentSnapshotId:9},preview:null,snapshot,busy:false,error:''};
  const props = {view, onRefresh:()=>{},onConfirm:()=>{},onReopen:()=>{}};
  const html = renderToStaticMarkup(<SettlementPanel {...props} owner={false}/>);
  assert.match(html,/Saved Alice/); assert.match(html,/499원/); assert.match(html,/Saved Bob/);
  assert.doesNotMatch(html,/>재개방</);
  assert.match(renderToStaticMarkup(<SettlementPanel {...props} owner/>),/>재개방</);
});
test('initially locked rooms do not mount a new composer and disable new receipt actions', () => {
  const html = renderToStaticMarkup(<RoomEditingContext.Provider value={{readOnly:true,reportError:()=>{}}}>
    <ReceiptsView token="token" roomId={1} members={[]} receipts={[]} selectedReceiptId={null} setSelectedReceiptId={()=>{}} selectedReceipt={null} assignees={{}} setAssignees={()=>{}} refreshRoom={async()=>{}} refreshReceipt={async()=>{}} setNotice={()=>{}}/>
  </RoomEditingContext.Provider>);
  assert.match(html, /aria-label="새 영수증"[^>]*disabled/);
  assert.doesNotMatch(html, /aria-label="새 영수증 입력"/);
  assert.doesNotMatch(html, /image-upload-panel|manual-panel/);
});

import { ImageReceiptPanel, ReceiptOcrPanel } from '../src/ImageReceiptPanel';
import { ManualItemForm } from '../src/ManualItemForm';
import { OcrDraftEditor } from '../src/OcrDraftEditor';
import { ApiError } from '../src/httpClient';
import { editingBlocked } from '../src/features/settlement/controller';
test('locked context disables upload, manual item and OCR draft writes while leaving photo viewing enabled', () => {
  const locked = (child: React.ReactNode) => renderToStaticMarkup(<RoomEditingContext.Provider value={{readOnly:true,reportError:()=>{}}}>{child}</RoomEditingContext.Provider>);
  assert.match(locked(<ImageReceiptPanel token="t" roomId={1} onUploaded={async()=>{}}/>),/<fieldset[^>]*disabled/);
  assert.match(locked(<ManualItemForm token="t" roomId={1} receiptId={2} formRef={{current:null}} onAdded={async()=>{}}/>),/<fieldset[^>]*disabled/);
  const job = {jobId:1,receiptId:2,status:'REVIEWABLE' as const,provider:'CLOVA',requestedByUserId:1,images:[],draft:{status:'EDITING' as const,revision:0,itemsTotal:0,warnings:[],blockers:[],items:[]}};
  assert.match(locked(<OcrDraftEditor token="t" roomId={1} job={job} members={[]} onSaved={()=>{}} onApplied={async()=>{}}/>),/<fieldset[^>]*disabled/);
  const photo = locked(<ReceiptOcrPanel token="t" roomId={1} receiptId={2} name="Photo" members={[]} onApplied={async()=>{}}/>);
  assert.match(photo,/<button[^>]*>사진 보기<\/button>/);
});
test('structured edit conflict immediately blocks editing and refreshes into the stored snapshot', async t => {
  t.mock.method(globalThis,'fetch',async url => response(String(url).endsWith('/settlement-state') ? {...open,status:'CONFIRMED',currentSnapshotId:9} : snapshot));
  const c = new SettlementController('t',1);
  c.reportError(new ApiError('locked',409,'SETTLEMENT_LOCKED'));
  assert.equal(editingBlocked(c.getSnapshot()),true);
  await new Promise(resolve=>setTimeout(resolve,0));
  assert.equal(c.getSnapshot().mode,'confirmed');
  assert.match(c.getSnapshot().error,/재개방/);
});

test('confirmation locks edits synchronously and prevents overlapping writes or refreshes', async t => {
  let finish!: (value: Response) => void; let posts = 0; let reads = 0; let confirmed = false;
  t.mock.method(globalThis,'fetch',async (url, options) => {
    if (options.method === 'POST') { posts++; return new Promise<Response>(resolve=>{finish=resolve;}); }
    reads++;
    if (String(url).endsWith('/settlement-state')) return response(confirmed ? {...open,status:'CONFIRMED',currentSnapshotId:9} : open);
    return response(confirmed ? snapshot : preview);
  });
  const c = new SettlementController('t',1); await c.refresh();
  const pending = c.confirm(); assert.equal(editingBlocked(c.getSnapshot()),true);
  await c.confirm(); await c.refresh(); assert.equal(posts,1); assert.equal(reads,2);
  confirmed = true; finish(response(snapshot)); await pending;
  assert.equal(c.getSnapshot().mode,'confirmed'); assert.equal(c.getSnapshot().busy,false);
});

test('input-change conflicts discard the stale confirmation payload and require a fresh preview', async t => {
  let changed = false; const payloads: string[] = [];
  t.mock.method(globalThis,'fetch',async (url, options) => {
    if (options.method === 'POST') {
      payloads.push(options.body);
      if (!changed) { changed=true; return new Response(JSON.stringify({success:false,data:null,error:{message:'changed',details:[{reason:'SETTLEMENT_INPUT_CHANGED'}]}}),{status:409}); }
      return response(snapshot);
    }
    return response(String(url).endsWith('/settlement-state') ? open : {...preview,previewToken:changed?'preview-b':'preview-a'});
  });
  const c = new SettlementController('t',1); await c.refresh(); await c.confirm();
  assert.match(c.getSnapshot().error,/최신/);
  await c.confirm();
  assert.equal(JSON.parse(payloads[1]).previewToken,'preview-b');
  assert.notEqual(JSON.parse(payloads[0]).requestId,JSON.parse(payloads[1]).requestId);
});

test('effect cleanup and activation replay allow a fresh load without late reads restoring stale state', async t => {
  let release!: (value: Response) => void; let stateReads = 0;
  t.mock.method(globalThis,'fetch',async url => {
    if (String(url).endsWith('/settlement-state') && ++stateReads === 1) return new Promise<Response>(resolve=>{release=resolve;});
    return response(String(url).endsWith('/settlement-state') ? open : preview);
  });
  const c = new SettlementController('t',1); const previous = c.refresh();
  c.dispose(); c.activate(); await c.refresh();
  assert.equal(c.getSnapshot().mode,'open');
  release(response({...open,status:'CONFIRMED',currentSnapshotId:9})); await previous;
  assert.equal(c.getSnapshot().mode,'open');
});

test('a known confirmed edit conflict cannot be downgraded to editable legacy mode by an unavailable state endpoint', async t => {
  t.mock.method(globalThis,'fetch',async url => response(String(url).endsWith('/members') ? {members:[]} : null, String(url).endsWith('/members') ? 200 : 404));
  const c = new SettlementController('t',1);
  c.reportError(new ApiError('locked',409,'SETTLEMENT_LOCKED'));
  await new Promise(resolve=>setTimeout(resolve,0));
  assert.equal(editingBlocked(c.getSnapshot()),true);
  assert.equal(c.getSnapshot().mode,'error');
});
