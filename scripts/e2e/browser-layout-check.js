async (page) => {
  const assert = (value, message) => { if (!value) throw new Error(message); };
  const writes = [];
  const guard = async route => {
    if (route.request().method() !== 'GET') { writes.push(route.request().method()); return route.abort(); }
    return route.continue();
  };
  await page.route('**/api/**', guard);
  // Use actual wheel input and a coordinate click: locator.click's automatic scrolling can hide layout bugs.
  async function wheelAndClick(button, form) {
    const viewport = page.viewportSize();
    await page.mouse.move(viewport.width - 30, viewport.height / 2);
    for (let attempt = 0; attempt < 30; attempt++) {
      const bounds = await button.boundingBox();
      const header = await page.locator('.topbar').boundingBox();
      if (bounds.y >= header.height + 12 && bounds.y + bounds.height <= viewport.height - 12) break;
      await page.mouse.wheel(0, bounds.y - viewport.height / 2);
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    }
    const bounds = await button.boundingBox();
    const header = await page.locator('.topbar').boundingBox();
    assert(bounds.y >= header.height && bounds.y + bounds.height <= viewport.height, '휠로 저장 버튼에 도달하지 못함');
    assert(await button.evaluate(element => {
      const box = element.getBoundingClientRect();
      return element.contains(document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2));
    }), '저장 버튼을 다른 요소가 가림');
    await form.evaluate(element => {
      delete element.dataset.submitReached;
      element.addEventListener('submit', event => {
        event.preventDefault(); event.stopImmediatePropagation(); element.dataset.submitReached = 'yes';
      }, { capture: true, once: true });
    });
    await page.mouse.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    await page.waitForFunction(() => document.querySelector('form[data-submit-reached="yes"]'));
    assert(await form.getAttribute('data-submit-reached') === 'yes', '저장 버튼 클릭이 폼에 도달하지 않음');
  }
  try {
    await page.getByRole('button', { name: '영수증', exact: true }).click();
    await page.getByRole('button', { name: /^실제 CLOVA 로컬 E2E 영수증 / }).click();
    for (const size of [{ width: 1440, height: 800 }, { width: 1024, height: 700 }, { width: 390, height: 700 }]) {
      await page.setViewportSize(size);
      const list = page.getByRole('region', { name: '영수증 목록', exact: true });
      await list.waitFor();
      const selected = page.getByRole('region', { name: '영수증 상세', exact: true });
      await selected.waitFor();
      const position = async locator => locator.evaluate(element => {
        const box = element.getBoundingClientRect(); return { top: box.top + scrollY, bottom: box.bottom + scrollY };
      });
      const memberBox = await position(page.locator('.room-member-panel'));
      const listBox = await position(list);
      const reviewBox = await position(page.getByRole('region', { name: '영수증 인식 결과', exact: true }));
      assert(memberBox.bottom <= listBox.top && listBox.bottom <= reviewBox.top, '멤버 → 목록 → 편집 순서 오류');
      assert(await page.locator('.receipt-list').evaluate(element => getComputedStyle(element).overflowY === 'visible'), '목록에 별도 스크롤이 남음');
      if (size.width > 900) {
        assert(await page.locator('.receipt-image-scroll').evaluate(element => element.scrollHeight <= element.clientHeight + 2), '일반 사진 보기에 내부 세로 스크롤이 남음');
      } else await page.getByRole('button', { name: '품목 보기', exact: true }).click();
      await page.getByRole('button', { name: '누락 품목 추가', exact: true }).click();
      const itemForm = page.getByRole('form', { name: '수동 품목 추가', exact: true });
      await itemForm.getByRole('textbox', { name: '추가 품목명', exact: true }).fill('스크롤 검증');
      await itemForm.getByRole('spinbutton', { name: '추가 단가', exact: true }).fill('1000');
      await wheelAndClick(itemForm.getByRole('button', { name: '이 영수증에 품목 추가', exact: true }), itemForm);
      await page.getByRole('button', { name: '새 영수증', exact: true }).click();
      const chooser = page.getByRole('group', { name: '새 영수증 입력 방식', exact: true });
      assert(await chooser.getByRole('button', { name: '영수증 사진 첨부', exact: true }).getAttribute('aria-pressed') === 'true', '사진 첨부가 기본 선택이 아님');
      assert(await page.getByRole('button', { name: '사진 업로드', exact: true }).isVisible(), '사진 입력 폼 누락');
      assert(!await page.getByRole('button', { name: '영수증 저장', exact: true }).isVisible(), '입력 방식 두 개가 동시에 보임');
      await chooser.getByRole('button', { name: '수동입력', exact: true }).click();
      const manual = page.locator('.manual-panel');
      await manual.getByRole('textbox', { name: '상호명', exact: true }).fill('입력 방식 전환 검증');
      await chooser.getByRole('button', { name: '영수증 사진 첨부', exact: true }).click();
      await chooser.getByRole('button', { name: '수동입력', exact: true }).click();
      assert(await manual.getByRole('textbox', { name: '상호명', exact: true }).inputValue() === '입력 방식 전환 검증', '방식 전환 중 입력 유실');
      for (let row = 0; row < 5; row++) {
        await manual.getByRole('button', { name: '품목 추가', exact: true }).click();
        await manual.getByPlaceholder('품목명', { exact: true }).last().fill(`스크롤 품목 ${row}`);
        await manual.getByPlaceholder('가격', { exact: true }).last().fill('1000');
      }
      assert((await position(list)).bottom <= (await position(manual)).top, '목록이 입력 폼 아래로 이동함');
      await wheelAndClick(manual.getByRole('button', { name: '영수증 저장', exact: true }), manual);
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), '가로 넘침 발생');
      await manual.getByRole('button', { name: '영수증 저장', exact: true }).screenshot({ path: `.local/e2e/screens/save-button-${size.width}.png` });
      await page.getByRole('button', { name: /^실제 CLOVA 로컬 E2E 영수증 / }).click();
      await selected.waitFor();
    }
    // A delayed room refresh must not undo the user's decision to create a new receipt.
    let release;
    const gate = new Promise(resolve => { release = resolve; });
    const delayed = async route => { await gate; await route.continue(); };
    await page.route('**/api/rooms/1/receipts', delayed);
    try {
      const pending = page.waitForRequest(request => new URL(request.url()).pathname === '/api/rooms/1/receipts');
      await page.getByRole('button', { name: '새로고침', exact: true }).click();
      await pending;
      await page.getByRole('button', { name: '새 영수증', exact: true }).click();
      await page.getByRole('textbox', { name: '사진 영수증 이름', exact: true }).fill('지연 갱신 중 작성 유지');
      release();
      await page.waitForFunction(() => !document.querySelector('.ghost-refresh').disabled);
      assert(await page.getByRole('group', { name: '새 영수증 입력 방식', exact: true }).isVisible(), '지연 갱신이 새 작성 화면을 닫음');
      assert(await page.getByRole('textbox', { name: '사진 영수증 이름', exact: true }).inputValue() === '지연 갱신 중 작성 유지', '지연 갱신이 새 작성 내용을 지움');
    } finally {
      release();
      await page.unroute('**/api/rooms/1/receipts', delayed);
    }
    assert(writes.length === 0, '읽기 검증 중 API 변경 요청 발생');
  } finally {
    await page.unroute('**/api/**', guard);
    await page.setViewportSize({ width: 1440, height: 1080 });
    await page.reload();
  }
  return 'PASS: 멤버·목록·편집 세로 배치, 사진 첨부 기본 선택, 입력 방식 전환·값 유지, 1440/1024/390px에서 실제 휠로 하단 저장 버튼 도달 및 좌표 클릭. DB/OCR 변경 없음.';
}
