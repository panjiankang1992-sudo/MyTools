async page => {
  const checks = [];
  const check = (condition, label) => { if (!condition) throw new Error(label); checks.push(label); };
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.getByTestId('category-TECH').click();
  await page.waitForFunction(() => document.querySelector('[data-testid="evidence-article-1"]').textContent.includes('\u8bc1\u636e\u5df2\u4fdd\u7559'));
  for (let index = 0; index < 3; index++) {
    const detail = page.locator('.detail details').nth(index);
    if (await detail.getAttribute('open') === null) await detail.locator('summary').click();
  }
  check((await page.getByTestId('evidence-article-1').innerText()).includes('\u8bc1\u636e\u5df2\u4fdd\u7559'), 'retained citation is labelled');
  check((await page.getByTestId('evidence-article-2').innerText()).includes('\u5df2\u8fc7\u4fdd\u7559\u671f\u9650'), 'expired citation is labelled');
  check((await page.getByTestId('evidence-article-3').innerText()).includes('\u8bc1\u636e\u4e0d\u53ef\u7528'), 'missing evidence is distinct from expired');
  check(!(await page.getByTestId('evidence-article-3').innerText()).includes('\u622a\u6b62\u65f6\u95f4'), 'missing evidence has no invented deadline');
  check((await page.getByTestId('evidence-overview').innerText()).includes('\u4e0d\u4ee3\u8868\u62a5\u9053\u5df2\u6838\u5b9e'), 'local retention status is explained without claiming verification');
  const before = await page.locator('.detail > p').allTextContents();
  await page.request.post('http://127.0.0.1:24142/__news_test', { data: { failEvidence: true } });
  await page.getByTestId('refresh-evidence').click();
  await page.getByTestId('evidence-error').waitFor();
  check(JSON.stringify(await page.locator('.detail > p').allTextContents()) === JSON.stringify(before), 'status failure preserves frozen excerpts');
  check((await page.getByTestId('evidence-article-1').innerText()).includes('\u72b6\u6001\u672a\u6838\u5bf9'), 'failed refresh clears stale available label');
  await page.request.post('http://127.0.0.1:24142/__news_test', { data: { failEvidence: false } });
  await page.getByTestId('refresh-evidence').click();
  await page.getByTestId('evidence-error').waitFor({ state: 'hidden' });
  await page.waitForFunction(() => document.querySelector('[data-testid="evidence-article-1"]').textContent.includes('\u8bc1\u636e\u5df2\u4fdd\u7559'));
  check(JSON.stringify(await page.locator('.detail > p').allTextContents()) === JSON.stringify(before), 'recovered status keeps same edition content');
  await page.getByTestId('evidence-overview').scrollIntoViewIfNeeded();
  await page.screenshot({ path: 'output/playwright/daily-news/evidence-desktop.png' });
  for (const width of [390, 360]) {
    await page.setViewportSize({ width, height: 844 });
    await page.waitForFunction(() => document.documentElement.scrollWidth <= innerWidth);
    check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `${width}px evidence view has no horizontal overflow`);
    if (width === 390) {
      await page.getByTestId('evidence-article-1').scrollIntoViewIfNeeded();
      await page.screenshot({ path: 'output/playwright/daily-news/evidence-mobile.png' });
    }
  }
  await page.evaluate(value => { window.__evidenceAcceptance = value; }, { passed: checks.length, checks });
}
