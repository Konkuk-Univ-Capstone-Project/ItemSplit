async (page) => {
  const assert = (value, message) => { if (!value) throw new Error(message); };
  await page.setViewportSize({ width: 1440, height: 1080 });
  await page.getByRole('button', { name: '영수증', exact: true }).click();
  await page.getByRole('button', { name: /^실제 CLOVA 로컬 E2E 영수증 / }).click();
  await page.getByRole('region', { name: '영수증 상세', exact: true }).waitFor();
  const auth = await page.evaluate(() => JSON.parse(localStorage.getItem('itemsplit.auth')));
  const headers = { Authorization: `Bearer ${auth.accessToken}` };
  const base = '/api/rooms/1/receipts/1';
  const origin = new URL(page.url()).origin;
  const requestWithRetry = async operation => {
    for (let attempt = 0; attempt < 3; attempt++) {
      const response = await operation();
      if (response.status() !== 429 || attempt === 2) return response;
      const seconds = Number(response.headers()['retry-after']) || 60;
      await page.waitForTimeout(Math.max(1, Math.min(seconds, 60)) * 1000);
    }
  };
  const read = async path => {
    const response = await requestWithRetry(() => page.request.get(origin + path, { headers }));
    assert(response.ok(), `조회 실패: ${path}`);
    return (await response.json()).data;
  };
  const before = await read(base);
  const jobBefore = await read(base + '/ocr/jobs/latest');
  const settlementBefore = await read('/api/rooms/1/settlements');
  const members = (await read('/api/rooms/1/members')).members;
  const testName = `E2E 수동 보완 검증 ${Date.now()}`;
  let addedId = null;
  let additions = 0;
  const forbidden = [];
  const guard = async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() === 'GET') return route.continue();
    if (request.method() === 'POST' && path === base + '/items' &&
        request.postDataJSON()?.name === testName && additions++ === 0) return route.continue();
    if (request.method() === 'PUT' && addedId !== null && path === `${base}/items/${addedId}/assignees`) return route.continue();
    forbidden.push(request.method() + ' ' + path);
    return route.abort();
  };
  await page.route('**/api/**', guard);
  try {
    // New receipt creation must not look like an empty, broken detail view.
    await page.getByRole('button', { name: '새 영수증', exact: true }).click();
    await page.getByRole('button', { name: '수동입력', exact: true }).click();
    assert(await page.getByRole('heading', { name: '수동 입력', exact: true }).isVisible(), '새 영수증 입력 폼 누락');
    assert(await page.getByRole('region', { name: '영수증 상세', exact: true }).count() === 0, '새 작성 화면에 상세가 남음');
    assert(await page.getByRole('heading', { name: '상세', exact: true }).count() === 0, '빈 상세 패널이 남음');
    await page.getByRole('button', { name: /^실제 CLOVA 로컬 E2E 영수증 / }).click();
    const detail = page.getByRole('region', { name: '영수증 상세', exact: true });
    await detail.getByText('Beef Soup (검토 완료)', { exact: true }).waitFor();
    await page.getByRole('button', { name: '누락 품목 추가', exact: true }).click();
    const form = page.getByRole('form', { name: '수동 품목 추가', exact: true });
    assert(await form.getByRole('textbox', { name: '추가 품목명', exact: true }).evaluate(input => input === document.activeElement), '추가 입력으로 이동하지 못함');
    await form.getByRole('textbox', { name: '추가 품목명', exact: true }).fill(testName);
    await form.getByRole('spinbutton', { name: '추가 단가', exact: true }).fill('1000001');
    await form.getByRole('button', { name: '이 영수증에 품목 추가', exact: true }).click();
    assert(additions === 0, '범위를 벗어난 단가로 저장 요청함');
    await form.getByRole('spinbutton', { name: '추가 단가', exact: true }).fill('1500');
    await form.getByRole('spinbutton', { name: '추가 수량', exact: true }).fill('2');
    const created = page.waitForResponse(response => new URL(response.url()).pathname === base + '/items' && response.request().method() === 'POST');
    await form.getByRole('button', { name: '이 영수증에 품목 추가', exact: true }).click();
    const response = await created;
    assert(response.status() === 201, '수동 품목 저장 실패');
    addedId = (await response.json()).data.itemId;
    const row = detail.locator('.item-row').filter({ hasText: testName });
    await row.waitFor();
    const after = await read(base);
    assert(after.items.length === before.items.length + 1, '품목 추가 개수 오류');
    assert(JSON.stringify(after.items.filter(item => item.itemId !== addedId)) === JSON.stringify(before.items), '기존 OCR 품목 변경');
    const added = after.items.find(item => item.itemId === addedId);
    assert(added.name === testName && added.price === 1500 && added.quantity === 2 && added.memberIds.length === 0, '추가한 품목 값 오류');
    // Assign the current members through the actual UI, then verify settlement.
    for (const member of members) {
      const assigned = page.waitForResponse(response => new URL(response.url()).pathname === `${base}/items/${addedId}/assignees` && response.request().method() === 'PUT');
      await row.getByRole('button', { name: member.nickname, exact: true }).click();
      assert((await assigned).ok(), '추가 품목 참여자 저장 실패');
      await page.waitForFunction(() => !document.querySelector('.item-row .member-chip:disabled'));
    }
    await page.reload();
    await page.getByRole('button', { name: /^실제 CLOVA 로컬 E2E 영수증 / }).click();
    await detail.getByText(testName, { exact: true }).waitFor();
    assert((await read(base)).items.find(item => item.itemId === addedId).memberIds.length === members.length, '재접속 후 참여자 누락');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole('button', { name: '품목 보기', exact: true }).click();
    await page.getByRole('button', { name: '누락 품목 추가', exact: true }).click();
    assert(await form.isVisible(), '모바일에서 수동 추가 폼이 보이지 않음');
    await page.getByRole('button', { name: '정산', exact: true }).click();
    const settlementAfter = await read('/api/rooms/1/settlements');
    assert(settlementBefore.ready && settlementAfter.ready, '정산 준비 오류');
    const amount = added.price * added.quantity;
    const money = value => value.toLocaleString('ko-KR') + '원';
    for (const member of settlementAfter.members) {
      const previous = settlementBefore.members.find(row => row.memberId === member.memberId);
      assert(member.paid === previous.paid + (member.memberId === before.payerMemberId ? amount : 0), '추가 품목 결제액 불일치');
      const difference = member.burden - previous.burden;
      assert(difference >= Math.floor(amount / members.length) && difference <= Math.ceil(amount / members.length), '추가 품목 부담액 불일치');
      const row = page.locator('.settlement-row').filter({ has: page.getByText(member.nickname, { exact: true }) });
      await row.getByText(`부담 ${money(member.burden)} · 결제 ${money(member.paid)}`, { exact: true }).waitFor();
    }
    assert(settlementAfter.members.reduce((sum, row) => sum + row.burden, 0) ===
      settlementBefore.members.reduce((sum, row) => sum + row.burden, 0) + amount, '추가 품목 정산 합계 불일치');
    assert(forbidden.length === 0, '허용하지 않은 변경 요청: ' + forbidden.join(', '));
    assert(additions === 1, '품목 중복 생성 요청');
  } finally {
    await page.unroute('**/api/**', guard);
    // Remove only this run's item, including after an interrupted UI assertion.
    const current = await read(base);
    for (const item of current.items.filter(item => item.name === testName && !before.items.some(original => original.itemId === item.itemId))) {
      const removed = await requestWithRetry(() => page.request.delete(`${origin}${base}/items/${item.itemId}`, { headers }));
      assert(removed.status() === 204, '테스트 추가 품목 정리 실패');
    }
    assert(JSON.stringify((await read(base)).items) === JSON.stringify(before.items), '기존 품목/참여자 변경');
    assert(JSON.stringify(await read(base + '/ocr/jobs/latest')) === JSON.stringify(jobBefore), 'OCR 결과 변경');
    assert(JSON.stringify(await read('/api/rooms/1/settlements')) === JSON.stringify(settlementBefore), '정산 결과 변경');
    await page.setViewportSize({ width: 1440, height: 1080 });
    await page.reload();
  }
  return 'PASS: 새 영수증 작성/선택 복귀, 반영 후 수동 품목 추가·DB 저장·참여자 지정·재접속·모바일·정산. 이번 테스트 품목만 정리, 기존 데이터와 OCR 유지.';
}
