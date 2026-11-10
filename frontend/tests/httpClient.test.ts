import assert from 'node:assert/strict';
import { test } from 'node:test';
import { request, ApiError } from '../src/httpClient';
import { ApiError as LegacyApiError } from '../src/api';

test('shared requests preserve caller headers and add JSON authentication headers without mutating input', async t => {
  const headers = new Headers({ 'X-Request-ID': 'create-42' });
  let sent: { url: unknown; options: RequestInit };
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    sent = { url, options };
    return new Response(JSON.stringify({ success: true, data: { snapshotId: 42 }, error: null }), { status: 201 });
  });

  const result = await request<{ snapshotId: number }>('/api/example', {
    token: 'session-token', method: 'POST', headers, body: JSON.stringify({ requestId: 'create-42' })
  });

  assert.deepEqual(result, { snapshotId: 42 });
  assert.equal(sent!.url, '/api/example');
  assert.equal(sent!.options.method, 'POST');
  const sentHeaders = sent!.options.headers as Headers;
  assert.equal(sentHeaders.get('Authorization'), 'Bearer session-token');
  assert.equal(sentHeaders.get('Content-Type'), 'application/json');
  assert.equal(sentHeaders.get('X-Request-ID'), 'create-42');
  assert.equal(sent!.options.body, '{"requestId":"create-42"}');
  assert.equal(headers.has('Authorization'), false);
  assert.equal(headers.has('Content-Type'), false);
});

test('shared requests preserve an explicit content type and support unauthenticated calls', async t => {
  t.mock.method(globalThis, 'fetch', async (_url, options) => {
    assert.equal(options.headers.get('Content-Type'), 'application/merge-patch+json');
    assert.equal(options.headers.has('Authorization'), false);
    return new Response(JSON.stringify({ success: true, data: null, error: null }));
  });
  assert.equal(await request('/api/example', {
    method: 'PATCH', headers: { 'Content-Type': 'application/merge-patch+json' }, body: '{}'
  }), null);
});

test('shared requests return undefined for empty 204 responses', async t => {
  t.mock.method(globalThis, 'fetch', async () => new Response(null, { status: 204 }));
  assert.equal(await request<void>('/api/example', { method: 'DELETE', token: 'session-token' }), undefined);
});

test('shared structured conflicts retain their reason and the legacy ApiError identity', async t => {
  t.mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({
    success: false, data: null, error: {
      code: 'CONFLICT', message: '정산이 확정되어 수정할 수 없습니다.',
      details: [{ field: 'settlement', rejectedValue: null, reason: 'SETTLEMENT_LOCKED' }]
    }
  }), { status: 409 }));

  await assert.rejects(request('/api/example'), error => {
    assert.ok(error instanceof ApiError);
    assert.ok(error instanceof LegacyApiError);
    assert.equal(error.status, 409);
    assert.equal(error.reason, 'SETTLEMENT_LOCKED');
    assert.equal(error.message, '정산이 확정되어 수정할 수 없습니다.');
    return true;
  });
});

test('shared requests pass cancellation through without wrapping AbortError', async t => {
  const controller = new AbortController();
  const abort = new DOMException('Request cancelled', 'AbortError');
  controller.abort(abort);
  t.mock.method(globalThis, 'fetch', async (_url, options) => {
    assert.equal(options.signal, controller.signal);
    options.signal.throwIfAborted();
  });
  await assert.rejects(request('/api/example', { signal: controller.signal }), error => error === abort);
});
