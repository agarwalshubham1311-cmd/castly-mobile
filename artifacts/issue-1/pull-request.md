Booking authorization depended on the JDBC/JSON representation of participant IDs, and conversation creation relied solely on RLS for participant checks. Normalize IDs to UUID, enforce explicit participant authorization, and harden database transitions and message/cancellation locking. The new tests cover raw UUID/string rows, a 45-case booking transition matrix, pooled claim isolation, negative access and both concurrency orderings.

The hosted schema reported in #1 has a different migration ledger and lacks the historical decision RPCs. Add a forward-only reconciliation package with read-only inventory, structural checks, preparation that preserves legacy mobile writes, and separately approved cutover/revocation. Preserve data, historical timestamps, name snapshots, Storage access and migration history. Historical migration files and mobile source/configuration are unchanged.

Validation:
- Java 21 `mvn clean verify`: 66 passing tests on fresh history and 66 on prepared reconciliation schema.
- Reconciliation: 286 SQL assertions/catalog gates; fresh RLS suite: 25 assertions.
- TypeScript, lint, 27 unit tests and client environment checks pass.
- Real Spring/PostgreSQL browser workflow passes on both schema paths; legacy M1, browser, portrait, UI, PNG decode and accessibility suites pass.
- Android/iOS/web bundle exports and connected web export pass; no native execution is claimed.

Unresolved rollout gates: full owner-supplied hosted schema inventory/equivalence, actual JWT signing verification (public JWKS probe returns HTTP 403), staging Supabase Realtime/Storage, dedicated hosted LOGIN/pooler/TLS, native checks and manual leaked-password protection. Local fixtures approximate the reported baseline, not a complete hosted clone. No hosted SQL, role provisioning, mobile backend switch, deployment, payments or main merge was performed.

See `supabase/reconciliation/issue-1/README.md` for gated commands/backups/rollback and `artifacts/issue-1/report.md` for test evidence. Refs #1. Review for the `feat/phase2-bookings-messaging` base; hosted cutover requires separate explicit approval.
