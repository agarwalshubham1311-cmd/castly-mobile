# Milestone 3: Expo ↔ Spring development setup

This branch is for review and local/staging validation. No hosted migration, permission/password/ledger change, cutover, production deployment, payment work or mobile release is included. Keep the owner's original worktree and existing hosted/mobile configuration unchanged. The issue reports `04-verify.sql` passing with `cutover=f`; that does not authorize running cutover or replaying migrations.

## Service boundaries and environments

Spring owns account/onboarding, talent/portfolio metadata, published discovery/categories, favourites, booking decisions, accepted-booking conversations and message history/sending. Supabase owns Auth/session persistence, direct Storage uploads/signing and Realtime invalidation. The API uses snake_case `/api/v1` contracts. The user identity comes from the verified bearer JWT, never an account ID supplied by the UI. No API failure falls back to PostgREST/RPC writes.

Mock mode preserves the Lavender Pearl local demo. With Supabase configured, Spring is the default business mode. The legacy fallback additionally requires **both** Metro development and `EXPO_PUBLIC_ISOLATED_SUPABASE_FALLBACK=true`, and must target a separately isolated pre-cutover development DB. Do not set that acknowledgement for the hosted API database. Exported/production builds reject Supabase business fallback.

Keep client-only settings in an ignored mobile `.env.local`, based on `.env.example`. Do not edit a coworker's existing worktree/environment file. Use Supabase publishable/anon credentials only. Never put a database password, service-role/secret key, signing secret or user token in an `EXPO_PUBLIC_` setting, screenshot, log or Git file.

| Client | `EXPO_PUBLIC_API_URL` example | Meaning |
|---|---|---|
| Android Studio emulator | `http://10.0.2.2:8080` | Emulator alias for the Windows host; not a physical-device address |
| iOS simulator on a Mac running Spring | `http://localhost:8080` | The simulator's Mac host |
| Web browser on the Spring computer | `http://localhost:8080` | That computer |
| Physical Android/iPhone, Spring on Windows LAN | `http://192.168.1.20:8080` | Replace with Windows Wi-Fi/Ethernet IPv4; same private network |
| Hosted/release API | `https://YOUR_APPROVED_API_HOST` | Trusted HTTPS, no credentials/query/path prefix in the URL |

**localhost on a physical phone points to the phone, not Windows.** `10.0.2.2` works only with the Android emulator. If an iOS simulator is on another Mac, use the Windows LAN address. Expo's Metro QR URL is not the Spring API URL. An Expo tunnel transports Metro assets; it does not tunnel Spring port 8080.

Only explicit development allows arbitrary LAN HTTP; production nonlocal APIs require HTTPS. Loopback/emulator HTTP remains available for local export fixtures. No release cleartext policy was added. A development build/device that blocks cleartext needs a trusted HTTPS development endpoint or a separately reviewed native debug configuration; do not disable TLS validation globally. URL changes require restarting Expo (`--clear`); they are bundled at build time, not fetched from the server.

## Windows: run the existing Java 21 backend

Use PowerShell in a dedicated checkout/worktree. Install JDK 21, Maven 3.9+, Node 22.13+ (or newer supported LTS), and Docker Desktop if running disposable integration tests. `java` and `javac` must both be Java 21:

```powershell
java -version
javac -version
mvn -version
node --version
Set-Location C:\work\castly-mobile\backend
```

Spring does not load `.env` automatically. Export server-only variables in this PowerShell process/approved secret manager. The following are placeholders, not hosted values to copy verbatim:

```powershell
$env:DATABASE_URL = 'jdbc:postgresql://YOUR_APPROVED_DB_OR_POOLER_HOST:5432/postgres?sslmode=verify-full&sslrootcert=C:/Users/YOU/.postgresql/root.crt&prepareThreshold=0'
$env:DATABASE_USERNAME = 'castly_api'
# If the approved Supabase pooler requires it: castly_api.PROJECT_REF
$castlyDbPassword = Read-Host 'Dedicated castly_api password' -AsSecureString
$env:DATABASE_PASSWORD = [System.Net.NetworkCredential]::new('', $castlyDbPassword).Password
$env:SUPABASE_JWT_ISSUER = 'https://YOUR_PROJECT.supabase.co/auth/v1'
$env:SUPABASE_JWT_AUDIENCE = 'authenticated'
$env:JWT_SIGNING_MODE = 'jwks'
$env:PORT = '8080'
$env:CORS_ALLOWED_ORIGINS = 'http://localhost:8081,http://127.0.0.1:8081'
Test-Path 'C:\Users\YOU\.postgresql\root.crt'
mvn spring-boot:run
```

Obtain the **correct trusted CA chain** from the approved Supabase/provider source and save a PEM root certificate at the exact `sslrootcert` path. Java/PostgreSQL JDBC requires remote `sslmode=verify-full`: both CA trust and hostname must verify. The owner's current missing `root.crt` error is a server-side JDBC prerequisite, independent of the mobile code. Keep verification enabled; do not replace it with `sslmode=require`, `ssl=false`, nonvalidating factories, or owner/admin credentials. Windows paths inside JDBC URLs should use forward slashes; use a readable path without ambiguous URL characters. Passwords stay out of the JDBC URL and Expo. Stop the process/close its shell and clear server-only environment values when finished.

The login must remain the dedicated non-admin `castly_api`, with no role memberships granted to it, table ownership, CREATE or BYPASSRLS. Preserve the reviewed Supabase-managed `postgres` membership exception from the owner's issue-1 workstream; do not alter hosted roles or provision LOGIN from this task. After the owner's trusted CA setup succeeds, check the real role/pooler and JWT configuration through the existing server startup checks. Use the exact approved endpoint and port (including 6543 for transaction pooling where the project specifies it). Use session/transaction pooling as documented in `backend/README.md`; `prepareThreshold=0` avoids named prepared-statement conflicts.

Spring listens on available interfaces by default unless the owner's configuration overrides `server.address`. From another Windows terminal:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/api/v1/categories
ipconfig
```

Find the active adapter's IPv4 (not a VPN/WSL/Docker address). Open `http://YOUR_WINDOWS_LAN_IP:8080/actuator/health` in the Android phone's browser. This checks network reachability only; it is not authenticated end-to-end proof. If needed, the owner can allow **private local-subnet** development traffic from an elevated PowerShell:

```powershell
New-NetFirewallRule -DisplayName 'Castly local API 8080' -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Private -RemoteAddress LocalSubnet
# Remove after development:
# Remove-NetFirewallRule -DisplayName 'Castly local API 8080'
```

Do not expose the database or API publicly for this smoke test. Wi-Fi client isolation, VPN routing, firewall rules and WSL/container port forwarding can prevent LAN access; fix reachability before testing Auth. If using WSL rather than Windows Java, explicitly arrange host forwarding; the Windows commands above assume Java runs on Windows.

### JWT signing mode, without shared signing secrets

Set the issuer to the project's exact `/auth/v1`, with audience normally `authenticated`. Do not infer active access-token signing from an old anon key. Owner inspects Auth signing configuration and may run the read-only `npm run test:jwt-config` from the repo root; Cloud's prior HTTP 403 is not evidence of the current algorithm.

- `jwks`: server verifies RS256/ES256 signatures using that issuer's pinned public JWKS.
- `supabase-auth`: explicitly choose this for legacy HS256/overlap verification through that project's `/auth/v1/user`. Set **server-only** `SUPABASE_PUBLISHABLE_KEY` to the publishable/anon key. No service-role key or JWT signing secret is required.

The backend additionally validates issuer, audience, expiration, authenticated global role and UUID subject. App Client/Talent roles come from the private account row. Validate with a real development user in the app; never paste that token into docs, logs, screenshots or environment templates. A health/public-category response alone does not validate Auth, RLS or the business API.

## Windows + physical Android Expo Go

In another PowerShell terminal at the repository root, create **only your isolated checkout's** ignored `.env.local`:

```dotenv
EXPO_PUBLIC_BACKEND_MODE=supabase
EXPO_PUBLIC_BUSINESS_API_MODE=spring
EXPO_PUBLIC_SUPABASE_URL=https://YOUR_PROJECT.supabase.co
EXPO_PUBLIC_SUPABASE_ANON_KEY=YOUR_PUBLISHABLE_OR_ANON_KEY
EXPO_PUBLIC_API_URL=http://192.168.1.20:8080
EXPO_PUBLIC_ISOLATED_SUPABASE_FALLBACK=false
```

Replace the LAN address with your Windows adapter's actual IPv4 and the two Supabase public settings with approved client values. Database credentials stay only in the server terminal. Then:

```powershell
npm ci
npx expo start --lan --clear
```

Install a compatible Expo Go version (this repository uses Expo SDK 58) on Android, connect phone and computer to the same private Wi-Fi, and scan the Metro QR code. Accept photo permissions only when testing portfolio uploads. If the installed Expo Go does not support this SDK, use a compatible development build; do not silently upgrade the project. Emulator users change only the API URL to `http://10.0.2.2:8080` and can use `npx expo start --android --clear`.

Native requests usually have no browser Origin and do not require CORS whitelisting the phone. For Expo **web**, include the exact Metro origin (including LAN hostname/port if used) in `CORS_ALLOWED_ORIGINS`; restart Spring after changing it. Do not use wildcard origins or cookie credentials. A browser CORS error and a phone's connection-refused error have different causes.

## Ordered manual end-to-end smoke test

Use two separate development accounts/devices or sessions: one Client and one Talent. Do not change their immutable primary roles. Perform this on the owner's approved prepared/staging environment; do not run cutover SQL.

1. Confirm JDK21, trusted JDBC CA, restricted Hikari connection, health/categories, phone-to-LAN reachability and correct app environment. Ensure Spring mode; do not enable the legacy fallback to hide an API error.
2. Sign up or sign in through Supabase Auth; complete email confirmation when enabled. Onboard a new account once as Client and another as Talent. Verify restarting the app restores the same session/account and that another role cannot be selected later.
3. Rename each account in Settings and reload. Verify each sees only its own account/entitlements/shortlist; a server-side 403 is an error, not an empty success.
4. As Talent, save a draft profile. Ensure it is absent from anonymous Discovery. Edit rate/category/city/skills, upload a portrait and portfolio photograph directly to Storage, save caption/order through Spring, then publish.
5. As Client (and as a signed-out user), refresh Discovery. Test category, name/skill query, city, min/max rate and availability filters, sorting and clearing. Catalog paging must not silently omit profiles after the first page. Search/Filters operate over the complete loaded public catalog; the typed service also exposes Spring query filters. Large catalogs have a bounded traversal and explicit error rather than hidden truncation.
6. Open the shared `/talent/UUID` and `/gallery/UUID` URL directly after a fresh launch, without first visiting Discovery. Check published profile/metadata and portrait cropping. Unpublished/missing profiles show an honest unavailable state. Storage failure should leave text visible with photo fallback/retry.
7. Save/unsave talent; reload Saved and restart. Sign out and into the other account: the previous user's saved state must disappear. A failed write must not show successful saving or trigger a PostgREST fallback.
8. As Client, request booking with future date, location, project requirements and whole-rupee budget. Validate invalid/empty dates and fields; confirm the request appears as Requested. Talent accounts must not create requests.
9. As relevant Talent, inspect details and accept. As Client, refresh and open the accepted-booking conversation. Reject another request and cancel another pending request as Client. Talent cannot cancel a merely requested booking; accepted bookings can be cancelled by either participant. Terminal records cannot reopen. Repeated same decisions remain idempotent.
10. Open Bookings/Messages and load subsequent pages if available. Change a first-page booking from the other account and refresh/reconnect while viewing later pages; loaded records must reflect the new state. Completed bookings have a filter.
11. Open the conversation from each account; send both ways, refresh, and load older history. Verify timestamps, no duplicate messages, no invented read/delivery claims, and no composer access for an unrelated account/direct URL.
12. Test hosted Realtime while both apps are foregrounded, then background/foreground and reconnect. Events invalidate Spring reads; API/RLS are still authoritative. If live delivery is unavailable, verify the visible status plus manual refresh/15-second foreground poll. This fixture does not prove hosted Realtime delivery.
13. Cancel the accepted booking. Verify both sides retain message history but sending pauses unless another accepted booking for the same pair exists. Test sending while cancellation occurs: only a send authorized before cancellation commits should succeed. A 409 must refresh state and preserve the draft, not fabricate a send.
14. Temporarily stop the **local** API or disable phone networking. Verify loading/error/retry states on catalog/account/booking/chat, preserving editable drafts where applicable. Restore connectivity and retry reads. For an uncertain POST, refresh history/bookings before manually retrying; writes are not retried after network failures.
15. In an isolated test environment, expire/revoke the development session. A private API 401 refreshes once for the same subject; terminal expiry returns to sign-in with a notice. A 403 remains an authorization error. Switching accounts during a pending request must not apply the old account's result to the new session.
16. Remove a portfolio photograph, reload metadata/gallery, and verify draft/private media remain owner-only. If registration failed after Storage upload, reload saved metadata before retrying; an unconfirmed registration does not automatically delete an uploaded object that may already be referenced. Unreferenced private upload cleanup is a manual follow-up.
17. Run native Android checks: system font scaling, TalkBack, keyboard/date picker, safe areas, media permission denial/retry, app restart/deep links and foreground subscription recovery. Record device/OS/build and actual results without tokens or private data. Browser tests/Expo exports do not establish native behavior.

Keep backend/database schema and owner worktree unchanged during these steps. Any hosted changes, password policy setting, mobile release or cutover require the owner's separate explicit approval.

## Deterministic Cloud/local regression

`npm test` exercises transport/DTOs, same-user refresh, account-switch rejection, error handling, pagination and service boundaries without credentials. `npm run test:mobile-api` uses Playwright HTTP fixtures for Auth/Storage/Spring, including a 201-profile catalog, deep links, retries, 401/403, onboarding/account and metadata editing; it asserts zero direct PostgREST/RPC requests. Build the local fixture app as documented in `backend/README.md`, preview it on 8086, then run it. `npm run test:backend` instead starts the actual Java API plus disposable PostgreSQL and a local signed Auth/JWKS fixture; it now also tests a real 401/refresh round trip. No hosted credentials or database URL is used. See the issue-3 validation report for commands, actual results and remaining blockers.
