async page => {
  const output = '/Users/pankang/mycode/MyTools/output/pdf/worksheet-once-print-2026-10-05/';
  const results = [];
  let requests = 0;
  const observe = request => { if (request.url().split('?')[0].endsWith('/translations')) requests++; };
  page.on('request', observe);
  await page.getByRole('button', { name: '自己输入 输入或粘贴字词' }).click();
  await page.locator('.source-step').getByRole('textbox', { name: /第 1 行汉字/ }).fill('春天\n蓝鲸\n红狐');
  await page.waitForFunction(() => document.querySelector('.source-step .right-cell textarea')?.value === 'chūn tiān');
  await page.locator('.source-step').getByRole('textbox', { name: /第 1 行汉字/ }).fill('秋天');
  await page.locator('.source-step').getByRole('heading', { name: '输入内容', exact: true }).click();
  await page.waitForTimeout(700);
  const retained = await page.locator('.source-step').getByRole('textbox', { name: /第 1 行拼音/ }).inputValue();
  if (retained !== 'chūn tiān') throw new Error('Source edit unexpectedly changed pinyin');
  await page.locator('.source-step').getByRole('textbox', { name: /第 1 行拼音/ }).fill('');
  await page.getByRole('button', { name: '下一步', exact: true }).click();
  await page.waitForTimeout(700);
  const cleared = await page.locator('.confirm-step').getByRole('textbox', { name: /第 1 行拼音/ }).inputValue();
  if (cleared !== '') throw new Error('Cleared pinyin was automatically restored');
  await page.getByRole('button', { name: '补全拼音', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('.confirm-step .right-cell textarea')?.value === 'qiū tiān');
  results.push({ name: 'chinese-once', retained, cleared, explicitCompletion: 'qiū tiān' });

  const produce = async (label, filename) => {
    await page.locator('.mode-option').filter({ hasText: label }).click();
    await page.getByRole('button', { name: '生成预览', exact: true }).click();
    await page.locator('.preview-pane').waitFor({ state: 'visible' });
    await page.waitForFunction(() => document.querySelector('.paper-viewport')?.getAttribute('aria-busy') === 'false' && document.querySelector('.paper-sheet canvas')?.width > 0);
    const downloaded = page.waitForEvent('download');
    await page.getByRole('button', { name: '下载 PDF', exact: true }).click();
    await (await downloaded).saveAs(output + filename + '.pdf');
    const opened = page.waitForEvent('popup');
    await page.locator('.preview-actions').getByRole('button', { name: '打印', exact: true }).click();
    const popup = await opened;
    await popup.waitForSelector('body.print-ready', { timeout: 30000 });
    const state = await popup.evaluate(() => ({
      ready: document.body.classList.contains('print-ready'),
      images: [...document.querySelectorAll('img.page')].map(image => ({ width: image.naturalWidth, height: image.naturalHeight, complete: image.complete })),
      embeds: document.querySelectorAll('embed').length,
      printDisabled: document.querySelector('button.primary').disabled
    }));
    if (!state.ready || state.printDisabled || state.embeds || !state.images.every(image => image.complete && image.width && image.height)) throw new Error('Print pages were not ready');
    const exported = popup.waitForEvent('download');
    await popup.getByRole('button', { name: '导出 PDF', exact: true }).click();
    await (await exported).saveAs(output + filename + '-from-print.pdf');
    await popup.pdf({ path: output + filename + '-print-layout.pdf', preferCSSPageSize: true, printBackground: true, displayHeaderFooter: false });
    await popup.screenshot({ path: '/Users/pankang/mycode/MyTools/output/playwright/once-print-' + filename + '.png', fullPage: true });
    results.push({ name: filename, ...state });
    await popup.close();
    await page.getByRole('button', { name: '上一步', exact: true }).click();
  };
  await produce('拼音字帖', 'chinese-pinyin');
  await produce('汉字字帖', 'chinese-dictation');
  await page.goto('https://toolbox.yuyutian.top/#/words');
  await page.getByRole('button', { name: '自己输入 输入或粘贴字词' }).click();
  await page.locator('.source-step').getByRole('textbox', { name: /第 1 行中文/ }).fill('星期一');
  await page.waitForFunction(() => document.querySelector('.source-step .right-cell textarea')?.value === 'Monday');
  const initialRequests = requests;
  await page.locator('.source-step').getByRole('textbox', { name: /第 1 行英语/ }).fill('Tuesday');
  await page.locator('.source-step').getByRole('textbox', { name: /第 1 行中文/ }).fill('');
  await page.getByRole('button', { name: '下一步', exact: true }).click();
  await page.waitForTimeout(800);
  const emptyMeaning = await page.locator('.confirm-step').getByRole('textbox', { name: /第 1 行中文/ }).inputValue();
  if (emptyMeaning !== '' || requests !== initialRequests) throw new Error('Manual edit triggered a new automatic translation');
  await page.getByRole('button', { name: '上一步', exact: true }).click();
  await page.getByRole('button', { name: '下一步', exact: true }).click();
  await page.waitForTimeout(700);
  if (requests !== initialRequests) throw new Error('Round trip translated again');
  await page.locator('.confirm-step').getByRole('textbox', { name: /第 1 行中文/ }).fill('星期二');
  await page.locator('.confirm-step').getByRole('heading', { name: '确认内容', exact: true }).click();
  results.push({ name: 'english-once', initialRequests, afterEdits: requests, emptyMeaning });
  await produce('英语默写', 'english-dictation');
  await produce('中文释义练习', 'english-meaning');
  if (requests !== initialRequests) throw new Error('Preview or print translated again');
  page.off('request', observe);
  return { asset: await page.locator('head script[type=module]').getAttribute('src'), requests, results };
}
