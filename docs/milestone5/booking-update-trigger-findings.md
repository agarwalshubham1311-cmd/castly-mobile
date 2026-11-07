# Booking acceptance UPDATE: trigger investigation

Date: 2026-11-03. Scope: repository inspection, temporary opt-in backend diagnostics, read-only inventory preparation and disposable local PostgreSQL regression tests. **No hosted connection, DDL, migration, policy change or grant was performed. No database repair has been applied.** No mobile code or database definitions changed.

## Confirmed and still missing

The owner has correlated the hosted failure to `booking.accepted.update`, SQLSTATE42501. This identifies:

```sql
update public.bookings
set status=cast(:status as public.booking_status)
where id=:id
returning *;
```

The owner confirms table SELECT, column UPDATE(status) and SELECT(status), participant UPDATE USING/WITH CHECK, and two BEFORE UPDATE triggers: `api_booking_transition` and `bookings_stamp`. Both functions are confirmed SECURITY INVOKER without API EXECUTE, and the guard has the four explicit rejection conditions described below. Table-wide UPDATE is intentionally absent. These facts do **not** determine which condition or additional privilege/RLS check caused the error. The current hosted function bodies and sanitized PostgreSQL message/CONTEXT have not been supplied. Repository definitions must not be assumed identical to hosted definitions.

In particular, a valid permissive participant policy does not rule out another restrictive policy. Inspect all SELECT/UPDATE/ALL policies; PostgreSQL also applies appropriate SELECT visibility checks to UPDATE RETURNING.

## Repository function inspection

`supabase/migrations/20261022000100_accounts_and_marketplace.sql` defines `stamp_updated_at()` with default SECURITY INVOKER, empty search_path, and only:

```sql
begin new.updated_at=now(); return new; end
```

It performs no SQL UPDATE or SELECT and no explicit authorization checks. Assigning to `NEW.updated_at` does **not** require the caller to have UPDATE(updated_at). Changing this function to SECURITY DEFINER or granting more booking columns is unnecessary for this definition.

`supabase/migrations/20261030090000_booking_guard_hardening.sql` and `supabase/reconciliation/issue-1/02-prepare.sql` define the same hardened `api_booking_transition_guard()` as SECURITY INVOKER, empty search_path. It:

1. Reads `auth.uid()` and only enforces its API guard when `current_user='castly_api'`.
2. Raises 42501 `Booking participant required` for absent/nonparticipant identity.
3. Raises 42501 `Protected booking fields are immutable` if anything except status/updated_at changes.
4. Runs `select primary_role into actor_role from public.users where id=me` **as the restricted caller, subject to users SELECT privileges and RLS**.
5. Raises 42501 `Booking account role mismatch` for a wrong role **or no visible self-account row**, because a missing row leaves actor_role NULL and `IS DISTINCT FROM` rejects it.
6. Accepts requested→accepted/declined for the relevant Talent, and supported participant cancellation. Otherwise raises 42501 `Invalid booking transition`.

There is no nested booking UPDATE and no `has_table_privilege(...,'UPDATE')` requirement in these repository definitions. PostgreSQL normally executes same-kind triggers alphabetically: `api_booking_transition` precedes `bookings_stamp` here. Inspect actual enabled trigger definitions for drift/extra triggers.

EXECUTE on an already attached **trigger function** is not checked for each triggering row at runtime. Revocation from castly_api is intentional and works in the local fixture. This differs from privileges needed by regular functions or SQL called *inside* an invoker trigger: `auth.uid()` needs schema usage/EXECUTE; the users lookup needs SELECT and self visibility.

Do not switch the guard to SECURITY DEFINER to mask a failure: that changes current_user to the function owner, and the current guard's first check could then skip API authorization entirely.

## Regression evidence

Added to `backend/src/test/java/com/castly/BusinessApiTest.java`:

- `bothInvokerBookingTriggersWorkWithOnlyStatusUpdateAndNoTriggerExecuteGrant`: asserts both functions are INVOKER, both have API EXECUTE denied, booking table-wide UPDATE and UPDATE(updated_at) are denied, UPDATE(status) is allowed; then successfully executes the actual acceptance HTTP flow including UPDATE RETURNING and conversation creation.
- `invokerGuardFailsClosedWhenActorAccountIsHiddenByUsersRls`: adds a restrictive self-account visibility failure **inside the disposable local fixture only**, confirms the exact UPDATE raises 42501 with `Booking account role mismatch` and guard CONTEXT, verifies the booking remains requested, removes that local test policy in finally, then confirms valid acceptance succeeds. This demonstrates a possible nested authorization cause; it does **not** assert that hosted users RLS is wrong.

Additional tests verify the probe reads identity/role on the same transaction/connection as the actual UPDATE, reports a deliberate expected-identity mismatch, and recovers via savepoint from a denied users SELECT without replacing the original UPDATE failure. Three diagnostic unit tests cover all four allowlisted guard errors, engine privilege/RLS denials, unknown/localized messages failing to a neutral classification, no raw message/CONTEXT/UUID logging, unchanged public 403 properties and the disabled probe doing no database work.

The existing Client/other Talent denial, transition matrix, protected-field, transaction rollback and conversation reuse tests remain. Java21 `mvn clean verify` passes **78 tests, zero failures/errors/skips on both fresh and reconciled schema fixtures**. Detailed logs: `artifacts/milestone5/trigger-investigation-java.log` and `artifacts/milestone5/trigger-investigation-reconciled.log`. Read-only inventory was syntax/execution checked locally and ends with ROLLBACK. No mobile source changed, so mobile suites were not repeated in this investigation.

## Running the temporary diagnostic safely

The code adds `SqlFailureKind.java` and `BookingUpdateDiagnostics.java`, wires evidence through the UPDATE's existing `DatabaseStep`, and extends the existing sanitized server log. It changes no SQL function, trigger, policy or grant. All diagnostic logging is fixed reason codes and booleans; arbitrary database messages/CONTEXT are parsed only for allowlisted classification and are never written to logs or the public response.

1. In an approved development server environment, set `CASTLY_BOOKING_DIAGNOSTICS_ENABLED=true` and restart Spring with the existing dedicated connection. Default is false. This is server configuration only, with no hosted DDL or migration. Do not deploy or mutate a hosted booking without separate authorization.
2. For an approved reproduction, correlate the existing public response's reference with the `Business DB failure` log. The unchanged HTTP403 body still has generic `Not permitted` plus the pre-existing operation/reference. No new reason or identity information is exposed to mobile clients.
3. Read `reason` and `booking_diagnostics`. The read probe executes immediately before UPDATE through the **same transaction-bound JdbcClient**, never a separate pooled connection. It compares `auth.uid()` to the authenticated principal as a boolean and checks the caller's expected role via the same users visibility as the invoker guard. UUIDs and the account row are not logged. Savepoint rollback prevents a diagnostic privilege failure from aborting the business transaction before its original UPDATE.
4. Disable the flag after collecting evidence; remove temporary probe instrumentation after diagnosis. Fixed sanitized classification remains useful for support, but no diagnostic should replace the guard or its RLS.

| Server reason | Meaning |
|---|---|
| `booking_guard_identity_or_participant` | Exact known guard message for missing identity/nonparticipant |
| `booking_guard_protected_fields` | Exact known immutable-fields guard message |
| `booking_guard_account_role` | Exact known account-role guard message |
| `booking_guard_transition` | Exact known transition guard message |
| `postgres_privilege_denied` | PostgreSQL permission-denied message, not one of the four known guard messages |
| `postgres_rls_denied` | PostgreSQL row-level-security rejection message |
| `unclassified_42501` | Unknown/changed/localized message; do not guess or dump raw text to logs |

Probe fields are tri-state: null means not established, false is an observed mismatch. For example, `identityMatches=true, accountVisible=false, roleMatches=false` with `booking_guard_account_role` demonstrates correct principal binding but missing self-account visibility in the invoking role's transaction. `accountVisible=true, roleMatches=false` instead shows a visible account whose role does not match this booking participant. If the probe itself says `postgres_privilege_denied`, any fields not established remain null and the original UPDATE still runs/fails normally; inspect the read-only dependency grants. These are interpretations of **future actual evidence**, not a claim that any one is the hosted cause.

## Smallest secure fix: gated on actual error evidence

No unconditional database patch is justified by the currently supplied facts. Use `booking-update-trigger-inventory.sql` to collect definitions/modes/ACLs and all booking/users policies without writes or JWT impersonation. Pair that with the same failed request's sanitized PostgreSQL message and PL/pgSQL CONTEXT; do not share tokens, connection strings or SQL parameter values.

The smallest repair depends on that evidence:

| Observed evidence | Narrow repair to propose for approval |
|---|---|
| `permission denied for table users`, CONTEXT points at the guard's role SELECT | Restore only missing SELECT privileges for `users.id`/`primary_role`, retaining self-only RLS. Do not grant booking table UPDATE. |
| `Booking account role mismatch`, role verified correct but self row hidden from API | Repair only the demonstrated users self-SELECT policy drift/restrictive condition; keep guard fail-closed and INVOKER. A new permissive policy cannot override a restrictive false condition. |
| Hosted stamp body performs a second SQL UPDATE | Replace only that body with the repository's `NEW.updated_at=now(); RETURN NEW` behavior, retaining INVOKER; do not grant UPDATE(updated_at). |
| Hosted trigger explicitly requires table-wide UPDATE | Remove the erroneous extra requirement or, if a privilege check is retained, scope it to UPDATE(status); PostgreSQL already enforces the actual column grant. Preserve participant/role/transition checks. |
| Different explicit guard error, nested function denial or restrictive booking RLS error | Repair the specific demonstrated identity, immutable-field, transition, dependency ACL or policy discrepancy. The SQLSTATE alone does not justify relaxing checks. |

These are conditional review decisions, **not claims about the hosted cause**. Once actual definitions/CONTEXT identify the failing line, reproduce that exact definition/error locally, add a before/after regression and prepare one forward repair artifact. Wait for explicit approval before any hosted application. Neither a blanket SECURITY DEFINER conversion, trigger EXECUTE grant, table-wide booking UPDATE, nor disabling RLS is proposed.


Update 2026-11-07: [Connectivity and acceptance follow-up](connectivity-and-acceptance-investigation.md) documents supplied lifecycle logs, the older acceptance log format, Hikari property wiring, and a split read probe that retains verified identity evidence when the account-role read is denied. The original 78-test results above are the earlier investigation, not the follow-up suite.
