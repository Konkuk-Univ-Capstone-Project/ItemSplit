async (page) => {
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  const forbiddenWrites = [];
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/**', async route => {
    if (route.request().method() !== 'GET') {
      forbiddenWrites.push(route.request().method() + ' ' + new URL(route.request().url()).pathname);
      return route.abort();
    }
    return route.continue();
  });
  await page.setViewportSize({ width: 1440, height: 1080 });
  await page.getByRole('button', { name: '영수증', exact: true }).click();
  await page.getByRole('button', { name: /^실제 CLOVA 로컬 E2E 영수증 / }).click();
  const original = page.getByRole('img', { name: '업로드된 영수증 사진 1', exact: true });
  await original.waitFor();
  await page.waitForFunction(() => {
    const image = document.querySelector('img[alt="업로드된 영수증 사진 1"]');
    return image?.complete && image.naturalWidth === 1000;
  });
  await page.getByRole('button', { name: '사진 확대', exact: true }).click();
  assert(await page.getByLabel('사진 확대 비율').textContent() === '150%', '사진 확대 실패');
  await page.getByRole('button', { name: '사진 축소', exact: true }).click();
  await page.getByRole('button', { name: 'Beef Soup (검토 완료) · 원본 보기', exact: true }).click();
  assert(await page.getByRole('region', { name: '업로드된 영수증 사진' }).textContent().then(text => text.includes('인식 영역 표시') || text.includes('사진 전체')), '품목 원본 연결 실패');
  await page.getByRole('region', { name: '영수증 인식 결과' }).screenshot({ path: '.local/e2e/screens/receipt-review.png' });
  await page.getByRole('button', { name: '정산', exact: true }).click();
  const auth = await page.evaluate(() => JSON.parse(localStorage.getItem('itemsplit.auth')));
  const response = await page.request.get(new URL('/api/rooms/1/settlements', page.url()).href,
    { headers: { Authorization: `Bearer ${auth.accessToken}` } });
  assert(response.ok(), '정산 조회 실패');
  const settlement = (await response.json()).data;
  assert(settlement.ready, '정산 준비 오류');
  const money = value => value.toLocaleString('ko-KR') + '원';
  for (const member of settlement.members) {
    const row = page.locator('.settlement-row').filter({ has: page.getByText(member.nickname, { exact: true }) });
    await row.getByText(`부담 ${money(member.burden)} · 결제 ${money(member.paid)}`, { exact: true }).waitFor();
  }
  await page.screenshot({ path: '.local/e2e/screens/settlement.png', fullPage: true });
  await page.reload();
  await page.getByRole('button', { name: '영수증', exact: true }).click();
  await page.getByRole('button', { name: /^실제 CLOVA 로컬 E2E 영수증 / }).click();
  await page.getByText('정산 품목에 반영되었습니다.', { exact: false }).waitFor();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: '사진 보기', exact: true }).click();
  await original.waitFor();
  await page.getByRole('region', { name: '영수증 인식 결과' }).screenshot({ path: '.local/e2e/screens/receipt-mobile.png' });
  await page.getByRole('button', { name: '품목 보기', exact: true }).click();
  assert(await page.getByRole('button', { name: 'Beef Soup (검토 완료) · 원본 보기', exact: true }).isVisible(), '모바일 품목 복귀 실패');
  assert(forbiddenWrites.length === 0, '읽기 전용 E2E에서 변경 요청 발생: ' + forbiddenWrites.join(', '));
  assert(errors.length === 0, '브라우저 오류: ' + errors.join(', '));
  await page.unroute('**/api/**');
  await page.setViewportSize({ width: 1440, height: 1080 });
  await page.getByRole('button', { name: '정산', exact: true }).click();
  return 'PASS: 사진 원본·줌·품목 출처·모바일 전환·새로고침·정산 화면. 데이터 변경/사진 업로드/OCR 시작 요청 0회.';
}
