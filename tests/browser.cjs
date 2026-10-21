const { assert, launch, go, ready, noOverflow } = require('./browser-utils.cjs');
(async () => {
  const browser = await launch();
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    await go(page, '/search', 'Search talent');
    assert.equal(await page.getByRole('button', { name: /View .*profile/ }).count(), 4);
    await page.getByRole('button', { name: 'Actors', exact: true }).click();
    assert.equal(await page.getByRole('button', { name: /View .*profile/ }).count(), 1);
    await page.getByRole('button', { name: "View Aria Kapoor's profile" }).click();
    await page.getByRole('button', { name: 'Request booking', exact: true }).waitFor();
    await page.getByRole('button', { name: 'Save Aria Kapoor', exact: true }).click();
    await page.getByRole('button', { name: 'Unsave Aria Kapoor', exact: true }).waitFor();
    await page.getByRole('button', { name: 'Back', exact: true }).click();
    await page.getByRole('button', { name: 'All', exact: true }).click();
    await page.getByRole('button', { name: 'Filters', exact: true }).click();
    await page.getByRole('button', { name: 'Delhi', exact: true }).click();
    await page.getByRole('button', { name: 'Show 1 results', exact: true }).click();
    await page.getByRole('button', { name: "View Rehan Mehta's profile" }).waitFor();
    assert.equal(await page.getByRole('button', { name: /View .*profile/ }).count(), 1);
    await page.getByRole('button', { name: 'Remove Delhi filter', exact: true }).click();
    await page
      .getByRole('textbox', { name: 'Search talent by name, skill or city' })
      .fill('no matching talent');
    await page
      .getByText('No matches just yet', { exact: true })
      .filter({ visible: true })
      .waitFor();
    await page.getByRole('button', { name: 'Clear search & filters', exact: true }).click();
    assert.equal(await page.getByRole('button', { name: /View .*profile/ }).count(), 4);
    await page.getByRole('tab', { name: 'Home', exact: true }).click();
    await page.getByRole('button', { name: 'Browse Models', exact: true }).click();
    await page.getByRole('button', { name: "View Rehan Mehta's profile" }).waitFor();
    await go(page, '/saved', 'Saved talent');
    await page.getByRole('button', { name: "View Aria Kapoor's profile" }).waitFor();
    await page.reload();
    await page.getByRole('button', { name: "View Aria Kapoor's profile" }).waitFor();
    await page.getByRole('button', { name: 'Unsave Aria Kapoor', exact: true }).click();
    await page.getByText('Your shortlist starts here', { exact: true }).waitFor();
    await noOverflow(page);
    await ready(page);
    assert.deepEqual(errors, []);
    console.log(
      'PASS: preserved category/search/filter/reset, profile navigation, synchronized save state, saved persistence/removal, Home shortcuts, active navigation, no overflow or browser errors.',
    );
  } finally {
    await browser.close();
  }
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
