const { assert, base, launch, go, ready } = require('./browser-utils.cjs');
(async () => {
  const browser = await launch();
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
    const remoteImages = [],
      errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    page.on('request', (req) => {
      if (req.resourceType() === 'image' && new URL(req.url()).host !== new URL(base).host)
        remoteImages.push(req.url());
    });
    for (const id of ['aria', 'rehan', 'maya', 'ishaan']) {
      await go(page, `/talent/${id}`, 'Portfolio');
      await page.getByRole('button', { name: 'View gallery', exact: true }).click();
      await ready(page);
      assert.equal(await page.getByText('Portrait unavailable', { exact: true }).count(), 0);
      const wrong = await page
        .locator('[aria-label*="portrait"], [aria-label^="Stock portrait study"]')
        .evaluateAll((elements) =>
          elements
            .filter((el) => el.querySelector('img'))
            .some((el) => {
              const a = el.getBoundingClientRect(),
                b = el.parentElement.getBoundingClientRect();
              return Math.abs(a.width - b.width) > 1 || Math.abs(a.height - b.height) > 1;
            }),
        );
      assert.equal(wrong, false, 'Images fit their frames');
    }
    assert.deepEqual(remoteImages, []);
    assert.deepEqual(errors, []);
    const failed = await browser.newPage({ viewport: { width: 390, height: 844 } });
    const held = [];
    await failed.route(/\.jpg(?:\?|$)/, (route) => held.push(route));
    await failed.goto(base + '/search', { waitUntil: 'domcontentloaded' });
    await failed.getByLabel('Loading portrait', { exact: true }).first().waitFor();
    await failed.waitForFunction(() => document.images.length === 4);
    await Promise.all(held.map((route) => route.abort()));
    await failed.getByText('Portrait unavailable', { exact: true }).first().waitFor();
    assert.equal(await failed.getByText('Portrait unavailable', { exact: true }).count(), 4);
    console.log(
      'PASS: all local profile/gallery assets, image bounds, loading and failure states; no remote portrait requests.',
    );
  } finally {
    await browser.close();
  }
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
