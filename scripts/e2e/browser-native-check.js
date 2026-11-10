async (page) => {
  const assert = (value, message) => { if (!value) throw new Error(message); };
  assert(page.viewportSize() === null, '사용자 창에 강제 뷰포트가 남았습니다. viewport:null로 다시 여세요.');
  const cdp = await page.context().newCDPSession(page);
  const window = await cdp.send('Browser.getWindowForTarget');
  const writes = [];
  const guard = async route => {
    if (route.request().method() !== 'GET') { writes.push(route.request().method()); return route.abort(); }
    return route.continue();
  };
  async function reachAndSubmit(form, button) {
    const viewport = await page.evaluate(() => ({ width: innerWidth, height: innerHeight }));
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
    assert(bounds.y >= header.height && bounds.y + bounds.height <= viewport.height, '실제 창에서 저장 버튼이 잘립니다.');
    assert(await button.evaluate(element => {
      const box = element.getBoundingClientRect();
      return element.contains(document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2));
    }), '실제 창에서 저장 버튼이 가려집니다.');
    await form.evaluate(element => {
      element.dataset.nativeSubmit = 'no';
      element.addEventListener('submit', event => {
        event.preventDefault(); event.stopImmediatePropagation(); element.dataset.nativeSubmit = 'yes';
      }, { capture: true, once: true });
    });
    await page.mouse.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    assert(await form.getAttribute('data-native-submit') === 'yes', '실제 창의 저장 버튼 클릭 실패');
  }
  const measurements = [];
  await page.route('**/api/**', guard);
  try {
    await page.getByRole('button', { name: '영수증', exact: true }).click();
    await cdp.send('Browser.setWindowBounds', { windowId: window.windowId, bounds: { windowState: 'normal' } });
    for (const size of [{ width: 1100, height: 800 }, { width: 900, height: 650 }]) {
      // Resize the real OS window, never page.setViewportSize().
      await cdp.send('Browser.setWindowBounds', { windowId: window.windowId, bounds: size });
      await page.waitForFunction(expected => outerHeight === expected.height && outerWidth === expected.width && innerHeight < outerHeight, size);
      const metrics = await page.evaluate(() => ({ innerWidth, innerHeight, outerWidth, outerHeight }));
      assert(metrics.innerWidth <= metrics.outerWidth, '페이지 너비가 실제 창보다 큽니다.');
      measurements.push(metrics);
      await page.getByRole('button', { name: '새 영수증', exact: true }).click();
      await page.getByRole('button', { name: '수동입력', exact: true }).click();
      const manual = page.locator('.manual-panel');
      await manual.getByRole('textbox', { name: '상호명', exact: true }).fill('실제 창 스크롤 검증');
      await reachAndSubmit(manual, manual.getByRole('button', { name: '영수증 저장', exact: true }));
      await page.screenshot({ path: `.local/e2e/screens/native-save-${size.width}.png` });
      await page.getByRole('button', { name: /^실제 CLOVA 로컬 E2E 영수증 / }).click();
      const itemForm = page.getByRole('form', { name: '수동 품목 추가', exact: true });
      await itemForm.getByRole('textbox', { name: '추가 품목명', exact: true }).fill('실제 창 품목 검증');
      await itemForm.getByRole('spinbutton', { name: '추가 단가', exact: true }).fill('1000');
      await reachAndSubmit(itemForm, itemForm.getByRole('button', { name: '이 영수증에 품목 추가', exact: true }));
    }
    assert(writes.length === 0, '검증 중 변경 API 요청 발생');
  } finally {
    await page.unroute('**/api/**', guard);
    const original = window.bounds;
    await cdp.send('Browser.setWindowBounds', { windowId: window.windowId, bounds: original.windowState === 'normal'
      ? { left: original.left, top: original.top, width: original.width, height: original.height }
      : { windowState: original.windowState } });
    await cdp.detach();
    await page.reload();
  }
  assert(page.viewportSize() === null, '검사 후 강제 뷰포트가 남음');
  return 'PASS: 실제 Chrome 창 크기 변경 및 휠 스크롤·저장 버튼 클릭. 강제 뷰포트 없음. DB/OCR 변경 없음. ' + JSON.stringify(measurements);
}
