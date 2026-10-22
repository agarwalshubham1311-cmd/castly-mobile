// Export the generated Castly sparkle artwork to native launcher sizes using Expo's asset pipeline.
const fs = require('node:fs');
const path = require('node:path');
const { generateImageAsync, generateImageBackgroundAsync } = require('@expo/image-utils');
(async () => {
  const projectRoot = path.resolve(__dirname, '..');
  const src = path.join(projectRoot, 'assets/branding/castly-sparkle-source.png');
  async function output(file, size, opaque = false) {
    const { source } = await generateImageAsync(
      { projectRoot, cacheType: 'castly-branding' },
      {
        src,
        name: file,
        width: size,
        height: size,
        resizeMode: 'contain',
        ...(opaque ? { backgroundColor: '#EDE5FF', removeTransparency: true } : {}),
      },
    );
    fs.writeFileSync(path.join(projectRoot, 'assets', file), source);
  }
  await output('icon.png', 1024, true);
  await output('android-icon-foreground.png', 1024);
  // Android tints the alpha silhouette for themed monochrome icons; RGB is intentionally ignored.
  fs.copyFileSync(
    path.join(projectRoot, 'assets/android-icon-foreground.png'),
    path.join(projectRoot, 'assets/android-icon-monochrome.png'),
  );
  fs.writeFileSync(
    path.join(projectRoot, 'assets/android-icon-background.png'),
    await generateImageBackgroundAsync({
      width: 1024,
      height: 1024,
      resizeMode: 'contain',
      backgroundColor: '#EDE5FF',
    }),
  );
  await output('splash-icon.png', 1024);
  await output('favicon.png', 64, true);
  console.log('Exported Castly app/adaptive icons at 1024px and opaque favicon at 64px.');
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
