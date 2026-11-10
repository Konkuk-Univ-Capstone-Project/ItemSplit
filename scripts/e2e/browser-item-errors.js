async (page) => {
  // Browser-only responses exercise delayed/error cases without changing the preserved DB.
  const assert = (value, message) => { if (!value) throw new Error(message); };
  await page.getByRole('button', { name: '영수증', exact: true }).click();
  await page.getByRole('button', { name: /^실제 CLOVA 로컬 E2E 영수증 / }).click();
  await page.getByRole('region', { name: '영수증 상세', exact: true }).waitFor();
  const auth = await page.evaluate(() => JSON.parse(localStorage.getItem('itemsplit.auth')));
  const origin = new URL(page.url()).origin;
  const base = '/api/rooms/1/receipts';
  const response = await page.request.get(origin + base + '/1', { headers: { Authorization: `Bearer ${auth.accessToken}` } });
  const first = (await response.json()).data;
  const second = { ...first, receiptId: 900000001, name: '화면 전환 검증 영수증', sourceType: 'MANUAL' };
  let mode = 'delay';
  let failRead = false;
  let release;
  const forbidden = [];
  const gate = new Promise(resolve => { release = resolve; });
  const guard = async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const ok = data => route.fulfill({ status: 200, contentType: 'application/json', json: { success: true, data, error: null } });
    if (request.method() === 'POST' && path === base + '/1/items') {
      if (mode === 'delay') await gate;
      else failRead = true;
      return route.fulfill({ status: 201, json: { success: true, data: { itemId: 900000002, receiptId: 1, ...request.postDataJSON(), excludedFromSettlement: false }, error: null } });
    }
    if (request.method() !== 'GET') { forbidden.push(path); return route.abort(); }
    if (path === base) return ok([first, second]);
    if (path === base + '/900000001') return ok(second);
    if (path === base + '/1') {
      if (failRead) return route.fulfill({ status: 503, json: { success: false, data: null, error: { code: 'UNAVAILABLE', message: '목록 갱신 검증 오류', details: [] } } });
      return ok(first);
    }
    return route.continue();
  };
  const failures = [];
  await page.route('**/api/**', guard);
  try {
    await page.getByRole('button', { name: '새로고침', exact: true }).click();
    await page.getByRole('button', { name: /^화면 전환 검증 영수증 / }).waitFor();
    const form = page.getByRole('form', { name: '수동 품목 추가', exact: true });
    const fill = async () => {
      await form.getByRole('textbox', { name: '추가 품목명', exact: true }).fill('저장 응답 검증');
      await form.getByRole('spinbutton', { name: '추가 단가', exact: true }).fill('1000');
    };
    await fill();
    const pending = page.waitForRequest(request => request.method() === 'POST' && new URL(request.url()).pathname === base + '/1/items');
    await form.getByRole('button', { name: '이 영수증에 품목 추가', exact: true }).click();
    await pending;
    await page.getByRole('button', { name: /^화면 전환 검증 영수증 / }).click();
    const detail = page.getByRole('region', { name: '영수증 상세', exact: true });
    await detail.getByRole('heading', { name: second.name, exact: true }).waitFor();
    const refreshed = page.waitForResponse(response => new URL(response.url()).pathname === '/api/rooms/1/settlements');
    release();
    await refreshed;
    try { await detail.getByRole('heading', { name: second.name, exact: true }).waitFor({ timeout: 3000 }); }
    catch { failures.push('늦게 완료된 저장이 현재 선택한 영수증을 덮어씀'); }
    mode = 'error';
    await page.getByRole('button', { name: /^실제 CLOVA 로컬 E2E 영수증 / }).click();
    await fill();
    await form.getByRole('button', { name: '이 영수증에 품목 추가', exact: true }).click();
    try { await form.getByRole('alert').filter({ hasText: '품목은 저장되었지만' }).waitFor({ timeout: 3000 }); }
    catch { failures.push('저장 후 조회 실패를 성공으로 표시함'); }
    assert(await form.getByRole('textbox', { name: '추가 품목명', exact: true }).inputValue() === '', '이미 저장한 값을 남겨 중복 추가를 유도함');
    assert(failures.length === 0, failures.join('; '));
    assert(forbidden.length === 0, '검증 외 변경 요청: ' + forbidden.join(', '));
  } finally {
    release();
    await page.unroute('**/api/**', guard);
    await page.reload();
  }
  return 'PASS: 저장 중 다른 영수증 선택 유지, 저장 성공 후 조회 실패 안내. 브라우저 응답만 모의 처리, DB 변경 없음.';
}
