async page => {
  const checks = [];
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.getByTestId('open-delivery').click();
  if (await page.evaluate(() => document.activeElement?.id) !== 'delivery-heading') throw new Error('delivery heading focus');
  checks.push('header action scrolls and focuses delivery heading');
  await page.screenshot({ path: 'output/playwright/daily-news/delivery-desktop.png' });
  for (const width of [390, 360]) {
    await page.setViewportSize({ width, height: 844 });
    await page.waitForFunction(() => document.documentElement.scrollWidth <= innerWidth);
    await page.getByTestId('open-delivery').click();
    const sizes = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth,
      panel: document.querySelector('[data-testid="news-delivery"]').getBoundingClientRect().width }));
    if (sizes.scroll > sizes.width) throw new Error(`overflow ${JSON.stringify(sizes)}`);
    if (sizes.panel > width) throw new Error('panel overflow');
    checks.push(`${width}px viewport has no horizontal overflow`);
    if (width === 390) await page.screenshot({ path: 'output/playwright/daily-news/delivery-mobile.png' });
  }
  const context = await page.context().browser().newContext();
  const anonymous = await context.newPage();
  await anonymous.goto('http://127.0.0.1:24141/news');
  await anonymous.waitForURL('**/login?redirect=/news');
  checks.push('anonymous news route redirects to login');
  await context.close();
  await page.evaluate(value => { window.__deliveryLayout = value; }, { passed: checks.length, checks });
}
