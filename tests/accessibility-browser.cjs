const fs = require('node:fs');
const path = require('node:path');
const { assert, launch, go, noOverflow, ready } = require('./browser-utils.cjs');
(async () => {
  const browser = await launch();
  const results = [];
  const folder = path.resolve(__dirname, '../artifacts/ui-accessibility-fixes');
  fs.mkdirSync(folder, { recursive: true });
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
    for (const [width, height] of [
      [360, 800],
      [390, 844],
      [430, 932],
    ]) {
      await page.setViewportSize({ width, height });
      for (const [route, label] of [
        ['/', 'Featured creators'],
        ['/search', 'Search talent'],
        ['/filters', 'Filters'],
        ['/talent/aria', 'Portfolio'],
        ['/gallery/aria', 'Photos'],
        ['/booking/aria', 'Request booking'],
        ['/messages', 'Messages'],
        ['/bookings', 'Bookings'],
        ['/account', 'Creative Studio'],
        ['/saved', 'Saved talent'],
      ]) {
        await go(page, route, label);
        await noOverflow(page);
        const undersized = await page
          .locator('[role=button], [role=tab], [role=slider], input:not([type=hidden]), textarea')
          .evaluateAll((elements) =>
            elements
              .map((el) => ({
                name: el.getAttribute('aria-label') || el.textContent,
                box: el.getBoundingClientRect(),
              }))
              .filter(
                ({ box }) =>
                  box.width > 0 && box.height > 0 && (box.width < 47.9 || box.height < 47.9),
              )
              .map(({ name, box }) => ({ name, width: box.width, height: box.height })),
          );
        assert.deepEqual(undersized, [], `${route} at ${width}: 48px controls`);
      }
      await go(page, '/account', 'Creative Studio');
      const caption = await page
        .getByText('Bookings', { exact: true })
        .first()
        .evaluate((el) => ({
          color: getComputedStyle(el).color,
          size: getComputedStyle(el).fontSize,
        }));
      assert.equal(caption.color, 'rgb(98, 90, 114)');
      await go(page, '/talent/aria', 'Portfolio');
      const tab = page.getByRole('button', { name: 'Portfolio', exact: true });
      const booking = page.getByRole('button', { name: 'Request booking', exact: true });
      const initial = await tab.boundingBox();
      const footer = await booking.boundingBox();
      assert.ok(
        initial.y + initial.height <= footer.y,
        `Profile tabs initially visible at ${width}`,
      );
      await page
        .getByRole('button', { name: 'View gallery', exact: true })
        .scrollIntoViewIfNeeded();
      await tab.evaluate((el) => {
        for (let parent = el.parentElement; parent; parent = parent.parentElement) {
          if (['auto', 'scroll'].includes(getComputedStyle(parent).overflowY)) {
            parent.scrollTop = parent.scrollHeight;
            break;
          }
        }
      });
      await page.waitForTimeout(150);
      const sticky = await tab.boundingBox();
      assert.ok(
        sticky.y >= 50 && sticky.y < initial.y && sticky.y + sticky.height < footer.y,
        'Tabs stay inside visible scroll region after actual content scroll',
      );
      await page.getByRole('button', { name: 'About', exact: true }).click();
      await page.getByText('About Aria', { exact: true }).scrollIntoViewIfNeeded();
      // Browser 150% text simulation. This is not a native Dynamic Type or TalkBack test.
      await page.evaluate(() =>
        document.querySelectorAll('[dir="auto"]').forEach((el) => {
          const style = getComputedStyle(el);
          el.style.fontSize = `${parseFloat(style.fontSize) * 1.5}px`;
          if (style.lineHeight !== 'normal')
            el.style.lineHeight = `${parseFloat(style.lineHeight) * 1.5}px`;
        }),
      );
      await noOverflow(page);
      const enlargedFooter = await booking.boundingBox();
      assert.ok(
        enlargedFooter.y >= 0 && enlargedFooter.y + enlargedFooter.height <= height,
        'Enlarged booking action fits viewport',
      );
      await page
        .getByText('Names, availability, services and rates are fictional fixtures.', {
          exact: true,
        })
        .scrollIntoViewIfNeeded();
      const note = await page
        .getByText('Names, availability, services and rates are fictional fixtures.', {
          exact: true,
        })
        .boundingBox();
      assert.ok(
        note.y + note.height <= enlargedFooter.y,
        'Final profile text is reachable above footer',
      );
      await page.screenshot({
        path: path.join(folder, `profile-enlarged-text-${width}x${height}.png`),
      });
      results.push({
        width,
        height,
        initialTabs: initial,
        footer,
        scrolledTabs: sticky,
        enlargedFooter,
        finalText: note,
      });
    }
    await go(page, '/booking/aria', 'Request booking');
    const date = page.getByLabel('Proposed date', { exact: true });
    assert.equal(await date.getAttribute('type'), 'date');
    assert.match(await date.getAttribute('min'), /^\d{4}-\d{2}-\d{2}$/);
    await date.focus();
    assert.equal(await date.evaluate((el) => document.activeElement === el), true);
    await date.fill('2027-12-20');
    assert.equal(await date.inputValue(), '2027-12-20');
    await date.fill('2020-01-01');
    await page.getByRole('button', { name: 'Save local request', exact: true }).click();
    assert.equal(await date.getAttribute('aria-invalid'), 'true');
    await go(page, '/filters', 'Filters');
    const available = page.getByRole('switch', { name: 'Available talent only', exact: true });
    await available.focus();
    await available.press('Space');
    assert.equal(await available.isChecked(), true, 'Switch is keyboard operable');
    await available.click();
    assert.equal(await available.isChecked(), false, 'Full switch target toggles');
    await go(page, '/notifications', 'Notifications');
    const notifications = page.getByRole('switch', { name: 'Demo notifications', exact: true });
    assert.equal((await notifications.boundingBox()).height, 48);
    await notifications.focus();
    await notifications.press('Space');
    await page.getByText('Demo notifications paused', { exact: true }).waitFor();
    await go(page, '/messages', 'Messages');
    await page.getByText('Sample · 10:24', { exact: true }).waitFor();
    const thread = page.getByRole('button', {
      name: 'Open conversation with Aria Kapoor',
      exact: true,
    });
    assert.match(
      await page
        .getByLabel('1 unread sample messages', { exact: true })
        .first()
        .getAttribute('aria-label'),
      /1 unread/,
    );
    assert.match(await thread.innerText(), /1/);
    await thread.click();
    await page
      .getByRole('textbox', { name: 'Local message', exact: true })
      .fill('Accessibility check: local draft only.');
    await page.getByRole('button', { name: 'Send locally', exact: true }).click();
    await page.getByRole('button', { name: 'Back', exact: true }).click();
    await page.getByText(/Local · \d\d:\d\d/).waitFor();
    assert.equal(
      await page
        .getByRole('button', { name: 'Open conversation with Aria Kapoor', exact: true })
        .getByText('1', { exact: true })
        .count(),
      0,
    );
    await ready(page);
    fs.writeFileSync(
      path.join(folder, 'accessibility-measurements.json'),
      JSON.stringify(
        { browser: 'Chromium; CSS pixels; 150% text simulation only', results },
        null,
        2,
      ) + '\n',
    );
    console.log(
      'PASS: 48px controls, three-width profile/sticky tabs/footer, 150% text simulation, date input keyboard/validation, unread/local timestamps.',
    );
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
