const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const base = process.env.TEST_URL || 'http://127.0.0.1:8082';
async function launch() {
  return chromium.launch({
    executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium',
    args: ['--no-sandbox'],
  });
}
async function ready(page) {
  await page.getByText('Loading Castly…', { exact: true }).waitFor({ state: 'hidden' });
  await page.waitForFunction(() =>
    [...document.images].every((img) => img.complete && img.naturalWidth > 0),
  );
  await page.evaluate(() => document.fonts.ready);
}
async function go(page, path, label) {
  await page.goto(base + path);
  if (label)
    await page.getByText(label, { exact: true }).filter({ visible: true }).first().waitFor();
  await ready(page);
}
async function noOverflow(page) {
  assert.equal(
    await page.evaluate(() => document.documentElement.scrollWidth > innerWidth),
    false,
    'No horizontal page overflow',
  );
}
module.exports = { assert, base, launch, ready, go, noOverflow };
