# Castly Phase 1 UI and accessibility fixes

22 October 2026 · `feat/initial-castly-mobile` · base `fac6c6c860e8eb843ff28b167bdac8f5f0ef2ed0`.

**Frontend fixes: PASS. Final native release sign-off: HOLD.** Implementation is ready for design review. Native runtime checks and two network-dependent Expo Doctor checks remain outstanding. This is an implementation report, not a claim of full WCAG conformance or Android device testing.

Changes are in the existing feature-branch working tree. No merge, new application architecture, backend integration, real delivery, billing or Phase 2 work was performed. The earlier `artifacts/phase1-quality-gate/` report was preserved.

## Before and after

Open the [side-by-side comparison](comparison.html) or [comparison PNG](before-after-comparison.png), or select individual PNGs below. All 16 original screenshots and the original Expo icon were backed up byte-for-byte against HEAD before comparison. Some fixtures have different saved states across captures; that is not a visual regression.

| Area | Before | After | Change and evidence |
|---|---|---|---|
| Discovery | [390×844](before/discovery-390x844.png) | [390×844](../screenshots/discovery-390x844.png) | Continuous lavender gradient covers the image's left edge. Cover cropping follows the available image frame; copy remains in natural flow. At native font scales above 1.3, copy uses full width and the image becomes a faint background. |
| Account | [390×844](before/account-profile-390x844.png) | [390×844](../screenshots/account-profile-390x844.png) | Summary captions increase from 10px to 11px and use darker secondary text. Primary colors `#8055E8` and `#6330D1` remain unchanged. |
| Talent Profile | [360×800](before/talent-profile-360x800.png) | [360×800](../screenshots/talent-profile-360x800.png) | Responsive 223/242/260px hero replaces fixed 330px image; smaller section gaps and two-line expandable bio. Sticky tabs stay in the scroll region; booking footer is a separate flex sibling, above the safe-area inset. Native large text stacks footer actions. |
| Messages | [390×844](before/messages-390x844.png) | [390×844](../screenshots/messages-390x844.png) | Card minimum height 86→76px; list gap 10→8px. Wrapping name/timestamp row, explicit sample timestamps, bolder unread preview, numeric unread badge derived from actual incoming fixture messages. Opening marks read; local send updates a local timestamp without claiming delivery. |
| Booking Request | [390×844](before/booking-request-390x844.png) | [390×844](../screenshots/booking-request-390x844.png) | HTML date input on web; Android system date dialog and iOS inline picker in a cancelable modal. Past-day minimum uses the device-local calendar day. Existing impossible/past-date validation and five-field errors remain. Submission is still explicitly local-only. |
| Launcher | [Expo starter](before/launcher-icon.png) | [Castly sparkle](after/launcher-icon.png) | Violet four-point Castly motif on lavender. New icon treatment needs final product acceptance. No Expo starter symbol remains in the configured launcher assets. |

The approved Lavender Pearl palette, Manrope hierarchy, rounded cards, local sample photography and five-tab navigation remain. The hero blend intentionally softens the portrait on its left. The shorter profile hero trades image height for accessible information and tabs on narrow devices. Category and featured-creator rows intentionally scroll horizontally, with a partial next item indicating more content. No real ratings, follower counts or verification claims were added.

## Contrast, targets and responsive measurements

Relative luminance calculations follow WCAG sRGB contrast. Normal-text threshold: 4.5:1.

| Secondary foreground / surface | Before `#777187` | After `#625A72` |
|---|---:|---:|
| White | 4.67:1 | 6.51:1 |
| Page `#FAF8FF` | 4.43:1 — FAIL | 6.18:1 — PASS |
| Summary tile `#F2ECFF` | 4.05:1 — FAIL | 5.65:1 — PASS |
| Lavender `#EDE5FF` | 3.84:1 — FAIL | 5.35:1 — PASS |

Unit checks also cover body, darker-primary and error text on the implemented light surfaces, plus white labels on primary buttons. Shared caption minimum is now 11px; this is a visual improvement, not a WCAG font-size requirement. Interactive controls previously using 44px now use at least 48px, including icon buttons, chips, form fields, section links and filter sliders. Web switches have a 56×48px native checkbox input, visible focus indication and Space-key support around a compact track. Native switches retain platform semantics inside a 48dp row with expanded hit slop; actual native bounds still need device measurement. Noninteractive unread badges/portraits are not touch targets.

| Viewport | Profile image | Initial tab top/bottom | Booking action top | Result |
|---|---:|---:|---:|---|
| 360×800 | 223px | 576–624px | 740px | Tabs fully visible; previously below the footer |
| 390×844 | 242px | 595–643px | 784px | Tabs fully visible; previously partially covered |
| 430×932 | 260px | 613–661px | 872px | Tabs and portfolio heading visible |

See [browser measurements](accessibility-measurements.json). Tests scroll the actual profile scroller to verify tab retention and reach the final About text above the footer. Browser-only 150% text simulations pass at all three widths: [360px](profile-enlarged-text-360x800.png), [390px](profile-enlarged-text-390x844.png), [430px](profile-enlarged-text-430x932.png). These CSS simulations do not exercise native `fontScale` branches or certify 200% Android system font scaling. Horizontally scrollable tabs remain reachable when enlarged; footer labels may wrap.

## Validation checklist

Node 24.19.0; Playwright with system Chromium 154.0.8037.57; CSS viewport sizes at device scale factor 1.

| Check | Result | Evidence |
|---|---|---|
| `npm run typecheck` | PASS | [log](logs/typecheck.log), exit 0 |
| `npm run lint` | PASS | [log](logs/lint.log), exit 0, no warnings |
| `npm test` | PASS | [log](logs/unit.log), 14 passed, 0 failed/skipped; includes date, contrast, branding and existing fixtures |
| `npm run test:browser` | PASS | [log](logs/browser.log); preserved discovery/search/filter/saved/navigation behavior |
| `npm run test:portraits` | PASS | [log](logs/portraits.log); local imagery, frame bounds, loading/failure states, no remote portraits |
| `npm run test:ui` | PASS | [log](logs/ui.log); ten-screen flow, local booking validation/confirmation, gallery, chat, notifications, three widths |
| `npm run test:screenshots` | PASS | [log](logs/screenshots.log); all 30 PNGs fully decode with exact dimensions and nonblank pixels |
| `npm run test:accessibility` | PASS | [log](logs/accessibility.log); control sizes, profile/footer scrolling, date field focus/validation, unread/local states, 150% text simulation |
| `npm run export:web` | PASS | [log](logs/export-web.log); static export |
| `npx expo export --platform android --output-dir /tmp/castly-export-android` | PASS | [log](logs/export-android.log); JS/Hermes/assets export, **not APK compilation** |
| `npx expo export --platform ios --output-dir /tmp/castly-export-ios` | PASS | [log](logs/export-ios.log); JS/Hermes/assets export, **not Xcode compilation** |
| Final `npx expo export --platform all --output-dir /tmp/castly-export-all` | PASS | [log](logs/export-all.log); revalidates all three bundles after the switch target fix |
| `npx expo install --check` online | BLOCKED / exit 1 | [log](logs/dependencies.log): `HTTP Proxy Network Error: Forbidden` |
| Same dependency check with `EXPO_OFFLINE=1` | PASS with limitation | [log](logs/dependencies-offline.log): `Dependencies are up to date`; Expo warns offline validation is unreliable. Date picker installed/pinned at SDK-bundled 9.2.1. |
| `npx expo-doctor` | INCOMPLETE / exit 1 | [log](logs/doctor.log): 18/20 pass. Config schema fetch fails with `TypeError: fetch failed`, `getaddrinfo EAI_AGAIN exp.host`; React Native Directory metadata check fails with unexpected server response. Also warns bundled native modules could not be fetched. |
| Native Android/iOS runtime | NOT RUN | No device, emulator, `adb`, Android emulator command or `/dev/kvm` available. No native success claim. |

Expo initially failed creating `/home/agent/.expo` (`ENOENT`). Successful exports used `EXPO_NO_TELEMETRY=1 __UNSAFE_EXPO_HOME_DIRECTORY=/tmp/castly-expo` to keep CLI state in a writable temporary directory. This is a shell-only Cloud workaround, not application configuration. Node emits its existing module-type detection warning for TypeScript tests; it does not fail tests.

Launcher unit checks fully decode PNGs, assert 1024×1024 native icons and 64×64 favicon, opaque launcher/background/favicon pixels, transparent adaptive/splash corners, nonempty matching silhouettes, and visible artwork inside the centered Android 66/108 safe circle. Android uses one solid lavender background plus transparent foreground; the monochrome asset supplies an alpha silhouette for system tinting. iOS supplies its own mask; no rounded mask is baked in. [Asset documentation](../../assets/branding/README.md) and `npm run assets:branding` describe reproducible exports. Actual launcher appearance, themed tint and splash transitions are untested.

## Screenshot inventory and downloads

[Manifest](../screenshots/manifest.json): SHA-256, nonzero byte count, decoded dimensions and nonblank sampled colors for each of 30 files. All ten screens are captured at **360×800, 390×844 and 430×932**. Screenshots are browser viewports; they deliberately omit fake phone status bars.

| Screen | 360×800 | 390×844 | 430×932 |
|---|---|---|---|
| Discovery | [PNG](../screenshots/discovery-360x800.png) | [PNG](../screenshots/discovery-390x844.png) | [PNG](../screenshots/discovery-430x932.png) |
| Search | [PNG](../screenshots/search-360x800.png) | [PNG](../screenshots/search-390x844.png) | [PNG](../screenshots/search-430x932.png) |
| Filters | [PNG](../screenshots/filters-360x800.png) | [PNG](../screenshots/filters-390x844.png) | [PNG](../screenshots/filters-430x932.png) |
| Talent Profile | [PNG](../screenshots/talent-profile-360x800.png) | [PNG](../screenshots/talent-profile-390x844.png) | [PNG](../screenshots/talent-profile-430x932.png) |
| Portfolio Gallery | [PNG](../screenshots/portfolio-gallery-360x800.png) | [PNG](../screenshots/portfolio-gallery-390x844.png) | [PNG](../screenshots/portfolio-gallery-430x932.png) |
| Booking Request | [PNG](../screenshots/booking-request-360x800.png) | [PNG](../screenshots/booking-request-390x844.png) | [PNG](../screenshots/booking-request-430x932.png) |
| Messages | [PNG](../screenshots/messages-360x800.png) | [PNG](../screenshots/messages-390x844.png) | [PNG](../screenshots/messages-430x932.png) |
| Bookings | [PNG](../screenshots/bookings-360x800.png) | [PNG](../screenshots/bookings-390x844.png) | [PNG](../screenshots/bookings-430x932.png) |
| Saved Talent | [PNG](../screenshots/saved-talent-360x800.png) | [PNG](../screenshots/saved-talent-390x844.png) | [PNG](../screenshots/saved-talent-430x932.png) |
| Account Profile | [PNG](../screenshots/account-profile-360x800.png) | [PNG](../screenshots/account-profile-390x844.png) | [PNG](../screenshots/account-profile-430x932.png) |

The downloadable review ZIP contains these PNGs, original before images, enlarged-text captures, comparisons, manifests and logs. Extract it before opening `artifacts/ui-accessibility-fixes/comparison.html` so relative links work.

## Remaining native acceptance steps

Use a separate local checkout of the reviewed changes with Node and the Android SDK installed; no backend credentials are needed. Rerun online `npx expo install --check` and `npx expo-doctor` where Expo API/React Native Directory are reachable.

1. `npm ci`, then `npx expo start --go --lan --port 8081`. Use an Expo Go build compatible with SDK 58 from <https://expo.dev/go>; if unavailable, use a development build. Open the printed QR code on a real Android phone.
2. In Booking Request, open Proposed date, choose today/future date, cancel without changing the field, then submit missing/past-date values and confirm accessible error announcements. Confirm valid submission stays a local draft. Repeat VoiceOver/modal date selection on iOS separately.
3. Enable TalkBack and test all five tabs, filter sliders, saved state, unread descriptions, gallery, profile sticky tabs and booking actions. Use Accessibility Scanner for actual dp hit targets and device contrast. Check logical focus order and date dialog dismissal.
4. At system font sizes 150% and 200%, verify 360/390/430dp-equivalent layouts, profile footer stacking, reachable last content, horizontal tab/category scrolling, message text, keyboards and both gesture/three-button navigation. Confirm cutout/bottom insets and no native footer overlap.
5. Expo Go cannot validate Castly's own launcher icons. In the separate local test checkout run `npx expo install expo-dev-client`, `npx expo prebuild --platform android` (choose an owned development application ID if prompted), then `npx expo run:android --device --no-bundler` and `npx expo start --dev-client --lan --port 8081`. Use the generated SDK/Gradle requirements. These local test-build changes are not part of this implementation.
6. Review the installed icon under round/squircle launcher masks and Android themed icons. Confirm no clipping/transparency halo, correct Castly sparkle and system background tint. Exercise actual native date dialogs, share sheet, warm/cold `castly://talent/aria` links, Back, lifecycle and scrolling.
7. For embedded assets/offline startup, build locally with `npx expo run:android --device --variant release --no-bundler`, stop Metro, disconnect the network, cold-start, and verify portraits/fonts/gallery/saved state. A bundle export alone does not establish this result.

Record device model, OS/build variant, commit, font/display scale, navigation mode and results. Final design acceptance (including the Castly launcher treatment), native runtime evidence and the blocked online checks are required before a native release sign-off. Implementation stops here for approval; nothing was merged into main.
