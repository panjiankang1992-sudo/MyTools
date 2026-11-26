async page => {
  const checks = [];
  const check = (condition, label) => { if (!condition) throw new Error(label); checks.push(label); };
  await page.getByTestId('news-reader').waitFor();
  check(await page.locator('.stories > li').count() === 20, 'twenty headlines rendered');
  check(await page.locator('.detail').count() === 8, 'only available top-ten details rendered');
  check(await page.locator('.missing-detail').count() === 2, 'missing details kept at original ranks');
  check((await page.locator('.trend').nth(1).innerText()).includes('\u4e03\u65e5\u91c7\u6837\u53ef\u7528'), 'trend readiness distinguished from generation readiness');
  check(await page.locator('.news-reader a[href^="javascript:"]').count() === 0, 'executable URL blocked');
  check(await page.locator('.news-reader img, .news-reader script').count() === 0, 'untrusted markup remains plain text');
  check((await page.locator('.region-snapshot').innerText()).includes('UNRESOLVED') || (await page.locator('.region-snapshot').innerText()).includes('\u5c1a\u672a\u89e3\u6790'), 'historical region preserved');
  check((await page.locator('.region-name').innerText()).includes('Fixture City'), 'current region shown separately');
  await page.locator('.detail summary').first().click();
  check(await page.locator('.detail details[open]').count() === 1, 'evidence can be expanded');
  await page.getByTestId('category-LOCAL').click();
  check(await page.locator('.stories > li').count() === 0, 'empty local category does not borrow national stories');
  await page.getByTestId('category-WORLD').click();
  check(await page.locator('.stories > li').count() === 3, 'category switching shows actual count');
  await page.getByTestId('category-TECH').click();

  await page.locator('#news-time').fill('07:45');
  await page.getByRole('switch', { name: '\u6bcf\u65e5\u751f\u6210' }).click();
  await page.getByTestId('save-subscription').click();
  await page.getByText('\u8ba2\u9605\u5df2\u4fdd\u5b58。', { exact: true }).waitFor();
  let result = await (await page.request.get('http://127.0.0.1:24142/__news_test')).json();
  const saved = result.data.requests.filter(r => r.method === 'PUT').at(-1).body;
  check(saved.expectedRevision === 1 && saved.cutoffTime === '07:45' && saved.enabled === true, 'subscription saved with expected revision');
  check(Object.keys(saved).sort().join(',') === 'cutoffTime,enabled,expectedRevision,zoneId', 'no client owner or destination fields');

  await page.request.post('http://127.0.0.1:24142/__news_test', { data: { conflict: true } });
  await page.locator('#news-time').fill('08:15');
  await page.getByTestId('save-subscription').click();
  await page.getByText('\u91cd\u65b0\u8f7d\u5165\u5df2\u4fdd\u5b58\u8bbe\u7f6e', { exact: true }).waitFor();
  check(await page.locator('#news-time').inputValue() === '08:15', 'conflict preserves draft');
  check(await page.getByTestId('save-subscription').isDisabled(), 'conflict blocks overwrite');
  await page.getByText('\u91cd\u65b0\u8f7d\u5165\u5df2\u4fdd\u5b58\u8bbe\u7f6e', { exact: true }).click();
  await page.waitForFunction(() => document.querySelector('#news-time').value === '07:45');
  check(await page.getByTestId('save-subscription').isEnabled(), 'explicit reload restores editing');
  await page.locator('#news-zone').fill('Invalid/Zone');
  await page.getByTestId('save-subscription').click();
  await page.getByText('\u8bf7\u8f93\u5165\u6709\u6548\u65f6\u533a\u4e0e HH:mm \u683c\u5f0f\u7684\u622a\u6b62\u65f6\u95f4。', { exact: false }).waitFor();
  await page.locator('#news-zone').fill('Asia/Shanghai');

  await page.getByTestId('generate-preview').click();
  await page.getByText('\u9884\u89c8\u7ed3\u679c\u5c1a\u672a\u786e\u8ba4；\u91cd\u8bd5\u4f1a\u6cbf\u7528\u672c\u6b21\u8bf7\u6c42\u4e0e\u622a\u6b62\u65f6\u95f4。', { exact: false }).waitFor();
  await page.getByRole('button', { name: '\u91cd\u8bd5\u672c\u6b21\u9884\u89c8', exact: true }).click();
  await page.waitForFunction(() => !document.querySelector('[data-testid="generate-preview"]').textContent.includes('\u91cd\u8bd5'));
  result = await (await page.request.get('http://127.0.0.1:24142/__news_test')).json();
  const previews = result.data.requests.filter(r => r.path.endsWith('/previews'));
  check(previews.length === 2 && JSON.stringify(previews[0].body) === JSON.stringify(previews[1].body), 'network-loss preview retries same key and cutoff');

  await page.getByRole('button', { name: '\u53d6\u6d88\u751f\u6210', exact: true }).click();
  await page.getByRole('button', { name: '\u786e\u8ba4\u53d6\u6d88', exact: true }).click();
  await page.getByText('2026-10-18 · \u5df2\u53d6\u6d88', { exact: true }).waitFor();
  check(await page.getByRole('button', { name: '\u53d6\u6d88\u751f\u6210', exact: true }).count() === 0, 'cancelled run no longer offers cancellation');

  await page.request.post('http://127.0.0.1:24142/__news_test', { data: { failHistory: true } });
  await page.getByRole('button', { name: '\u5237\u65b0\u5217\u8868', exact: true }).click();
  await page.getByText('\u90e8\u5206\u6570\u636e\u52a0\u8f7d\u5931\u8d25，\u8bf7\u5728\u5bf9\u5e94\u533a\u57df\u91cd\u8bd5。', { exact: false }).waitFor();
  check(await page.locator('.history-list li').count() === 1, 'failed refresh keeps last readable history with error');
  await page.request.post('http://127.0.0.1:24142/__news_test', { data: { failHistory: false } });
  await page.getByRole('button', { name: '\u5237\u65b0\u5217\u8868', exact: true }).click();
  await page.waitForFunction(() => !document.body.innerText.includes('\u90e8\u5206\u6570\u636e\u52a0\u8f7d\u5931\u8d25'));
  await page.evaluate(result => { window.__newsAcceptance = result; }, { passed: checks.length, checks });
}
