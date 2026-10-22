# Castly launcher assets

The transparent source artwork is a generated violet four-point sparkle derived from the approved Castly wordmark motif. It replaces the Expo starter symbol. This launcher treatment needs final product approval and native launcher review.

Run `npm run assets:branding` to export the committed source through `@expo/image-utils`:

- `icon.png`: 1024×1024, opaque lavender square; iOS supplies its corner mask.
- `android-icon-foreground.png`: 1024×1024 RGBA with transparent padding. The visible sparkle is centered inside the adaptive safe zone.
- `android-icon-monochrome.png`: same alpha silhouette; Android applies its themed tint and ignores source RGB.
- Android configuration uses the opaque `#EDE5FF` background color. The matching background PNG is retained as a uniform export, but is not layered again.
- `splash-icon.png`: transparent 1024×1024 artwork; no Expo symbol remains.
- `favicon.png`: opaque 64×64 web icon.

PNG dimensions, transparency and adaptive safe-zone bounds are checked separately from native rendering. Expo Go cannot preview a project's installed launcher icon. Test a development or release build on a real launcher, including Android themed icons.
