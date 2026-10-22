const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { assert, launch } = require('./browser-utils.cjs');
(async () => {
  const browser = await launch();
  try {
    const page = await browser.newPage();
    const targets = [
      'discovery',
      'search',
      'filters',
      'talent-profile',
      'portfolio-gallery',
      'booking-request',
      'messages',
      'bookings',
      'saved-talent',
      'account-profile',
    ].map((name) => [name, 390, 844]);
    for (const [width, height] of [
      [360, 800],
      [430, 932],
    ])
      for (const name of [
        'discovery',
        'search',
        'filters',
        'talent-profile',
        'portfolio-gallery',
        'booking-request',
        'messages',
        'bookings',
        'saved-talent',
        'account-profile',
      ])
        targets.push([name, width, height]);
    const results = [];
    for (const [name, width, height] of targets) {
      const relative = `${name}-${width}x${height}.png`;
      const file = path.resolve(__dirname, '../artifacts/screenshots', relative);
      const bytes = fs.readFileSync(file);
      assert.equal(bytes.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
      const decoded = await page.evaluate(async (data) => {
        const image = new Image();
        image.src = 'data:image/png;base64,' + data;
        await image.decode();
        const canvas = document.createElement('canvas');
        canvas.width = image.naturalWidth;
        canvas.height = image.naturalHeight;
        const context = canvas.getContext('2d');
        context.drawImage(image, 0, 0);
        const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
        const colors = new Set();
        for (let i = 0; i < Math.floor(pixels.length * 0.85); i += 116)
          colors.add(`${pixels[i]}:${pixels[i + 1]}:${pixels[i + 2]}`);
        return { width: image.naturalWidth, height: image.naturalHeight, colors: colors.size };
      }, bytes.toString('base64'));
      assert.equal(decoded.width, width);
      assert.equal(decoded.height, height);
      assert.ok(decoded.colors > 12, 'Screen must contain rendered UI, not a blank transition');
      results.push({
        file: relative,
        width,
        height,
        bytes: bytes.length,
        sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
        decoded: true,
        sampledColors: decoded.colors,
      });
    }
    fs.writeFileSync(
      path.resolve(__dirname, '../artifacts/screenshots/manifest.json'),
      JSON.stringify(
        {
          verifiedAt: new Date().toISOString(),
          engine: 'Playwright Chromium PNG decode + canvas pixel inspection',
          screenshots: results,
        },
        null,
        2,
      ) + '\n',
    );
    console.log(
      `PASS: ${results.length} PNGs fully decoded at the expected dimensions, with nonblank UI; manifest written.`,
    );
  } finally {
    await browser.close();
  }
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
