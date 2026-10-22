# Castly Phase 1 final quality gate

Reviewed 22 October 2026 (Asia/Calcutta). Branch: `feat/initial-castly-mobile`. Commit: `fac6c6c860e8eb843ff28b167bdac8f5f0ef2ed0`.

**Final verdict: FAIL / HOLD for final native release sign-off. Automated frontend/build gate: PASS.**

No required command failed. Release hold is based on confirmed normal-text contrast failures, Android touch-target guideline gaps, outstanding visual acceptance, and missing native Android runtime evidence. “Not run” native checks are evidence gaps, not observed crashes. The frontend remains suitable for a supervised, clearly labeled demo; that is not equivalent to final Android release readiness.

No application code, dependencies, branch history or existing review screenshots were changed. No merge, push, backend work or billing work was performed. The screenshot scripts necessarily regenerate artifacts; their outputs were retained outside the checkout and the original 16 PNGs and manifest restored byte-for-byte. All 91 previously tracked files match their starting SHA-256 hashes. Only this report/evidence directory is new.

## PASS/FAIL checklist

| Gate | Result | Evidence / precise limit |
|---|---|---|
| Correct feature branch / audited commit | PASS | `feat/initial-castly-mobile`, `fac6c6c` |
| Application code and original artifacts preserved | PASS | All 91 tracked file hashes unchanged |
| `npm run typecheck` | PASS | Exit 0 |
| `npm run lint` | PASS | Exit 0; no lint errors or warnings |
| `npm test` | PASS | Exit 0; 11 passed, 0 failed, 0 skipped |
| `npm run test:ui` | PASS | Exit 0; ten-screen flow, navigation states, filters, sorting, local booking/chat and responsive grid assertions |
| `npm run test:screenshots` on fresh captures | PASS | Exit 0; 16 PNGs decoded with expected dimensions and nonblank content |
| `npm run test:screenshots` on original screenshots | PASS | Exit 0; same checks on the original 16 images; original manifest restored afterward |
| Expo web + Android + iOS exports | PASS | Exit 0; web bundles, Android Hermes and iOS Hermes outputs; bundled fonts/images |
| Base Lavender Pearl palette and component family | PASS | Approved base colors, Manrope family, white cards, violet accents, rounded surfaces |
| Normal-text contrast, WCAG AA 4.5:1 | FAIL | Secondary text on page background 4.43:1; account tile captions 4.05:1 |
| Android-recommended 48dp control targets | FAIL against that guideline | Back/filter/save controls 44×44; chips 44 high. Current browser test uses a 43px threshold, not Android's 48dp recommendation |
| Exact reference visual acceptance | NOT SIGNED OFF | Specific layout/imagery differences below; not an automated pixel-diff failure |
| Castly launcher branding ready to ship | FAIL for branded native release | `assets/icon.png` and adaptive foreground still show the blue Expo starter icon |
| Android APK build/install/cold launch | NOT RUN — release gate incomplete | No SDK, adb, emulator or connected Android device in this instance |
| Android native accessibility, keyboard, safe areas and performance | NOT RUN — release gate incomplete | Browser screenshots and Hermes exports cannot establish these |

`NOT RUN` is intentionally distinct from a failed execution. The overall final gate cannot pass while required evidence is missing.

## Commands and results

Environment: Node `v24.19.0`, npm `11.9.0`, Playwright Chromium at `/usr/bin/chromium`. The existing static SPA preview served the fresh export on an internal loopback port, and `TEST_URL` selected it for the UI runner.

```sh
npm run typecheck
npm run lint
npm test
TEST_URL=http://127.0.0.1:8123 npm run test:ui
npm run test:screenshots
CI=1 EXPO_NO_TELEMETRY=1 npx expo export --platform all \
  --output-dir /tmp/castly-phase1-gate/export --max-workers 2
```

All exited 0. PNG verification was run once on fresh captures and once on the original set. The preview was started with the existing `npm run preview` script using a temporary export directory; no server implementation was changed.

Nonfatal output: Node's `MODULE_TYPELESS_PACKAGE_JSON` warning for TypeScript tests; Expo worker `NO_COLOR`/`FORCE_COLOR` notices; an npm version-update notice. These did not fail tests or exports, and no dependency upgrade was performed.

The original screenshots were personally inspected, not just decoded. `test:screenshots` checks format, decoding, dimensions and nonblank pixels; it does **not** compare to the approved reference, certify contrast, or establish native fidelity. `test:ui` checks normal browser font scale; it does not operate an Android keyboard, TalkBack or system font-size settings.

See [command results](evidence/results.json), [DOM measurements](evidence/measurements.json), and the individual `.log` files in [evidence](evidence/).

## Confirmed accessibility findings

### A1 — Normal secondary text misses AA contrast

Measured using the standard sRGB relative-luminance formula:

| Foreground / background | Contrast | Normal-text AA |
|---|---:|---|
| `#241B38` / `#FAF8FF` | 15.47:1 | PASS |
| `#777187` / `#FFFFFF` | 4.67:1 | PASS |
| `#777187` / `#FAF8FF` | 4.43:1 | FAIL |
| `#777187` / account tile `#F2ECFF` | 4.05:1 | FAIL |
| White / primary `#8055E8` | 4.79:1 | PASS |
| `#6330D1` / `#EDE5FF` | 6.00:1 | PASS |

The talent subtitle “Actor · Mumbai” is computed at 13px in `#777187` over the page background. Account “Open projects” is 10px in that foreground on `#F2ECFF`. These are normal-sized text, so the 4.5:1 threshold applies. The approved color token itself does not guarantee accessible contrast on every approved surface. This is a confirmed defect, not a subjective preference. No colors were changed during the gate.

Relevant implementation: `src/theme.ts`; `src/components/ui.tsx` body/metadata styles; `src/screens/Account.tsx` statistic tiles.

### A2 — 44-unit controls need Android target review

At 390px, Back, Filters and each Save control measured 44×44 CSS pixels. Category/sort chips measured 44px high. Shared React Native styles explicitly set these dimensions to 44, so they are also below the usual Android 48dp touch-target recommendation unless expanded hit regions are added. No such expanded hit regions are specified here.

This is a platform-guideline gap, not a claim that these controls fail WCAG's separate 24-CSS-pixel minimum. The browser assertion only rejects dimensions below 43px and only samples the final Search screen; it cannot serve as an app-wide Android touch-target certificate.

### A3 — Small typography and font-scaling risk

Card metadata and navigation labels are 10px; the rate suffix is 9px; several prototype notices are 10px. They are legible in the provided screenshots but materially smaller than the primary content hierarchy and deserve device review. There is no claim that a small font alone violates WCAG.

The grid changes to one column above native font scale 1.4. The 185px hero and fixed-height profile image/header composition do not establish that all enlarged text fits. Native 130%, 150% and 200% font settings remain untested; clipping under those settings is a risk, not a confirmed device failure.

## All 16 screenshots reviewed against the reference

Comparison uses the attached Lavender Pearl mockup, not a missing pixel-perfect baseline. Reference ratings, verification badges, counts and identity photographs were not treated as required real claims. Their omission is correct for fictional data.

| Screen / existing image | Findings and differences |
|---|---|
| [Discovery 390](../screenshots/discovery-390x844.png) | Correct light palette, branding, overlapping search, visual shortcuts, horizontal featured cards and five tabs. Hero is a rectangular blue-lit portrait with a hard vertical edge, rather than the reference's warm, blended editorial treatment. Cards/header are more vertically generous. Trending cards only begin at the bottom edge of the initial viewport. |
| [Search 390](../screenshots/search-390x844.png) | Two columns, recognizable filters/sort and synchronized hearts. Larger portraits and more vertical space than the reference mean fewer full cards initially visible. Metadata/rate captions are small. The screenshot is unfiltered, so absence of active filter chips is expected; the UI test exercises applied chips. |
| [Filters 390](../screenshots/filters-390x844.png) | Location/category/skill groups and fixed apply action are consistent in style. Uses two separate minimum/maximum sliders rather than a single dual-thumb range, an icon instead of the reference's top-right “Clear all” text, and fewer sample cities/categories. Lower skills/availability/reset controls require scrolling. |
| [Talent profile 390](../screenshots/talent-profile-390x844.png) | Correct portrait identity across card/profile, indicative-rate block, save/share and fixed Message/Request actions. Uses a top bar and inset photo instead of the reference's edge-to-edge hero and overlapping information sheet. Sample notices and spacing push tabs low in the first viewport. The fixed crop is tighter around the top of the head at wider widths, but the face remains visible. |
| [Portfolio gallery 390](../screenshots/portfolio-gallery-390x844.png) | Two-column rounded portraits and category tabs work. Three sample images rather than the reference's six; different stock models are explicitly labeled. Video/Reels/Brand work are honest empty states, not populated-media parity. |
| [Booking request 390](../screenshots/booking-request-390x844.png) | Talent summary, form, budget and primary action match the component family. Date uses manual `YYYY-MM-DD` entry instead of a calendar picker. “Save local request” correctly replaces the reference's implication of sending a real request. Extra disclosure/budget copy makes the page longer. |
| [Messages 390](../screenshots/messages-390x844.png) | Five-tab bar and All/Unread/Bookings/Archived filters match the structure. Includes search and larger bordered conversation cards, three visible active fixtures, unread dots rather than numeric badges/timestamps, and substantially more empty space than the reference's dense list. |
| [Bookings 390](../screenshots/bookings-390x844.png) | Clear status pills, portraits and category tabs. Larger white cards, ISO dates, and three fixture statuses differ from the reference. These are sample states, not actual reservations. |
| [Saved talent 390](../screenshots/saved-talent-390x844.png) | Two saved fixtures, category filters, rates, hearts and profile entry points. More whitespace and taller rows. Unlike the reference it is a secondary stack screen without the five-tab bar; consistent with the implemented route structure, but requires visual acceptance. |
| [Account profile 390](../screenshots/account-profile-390x844.png) | Cohesive violet summary tiles, white settings rows and correct active Profile tab. Counts reflect sample/local state rather than copied marketing numbers. Subscription information replaces Payment Methods; an extra promotional panel is present. Tile captions have the confirmed contrast failure. |
| [Discovery 360](../screenshots/discovery-360x800.png) | Brand/search/five tabs fit; category and featured rails intentionally show a partial next item. Trending grid is below the initial fold. Small captions remain a device-legibility concern. |
| [Search 360](../screenshots/search-360x800.png) | Two approximately 158px cards fit; no horizontal page overflow in the test. First row is fully readable; second row requires scrolling. Horizontal chips are scrollable, not a page-overflow defect. |
| [Talent profile 360](../screenshots/talent-profile-360x800.png) | Name, photo and bottom actions fit, but Portfolio/Services/Reviews/About are below the initial scroll viewport. Portfolio tab starts at y=761 while footer begins around y=727; scrolling is necessary, not proof that the tabs are inaccessible. |
| [Discovery 430](../screenshots/discovery-430x932.png) | More category shortcuts and part of the trending grid fit. Featured remains a rail with partial-next-card affordance, rather than stretching cards across the viewport. Hero's hard image seam remains. |
| [Search 430](../screenshots/search-430x932.png) | Two approximately 189px cards fit; more of the second row is visible. No horizontal page overflow. There is no three-column mobile switch. |
| [Talent profile 430](../screenshots/talent-profile-430x932.png) | Tabs and portfolio heading are visible initially. Fixed 330px hero at wider width produces tighter headroom than at 360. Footer placement and typography remain consistent. |

At 390px the Portfolio tab spans y=740–784, while the footer begins around y=771: the last part is outside the initial scroll viewport and becomes visible by scrolling. At 430px the footer begins around y=859 and the tab fits. This is a density/reference-parity finding, not an assertion that a fixed footer permanently obscures scrollable content.

Base colors, card family, save states and five active primary tabs are coherent. Spacing/radius/type tokens exist, but many component values remain numeric literals rather than references to those token objects; full token-driven consistency is incomplete. No approved-reference pixel-diff test exists, and no exact pixel-parity claim is made.

## Additional native-release finding

`app.json` still points to `assets/icon.png` and `assets/android-icon-foreground.png`, which visibly contain the blue Expo starter symbol, not Castly branding. This can be established from the source assets without running Android. Installed launcher masking/monochrome icon rendering and startup branding still need a native build check. The app has no generated `android/` project, configured `android.package`, `expo-dev-client` dependency or EAS configuration in this audited checkout.

## Android checks unavailable in this Codex Cloud instance

Java 21 is present. `adb`, `emulator`, an Android SDK installation and `/dev/kvm` are absent. No Android device is attached. Installing a native toolchain, generating Android project files or provisioning remote build credentials was not part of this read-only gate.

Consequently none of the following can be passed here:

- Gradle APK assembly, install, process cold start, native-module initialization and crash/ANR inspection.
- Actual status/navigation-bar contrast, display cutouts, gesture/three-button insets, keyboard resize and footer reachability.
- Native system Back from filter sheets, media viewer, detail stacks and cold deep links. Predictive-back preview is **disabled** in current `app.json`; its absence alone is not a new failure.
- TalkBack focus/order/labels/announcements, Android Accessibility Scanner, enlarged system font/display sizes and real 48dp touch behavior.
- Android AsyncStorage survival across force-stop/relaunch, app lifecycle/backgrounding, native share sheet and custom `castly://` routing.
- Smoothness, memory pressure, rapid image scrolling and embedded-asset offline behavior in an installed release-variant APK.

The Hermes exports above are bundle checks only. Android application signing, package identity, APK delivery and device runtime have not been validated. No Play Store or production-readiness claim is warranted.

## Exact Android test procedure

These steps are for a **separate local test checkout** on a machine with Android Studio or a physical Android phone. They were not executed in Cloud and do not change the audited branch here. No backend credentials are required.

### 1. Obtain the audited frontend

```sh
git clone --branch feat/initial-castly-mobile --single-branch \
  https://github.com/agarwalshubham1311-cmd/castly-mobile.git
cd castly-mobile
git rev-parse --short HEAD
# For this report, the reviewed revision is fac6c6c.
npm ci
npx expo-doctor
```

Use Node 22.13+; this gate used Node 24. If the branch has advanced, test the audited revision in a separate local checkout or request a gate for the new revision. Do not reset or overwrite someone else's changes.

### 2A. Fast UI walkthrough with Expo Go

1. Install an Android Expo Go build compatible with **Expo SDK 58** from the official Expo distribution: <https://expo.dev/go>. Do not assume the installed Play Store version supports this SDK. If no compatible build is available, use the development-build path below; do not downgrade the shared project to force compatibility.
2. Connect the computer and phone to the same LAN. Run:

```sh
npx expo start --go --lan --port 8081
```

3. Open the QR code in Expo Go. Confirm there is no red screen and Manrope/icons/portraits load.
4. If LAN access is unavailable, use USB debugging and Android platform-tools:

```sh
adb devices
# Accept the phone's USB-debugging authorization; status must be "device".
adb reverse tcp:8081 tcp:8081
npx expo start --go --localhost --port 8081
# From another terminal, if needed:
adb shell am start -a android.intent.action.VIEW \
  -d 'exp://127.0.0.1:8081'
```

For an Expo Go deep-link test using that USB setup:

```sh
adb shell am start -a android.intent.action.VIEW \
  -d 'exp://127.0.0.1:8081/--/talent/aria'
```

Expo Go is useful for native UI smoke tests but does not validate Castly's own launcher assets, application identity, standalone cold start or custom scheme registration.

### 2B. Android development build

1. Install Android Studio, Android SDK Platform-Tools and Command-Line Tools, and an emulator/system image if using an AVD. Use Android Studio's bundled JDK and point `JAVA_HOME` to that JDK. Configure the local Android SDK location in the normal Android development environment. Verify `java -version` and `adb devices`.
2. On a physical phone enable Developer options → USB debugging, connect USB and authorize the computer. Alternatively start an AVD from Android Studio Device Manager.
3. In the separate test checkout, stop any running Expo Go Metro process, then run:

```sh
npx expo install expo-dev-client
npx expo prebuild --platform android
```

If prompted, provide a unique development Android application ID owned by your organization, for example `com.yourcompany.castly.dev` after replacing `yourcompany`. Prebuild and dev-client installation generate/modify native configuration **locally**; do not commit or push those changes as part of this gate.

4. Use the SDK/build-tools/NDK/Gradle versions required by the generated project. Open `android/` in Android Studio and install missing SDK components when prompted; do not downgrade the project's pins. The installed React Native dependency catalog lists compile SDK 37, Build Tools 37.0.0 and AGP 9.2.1, but the generated Expo project is the authoritative configuration. Cloud has not compiled it.
5. Build/install, selecting the device from the prompt:

```sh
npx expo run:android --device --no-bundler
npx expo start --dev-client --lan --port 8081
```

Open the development build and connect it to the printed project URL/QR. For USB-only transport, use `adb reverse tcp:8081 tcp:8081` and start Metro with `--dev-client --localhost --port 8081` instead. If multiple devices are attached, use `adb -s DEVICE_SERIAL ...` and select the intended target.

6. With Metro reachable, exercise the actual app scheme:

```sh
adb shell am start -a android.intent.action.VIEW \
  -d 'castly://talent/aria'
```

Also cold-start this link after force-stopping the application, then press Back. Expect the talent profile and a safe return to Home, not a blank screen. Use the package ID generated above when force-stopping:

```sh
adb shell am force-stop com.yourcompany.castly.dev
```

### 3. Device acceptance checklist

Record device model, Android version, application/build variant, audited commit, font/display scale, navigation mode, screen dimensions/density and results for each row.

| Native test | Expected result |
|---|---|
| All five tabs and secondary stacks | Correct active state, no blank screen, Back returns sensibly |
| Home and Search at narrow/wide sizes | No clipped essential labels or horizontal page overflow; category/carousel scrolling remains usable |
| Filter apply and cancel | Apply updates Search; Back without apply preserves previous constraints |
| 150% and 200% system font sizes | Essential headings, cards, tabs and actions remain readable/reachable; large text may use one grid column |
| Gesture and three-button navigation; cutout devices | System bars do not cover controls; bottom actions and final list items remain reachable |
| Search, booking and chat keyboards | Focused field and action stay visible; keyboard dismissal restores navigation; numeric input remains usable |
| TalkBack + Accessibility Scanner | Meaningful labels and selected states, logical order, usable sliders/viewer, reachable errors and confirmations; address contrast/target findings above |
| Portraits and gallery | No broken assets, incorrect identity switching, blank viewer, or face-obscuring crop; consistent rounded frames |
| Saved shortlist | Save on card → same state in profile/list; force-stop/relaunch preserves it; removal persists |
| Local request/message flows | Invalid form reports fields; valid draft and local bubble work; no claim of backend delivery/payment |
| Session reset | Booking/message modifications reset on reload as documented; saved shortlist survives |
| Share and deep links | Native share sheet opens or selectable fallback appears; warm/cold links route correctly |
| Lifecycle and fast scrolling | Background/foreground safely; no crash/ANR or obvious sustained frame drops |
| Launcher / startup | Validate approved Castly icon/splash treatment; current template-icon finding must be resolved or explicitly accepted for a demo |

For reproducible emulator dimensions, optional custom hardware profiles at 480dpi can use 1080×2400, 1170×2532 and 1290×2796 pixels to approximate 360×800, 390×844 and 430×932 dp full displays. Android system bars reduce available content area: verify safe-area behavior rather than assuming browser viewport equality.

### 4. Embedded assets and release-mode smoke test

Expo Go/development mode retrieves JavaScript/assets from Metro, so it cannot establish offline cold-start packaging. In the separate native test checkout, a local release-variant install can be used without a store upload:

```sh
npx expo run:android --device --variant release --no-bundler
```

After successful install, stop Metro, disconnect network and cold-start the app. Verify the shipped portraits/fonts, gallery and local saved state. Inspect Android Studio Logcat for app-process errors and record startup/scrolling observations. This command is a proposed test, not a build performed or a signed distribution approved by this report. Store signing/distribution remain separate from a frontend Phase 1 demo gate.

## Release decision and next actions

1. Hold final Android release sign-off. Automated commands are green, but native runtime evidence is missing and the contrast issue is confirmed.
2. In a separately authorized implementation task, resolve normal-text contrast and review Android 48dp hit targets. Do not change approved tokens silently: a darker secondary text variant or surface-specific treatment needs design acceptance.
3. Obtain acceptance of hero composition, profile density, filter control differences, secondary Saved navigation, fixture-limited media and native launcher branding.
4. Run the native checklist above on at least a representative physical Android device and an emulator/configuration covering the requested sizes and enlarged text; record results and rerun this gate for any changed commit.
5. Keep backend, Supabase, real messaging/booking delivery and billing out of scope. Nothing has been merged or released by this audit.
