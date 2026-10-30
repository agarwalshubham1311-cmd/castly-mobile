# Castly Spring business API

Java 21 / Spring Boot 3.5.7 is the business API for Milestones 1 and 2. Supabase still owns Auth, Storage uploads/download signing and Realtime transport. The application never connects as a database owner, administrator or `service_role`, never handles a JWT signing secret, and never applies migrations at startup. No payments or production deployment is included.

## Local verification

Install a **JDK 21** (a JRE alone cannot compile), Maven 3.9+, Node 22.13+ and Docker. From `backend/`:

```sh
java -version
javac -version
mvn -version
mvn clean verify
```

The integration suite automatically calls `scripts/prepare-test-db.cjs`. Repeat with `CASTLY_TEST_SCHEMA_PATH=reconciliation mvn clean verify` to exercise the prepared reconciliation schema instead of the fresh repository-history path. The same environment switch also works with `npm run test:backend` from the root. Both paths are disposable local fixtures, never hosted schemas. It starts/uses the isolated Docker container `castly-spring-test-db` (`postgres:17-alpine`, loopback port 55432), recreates only `castly_spring_test`, applies all versioned migrations and seeds synthetic users. The Docker owner is used **only for local DDL/test-fixture setup**. Spring tests and business requests connect exclusively as the restricted `castly_api` login. The database is intentionally disposable; do not put personal data there or rename production containers to this test name. Ports 55432, 8080, 8086 and 8098 are used by the full browser test. No test uses a hosted `DATABASE_URL`.

`mvn -Dtest=JwtSecurityTest,ConfigurationTest test` runs the signing/configuration tests without PostgreSQL. Complete verification requires the Docker-backed API tests. CI must provide Docker and Node; tests do not silently skip missing infrastructure. To remove the local test resource after review: `docker rm -f castly-spring-test-db`.

Cloud validation used a workspace-installed Maven/JDK and temporary Maven proxy/trust-store settings because the base image provided only a runtime and direct Java network resolution failed. Those machine-specific settings and tools are not application dependencies or committed secrets.

## Existing hosted schema: issue #1

The owner's hosted baseline has a different migration ledger and lacks the old decision RPCs. **Do not replay repository history or run `supabase db push` against it.** Use the [gated forward-only reconciliation plan](../supabase/reconciliation/issue-1/README.md): read-only inventory, reviewed preparation that preserves mobile writes, staging verification, and a separately approved cutover. This PR changes no mobile configuration. Historical migration instructions below describe the fresh repository-history path only.

`npm run test:reconciliation` from the repository root verifies the two reconciliation paths on disposable Docker PostgreSQL. New `20261030090000_booking_guard_hardening.sql` extends the fresh-history path with protected-field/account-role transition checks and database-level send/cancel locking, matching the reconciliation preparation. No historical migration is rewritten.

## Approved database cutover

**Do not execute against a hosted database without approval.** Inspect the versioned migrations in `supabase/migrations/`. The existing `20261027120000_booking_decisions_and_messaging.sql` is preserved as history. The new forward migration, `20261027140000_spring_business_api.sql`, reconciles it by:

- Revoking table **and column** business-write privileges from `authenticated`/`anon`.
- Revoking ordinary execution of `respond_to_booking`, `cancel_booking` and `complete_onboarding`. Spring does not call these SECURITY DEFINER functions.
- Adding a dedicated nonprivileged `castly_api` role, narrowly granted columns, API-specific RLS, transaction-safe transition checks and participant name snapshots.
- Retaining Supabase participant SELECT policies and Storage permissions, and adding messages/bookings/conversations to the existing Realtime publication.

After explicit approval, the project database owner applies the versioned migration using the normal reviewed SQL/CLI workflow. The role is initially **NOLOGIN** so no guessed/default password is usable. The owner then provisions its password through a secure prompt/secret manager, for example `ALTER ROLE castly_api LOGIN;` followed by `psql`'s `\password castly_api`. This owner operation is provisioning, not an API connection. Do not grant role memberships, ownership, schema/database CREATE, BYPASSRLS, SUPERUSER, CREATEROLE, CREATEDB, replication or entitlement writes to the login.

The application checks configured usernames/URL overrides before connecting, then verifies the actual role privileges and table ownership before serving requests. A Supabase pooler may require the username `castly_api.PROJECT_REF`; `current_user` must still be `castly_api`. Use a session pool or transaction-capable pooler, TLS `sslmode=verify-full` and the trusted root certificate. `prepareThreshold=0` avoids named prepared-statement conflicts with transaction pooling. No credentials may be embedded in JDBC URL user-info/query parameters. Local loopback test connections may omit TLS.

Each business operation runs in a transaction with **both** standard Supabase JWT claim formats (`request.jwt.claim.sub` and `request.jwt.claims`) plus the role set using transaction-local `set_config`. This prevents identity inheritance through pooled connections, including anonymous reads. SQL parameters are bound; user input never chooses SQL identifiers or database identities.

There is no automatic DDL runner/Flyway startup action. Audit any pre-existing permissive policies/functions outside this repository before rollout; PostgreSQL combines permissive policies. Reconcile and back up existing application data before approving migration application.

## JWT signing configuration

Set `SUPABASE_JWT_ISSUER` to the **exact** project's `https://PROJECT.supabase.co/auth/v1` and expected `SUPABASE_JWT_AUDIENCE` (normally `authenticated`). Do not infer the access-token algorithm from a legacy anon key: a project can retain an HS256 anon key while issuing new ES256 access tokens.

```sh
# Read-only inspection, using SUPABASE_JWT_ISSUER or the existing mobile .env.local URL:
node scripts/check-jwt-config.cjs
```

- `JWT_SIGNING_MODE=jwks`: pins `/auth/v1/.well-known/jwks.json` to that issuer; permits RS256/ES256 only. Nimbus verifies signatures/key rotation. Tokens cannot supply another JWK URL or downgrade to HMAC.
- `JWT_SIGNING_MODE=supabase-auth`: supports legacy HS256 and overlapping RS256/ES256 tokens by asking **that configured project's** `/auth/v1/user` to verify the token, using a publishable/anon API key. The returned user must match `sub`. It fails closed on provider errors; there is no decode-only mode. No HMAC signing secret or service-role credential is needed.

Both paths independently require matching issuer/audience, nonexpired `exp`, valid timestamp constraints, UUID `sub` and global JWT `role=authenticated`. Application Client/Talent roles come from the private database row, not user metadata. Use a real development user token to smoke-test a protected endpoint before deployment; never publish the token or put it in an Expo public variable.

The actual configured project's public JWKS request returned **HTTP 403 from this Cloud environment**. Actual hosted signing keys and real-user token validation therefore remain unverified. Local tests cover cryptographic RS256/ES256 verification, forged signatures, issuer/audience/expiration/role rejection, HMAC downgrade denial and the legacy Auth verification contract. Do not declare hosted compatibility validated until the read-only probe and a real-user request succeed from an authorized environment.

## Start against an approved development database

Export the values from `backend/.env.example` using your shell/secret manager; Spring does not automatically load dotenv files. Backend credentials remain server-only. Then:

```sh
mvn spring-boot:run
curl http://localhost:8080/actuator/health
```

Only health and published discovery/category/portfolio reads are public. All private routes require `Authorization: Bearer USER_ACCESS_TOKEN`. Only exact configured CORS origins are accepted; cookies are not used, so CORS credentials are disabled and CSRF protection is unnecessary for bearer-only endpoints. JSON errors use Problem Details without SQL/token/stack disclosure. Unknown JSON fields, invalid UUID/date/range/length/role values and decimal-to-integer coercions are rejected.

## API contract

JSON requests/responses use **snake_case**, matching the typed mobile service layer. Caller IDs, booking status, sender IDs and agreed rates are not writable request fields. Published filters use query parameters `query`, `category`, `city`, `minRate`, `maxRate`, `available`, `limit`, `offset`.

| Method | Path under `/api/v1` | Behavior |
|---|---|---|
| GET/PATCH | `/account` | Own account / display name only |
| POST | `/account/onboarding` | `{selected_role, account_name}`; primary role immutable after first selection |
| GET | `/account/entitlements` | Own read-only entitlements |
| GET | `/categories`, `/talents`, `/talents/{id}` | Published discovery and filtering |
| GET/POST/PUT | `/talents/me` | Own draft or published profile, creation/editing; 404 when not created |
| GET | `/portfolio`, `/talents/{id}/portfolio` | Published metadata or own assets under RLS |
| POST | `/talents/me/portfolio` | Register own uploaded object metadata |
| PATCH/DELETE | `/portfolio/{id}` | Owner label/order editing/removal |
| GET | `/favourites` | Own favourites |
| PUT/DELETE | `/favourites/{talentId}` | Idempotent own add/remove |
| GET/POST | `/bookings` | Participant listing / Client request creation |
| GET | `/bookings/{id}` | Participant-only details |
| POST | `/bookings/{id}/accept`, `/reject`, `/cancel` | Serialized, validated decisions |
| GET/POST | `/conversations` | Participant listing / open from an accepted `booking_id` |
| GET | `/conversations/{id}` | Participant context and current `can_send` |
| GET/POST | `/conversations/{id}/messages` | Participant history / `{body}` send |

A Client requests published talent. Only the relevant Talent can accept/reject a requested booking. The Client can cancel requested/accepted bookings; either participant can cancel an accepted booking. Terminal decisions cannot be reversed. Repeating the same decision is idempotent. Acceptance and unique per-pair conversation creation commit atomically; opening a conversation again never duplicates it. Messaging requires at least one currently accepted booking for that pair. Send/cancel transactions lock booking rows so a cancellation cannot race a new send past the authorization boundary. Existing history remains participant-readable after cancellation. There is no receipt/read-status or payment claim.

Booking/conversation lists are bounded and offset-paginated (up to 100); message history returns the latest 50 by default, with `before=MESSAGE_UUID` for older pages (up to 100). The client deduplicates confirmed POST/history results in server timestamp order. It never retries a failed network write automatically; uncertain writes should be checked with Refresh before manual retry. Public discovery is capped at 200 profiles/1,000 portfolio rows per fetch. Existing search budget UI is still the approved ₹30,000/day ceiling.

## Mobile setup and fallback

Keep Supabase Auth and Storage variables. Add to the ignored mobile `.env.local`:

```dotenv
EXPO_PUBLIC_API_URL=http://localhost:8080
EXPO_PUBLIC_BUSINESS_API_MODE=spring
```

Use `http://10.0.2.2:8080` for the Android emulator; a physical device needs the developer machine's reachable address and an appropriate development CORS/origin setup. Nonlocal HTTP is allowed only in Metro development; production API URLs must use HTTPS. Never prefix backend database credentials or JWT secrets with `EXPO_PUBLIC_`.

The existing direct Supabase implementation is retained **only** with `EXPO_PUBLIC_BUSINESS_API_MODE=supabase` and Metro `__DEV__=true`. It is rejected in production/exported builds. It is an explicit development mode, not an automatic fallback after API errors. It targets a **separate pre-cutover development database**: the approved cutover deliberately removes those old direct write grants. Do not restore direct client write privileges on the API-backed database to make fallback work. Mock mode without credentials remains unchanged.

Realtime subscriptions send the current Supabase user JWT, filter by booking participant/conversation, refresh auth when sessions change, and remove channels/listeners/timers on cleanup. Only INSERT and booking UPDATE events are subscribed; DELETE events are deliberately excluded. Event payloads only invalidate authorized Spring reads. RLS is the final delivery boundary. Reconnect/foreground/manual refresh and 15-second foreground polling recover API state if Realtime is unavailable. The interface explicitly reports unavailable live updates. **Actual hosted Realtime delivery is not validated**: no approved hosted migration or gateway access was available. Native AppState/deep-link/device behavior also needs device tests.

## Browser/API regression

Build the fixture app from the repository root; these are local synthetic values, not hosted credentials:

```sh
EXPO_PUBLIC_BACKEND_MODE=supabase \
EXPO_PUBLIC_BUSINESS_API_MODE=spring \
EXPO_PUBLIC_SUPABASE_URL=http://127.0.0.1:8098 \
EXPO_PUBLIC_SUPABASE_ANON_KEY=sb_publishable_local_fixture \
EXPO_PUBLIC_API_URL=http://127.0.0.1:8080 \
npm run export:web -- --output-dir /tmp/castly-spring-web
WEB_EXPORT_DIR=/tmp/castly-spring-web PORT=8086 npm run preview
# Another terminal; run mvn clean verify first to build the jar:
npm run test:backend
```

The browser suite starts a local Auth/JWKS fixture, the actual packaged Java API and PostgreSQL. It exercises real Spring HTTP/signature validation/RLS from the rendered application. It stops its Java/Auth fixture on completion; the static preview and disposable DB can be stopped separately. Set `CASTLY_JAVA` if Java is not on PATH. Keep test ports free. The fixture does not implement Supabase Realtime; manual/poll recovery is tested, not hosted WebSocket delivery.

The original M1 HTTP contract suite remains `npm run test:legacy-backend`, run against a Metro development web server configured for the explicit Supabase fallback and `https://castly-test.supabase.co`/test publishable key. Its requests are intercepted; it does not mutate a hosted project. `npm run test:sql` now runs cutover policies; the old M1 policy file is preserved as history for pre-cutover development.

No main merge, production deployment, hosted migration or payment enablement is part of this work.
