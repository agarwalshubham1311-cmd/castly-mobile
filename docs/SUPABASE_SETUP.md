# Supabase — historical Milestone 1 development guide

**Current architecture:** normal business requests use Spring Boot. Follow [backend/README.md](../backend/README.md) for the reviewed cutover migration, restricted login, JWT verification and current setup. This document describes the preserved pre-cutover direct-Supabase implementation; its business writes are development-only and are deliberately revoked on an API-backed database.

This branch adds authentication, one primary role, talent profile/portfolio editing, published discovery, private saved talent and a connected Account Profile. Existing ten-screen layouts are retained. Without credentials, Phase 1 remains a local mock demo. Live booking operations, message sending, checkout, commissions and entitlement management are not enabled.

## Configure a project

1. Create a development Supabase project. No hosted project has been created or modified by this implementation.
2. Apply `supabase/migrations/20261022000100_accounts_and_marketplace.sql`, then `20261022000200_media_storage.sql`, in order. For manual installation, paste each complete file into the Supabase SQL Editor as the project database administrator and run it once. Each file is transactional. Use a clean development project; existing tables, bucket names or unrelated permissive policies need review before applying. Do not run `supabase/tests/bootstrap-local.sql` in a real Supabase project.
3. Alternatively, install the Supabase CLI, run `supabase login`, then `supabase link --project-ref YOUR_PROJECT_REF`, review `supabase db push --dry-run`, and run `supabase db push`. CLI credentials/database passwords are local administration tools; never put them in Expo public environment variables. Only the two migration files belong in the deployment flow. CLI tracks migration history; do not mix manual SQL installation and CLI push without reconciling that history.
4. In Authentication → Providers, enable email/password. Keep email confirmation enabled. Set the password minimum to at least 8 characters and configure appropriate signup/rate limits. Configure a supported SMTP sender for email delivery outside a limited development test. No email-provider credentials belong in the app.
5. Set Authentication → URL Configuration → Site URL to the deployed web app URL. Add exact development/production callback URLs to the redirect allowlist:
   - `http://localhost:8081/auth/callback` (or the actual local web origin/port)
   - The actual exported web origin, e.g. `http://127.0.0.1:8082/auth/callback`
   - `https://YOUR_WEB_DOMAIN/auth/callback`
   - `castly://auth/callback` for a development/release native build.
   - Expo Go generates an `exp://…/--/auth/callback` URL that depends on the Metro host. Use a development build for stable native auth redirects. Do not add broad production wildcard redirects just to accommodate a test.
6. Copy `.env.example` to `.env.local`. From the Supabase project API settings set:

   ```dotenv
   EXPO_PUBLIC_SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
   EXPO_PUBLIC_SUPABASE_ANON_KEY=YOUR_PUBLISHABLE_OR_ANON_KEY
   EXPO_PUBLIC_BACKEND_MODE=supabase
   ```

   Use the publishable key (`sb_publishable_…`) or legacy `anon` JWT. **Never use `service_role`, `sb_secret_…`, database passwords or signing secrets in the app.** These Expo variables are public and embedded in the bundle. `app.config.ts` refuses privileged public keys before bundling; runtime configuration also rejects nonclient keys. A mistakenly exposed key must still be revoked/rotated in Supabase. Secrets are ignored by Git; only the empty `.env.example` is tracked.
7. Restart Metro after environment changes: `npm start -- --clear`, or `npm run web -- --clear`. For web deployment export again with `npm run export:web`; environment values are build-time values, not changes to an already exported bundle. `npm run export:all` validates web, Android and iOS JavaScript/assets; it does not compile native apps.

No hosted credentials are needed for mock mode:

```sh
# Both credential variables absent -> auto selects mock.
EXPO_PUBLIC_BACKEND_MODE=mock npm start
```

A partial/invalid configuration fails closed with a configuration message; API failure never silently switches a connected user to sample data. Explicit mock mode displays the original fictional data and local-only behavior. There is no fake signup/password persistence in mock mode.

## Account and media behavior

- Signup creates an Auth user and an own private `public.users` row through a trigger. User metadata cannot assign permissions. `primary_role` starts null.
- After email confirmation/login, `complete_onboarding` atomically chooses Client or Talent. The role is immutable after first selection, including attempts through the RPC. Renaming uses a narrowly granted column update. Auth email stays in Supabase Auth, not a publicly discoverable profile table.
- Supabase session tokens persist under the separate AsyncStorage key `castly.auth.v1`. AsyncStorage is not encrypted storage; production device threat-model review may choose a secure native adapter. Passwords are never stored by Castly. SDK refresh is tied to native AppState; web uses SDK visibility handling. Auth/account errors have explicit retry/sign-out states. PKCE callback code exchange is handled at `/auth/callback`; confirmation on another device may require returning to password login because that device has no original PKCE verifier.
- Protected routes never mount private components before session restoration/account loading completes. Talent accounts see Manage talent profile; a client cannot open that editor. Five existing navigation tabs are retained. Authenticated accounts lacking a role are sent to onboarding when entering protected screens.
- The talent editor creates a draft first, then supports biography/category/city/rate/skills/availability/publish changes, portrait selection and portfolio photo upload/removal. Only owner-selected JPEG/PNG/WebP up to 5 MiB are accepted. ImagePicker permissions and cropping remain platform-native. Actual native picking/permissions have not been exercised in Cloud.
- Both `portfolio` and `private-media` buckets are private. Published portfolio assets are shared with anonymous readers only through RLS-approved **60-second signed URLs**; `private-media` permits only owner folder access and is never used for public portraits. The portfolio bucket allows images up to 5 MiB; private media allows selected image/PDF types up to 10 MiB. No private document upload UI is included in this milestone.
- An object's first path segment is the owner's UUID. Public reads require a published profile and a registered portrait/portfolio path. Staged/orphaned files are owner-only. Object overwrite/move is disabled; uploads use fresh filenames. Unpublishing immediately hides database rows and blocks new signed URLs. A previously issued signed URL can remain usable for up to its remaining 60-second TTL, and a previously downloaded image cannot be recalled. Discovery refreshes active data approximately every 45 seconds; an owner save triggers immediate refresh. Background/offline screens may retain already loaded imagery until refresh; there is no automatic entitlement/verification claim.
- Upload and metadata writes are not a cross-service transaction. A failed portfolio registration attempts object cleanup; a failed cleanup can leave an owner-only orphan. Replacing a portrait can also leave an old owner-only object. A future trusted cleanup job may remove unreferenced objects after a retention window; no privileged cleanup runs in the mobile app.
- Saved talent loads from the signed-in account; identity changes remount scoped state so the previous user's shortlist is not displayed. No fixture bookings/messages enter connected account counts. Database participant-scoped reads exist in the service layer; live booking and messaging screens intentionally show a Milestone 1 availability state.
- Subscription entitlements are read-only to their owner. No ordinary role has insert/update/delete grants, and no client checkout/grant endpoint exists. No Realtime subscription or live message-send API is installed. Search retains the approved legacy ₹30,000/day budget-filter ceiling; higher rates remain visible on Home/profile. Expanding that filter is outside this auth/database milestone.

## Database authorization map

| Table | Read | Ordinary writes |
|---|---|---|
| users | Own account | Own display name only; onboarding RPC selects the role once |
| categories | Public | None |
| talent_profiles | Published or own draft | Talent role, own profile only; owner/server timestamps immutable to client |
| portfolio_assets | Published talent assets or own | Owner only, own-path checks; only labels/order editable |
| bookings | Client and relevant talent | Client may insert a request for published talent; identity/date constraints; no ordinary status/agreed-rate/participant updates |
| conversations | Both participants | Client can create own conversation with published talent; no reassignment |
| messages | Conversation participants | None in Milestone 1, including impersonated sends |
| saved_talent | Owner | Owner add/remove published talent |
| subscription_entitlements | Owner | None; trusted administration only |

RLS and column grants are the authority. UI role checks and service filters are defense in depth. No SECURITY DEFINER API accepts a caller-provided user ID. `complete_onboarding` uses `auth.uid()`, fixed empty search path, restricted execute grant and an immutable-role trigger. Applying migrations to an established project also requires auditing pre-existing permissive RLS policies, since PostgreSQL combines permissive policies with OR.

## Validation

```sh
npm ci
npm run typecheck
npm run lint
npm test
npm run export:web
npm run preview                 # leave running on 127.0.0.1:8082
# In another terminal, using mock mode for the existing fixture tests:
npm run test:browser
npm run test:portraits
npm run test:ui
npm run test:accessibility
npm run test:screenshots
npm run export:all
```

For connected UI contract tests, make a separate export using **test-only public values**, start its preview, and run:

```sh
EXPO_PUBLIC_BACKEND_MODE=supabase \
EXPO_PUBLIC_SUPABASE_URL=https://castly-test.supabase.co \
EXPO_PUBLIC_SUPABASE_ANON_KEY=sb_publishable_castly_test_only \
npx expo export --platform web --output-dir /tmp/castly-connected-test
WEB_EXPORT_DIR=/tmp/castly-connected-test PORT=8084 npm run preview
# Another terminal:
TEST_URL=http://127.0.0.1:8084 npm run test:backend
```

The contract test intercepts all Supabase HTTP requests; these are not valid project credentials and no hosted signup occurs. Do not deploy that export. It checks auth/session/private routes/account isolation/profile writes and failure/recovery states. Real hosted email, token refresh, storage upload and native runtime need separate acceptance.

For actual PostgreSQL policy tests using Docker:

```sh
docker run --name castly-phase2-policy-db \
  -e POSTGRES_PASSWORD=castly-local-test-only -d postgres:17-alpine
# Wait until: docker exec castly-phase2-policy-db pg_isready
npm run test:sql
```

`test:sql` recreates only the isolated `castly_policy_test` database inside that named container, bootstraps a minimal test-only Auth/Storage SQL contract, applies the actual migrations and executes `supabase/tests/policies.sql`. The policies run as `anon`/`authenticated` with simulated JWT subjects, not as table owner. Fixtures roll back. This validates PostgreSQL RLS/privileges; the bootstrap is not a replacement for testing the Supabase Auth/Storage HTTP servers.

On an isolated **local Supabase database with migrations already applied**, install `psql` and use:

```sh
SUPABASE_TEST_DATABASE_URL=postgresql://postgres:LOCAL_PASSWORD@127.0.0.1:54322/postgres npm run test:sql
```

Do not point test fixtures at production. This path does not bootstrap schemas or apply migrations; it runs the rollback-only policy suite. Local cleanup after reviewing results: `docker rm -f castly-phase2-policy-db` removes only the test container you created.

## Hosted/native acceptance still required

Use the configured development project to confirm email signup/login, PKCE links on the actual web/native origins, persisted session and token refresh, logout, both immutable roles, draft/published discovery, real portrait/portfolio uploads, cancellation/denied permissions, saved state after account switching and server denial using another authenticated user's JWT. Re-run the SQL suite on local Supabase for platform-schema parity. Verify storage MIME/size enforcement through the actual Storage API and expiration of signed URLs. Never use a service-role key to establish ordinary-user authorization results.

Use an Expo Go build compatible with SDK 58, or a separate local development-build checkout with `npx expo install expo-dev-client`, `npx expo prebuild --platform android`, `npx expo run:android --device`, and the owned development application ID. Test TalkBack, keyboard/safe areas, image permissions and auth links. No actual Android/iOS device, emulator or native compilation has been used for this milestone. Stop at Milestone 1; no merge or billing/live-messaging work is authorized here.
