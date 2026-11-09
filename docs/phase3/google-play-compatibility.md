# Dependency evidence (2026-11-09)

The installed published npm package `react-native-iap` 17.3.4 declares React/React Native wildcard peers and Nitro >=0.36.5. Its README reports Google Play Billing 9.1.0; `openiap-versions.json` pins Google 4.0.10. The package's own development matrix references Expo 57 / RN 0.86.2; this repository uses Expo 58 / RN 0.88.0. Thus installation/type checking is evidence of JavaScript compatibility only, not proof of native Expo 58 compatibility. Nitro is pinned to 0.36.5 for the adapter. A custom Android build, Play license testing and final official documentation review are release blockers.

Attempted official sources returned network proxy `Tunnel connection failed: 403 Forbidden`:

- https://developer.android.com/google/play/billing/release-notes
- https://docs.expo.dev/versions/latest/sdk/in-app-purchases/
- https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2/get
- https://hyochan.github.io/react-native-iap/docs/intro

Registry package source was successfully fetched from https://registry.npmjs.org/react-native-iap/latest and its integrity-pinned tarball. The adapter was implemented against the installed declarations, including `request.google`, `obfuscatedAccountId`, base-plan offer tokens, product replacement parameters and the purchased/pending purchase states. Current official Google support status could not be independently verified from this environment; release remains gated on that review.
