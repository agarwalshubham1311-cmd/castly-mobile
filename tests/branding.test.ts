import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
const require = createRequire(import.meta.url);
const { getPngInfo } = require('@expo/image-utils');
test('launcher PNGs decode at platform sizes with opaque app icons and padded adaptive silhouettes', async () => {
  const config = JSON.parse(readFileSync(new URL('../app.json', import.meta.url), 'utf8')).expo;
  assert.equal(config.android.adaptiveIcon.backgroundColor, '#EDE5FF');
  assert.equal(config.android.adaptiveIcon.backgroundImage, undefined);
  for (const file of ['icon.png', 'android-icon-background.png', 'favicon.png']) {
    const image = await getPngInfo(new URL(`../assets/${file}`, import.meta.url).pathname);
    assert.equal(image.width, file === 'favicon.png' ? 64 : 1024);
    assert.equal(image.height, image.width);
    for (let pixel = 3; pixel < image.data.length; pixel += 4) assert.equal(image.data[pixel], 255);
  }
  let foregroundAlpha: number[] = [];
  for (const file of [
    'android-icon-foreground.png',
    'android-icon-monochrome.png',
    'splash-icon.png',
  ]) {
    const image = await getPngInfo(new URL(`../assets/${file}`, import.meta.url).pathname);
    assert.equal(image.width, 1024);
    assert.equal(image.height, 1024);
    assert.equal(image.alpha, true);
    assert.equal(image.data[3], 0, 'Transparent corner');
    const alpha: number[] = [];
    let visible = 0;
    for (let y = 0; y < 1024; y++)
      for (let x = 0; x < 1024; x++) {
        const opacity = image.data[(y * 1024 + x) * 4 + 3];
        alpha.push(opacity);
        if (opacity > 16) {
          visible++;
          assert.ok(
            Math.hypot(x - 512, y - 512) <= (1024 * 33) / 108,
            'Visible artwork inside adaptive safe circle',
          );
        }
      }
    assert.ok(visible > 10000, 'Artwork is not empty');
    if (!foregroundAlpha.length) foregroundAlpha = alpha;
    else assert.deepEqual(alpha, foregroundAlpha, 'Consistent sparkle silhouette');
  }
});
