Complete Milestone 3 Expo ↔ Spring integration while retaining the existing Lavender Pearl screens. Existing business routes already used Spring, but catalog paging/deep links, category data, session expiry, stale loaded pages and portfolio metadata editing were incomplete.

Changes:
- `src/backend/{api,index,composition,platform,tokens,sessionEvents}`: typed snake_case HTTP contracts, lazy Spring-only business composition, isolated dev fallback acknowledgement, shared refresh, targeted expiry, expected-subject guards for delayed writes and rejection of cross-account results. Supabase remains Auth/Storage/Realtime.
- Catalog/provider/profile hook and Discovery/Search/Filters/Saved/Talent/Gallery: complete bounded catalog paging, API categories, direct published-profile/gallery reads and resilient media/error/retry handling without a redesign.
- Auth, Account, TalentEditor and LiveActivity: role/session guards, complete counts, caption/order editing, confirmed-only updates, paginated activity refresh, booking state controls, chat conflict recovery and booking-driven Realtime invalidation.
- Public Spring `/portfolio` accepts validated limit/offset with the previous default retained; integration test added. No SQL, roles, permissions, ledger or cutover file changes.
- Tests add a deterministic 201-profile/101-booking HTTP fixture and assert zero direct PostgREST business traffic. Real Spring/PostgreSQL browser checks cover refresh, all booking decisions and participant messaging. `.env.example`, READMEs and `docs/MOBILE_SPRING_SETUP.md` provide exact Windows/JDK21/verified-CA/LAN/Expo Go setup and ordered manual checks.

Base: latest `fix/issue-1-booking-security` (`4726065`), preserving the earlier integration and the owner's newer reconciliation fixes. Work is in a separate worktree; the original worktree is untouched.

Validation:
- TypeScript, lint, client-env checks and 41 unit tests pass.
- Java 21 `mvn clean verify`: 67 passing tests on fresh history and 67 on prepared reconciliation schema.
- Deterministic mobile API and real Spring/PostgreSQL browser workflows pass; legacy M1 fallback, browser, portraits, UI, 30-PNG decode and accessibility suites pass.
- SQL: 25 fresh-history assertions and 286 reconciliation checks pass on disposable Docker databases.
- Connected web and Android/iOS/web bundle exports pass; no native build/device claim.
- Hosted JWKS probe remains HTTP 403. Actual hosted token/private requests, Windows trusted CA/pooler, hosted Realtime/Storage and native device checks remain manual blockers. Full commands/evidence: `artifacts/issue-3/report.md`.

Manual review: follow `docs/MOBILE_SPRING_SETUP.md` in order, using two Client/Talent development accounts, verified JDBC TLS, a physical phone's LAN API URL (not localhost), direct Storage uploads, Spring metadata/favourites/booking/message operations, role/status denial, expiry/offline/account-switch recovery and Realtime foreground/reconnect. Do not run cutover or change hosted permissions to make tests pass.

Draft review only. No hosted mutation, mobile release/environment switch, deployment, merge, payments or new category scope. Refs #3.
