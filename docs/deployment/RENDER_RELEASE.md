# Castly Render and EAS release checkpoint

Prepared for `feat/milestone5-booking-messaging-notifications`. This is a preparation runbook, **not approval to deploy, build on EAS, migrate hosted PostgreSQL, or enable notifications**. The existing Spring business API, Supabase Auth/Storage/Realtime, Lavender Pearl screens, JWT verification and transaction-local `private.request_user_id()` identity remain the architecture.

## 1. Review before any cloud action

Review the final Git SHA, this runbook, `render.yaml`, optional `render-web.yaml`, `eas.json`, and the validation report. Confirm the Render owner, service names, region, paid plan, exact Supabase endpoint/CA, existing JWT signing mode, allowed origins, owned Android package and EAS project UUID. `castly-api`, `castly-web`, Singapore and Starter are proposed settings, not provisioned resources or confirmed URLs. Choose a region near the actual Supabase project; preserve its current region and endpoints.

**STOP for the reviewed release checkpoint before clicking Create/Apply Blueprint or Manual Deploy.** Creating/applying a Blueprint can perform its initial deployment even with `autoDeployTrigger: "off"`. No service was created by this change. Future Git pushes must not automatically deploy. No pre-deploy/migration command belongs in either service. No hosted SQL has been executed.

## 2. Local release verification — Windows PowerShell 7

Prerequisites: Git, Node 24 (`package.json` enforces >=24 <25), npm, Java 21 JDK, Maven 3.9+, Docker Desktop using Linux containers, Chromium. Maven and Java must be on PATH. These commands are run from the repository root in a **test-only terminal without hosted database variables**. Node and Java must use the same normal Windows temporary directory. Do not point the fixture container names at real data: these scripts recreate disposable databases.

```powershell
git switch feat/milestone5-booking-messaging-notifications
git status --short
git rev-parse HEAD
java -version
mvn -version
node --version
docker version
npm ci
npm run typecheck
npm run lint
npm test
npm run test:components

# The Java suite creates/uses only local castly-spring-test-db on 127.0.0.1:55432.
mvn -f backend/pom.xml clean verify
if ($LASTEXITCODE -ne 0) { throw 'Fresh Java suite failed' }
$env:CASTLY_TEST_SCHEMA_PATH = 'reconciliation'
try {
  mvn -f backend/pom.xml clean verify
  if ($LASTEXITCODE -ne 0) { throw 'Reconciled Java suite failed' }
} finally { Remove-Item Env:CASTLY_TEST_SCHEMA_PATH -ErrorAction SilentlyContinue }

# SQL policy tests: never set SUPABASE_TEST_DATABASE_URL to a hosted database.
Remove-Item Env:SUPABASE_TEST_DATABASE_URL -ErrorAction SilentlyContinue
Remove-Item Env:CASTLY_TEST_DB_CONTAINER -ErrorAction SilentlyContinue
docker inspect castly-phase2-policy-db *> $null
if ($LASTEXITCODE -ne 0) {
  docker run --name castly-phase2-policy-db -e POSTGRES_PASSWORD=local-test-only -d postgres:17-alpine
} else { docker start castly-phase2-policy-db }
$ready = $false
for ($n = 0; $n -lt 30; $n++) {
  docker exec castly-phase2-policy-db pg_isready -U postgres *> $null
  if ($LASTEXITCODE -eq 0) { $ready = $true; break }
  Start-Sleep -Seconds 1
}
if (-not $ready) { throw 'Disposable PostgreSQL did not start' }
npm run test:sql
if ($LASTEXITCODE -ne 0) { throw 'SQL security suite failed' }

docker build -t castly-api:release-check backend
if ($LASTEXITCODE -ne 0) { throw 'Docker build failed' }
docker image inspect castly-api:release-check --format '{{.Config.User}}'
# Expected 10001:10001. Docker does not run integration tests or migrations.
```

Each test command must finish successfully; PowerShell does not inherently stop on a nonzero native exit code. Use the Java/SQL checks above and check `$LASTEXITCODE` for npm checks before continuing.

For browser checks, install Chromium and supply its actual executable path (the Linux default `/usr/bin/chromium` is not a Windows path):

```powershell
$env:CHROMIUM_PATH = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
$env:EXPO_NO_DOTENV = '1'
$env:EXPO_PUBLIC_BACKEND_MODE = 'mock'
$env:EXPO_PUBLIC_BUSINESS_API_MODE = 'spring'
npm run export:web
# Terminal A, keep running:
$env:PORT = '8082'
npm run preview
```

In another test-only terminal at the same repository, set the same `CHROMIUM_PATH`, then:

```powershell
$env:CHROMIUM_PATH = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
$env:TEST_URL = 'http://127.0.0.1:8082'
npm run test:ui
npm run test:screenshots
npm run test:browser
npm run test:portraits
npm run test:accessibility
```

The existing UI/screenshot scripts write to tracked `artifacts/screenshots/`; review/copy their outputs and discard only generated screenshot changes if this is a deployment-only change. Stop the preview, then rebuild its export in **test-only HTTP fixture mode** for the real Spring/PostgreSQL browser suite. Do not use this fixture configuration in release builds:

```powershell
Remove-Item Env:CASTLY_RELEASE_CHECK -ErrorAction SilentlyContinue
$env:EXPO_NO_DOTENV = '1'
$env:EXPO_PUBLIC_BACKEND_MODE = 'supabase'
$env:EXPO_PUBLIC_BUSINESS_API_MODE = 'spring'
$env:EXPO_PUBLIC_SUPABASE_URL = 'http://127.0.0.1:8098'
$env:EXPO_PUBLIC_SUPABASE_ANON_KEY = 'sb_publishable_fixture'
$env:EXPO_PUBLIC_API_URL = 'http://127.0.0.1:8080'
npm run export:web
$env:PORT = '8086'
npm run preview
```

Other terminal with Chromium configured:

```powershell
$env:TEST_URL = 'http://127.0.0.1:8086'
$env:CASTLY_SCREENSHOT_DIR = Join-Path $env:TEMP 'castly-render-api-screenshots'
npm run test:backend
npm run test:mobile-api
```

The API test starts local Auth/JWKS fixtures and Spring itself, and resets the disposable PostgreSQL database. Ports 8080, 8086, 8098 and 55432 must be free. Do not run Maven and API browser tests simultaneously against their shared fixture. Real JWT verification, acceptance, conversation creation, two-way messages and notifications are tested locally; these do not certify hosted Realtime transport or an Android device.

## 3. Backend Render dashboard — after checkpoint approval only

Either apply **only `render.yaml`**, or create a Web Service with:

| Setting | Value |
| --- | --- |
| Repository / branch | `agarwalshubham1311-cmd/castly-mobile` / `feat/milestone5-booking-messaging-notifications` at reviewed SHA |
| Runtime | Docker |
| Root Directory | `backend` |
| Dockerfile Path / Docker Context | `./Dockerfile` / `.` relative to Root Directory |
| Plan / region / instances | Reviewed choice; proposed Starter / Singapore / 1 |
| Health Check Path | `/actuator/health` |
| Auto-Deploy | Off; manual deploy reviewed commit only |
| Docker command override | Leave blank; image runs Java directly as UID/GID 10001 |
| Pre-deploy / migrations | Leave blank |
| PORT | Let Render inject it; application binds `0.0.0.0:${PORT}` |

The image compiles using Maven/Java 21 and runs on Temurin Java 21 JRE. It contains application classes/resources only, no `.env`, tests, migration SQL or credentials. The runtime is non-root and supports graceful SIGTERM shutdown. JVM heap is capped at 65% of container memory; size the reviewed plan from actual memory/latency measurements. Maven dependencies require Maven Central DNS/network access during the Render build. No SQL initializer runs at startup (`spring.sql.init.mode=never`); no Flyway/Liquibase dependency is introduced.

### Backend environment (server service only)

Use `deploy/backend.render.env.example` as a blank reference, not a deployable filled file. Enter secrets in the dashboard, not Git, screenshots, support logs, command arguments or the JDBC URL.

| Name | Required value |
| --- | --- |
| `DATABASE_URL` | Actual approved JDBC endpoint, database and TLS parameters below |
| `DATABASE_USERNAME` | Dedicated pooler login, normally `castly_api.<project-ref>`; direct endpoint normally `castly_api` |
| `DATABASE_PASSWORD` | Password for **castly_api only** |
| `SUPABASE_JWT_ISSUER` | Exact current project Auth issuer `https://<project-ref>.supabase.co/auth/v1` |
| `SUPABASE_JWT_AUDIENCE` | Existing expected audience, normally `authenticated` |
| `JWT_SIGNING_MODE` | Preserve actual working mode: `jwks` for supported asymmetric JWTs, `supabase-auth` for existing legacy verification via Auth. Do not switch signing configuration during this release. |
| `SUPABASE_PUBLISHABLE_KEY` | Only if existing `supabase-auth` mode requires it: current public publishable/anon key. Never service-role/secret key. |
| `CORS_ALLOWED_ORIGINS` | Exact comma-separated approved web origins, no wildcard or paths; native requests have no browser origin and remain JWT-protected |
| `CASTLY_NOTIFICATIONS_ENABLED` | **`false`** for initial deployment |
| `CASTLY_BOOKING_DIAGNOSTICS_ENABLED` | `false` normally; temporary sanitized diagnostics only when deliberately reviewed |

Do not add `SUPABASE_JWT_SECRET`, owner/admin credentials, `service_role`, role memberships, auth schema grants, `SECURITY DEFINER` or RLS bypass. Startup rejects a privileged/current role other than `castly_api`. JWT signature, issuer, expiry and audience checks remain in place; actor UUID binding stays transaction-local and is not accepted from request body fields.

### Supabase endpoint and TLS

Use Supabase Dashboard **Connect**, verify the current project reference/region/database and select **Session pooler**, normally port **5432**. Copy the current hostname; do not reuse a guessed historical region hostname. Change the username to the project-approved dedicated `castly_api.<project-ref>` login and use its password, not the dashboard owner password. A session pooler is appropriate for the persistent Hikari pool (existing maximum 8 connections per instance); confirm pooler/project connection capacity before scaling. A direct endpoint is an alternative only with verified Render reachability (including IPv6 if required). Transaction pooler 6543 is not the default here; if separately approved, retain transaction-local binding, `prepareThreshold=0`, and rerun the security/identity isolation suite against it.

Example **shape**, not an actual endpoint:

```text
jdbc:postgresql://<approved-session-pooler-host>:5432/postgres?sslmode=verify-full&sslrootcert=/etc/secrets/supabase-root.crt&prepareThreshold=0&connectTimeout=10&tcpKeepAlive=true
```

Obtain the trusted CA from the official current project connection instructions and add it as Render **Secret File** named `supabase-root.crt`, available at `/etc/secrets/supabase-root.crt`. Verify UID 10001 can read it. Use URL encoding if certificate paths contain reserved characters. Preserve `sslmode=verify-full`; do not replace the hostname with an IP or use a nonvalidating SSL factory. No CA/private key is committed. `pg_stat_ssl` describes the database-side socket; behind a pooler it does **not** certify the client-to-pooler hop. JDBC full hostname/CA verification provides that protection.

### Read-only connection verification

The new checker opens an explicitly read-only transaction, verifies the actual restricted database role and invoker helper, checks absence of a stale subject, and reports schema readiness. It performs **no migrations, business writes or claim binding** and prints only booleans/endpoint kind/SQLSTATE/error class. Do not paste passwords or raw JDBC exceptions into support logs.

On Render Shell (after approved initial deployment):

```sh
java -Dloader.main=com.castly.tools.DatabaseConnectionCheck -cp /app/castly-api.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

Or Windows PowerShell in a separate **server-only** terminal after setting the approved non-secret URL/username and local trusted CA path:

```powershell
$env:DATABASE_URL = Read-Host 'Approved JDBC URL (no credentials; verify-full and trusted local CA path)'
$env:DATABASE_USERNAME = Read-Host 'Dedicated castly_api login'
$env:CASTLY_NOTIFICATIONS_ENABLED = 'false'
$securePassword = Read-Host 'castly_api password' -AsSecureString
try {
  $env:DATABASE_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password
  java '-Dloader.main=com.castly.tools.DatabaseConnectionCheck' -cp backend/target/castly-api-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher
  if ($LASTEXITCODE -ne 0) { throw 'Read-only database verification failed; do not deploy further' }
} finally {
  Remove-Item Env:DATABASE_PASSWORD -ErrorAction SilentlyContinue
  $securePassword.Dispose()
}
```

A successful check should report role/private identity/read-only true, notification flag false. `notification_schema_ready=false` is expected before the pending migration and must not block baseline booking/message deployment. DNS failure occurs before SQL authentication; diagnose from the same host using `Resolve-DnsName <actual-host>` on Windows. Check Render logs/provider status and retry after connectivity returns; pool lifetime tuning cannot fix `UnknownHostException`. Preserve TLS and credentials. No hosted connection was verified in this workspace because hosted connection variables were not supplied.

### Initial service checks

```powershell
$api = Read-Host 'Actual approved Render HTTPS origin (no trailing slash)'
Invoke-RestMethod "$api/actuator/health"  # 200, exactly minimal status UP
$r = Invoke-WebRequest "$api/api/v1/account" -SkipHttpErrorCheck
if ([int]$r.StatusCode -ne 401) { throw 'Private API not protected' }
```

Health deliberately includes database connectivity and exposes no components/details. A 503/failed startup is a release blocker; do not change health to unconditional UP. Confirm expected CORS from the approved web origin; arbitrary origins must fail. Using existing client/talent test accounts, verify session restoration, own account/profile, discovery, saving, request → accept/decline/cancel, a single conversation after acceptance and two-way message history/send. Do not put bearer tokens in shell history; use the app and sanitized server references. Verify unrelated accounts cannot read bookings/conversations/messages. With notifications disabled, the app must retain its existing unavailable/empty notification behavior without breaking bookings/messages. Check logs for role/JWT/DB failures and do not publish raw JDBC errors.

## 4. Expo release environment and EAS — separate public build environment

Start a new terminal with **no server database variables**. `EXPO_PUBLIC_*` values are public and compiled into the app/web bundle; they are not secrets. `deploy/mobile.render.env.example` lists all required values. Replace placeholders with the existing Supabase Auth project and the **actual approved Render origin**, without `/api/v1` (the typed service adds it). Keep `EXPO_PUBLIC_BUSINESS_API_MODE=spring` and `EXPO_PUBLIC_ISOLATED_SUPABASE_FALLBACK=false`; no business API switch or mobile redesign is needed.

```powershell
$env:CASTLY_RELEASE_CHECK = '1'
$env:EXPO_NO_DOTENV = '1'
$env:EXPO_PUBLIC_BACKEND_MODE = 'supabase'
$env:EXPO_PUBLIC_BUSINESS_API_MODE = 'spring'
$env:EXPO_PUBLIC_ISOLATED_SUPABASE_FALLBACK = 'false'
$env:EXPO_PUBLIC_API_URL = Read-Host 'Approved Render API HTTPS origin'
$env:EXPO_PUBLIC_SUPABASE_URL = Read-Host 'Existing Supabase project HTTPS origin'
$env:EXPO_PUBLIC_SUPABASE_ANON_KEY = Read-Host 'Public Supabase publishable/anon key only'
$env:EXPO_EAS_PROJECT_ID = Read-Host 'Actual linked EAS project UUID'
$env:CASTLY_ANDROID_PACKAGE = Read-Host 'Owned Android application ID, stable for future releases'
npm run check:release-env
npx expo config --type public
npm run export:web
```

The guard rejects incomplete release flags, local/non-HTTPS/placeholder origins, privileged client keys and server-only credentials in the public build environment. Config requires the real EAS UUID and owned reverse-DNS Android package for cloud EAS builds; neither is invented in source. The dynamic config preserves existing Castly icons, permissions, plugins and `castly` scheme. Before running EAS commands, use the Expo dashboard to create/link the owner's actual project; verify its UUID. Enter the same public values, `EXPO_EAS_PROJECT_ID` and `CASTLY_ANDROID_PACKAGE` in **both preview and production EAS environments** using plaintext visibility. They must also be available locally while EAS evaluates config. The checked-in profiles supply the release flags; never import a backend env group. Remote version management supports auto-increment with dynamic app config. Review the initial remote Android version code for the actual package.

After explicit release/build checkpoint approval, commands from that configured terminal:

```powershell
npx eas-cli@latest login
npx eas-cli@latest project:info
npx eas-cli@latest build:version:get --platform android --profile preview
npx eas-cli@latest build --platform android --profile preview
# Internal distribution APK, install on actual Android device for acceptance.
# Only after preview acceptance and another approved production build checkpoint:
npx eas-cli@latest build --platform android --profile production
# Store AAB only. No automatic store submission is configured.
```

Choose the approved Android signing/keystore ownership in EAS; never commit keystores. EAS login, cloud builds, APK/AAB installation, signing and Play upload were **not** performed here. Browser tests do not establish Android safe areas, keyboards, native date picker, secure session lifecycle, background/resume, deep links or native WebSocket reconnection. Test those on a real device/development build, including slow/offline network and signout/account-switch isolation. Preview is an installable APK; production emits an AAB and must be tested through a reviewed Play internal track before release.

## 5. Optional web — separate Render Static Site

Apply **only `render-web.yaml`** after its own checkpoint. Runtime Static Site, repository root, reviewed branch/SHA, build `npm ci && npm run check:release-env && npm run export:web`, publish `dist-web`, auto-deploy Off. Supply only public Expo environment values from section 4; never database credentials/server env groups. The blueprint pins Node 24.19.0, explicit Spring mode and SPA rewrite to `/index.html`; public asset hashes receive immutable caching. Confirm the provider accepts the Blueprint in its dashboard before creating resources; its remote schema could not be downloaded in this workspace (HTTP 403).

Add the **actual static site's HTTPS origin** to reviewed backend CORS. Review Supabase Auth redirect allowlist additions for the actual `https://<web-origin>/auth/callback` and native `castly://auth/callback`; preserve all existing working entries. Do not execute Auth configuration changes automatically. Test direct refresh of `/search`, `/bookings` and `/auth/callback`, sign-in/out, image/font loading, API CORS and offline errors. Changing a compiled `EXPO_PUBLIC_*` value requires a new approved web/native build, not a runtime database migration.

## 6. Notifications deployment order — separate migration approval

Initial backend deployment stays **`CASTLY_NOTIFICATIONS_ENABLED=false`**. Preserve already-working booking acceptance, conversation creation and two-way messaging. Hosted `private.request_user_id()` already exists; the pending notification tables/triggers do not. No mobile or backend flag may bypass that dependency.

1. Review the actual hosted inventory/ledger, current identity helper, restricted grants and notification absence using the existing [notification deployment report](../milestone5/invoker-notifications-deployment.md). Confirm backups/rollback procedure and exact SQL hash.
2. Approve and deploy the baseline API at the reviewed SHA with notifications false; verify health and the working booking/message path. Optional web/EAS builds have their own checkpoint.
3. Rerun fresh/reconciled Java, SQL policy and notification negative/concurrency/rollback tests on the reviewed SQL; optionally rehearse against a **disposable** staging copy with matching permissions. Validate the actual Realtime publication/replication configuration and recipient JWT policies.
4. Obtain explicit hosted migration approval. Apply **only** `supabase/migrations/20261107170000_invoker_notifications.sql` via the reviewed owner-operated SQL procedure. No unrestricted `supabase db push`, no older `20261102100000_in_app_notifications.sql` proposal, no auth schema USAGE proposal, no replay of unrelated migrations. Do not edit historical SQL or blindly repair the migration ledger. Any hosted drift requires renewed review.
5. Verify invoker modes, column-scoped trigger grants, private helper grants, recipient-only list/count/read policies, all five event types, unique event indexes, source-event rollback and restricted negative writes. Verify publication includes notifications; test two real account subscriptions and reconnect/account switching. The checker must now show `notification_schema_ready=true`.
6. Only after those checks and owner approval, set `CASTLY_NOTIFICATIONS_ENABLED=true` and manually deploy the approved API. Test list, unread count, mark read, booking requested/accepted/declined/cancelled and message received, no duplicates and unauthorized isolation. Client Realtime invalidates/refetches authoritative API results; direct database business writes remain disabled.
7. If notification verification fails, set flag false and manually redeploy the approved baseline API. **This flag disables API notification functionality; it does not remove installed database triggers.** A trigger failure can roll back booking/message writes, so stop and use an explicitly reviewed targeted database recovery if needed. Never promise that a flag alone rolls back a migration or masks a broken trigger.

No hosted migration, Render deployment, EAS build or live Realtime claim is authorized by this document alone.
