# Castly mobile

Expo + TypeScript talent discovery prototype for iOS, Android and web. Uses a luxury-purple palette, responsive cards, category and skill search, location/budget/availability filters, talent profiles and an in-memory saved shortlist. Android back closes filters or returns from a profile. No backend, booking, billing or authentication is implemented. All talent data is fictional; eleven Unsplash stock portraits are bundled locally (about 1.6 MB total), including profile gallery samples. Images use cover cropping, rounded corners, loading indicators and accessible fallbacks. Gallery models may differ from the primary portrait; these are explicitly labeled stock studies, not real portfolios. See [image sources and licensing](assets/talent/README.md). No ratings, follower counts or verification claims are shown. Saved selections reset when the app reloads.

The approved visual reference was not accessible during implementation. Visual approval and real-device verification remain outstanding.

## Develop

Requires Node 22.13+ (tested with Node 24) and npm.

```sh
npm ci
npm start
```

Open with a compatible Expo Go client or use `npm run android` / `npm run ios` with the appropriate native simulator toolchain. Web: `npm run web`. If this cloud machine's home cache is not writable, prefix npm commands with `npm_config_cache=/workspace/.npm-cache`. Set `EXPO_NO_TELEMETRY=1` for noninteractive cloud exports.

## Checks

```sh
npm run typecheck
npm test
npx expo install --check
npx expo export --platform ios --platform android
npx expo export --platform web --output-dir dist-web
```

The compatibility check needs Expo API access; `EXPO_OFFLINE=1 npx expo install --check` checks the installed SDK's bundled version map when network access is unavailable.

Browser smoke test (uses a locally installed Chromium, or set `CHROMIUM_PATH`):

```sh
python -m http.server 8082 --directory dist-web
# In a second terminal:
npm run test:browser
```

Override `TEST_URL` to test a different local server. The smoke test exercises categories, profiles, saving, tabs, filters, empty states and reset, and checks phone overflow and tablet rendering. Screenshots are written to `/tmp/castly-mobile.png` and `/tmp/castly-tablet.png`. Native exports verify bundling; they do not replace device interaction tests.

Portrait-specific checks: `npm run test:portraits` (same static server and `TEST_URL` options). This exercises all eleven bundled photographs, checks frame sizing, holds image requests to verify loading indicators, and fails image requests to verify fallbacks. Fresh 390×844 screenshots are written to `/tmp/castly-portraits-{home,discovery,search,profile,gallery}-390x844.png`. Source integrity and coverage checks run with `npm test`.
