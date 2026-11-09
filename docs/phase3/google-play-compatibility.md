# Dependency evidence (2026-11-09)

**Final selection: `expo-iap` 6.4.3**, pinned exactly in package.json and package-lock.json. The published package is an Expo Module, provides `app.plugin.js` → `plugin/build/withIAP`, and declares Expo/React/React Native wildcard peers. Its source pins OpenIAP Google 4.0.10 and Client Protocol 1.3.3; its README reports Google Play Billing 9.1.0. Its own development matrix references Expo 57 / RN 0.86.2, so this repository's Expo 58 / RN 0.88.0 still requires an approved native build/device compatibility test.

The installed TypeScript declarations were inspected for `request.google`, `obfuscatedAccountId`, eligible base-plan offer tokens, `subscriptionProductReplacementParams`, purchase purchased/pending states, available purchase restoration and transaction finishing. The adapter typechecks against those actual declarations. Config-plugin resolution is checked locally with Expo config; no native or cloud build is implied.

An initially evaluated react-native-iap/Nitro combination was rejected during final source inspection: that package explicitly targets bare React Native and marks Expo Dev Client unsupported. It and Nitro were removed from runtime dependencies, lockfile and imports. The final implementation uses only expo-iap. Earlier milestone commit references describe the development sequence; this document describes the delivered dependency.

Attempted official documentation requests returned network proxy `Tunnel connection failed: 403 Forbidden`:

- https://developer.android.com/google/play/billing/release-notes
- https://docs.expo.dev/versions/latest/sdk/in-app-purchases/
- https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2/get
- https://hyochan.github.io/react-native-iap/docs/intro

Registry metadata and integrity-pinned published source were successfully fetched from https://registry.npmjs.org/expo-iap/latest. Source declarations are not independent official policy verification. Current official Google support requirements, native Expo 58 compatibility and real Play licence-test purchase/acknowledgement remain release blockers. Expo Go/web retain non-native subscription screens with purchase controls unavailable.
