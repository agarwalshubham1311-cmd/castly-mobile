const { chromium } = require('playwright');
const assert = require('node:assert/strict');
(async () => {
 const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox'] });
 try {
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
  const errors = [], remoteImages = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('request', req => { if (req.resourceType() === 'image' && new URL(req.url()).hostname !== '127.0.0.1' && new URL(req.url()).hostname !== 'localhost') remoteImages.push(req.url()); });
  const url = process.env.TEST_URL || 'http://127.0.0.1:8082';
  const settleImages = async () => {
   await page.waitForFunction(() => [...document.images].length > 0 && [...document.images].every(img => img.complete && img.naturalWidth > 0));
   await page.getByLabel('Loading portrait', { exact: true }).first().waitFor({ state: 'hidden' });
   const overflowing = await page.locator('[aria-label^="Sample portrait for"], [aria-label^="Stock portrait study"]').evaluateAll(elements => elements.some(el => { const image = el.getBoundingClientRect(), frame = el.parentElement.getBoundingClientRect(); return Math.abs(image.width - frame.width) > 1 || Math.abs(image.height - frame.height) > 1; }));
   assert.equal(overflowing, false, 'Portraits must fit their frame, not use intrinsic dimensions');
  };
  await page.goto(url); await settleImages();
  await page.screenshot({ path: '/tmp/castly-portraits-home-390x844.png' });
  await page.getByText('Discover talent', { exact: true }).evaluate(el => el.scrollIntoView({ block: 'start' }));
  await page.screenshot({ path: '/tmp/castly-portraits-discovery-390x844.png' });
  await page.getByRole('textbox').fill('Aria');
  await settleImages();
  const aria = page.getByRole('button', { name: "View Aria Kapoor's profile" });
  assert.equal(await page.getByRole('button', { name: /View .*profile/ }).count(), 1);
  await page.getByRole('textbox').evaluate(el => el.parentElement.scrollIntoView({ block: 'start' }));
  await page.screenshot({ path: '/tmp/castly-portraits-search-390x844.png' });
  await aria.click(); await settleImages();
  await page.screenshot({ path: '/tmp/castly-portraits-profile-390x844.png' });
  await page.getByText('Sample portfolio', { exact: true }).evaluate(el => el.scrollIntoView({ block: 'start' }));
  await page.screenshot({ path: '/tmp/castly-portraits-gallery-390x844.png' });
  assert.equal(await page.getByText('Portrait unavailable', { exact: true }).count(), 0);
  for (const name of ['Rehan Mehta', 'Maya Sen', 'Ishaan Roy']) {
   await page.getByRole('button', { name: '← Back to discovery' }).click();
   await page.getByRole('textbox').fill(name);
   await page.getByRole('button', { name: `View ${name}'s profile` }).click();
   await settleImages();
   await page.getByText('Sample portfolio', { exact: true }).waitFor();
   assert.equal(await page.getByText('Portrait unavailable', { exact: true }).count(), 0);
  }
  for (const width of [360, 768, 1024]) { await page.setViewportSize({ width, height: 844 }); await settleImages(); assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `Overflow at ${width}px`); }
  assert.deepEqual(remoteImages, []);
  assert.deepEqual(errors, []);
  // Hold image responses to verify the loading state, then fail them to verify fallback.
  const failurePage = await browser.newPage({ viewport: { width: 390, height: 844 } });
  const held = [];
  await failurePage.route(/\.jpg(?:\?|$)/, route => { held.push(route); });
  await failurePage.goto(url, { waitUntil: 'domcontentloaded' });
  await failurePage.getByLabel('Loading portrait', { exact: true }).first().waitFor();
  await failurePage.waitForFunction(() => document.images.length === 4);
  await Promise.all(held.map(route => route.abort()));
  await failurePage.getByText('Portrait unavailable', { exact: true }).first().waitFor();
  await failurePage.waitForFunction(() => [...document.querySelectorAll('[aria-label="Loading portrait"]')].length === 0);
  assert.equal(await failurePage.getByText('Portrait unavailable', { exact: true }).count(), 4);
  console.log('PASS: all 11 local portraits across Discovery, search, four profiles and galleries; loading and error fallback; no remote image requests or browser errors. Fresh 390x844 screenshots saved in /tmp.');
 } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exit(1); });
