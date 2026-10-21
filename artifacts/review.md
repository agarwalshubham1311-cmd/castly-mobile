# Lavender Pearl implementation review

Branch: `feat/initial-castly-mobile`. Review base: `dccedf6`. No merge into `main`, backend, Supabase integration, subscription billing, booking commission or mandatory booking payment is included.

## What changed

- Replaced the previous dark shell with the approved light Lavender Pearl palette, local Manrope typography, violet selected states, white cards, subtle borders/shadows and an editorial photography hero.
- Migrated the existing application to Expo Router: Home, Search, Bookings, Messages and Profile tabs, plus secondary stack routes and direct-link fallbacks.
- Retained the original catalog and all eleven bundled portraits. Preserved search, category filtering, profile navigation and saved selection behavior. Added responsive card sizing, skill/minimum-budget filters, sorting, applied chips and persistent local saved selections.
- Added profile Portfolio/Services/Reviews/About views, native/web share entry, full-screen gallery viewer, and honest media/review empty states.
- Added validated local booking forms, confirmation and details; searchable sample conversations, unread/booking/archive filters, archive actions and local chat bubbles.
- Added sample account summaries, saved list, notifications, account form preview, subscription information and help. Every booking/message request is explicitly local and no payment or delivery is implied.
- Extracted shared TalentCard, FeaturedTalentCard, CategoryChip, SearchBar, FilterChip, PriceBadge, PortfolioGrid, BookingCard, MessageCard, BottomNavigation, PrimaryButton, EmptyState and LoadingSkeleton components.
- Added lint configuration, a static SPA preview command, logic/asset tests, browser interaction checks and reproducible screenshot verification.

## Validation

| Check | Result |
|---|---|
| `npm run typecheck` | Passed |
| `npm run lint` | Passed, no warnings |
| `npm test` | 11 passed; 0 failed/skipped |
| `EXPO_OFFLINE=1 npx expo install --check` | Installed SDK version map reports compatible dependencies; online metadata was not used |
| `npm run test:browser` | Preserved category/search/filter/reset, profile, synchronized save, reload persistence/removal and navigation regressions passed |
| `npm run test:portraits` | Local portrait/gallery assets, image bounds, loading and error states passed; no remote portrait requests |
| `npm run test:ui` | Ten-screen flow, filter synchronization, sorting, five selected tabs, local booking validation/confirmation, local chat, gallery controls, saved/account/notifications and responsive grid/touch-target assertions passed |
| `expo export --platform all --max-workers 2` | Web, Android Hermes and iOS Hermes exports passed with local fonts and portraits |
| `npm run test:screenshots` | 16 PNGs fully decoded in Chromium; expected dimensions, signature and nonblank content passed |
| Static preview and SPA deep links | Passed for Home, Search, Talent and Gallery; JavaScript served with the correct MIME type |
| Native device/emulator interaction | Not run |

Browser checks used Playwright with `/usr/bin/chromium`. Screenshot captures wait for screen-specific content, local image decoding and fonts. The verifier excludes the bottom navigation region when checking nonblank content so a navigation-only loading frame cannot pass.

## Verified screenshots

Click a file on GitHub to view it or download the raw PNG. These are repository files, not unsupported task-attachment links. Exact dimensions, byte lengths, SHA-256 hashes and decoding results are in [screenshots/manifest.json](screenshots/manifest.json).

| Screen | 390 × 844 | 360 × 800 | 430 × 932 |
|---|---|---|---|
| Discovery | [PNG](screenshots/discovery-390x844.png) | [PNG](screenshots/discovery-360x800.png) | [PNG](screenshots/discovery-430x932.png) |
| Search talent | [PNG](screenshots/search-390x844.png) | [PNG](screenshots/search-360x800.png) | [PNG](screenshots/search-430x932.png) |
| Advanced filters | [PNG](screenshots/filters-390x844.png) | — | — |
| Talent profile | [PNG](screenshots/talent-profile-390x844.png) | [PNG](screenshots/talent-profile-360x800.png) | [PNG](screenshots/talent-profile-430x932.png) |
| Portfolio gallery | [PNG](screenshots/portfolio-gallery-390x844.png) | — | — |
| Booking request | [PNG](screenshots/booking-request-390x844.png) | — | — |
| Messages | [PNG](screenshots/messages-390x844.png) | — | — |
| Bookings | [PNG](screenshots/bookings-390x844.png) | — | — |
| Saved talent | [PNG](screenshots/saved-talent-390x844.png) | — | — |
| Account profile | [PNG](screenshots/account-profile-390x844.png) | — | — |

## Known limits and demo scope

- Four fictional talent records use eleven licensed sample portraits. Some gallery photographs feature different models and are labeled stock studies, not the named talent’s actual portfolio. No real verification, reviews or follower claims are asserted.
- Photos are bundled. Video, reels, brand work and reviews intentionally have empty states; no videos or reviews were invented.
- Only the saved shortlist persists on the device. Booking drafts, sent-local message bubbles, read/archive changes and settings previews are session-local. A reload restores the sample fixtures.
- The subscription screen is information only. There is no active plan, payment method collection, checkout or billing implementation.
- Native safe-area handling, keyboard avoidance and font-scaling layout branches are implemented, but native devices, VoiceOver/TalkBack, notch/home-indicator configurations and enlarged native font settings still require device review. Browser screenshots do not establish native-device validation.
- Screens follow the attached layout/palette using the existing licensed photographs, not the exact people or fabricated ratings/badges in the reference. No pixel-perfect equivalence is claimed.
- The responsive application is centered at a maximum width of 600px on wider web viewports. Standard mobile text uses two talent columns; very large native font scale uses one column.

## Files changed

The following is the complete changed/new file inventory relative to the review base. Files are grouped by role; unchanged photo binaries and their source/license records remain intact.

### Entry/configuration/documentation

- `.prettierrc.json`
- `App.tsx`
- `README.md`
- `app.json`
- `eslint.config.js`
- `index.ts`
- `package-lock.json`
- `package.json`

### Router routes

- `app/(tabs)/_layout.tsx`
- `app/(tabs)/account.tsx`
- `app/(tabs)/bookings.tsx`
- `app/(tabs)/index.tsx`
- `app/(tabs)/messages.tsx`
- `app/(tabs)/search.tsx`
- `app/+not-found.tsx`
- `app/_layout.tsx`
- `app/booking-detail/[id].tsx`
- `app/booking/[id].tsx`
- `app/chat/[id].tsx`
- `app/filters.tsx`
- `app/gallery/[id].tsx`
- `app/notifications.tsx`
- `app/saved.tsx`
- `app/settings/[section].tsx`
- `app/talent/[id].tsx`

### Application components, state and fixtures

- `src/TalentImage.tsx`
- `src/components/ui.tsx`
- `src/demo.ts`
- `src/screens/Account.tsx`
- `src/screens/Activity.tsx`
- `src/screens/Booking.tsx`
- `src/screens/Chat.tsx`
- `src/screens/Discovery.tsx`
- `src/screens/Filters.tsx`
- `src/screens/Gallery.tsx`
- `src/screens/Saved.tsx`
- `src/screens/Search.tsx`
- `src/screens/Talent.tsx`
- `src/store.tsx`
- `src/talent.ts`
- `src/theme.ts`

### Tests and preview tooling

- `scripts/serve-web.cjs`
- `tests/browser-utils.cjs`
- `tests/browser.cjs`
- `tests/demo.test.ts`
- `tests/lavender-browser.cjs`
- `tests/portraits-browser.cjs`
- `tests/portraits.test.ts`
- `tests/talent.test.ts`
- `tests/verify-screenshots.cjs`

### Review artifacts

- `artifacts/review.md`
- `artifacts/screenshots/account-profile-390x844.png`
- `artifacts/screenshots/booking-request-390x844.png`
- `artifacts/screenshots/bookings-390x844.png`
- `artifacts/screenshots/discovery-360x800.png`
- `artifacts/screenshots/discovery-390x844.png`
- `artifacts/screenshots/discovery-430x932.png`
- `artifacts/screenshots/filters-390x844.png`
- `artifacts/screenshots/manifest.json`
- `artifacts/screenshots/messages-390x844.png`
- `artifacts/screenshots/portfolio-gallery-390x844.png`
- `artifacts/screenshots/saved-talent-390x844.png`
- `artifacts/screenshots/search-360x800.png`
- `artifacts/screenshots/search-390x844.png`
- `artifacts/screenshots/search-430x932.png`
- `artifacts/screenshots/talent-profile-360x800.png`
- `artifacts/screenshots/talent-profile-390x844.png`
- `artifacts/screenshots/talent-profile-430x932.png`
