# Castly — mobile and Spring business API

Current development branch: `feat/phase2-bookings-messaging`. Spring Boot now owns Milestone 1 business operations and Milestone 2 booking/messaging workflows. Supabase remains the Auth, Storage and Realtime provider. See [backend setup and API contract](backend/README.md) and [validation results](artifacts/spring-api/report.md).

No hosted migrations, production deployment, main merge or payment enablement is performed by this work. The direct Supabase business implementation is retained only as an explicit Metro development fallback against a pre-cutover development database.

## Earlier Phase 2 foundation

Phase 2 Milestone 1 adds Supabase authentication, role-based onboarding, connected accounts, published discovery, private saved talent and talent/portfolio editing. The Phase 1 Lavender Pearl UI remains available in mock mode. See [Supabase setup and authorization](docs/SUPABASE_SETUP.md) and [Milestone 1 validation](artifacts/phase2-milestone1/report.md).

Approved Phase 1 fixes are preserved on `feat/initial-castly-mobile` at `1f94f82`. Phase 2 branches from that commit. No changes are merged into main.

## Phase 1 Lavender Pearl preview

Expo + React Native + TypeScript frontend using Expo Router. Implements the approved light Lavender Pearl direction: `#FAF8FF` surfaces, `#8055E8` violet, white cards, plum text, bundled Manrope typography, editorial portraits, accessible controls and adaptive two-column talent grids.

## Screens and navigation

Five tabs: **Home, Search, Bookings, Messages, Profile**. Stack routes provide advanced filters, talent profiles, galleries/media viewing, booking requests/details, conversations, saved talent, notifications and settings.

- Home: location picker, editorial hero, category shortcuts, featured carousel and talent grid.
- Search: combined query/category/location/budget/skill/availability filters, removable chips, result count, sorting, hydration loading and empty states.
- Talent: portfolio/services/reviews/about tabs, save/share, indicative rates, local message/booking entry points.
- Gallery: bundled photos, full-screen viewer with previous/next controls; honest empty states for video/reels/brand work.
- Booking: validated project/date/location/requirements/budget form and local confirmation/detail view.
- Messages: searchable fixtures with unread/booking/archive filters and local conversation actions.
- Bookings: filtered sample statuses and local draft details.
- Saved: persistent device-local shortlist with category filters.
- Account: sample business workspace, summaries, settings, subscription information, notifications and help.

## Demo boundaries

All talent names, rates, biographies, availability, account details, messages and booking records are **fictional development fixtures**. Stock photographs are not real talent identities or professional portfolios. No verification, reviews or follower claims are invented.

Only the saved shortlist persists using AsyncStorage (`castly.saved.v1`). Local messages and booking drafts last for the current running session. Reloading restores the sample conversation/booking fixtures. Requests are not delivered, bookings are not confirmed, settings do not update a real account, and no payments occur. Subscription is informational only: no commission, mandatory booking payment, checkout or backend is implemented.

Eleven licensed Unsplash photographs are bundled locally; no image API, runtime image-host access or credentials are required. See [image sources and licensing](assets/talent/README.md). Manrope fonts are locally bundled from `@expo-google-fonts/manrope` (OFL-1.1); the package includes its license.

## Develop

Requires Node 22.13+ and npm; validated here with Node 24.

```sh
npm ci
npm start
```

Use a compatible Expo Go/native development environment or `npm run web`. Native simulators require their normal Android/iOS toolchains. If the cloud machine's npm home cache is unwritable, prefix npm commands with `npm_config_cache=/workspace/.npm-cache`.

## Validate

```sh
npm run typecheck
npm run lint
npm test
EXPO_OFFLINE=1 npx expo install --check
npx expo export --platform all --output-dir dist --max-workers 2
```

Browser interaction and screenshot tests use an exported SPA:

```sh
npm run export:web
npm run preview
# In another terminal:
npm run test:browser
npm run test:portraits
npm run test:ui
npm run test:screenshots
npm run test:accessibility
```

The static preview serves `dist-web` with Expo Router SPA fallback on port 8082. Override `WEB_EXPORT_DIR` or `PORT` if needed. The browser tests use locally installed Chromium (`/usr/bin/chromium` by default); override `CHROMIUM_PATH` and `TEST_URL` as needed. This static file server is development tooling, not an application backend.

`test:browser` preserves the original discovery/filter/profile/save regression coverage, including saved persistence. `test:portraits` exercises all local profile/gallery assets, image bounds, loading and error states. `test:ui` exercises the ten screens, local booking and chat flows, sorting/filter synchronization and five active tabs. It captures all ten screens at 360×800, 390×844 and 430×932.

`test:screenshots` fully decodes the 30 PNGs in Chromium, checks exact dimensions and nonblank pixels, and writes hashes, sizes and decoding results to [the screenshot manifest](artifacts/screenshots/manifest.json). Images live in [artifacts/screenshots](artifacts/screenshots/).

Native exports verify bundling, not real-device rendering, native keyboards, screen readers or notch/home-indicator behavior. Android/iOS device validation remains required before release.

## Structure

- `app/`: Expo Router tabs and secondary stack routes.
- `src/theme.ts`: centralized colors, fonts, spacing, radii and shadows.
- `src/components/ui.tsx`: shared cards, navigation, search/filter controls, fields, loading/empty states and gallery grid.
- `src/screens/`: screen presentation.
- `src/talent.ts`, `src/demo.ts`: catalog, fixture data and pure search/validation helpers.
- `src/store.tsx`: shared local state and shortlist storage.
- `src/TalentImage.tsx`, `src/talentMedia.ts`: bundled image rendering and registry.
- `tests/`: pure logic/asset tests and Playwright UI checks.

Work remains on `feat/initial-castly-mobile`. No merge, Supabase integration, backend development or billing is part of this implementation.

The UI/accessibility follow-up adds AA secondary text, 48px web control targets, a blended Discovery hero, compact/sticky profile content, local message timestamps/unread counts, platform date selection and Castly launcher assets. `test:accessibility` covers responsive controls, sticky tabs/footer, browser 150% text simulation, switch keyboard use and local interactions. Native TalkBack, system font scaling, calendar dialogs and launcher masking still require device testing. See [the implementation report and before/after comparisons](artifacts/ui-accessibility-fixes/report.md), and [branding exports](assets/branding/README.md).
