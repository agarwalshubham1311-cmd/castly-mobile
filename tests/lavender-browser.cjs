const { assert, launch, go, ready, noOverflow } = require('./browser-utils.cjs');
const path = require('node:path');
const fs = require('node:fs');
(async () => {
  const browser = await launch();
  try {
    const page = await browser.newPage({
      viewport: { width: 390, height: 844 },
      deviceScaleFactor: 1,
    });
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    const folder = path.resolve(__dirname, '../artifacts/screenshots');
    fs.mkdirSync(folder, { recursive: true });
    async function capture(name, width = 390, height = 844) {
      const controls = {
        discovery: 'Search talent',
        search: 'Filters',
        messages: 'Open conversation with Aria Kapoor',
        bookings: 'View Brand photoshoot booking',
        'saved-talent': "View Aria Kapoor's profile",
        'account-profile': 'Account settings',
        'talent-profile': 'Request booking',
        filters: 'Clear all filters',
        'portfolio-gallery': 'Photos',
        'booking-request': 'Save local request',
      };
      if (controls[name])
        await page.getByRole('button', { name: controls[name], exact: true }).waitFor();
      await ready(page);
      await noOverflow(page);
      const tabs = {
        discovery: 'Home',
        search: 'Search',
        bookings: 'Bookings',
        messages: 'Messages',
        'account-profile': 'Profile',
      };
      if (tabs[name])
        assert.equal(
          await page
            .getByRole('tab', { name: tabs[name], exact: true })
            .getAttribute('aria-selected'),
          'true',
        );
      if (name === 'search') {
        const boxes = await page
          .getByRole('button', { name: /View .*profile/ })
          .evaluateAll((elements) =>
            elements.slice(0, 2).map((el) => el.getBoundingClientRect().toJSON()),
          );
        assert.equal(boxes.length, 2);
        assert.ok(
          Math.abs(boxes[0].y - boxes[1].y) < 2 && boxes[1].x > boxes[0].x,
          'Two-column talent layout',
        );
      }
      await page.waitForTimeout(200);
      await page.screenshot({
        path: path.join(folder, `${name}-${width}x${height}.png`),
        type: 'png',
        fullPage: false,
      });
    }
    await go(page, '/', 'Featured creators');
    await page.getByRole('button', { name: 'Save Aria Kapoor', exact: true }).first().click();
    await page.getByRole('button', { name: 'Save Rehan Mehta', exact: true }).first().click();
    await capture('discovery');
    await page.getByRole('tab', { name: 'Search', exact: true }).click();
    await page.getByRole('button', { name: 'Filters', exact: true }).waitFor();
    await capture('search');
    await page.getByRole('button', { name: 'Filters', exact: true }).click();
    await capture('filters');
    await page.getByRole('button', { name: 'Mumbai', exact: true }).click();
    await page.getByRole('button', { name: 'Actors', exact: true }).click();
    await page.getByRole('button', { name: 'Brand films', exact: true }).click();
    await page.getByRole('button', { name: 'Show 1 results', exact: true }).click();
    await page.getByRole('button', { name: 'Remove Mumbai filter', exact: true }).waitFor();
    assert.equal(await page.getByRole('button', { name: /View .*profile/ }).count(), 1);
    await page.getByRole('button', { name: "View Aria Kapoor's profile" }).click();
    await page.getByRole('button', { name: 'Request booking', exact: true }).waitFor();
    await capture('talent-profile');
    await page.getByRole('button', { name: 'Reviews', exact: true }).click();
    await page.getByText('No reviews to display', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Portfolio', exact: true }).click();
    await page.getByRole('button', { name: 'View gallery', exact: true }).click();
    await capture('portfolio-gallery');
    await page
      .getByRole('button', { name: 'Open Aria Kapoor sample portrait', exact: true })
      .click();
    await page.getByRole('button', { name: 'Next photograph', exact: true }).click();
    await page.getByText('Sample photograph 2 / 3', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Close media viewer', exact: true }).click();
    await page.getByRole('button', { name: 'Videos', exact: true }).click();
    await page.getByText('No videos yet', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Back', exact: true }).click();
    await page.getByRole('button', { name: 'Request booking', exact: true }).click();
    await capture('booking-request');
    await page.getByRole('button', { name: 'Save local request', exact: true }).click();
    assert.equal(await page.getByRole('alert').count(), 5);
    await page.getByRole('button', { name: 'Choose project type', exact: true }).click();
    await page.getByRole('button', { name: 'Brand photoshoot', exact: true }).click();
    await page.getByRole('textbox', { name: 'Proposed date', exact: true }).fill('2027-12-20');
    await page.getByRole('textbox', { name: 'Location', exact: true }).fill('Mumbai studio');
    await page
      .getByRole('textbox', { name: 'Project requirements', exact: true })
      .fill('A local editorial portrait campaign with three finished images.');
    await page.getByRole('textbox', { name: 'Indicative budget (INR)', exact: true }).fill('18000');
    await page.getByRole('button', { name: 'Save local request', exact: true }).click();
    await page.getByText('Your local draft is ready', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'View local request', exact: true }).click();
    await page.getByText('Local request draft', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Open local conversation', exact: true }).click();
    await page
      .getByRole('textbox', { name: 'Local message', exact: true })
      .fill('Hello, this is a local draft message.');
    await page.getByRole('button', { name: 'Send locally', exact: true }).click();
    await page.getByText('Hello, this is a local draft message.', { exact: true }).waitFor();
    await page.getByText('Local only · not sent', { exact: true }).waitFor();
    await go(page, '/messages', 'Messages');
    await capture('messages');
    await page.getByRole('button', { name: 'Archived', exact: true }).click();
    await page
      .getByRole('button', { name: 'Open conversation with Ishaan Roy', exact: true })
      .waitFor();
    await go(page, '/bookings', 'Bookings');
    await capture('bookings');
    await page.getByRole('button', { name: 'Completed', exact: true }).click();
    await page
      .getByRole('button', { name: 'View Live performance booking', exact: true })
      .waitFor();
    await go(page, '/saved', 'Saved talent');
    await capture('saved-talent');
    assert.equal(await page.getByRole('button', { name: /View .*profile/ }).count(), 2);
    await go(page, '/account', 'Creative Studio');
    await capture('account-profile');
    await page.getByRole('button', { name: 'Subscription', exact: true }).click();
    await page.getByText('A space for creative connections', { exact: true }).waitFor();
    await go(page, '/notifications', 'Notifications');
    await page.getByRole('switch', { name: 'Demo notifications', exact: true }).click();
    await page.getByText('Demo notifications paused', { exact: true }).waitFor();
    for (const [width, height] of [
      [360, 800],
      [430, 932],
    ]) {
      await page.setViewportSize({ width, height });
      await go(page, '/', 'Featured creators');
      await capture('discovery', width, height);
      await go(page, '/search', 'Search talent');
      await capture('search', width, height);
      await go(page, '/talent/aria', 'Portfolio');
      await capture('talent-profile', width, height);
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await go(page, '/search', 'Search talent');
    await page.getByRole('button', { name: 'Sort: Recommended', exact: true }).click();
    await page.getByRole('button', { name: 'Price: low to high', exact: true }).click();
    assert.match(
      (await page.getByRole('button', { name: /View .*profile/ }).allTextContents())[0],
      /Maya Sen/,
    );
    const targets = await page.getByRole('button').evaluateAll((elements) =>
      elements
        .filter((el) => {
          const r = el.getBoundingClientRect();
          return r.width > 0 && r.height > 0 && (r.height < 43 || r.width < 43);
        })
        .map((el) => el.getAttribute('aria-label') || el.textContent),
    );
    assert.deepEqual(targets, [], 'Interactive controls meet minimum touch target size');
    assert.deepEqual(errors, []);
    console.log(
      'PASS: ten screens, gallery viewer/empty media, filters/skills/sorting, five-tab routes, local booking validation/confirmation, local chat, saved persistence, account/notifications, responsive bounds. Captured 16 PNGs.',
    );
  } finally {
    await browser.close();
  }
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
