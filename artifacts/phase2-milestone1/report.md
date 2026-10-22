# Castly Phase 2 — Milestone 1 validation

22 October 2026 (Asia/Kolkata). Branch: `feat/phase2-supabase-backend`.

**PASS for local implementation review. Hosted/native acceptance is pending configuration and testing.** No hosted Supabase project was created or migrated, and no Android/iOS device or emulator was used. No merge, payment, commission, checkout or live messaging implementation occurred.

## Phase 1 preservation

The complete Phase 1 fixes were validated before Phase 2: TypeScript, lint, 14 unit tests, browser/portrait/UI/accessibility tests, 30 decoded screenshots and web/Android/iOS exports all passed. They were committed and pushed to `feat/initial-castly-mobile` at **`1f94f82`**. This branch was created from that commit. See [preservation results](../ui-accessibility-fixes/preservation-validation/results.json).

Phase 1 screenshots and review outputs remain byte-for-byte at the preserved base version in this checkout. Fresh Phase 2 mock-mode captures were copied to [mock-screenshots](mock-screenshots/manifest.json) before restoring the Phase 1 artifact paths. Application changes are isolated to the new feature branch. The final Phase 2 commit SHA is supplied in the task response/Git history.

## Delivered

- Supabase client with publishable/anon-key validation, persisted React Native sessions, native process locking/AppState token refresh, timeout handling and PKCE email callback. A build-time guard rejects public service-role/secret keys, including dotenv values.
- Signup/login/logout, confirmation and error states; session restoration; private-route guards; Client/Talent onboarding with one immutable primary role. User metadata cannot grant a role. Protected components remain unmounted while identity/account loading is unresolved.
- Typed service/repository layer for accounts, public talent, portfolio/storage, saved talent, participant activity reads and read-only entitlements. UI components do not issue raw Supabase queries. Services whitelist talent write columns so full database rows cannot accidentally update IDs/timestamps.
- Connected Account Profile using the existing Lavender Pearl header, summary tiles, row list and navigation. Real account identity, scoped counts and private shortlist replace fixture values only in connected mode. Talent accounts get a guarded profile editor.
- Talent draft creation/editing/publishing, category/city/rate/biography/skills/availability, portrait selection and portfolio image upload/removal with signed previews. Unpublished profiles/assets remain private. Public discovery, search, profile/gallery and saved cards use published backend data and existing image/loading/fallback components.
- Two versioned migrations for all nine requested tables, RLS on every application table, scoped Storage policies, restricted function/column privileges, immutable role enforcement and query indexes. No ordinary role can grant entitlements, update protected booking fields or send messages.
- Explicit mock mode preserves the original ten screens and local-only flows without project credentials. Partial configuration and connected network failures fail visibly; they do not silently show sample data. Connected live booking/messaging/notification operations show a Milestone 1 availability state.
- [Environment example](../../.env.example), [Supabase/local test configuration](../../supabase/config.toml), and [complete setup/security instructions](../../docs/SUPABASE_SETUP.md).

## Validation results

Node 24.19.0, Chromium 154 via Playwright, PostgreSQL **17.11** in a local Docker container.

| Check | Result | Evidence |
|---|---|---|
| `npm run check:client-env` | PASS | [log](logs/env.log); privileged public-key guard |
| `npm run typecheck` | PASS | [log](logs/typecheck.log), exit 0 |
| `npm run lint` | PASS | [log](logs/lint.log), no errors/warnings |
| `npm test` | PASS | [log](logs/unit.log), **19 passed**, 0 failed/skipped |
| `npm run test:sql` | PASS | [log](logs/sql.log), **71 assertions** on actual PostgreSQL; both actual migration files applied to a fresh isolated database |
| `npm run test:backend` | PASS | [log](logs/backend-browser.log); actual Supabase JS client against mocked HTTP responses, **not a hosted project** |
| `npm run test:browser` | PASS | [log](logs/browser.log), preserved Phase 1 search/filter/save/navigation |
| `npm run test:portraits` | PASS | [log](logs/portraits.log), local asset bounds/loading/error states |
| `npm run test:ui` | PASS | [log](logs/ui.log), existing ten-screen flows and 30 captures |
| `npm run test:accessibility` | PASS | [log](logs/accessibility.log), three widths, targets, profile/footer scrolling and browser text simulation |
| `npm run test:screenshots` | PASS | [log](logs/screenshots.log), all 30 fresh mock PNGs decoded, exact dimensions/nonblank pixels |
| Mock `npm run export:web` | PASS | [log](logs/export-web.log) |
| Connected `npm run export:all` | PASS | [log](logs/export-all-connected.log), web + Android + iOS JS/Hermes/assets; **not native compilation** |
| `npx expo-doctor` | INCOMPLETE / exit 1 | [log](logs/doctor.log), 18/20 pass. Config schema fetch: `EAI_AGAIN exp.host`; React Native Directory: unexpected server response. Both require network access. |
| `EXPO_OFFLINE=1 npx expo install --check` | PASS with limitation | [log](logs/dependencies-offline.log); bundled dependency versions current; Expo warns offline validation is unreliable |

Backend HTTP contract coverage includes signup confirmation, PKCE callback success/error, invalid login, session restoration/logout, role guards, onboarding, account name persistence/retry, anonymous save interception, cross-account shortlist isolation, draft/create/edit/publish, actual web file selection and upload request bodies, portfolio removal, failed-write handling, and offline discovery/shortlist recovery. These are real browser/SDK paths with intercepted network responses. They do not establish hosted Auth/Storage correctness. The callback test found and fixed a double-code-consumption bug during identity-scoped state remounts.

SQL tests run as `anon` and `authenticated` with simulated JWT subjects, including positive owner/participant operations. They check private account/Auth-directory reads, published/draft visibility, own-profile CRUD, another user's profile/portfolio access, role metadata spoofing and immutable onboarding, booking participants/defaults/past dates/published targets, sender impersonation, private saved lists, entitlement grants/modification, private-media isolation, staged assets and unpublishing. Helpers use invoker privileges, not owner bypass. The Docker bootstrap supplies only minimal Auth/Storage SQL contracts; actual hosted gateway JWT verification, Auth email delivery and Storage HTTP enforcement remain separate acceptance checks. Fixtures roll back.

## Migrations and project configuration

Apply in order to a development project:

1. [20261022000100_accounts_and_marketplace.sql](../../supabase/migrations/20261022000100_accounts_and_marketplace.sql)
2. [20261022000200_media_storage.sql](../../supabase/migrations/20261022000200_media_storage.sql)

Use Supabase SQL Editor for each complete transaction, or `supabase login`, `supabase link --project-ref YOUR_PROJECT_REF`, `supabase db push --dry-run`, then `supabase db push`. Do not apply the test bootstrap to a hosted project. Do not mix manual migration installation and CLI history without reconciling versions.

Configure email/password, email confirmation, minimum 8-character passwords, SMTP and rate limits. Add exact web and `castly://auth/callback` redirect URLs for your deployment. Set `EXPO_PUBLIC_SUPABASE_URL` and `EXPO_PUBLIC_SUPABASE_ANON_KEY` in ignored `.env.local`; use only publishable/anon credentials. Restart Metro/re-export after changes. See [setup](../../docs/SUPABASE_SETUP.md) for exact commands, local DB tests and native testing.

Both `portfolio` and `private-media` are separate private buckets. Published portfolio reads are authorized through RLS and 60-second signed URLs; private media remains owner-only. An already issued signed URL can survive unpublishing until its TTL expires, and downloaded files cannot be recalled. Active discovery refreshes around every 45 seconds; owner saves refresh immediately. Replacing portraits or failed cross-service cleanup may leave owner-only orphaned objects. No privileged cleanup job runs in the client.

## Review screenshots

All PNGs are fully decoded and dimension-verified in [the review manifest](png-validation.json). They are Chromium captures, not native screenshots. Test identities use `example.test` addresses.

| Connected view, 390×844 | PNG |
|---|---|
| Login | [PNG](screenshots/login-390x844.png) |
| Signup | [PNG](screenshots/signup-390x844.png) |
| Role onboarding | [PNG](screenshots/onboarding-390x844.png) |
| Connected Client Account | [PNG](screenshots/client-account-390x844.png) |
| Talent editor | [PNG](screenshots/talent-editor-390x844.png) |
| Offline state | [PNG](screenshots/offline-390x844.png) |

The [fresh mock-mode manifest](mock-screenshots/manifest.json) includes all ten existing screens at 360×800, 390×844 and 430×932. [Enlarged-text measurements](enlarged-text/accessibility-measurements.json) are browser-only simulations. The review ZIP preserves repository-relative report, image, migration and setup paths.

## Remaining limitations / release gates

- A real development Supabase project URL/client key and migration application are required to use connected mode. No production/staging database, Auth user, bucket or email configuration was modified by this task.
- Hosted email/PKCE redirects, real JWT refresh/revocation, Storage MIME/size enforcement and signed URL expiry have not been exercised against a project. Run both roles and cross-user attempts using ordinary JWTs; never substitute a service-role key as an authorization test. Repeat SQL tests on isolated local Supabase for platform-schema parity.
- AsyncStorage session persistence is not encrypted storage. Native device security review may choose a secure adapter. Passwords are not persisted by Castly.
- Native Android/iOS runtime, image permissions/cancellation/cropping, AppState refresh, deep links, TalkBack/VoiceOver, device fonts/safe areas and native compilation remain untested. Follow the setup document with SDK-58-compatible Expo Go or a local development build; exports alone are not native test evidence.
- MVP discovery currently loads at most 200 profiles/1,000 portfolio rows per refresh. Pagination and large-catalog optimization are not implemented. Search retains the existing ₹30,000/day budget-filter ceiling; Home discovery can show higher-rate published profiles.
- Live booking transitions, message sending, push notifications, billing, checkout, commissions and client entitlement grants remain disabled. Their tables/participant reads are foundations, not claims of delivered live workflows.

Milestone 1 stops here for review; no merge into main.
