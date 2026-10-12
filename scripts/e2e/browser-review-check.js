async (page) => {
  // Simulate editable/expired responses in this browser only; the preserved DB stays APPLIED.
  const assert = (value, message) => { if (!value) throw new Error(message); };
  let expireNext = false;
  const writes = [];
  const guard = async route => {
    if (route.request().method() !== 'GET') { writes.push(route.request().method()); return route.abort(); }
    return route.continue();
  };
  const draft = async route => {
    if (route.request().method() !== 'GET') return guard(route);
    const response = await route.fetch();
    const body = await response.json();
    body.data.draft.status = 'EDITING';
    await route.fulfill({ response, json: body });
  };
  const images = async route => {
    if (route.request().method() !== 'GET') return guard(route);
    const response = await route.fetch();
    const body = await response.json();
    if (expireNext) { expireNext = false; body.data.forEach(image => { image.expiresAt = '2000-01-01T00:00:00Z'; }); }
    await route.fulfill({ response, json: body });
  };
  await page.route('**/api/**', guard);
  await page.route('**/ocr/jobs/latest', draft);
  await page.route('**/receipts/*/images', images);
  try {
    // Prove specific mocks cannot bypass the general read-only guard. No file is sent.
    const blocked = await page.evaluate(async () => {
      try { await fetch('/api/rooms/1/receipts/1/images', { method: 'POST' }); return false; }
      catch { return true; }
    });
    assert(blocked && writes.length === 1 && writes[0] === 'POST', '사진 변경 요청 차단 실패');
    writes.length = 0;
    await page.setViewportSize({ width: 1440, height: 1080 });
    await page.reload();
    await page.getByRole('button', { name: /^실제 CLOVA 로컬 E2E 영수증 / }).click();
    const name = page.getByRole('textbox', { name: '1번 품목명', exact: true });
    await name.fill('저장하지 않는 화면 검증');
    await page.getByRole('button', { name: '누락 품목 추가', exact: true }).click();
    const manualName = page.getByRole('textbox', { name: '3번 품목명', exact: true });
    await manualName.fill('검토 중 수동 추가');
    await page.getByRole('spinbutton', { name: '3번 단가', exact: true }).fill('1500');
    await page.getByRole('spinbutton', { name: '3번 수량', exact: true }).fill('2');
    const imageResponse = response => new URL(response.url()).pathname.endsWith('/receipts/1/images');
    expireNext = true;
    const expired = page.waitForResponse(imageResponse);
    await page.getByRole('button', { name: '사진 다시 불러오기', exact: true }).click();
    await expired;
    await page.waitForResponse(imageResponse, { timeout: 5000 });
    assert(await name.inputValue() === '저장하지 않는 화면 검증', 'URL 갱신 시 편집값 유실');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole('button', { name: '사진 보기', exact: true }).click();
    await page.getByRole('img', { name: '업로드된 영수증 사진 1', exact: true }).waitFor();
    await page.getByRole('button', { name: '품목 보기', exact: true }).click();
    assert(await name.inputValue() === '저장하지 않는 화면 검증', '모바일 전환 시 편집값 유실');
    assert(await manualName.inputValue() === '검토 중 수동 추가', '수동 추가 품목 유실');
    assert(writes.length === 0, '저장 요청이 발생했습니다.');
  } finally {
    await page.unroute('**/receipts/*/images', images);
    await page.unroute('**/ocr/jobs/latest', draft);
    await page.unroute('**/api/**', guard);
    await page.setViewportSize({ width: 1440, height: 1080 });
    await page.reload();
    await page.getByRole('button', { name: /^실제 CLOVA 로컬 E2E 영수증 / }).click();
  }
  return 'PASS: 만료된 URL 자동 갱신 및 모바일 탭 전환 중 초안 입력값 유지. 브라우저 응답만 모의 처리, DB 변경 없음.';
}
