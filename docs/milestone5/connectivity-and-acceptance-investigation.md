# Milestone 5: connectivity and acceptance investigation

2026-11-07. User-supplied timestamps below are Asia/Kolkata (+05:30). Investigation branch: `feat/milestone5-booking-messaging-notifications`, starting at `99c3ad2`.

No hosted SQL, policy, grant, trigger, schema or data write was performed. No credentials, TLS verification, DNS settings, firewall, VPN, IP-family preference or network security settings were changed. Mobile code is unchanged. All database regression writes were confined to disposable Docker fixtures.

## Findings at a glance

| Issue | Confirmed cause/failure boundary | Still unconfirmed |
|---|---|---|
| A | New pgJDBC connections fail in hostname resolution (`UnknownHostException`), before database authentication or TLS verification. This happens after successful startup, alongside long housekeeper scheduling gaps and stale/closed connections. | Why the originating Windows host cannot resolve the pooler at those times; whether the long scheduling gaps are sleep/hibernation, starvation, VM suspension, GC or clock changes. |
| B | PostgreSQL rejects the status UPDATE with SQLSTATE 42501. The supplied log uses the old format and lacks the diagnostic reason even though the patched handler always includes it. | Which guard condition, nested privilege denial or RLS check actually fails in hosted PostgreSQL. BadSqlGrammarException does not establish a syntax error. |

These are independent issues: the acceptance error occurred before the first supplied housekeeper/connection-replacement warnings. Neither increasing privileges nor changing TLS can correct a hostname-resolution error.

## A — supplied lifecycle evidence

- Nov 3 10:52:53–54: Hikari and Spring successfully start and open a PostgreSQL connection. This was not a permanently failing startup connection in the supplied process.
- Nov 3 10:55:54: acceptance reaches `booking.accepted.update` and gets42501. A database connection and PostgreSQL execution were available for this request.
- Nov 3 14:57 onward: housekeeper gaps include 29m, 19m and 4h; later logs include 12h and 20h gaps. Connection validation reports already-closed sockets.
- Nov 4 12:12, Nov 5 19:28 and Nov 6 10:56: the connection-adder attempts replacements and fails inside pgJDBC socket creation with `UnknownHostException` for `aws-0-ap-northeast-2.pooler.supabase.com`.
- Later socket resets are additional transport evidence; they are not certificate-verification errors and do not justify disabling verification.

The observed sequence is compatible with a suspended/stalled development machine returning to stale sockets and an unavailable resolver/network path. It does **not** prove the machine slept or identify the failing resolver. Hikari's generic “consider a shorter maxLifetime” warning is not evidence of the pooler's actual timeout. No timer, keepalive or lifetime setting can run while the JVM is unscheduled for hours, and none resolves DNS by itself.

### Code/configuration review and smallest code correction

There is one application-scoped DataSource bean, not a new pool per request. ActorDatabase uses TransactionTemplate and a transaction-bound JdbcClient; transaction cleanup returns logical connections to Hikari. Existing parallel-identity/rollback tests and the new lifecycle regression cover reuse and failure cleanup.

The custom DataSource previously constructed HikariConfig with literal 8/10000 values. Consequently, the two corresponding `spring.datasource.hikari` YAML properties were ignored by this factory. The code now binds **maximum-pool-size and connection-timeout explicitly before constructing HikariDataSource**. Their current effective defaults remain 8 and 10000ms; URL, username/password, TLS validation and all driver properties are preserved. This fixes configuration wiring, **not DNS**. Other Hikari YAML properties are not newly bound by this small patch; do not assume arbitrary pool properties now apply automatically.

Effective HikariCP 6.3.3 lifecycle defaults remain unchanged:

| Setting | Effective value/meaning |
|---|---|
| Maximum/minimum idle | 8/8 after config validation; minimum idle defaults to maximum |
| Connection timeout |10000ms borrower acquisition wait; not a DNS timeout or JDBC socket timeout |
| Validation timeout |5000ms Hikari validation budget |
| maxLifetime |1800000ms (30min), with Hikari retirement scheduling |
| idleTimeout |600000ms; cannot shrink the fixed minimum below 8 |
| keepaliveTime |120000ms; validation of idle connections, not DNS repair |
| Initialization fail timeout |Default fail-fast behavior retained; startup least-privilege verification also still requires a working connection |

No speculative lifetime/keepalive changes, unlimited application retry loop, forced IP, DNS-cache override or TLS relaxation was added.

### Operational correction and evidence collection

1. On the **same backend machine**, perform read-only lookup checks while failing and after resume. Record local timestamp and result; do not share credentials or a JDBC URL:

   ```powershell
   $poolerName = 'aws-0-ap-northeast-2.pooler.supabase.com'
   Resolve-DnsName $poolerName -Type A
   Resolve-DnsName $poolerName -Type AAAA
   nslookup $poolerName
   ```

   No AAAA answer alone does not explain failure when A resolves. Check the hostname against the project's current approved connection details read-only; do not silently replace an endpoint. A fresh Java InetAddress lookup can help compare JVM/OS results, but a new JVM does not prove the old process's DNS cache state.
2. Correlate Windows power/resume events, JVM pause/GC evidence and host time changes with the housekeeper gaps. Run sustained development on an awake/scheduled host. Once DNS resolution is healthy, restarting the approved development server after a long suspension is a minimal recovery step; restart alone is not a resolver fix. None of these operational actions were executed on the user's machine by this task.
3. Preserve dedicated credentials and `sslmode=verify-full` with the existing trusted CA. Do not change DNS servers, VPN/firewall rules or certificates to hide a missing DNS answer. Any environment correction requires evidence and the normal owner process.
4. Hikari already retries physical connection creation with backoff. New code classifies nested UnknownHostException as `dns_resolution_failed` and retains the enclosing SQLSTATE, typically 08001, instead of losing it by looking only at the deepest cause. Transaction-begin connection failures now return the existing generic temporary-unavailable text with HTTP 503, rather than falling into the generic 500 handler. No business write is automatically retried or replayed.

Cloud resolver checks could not independently validate the hostname: OS lookups returned EAI_AGAIN(-3) for **both** the pooler and controls `example.com`/`supabase.com`. Public HTTPS DNS checks returned 403 for the pooler and a control. This does not establish NXDOMAIN or a Supabase outage and is not evidence about the user's Windows resolver. No hosted database connection was attempted.

## B — trigger/identity review and missing evidence

The exact statement remains:

```sql
update public.bookings set status=cast(:status as public.booking_status)
where id=:id returning *;
```

The repository guard is SECURITY INVOKER. It checks participant identity, protected fields, self-account role and eligible transition; each explicit rejection uses42501. Its nested `SELECT primary_role FROM public.users WHERE id=auth.uid()` is subject to users privileges/RLS. The timestamp trigger only assigns NEW.updated_at and does not perform a second SQL UPDATE. Neither direct EXECUTE on attached trigger functions nor UPDATE(updated_at) is required for these repository definitions. Both are denied in a passing regression; table-wide booking UPDATE remains denied.

ActorDatabase binds the authenticated principal through transaction-local `request.jwt.claim.sub`, JSON `request.jwt.claims` and role via set_config(...,true). The UPDATE uses the same JdbcClient/transaction. Existing concurrency/rollback/anonymous-reuse tests verify no identity inheritance across pooled users. The opt-in probe verifies identity equality and expected account-role visibility immediately before UPDATE in that same transaction. This has passed **locally**, not against the hosted failing request.

The newly supplied acceptance log ends at `cause_class=PSQLException`. In the code already pushed at 99c3ad2, `reason=` is always present, even with the identity probe disabled. Therefore this historical line is from the earlier handler format and cannot identify the trigger condition. It does not establish which binary is running now. Pulling source does not replace classes in an already-running Java process.

### Minimal next step: use the diagnostic build, not new database privileges

1. Preserve existing worktrees/untracked files. In the approved development checkout verify the branch/commit and build Java21 with `mvn clean verify` from backend. Run the resulting `backend/target/castly-api-0.1.0-SNAPSHOT.jar` with the **existing** secure database configuration. Restart only the approved development process; do not deploy or write hosted objects without approval.
2. Set `CASTLY_BOOKING_DIAGNOSTICS_ENABLED=true` for that development process. The flag is server-only diagnostic configuration, not a database change. Keep notifications at their existing approved setting. Disable the probe after collecting evidence.
3. On an approved reproduction, correlate one response reference with the fresh `Business DB failure` line. Share only its fixed reason and boolean diagnostic fields. A known booking retry can mutate data if it succeeds; this task has not retried hosted acceptance.

| Reason | Distinction and smallest conditional repair |
|---|---|
| booking_guard_identity_or_participant | Check identityPresent/identityMatches and participant boolean. Correct the demonstrated claim-binding/identity discrepancy; do not broaden RLS. |
| booking_guard_protected_fields | Inspect exact hosted guard/trigger ordering and changed fields privately. Correct only a demonstrated guard/stamp discrepancy; never exempt all protected fields. |
| booking_guard_account_role | identityMatches=true plus accountVisible=false points to self-account visibility; accountVisible=true/roleMatches=false points to the visible role. Correct only the demonstrated self-SELECT drift or actual account-data issue, preserving fail-closed checks. |
| booking_guard_transition | Inspect actual locked status and hosted guard definition; retain existing allowed lifecycle. |
| postgres_privilege_denied | Find the specific nested dependency/column denied using read-only definitions and owner-approved private error context; propose only that missing privilege if required. |
| postgres_rls_denied | Inventory all applicable policies, including restrictive policies and SELECT checks for RETURNING. Repair only the demonstrated policy discrepancy. |
| unclassified_42501 | Unknown/changed/localized message; do not infer a condition or log raw message/CONTEXT. |

The probe now performs identity and account-role reads separately under a savepoint, so a denied role SELECT preserves a verified identity match while leaving account/role fields unknown. A failed probe rolls back to its savepoint and the original UPDATE still executes, preserving its failure and public 403. The application diagnostic log lines introduced here contain no raw SQL/error messages, token, credentials, account IDs or booking details. Only fixed reason codes, exception classes, SQLSTATE, support reference and booleans are emitted. Public 403 fields/detail remain unchanged; classifications are server-side only.

**No confirmed hosted trigger repair can yet be named.** The exact four supplied exception fields are compatible with several distinct, locally reproduced 42501 failures. Read-only inventory and conditional repair guidance remain in `booking-update-trigger-inventory.sql` and `booking-update-trigger-findings.md`. No SQL repair artifact or hosted application is justified until the fresh diagnostic identifies the actual condition.

## Regression coverage and results

The new/expanded tests cover:

- Spring property binding actually reaches the custom HikariConfig while retaining credentials/URL/TLS and lifecycle defaults, without a network connection in that unit test.
- Healthy socket reuse during a simulated DNS outage, replacement failure and recovery after DNS returns. Uses real Hikari and a synthetic transport, not hosted DNS. Transaction acquisition failure never executes/replays the business callback.
- Nested UnknownHostException classification retains SQLSTATE 08001, returns generic 503 and logs no hostname, credentials, raw error or stack.
- The actual Spring BadSqlGrammarException wrapper still classifies a known 42501 guard failure and preserves 403; its name is not treated as evidence of SQL syntax error.
- Denied role SELECT preserves same-transaction identity evidence, reports account/role as unknown, restores the transaction after the diagnostic read and retains the original UPDATE failure.
- Existing valid Talent acceptance, Client/other Talent denial, four guard classifications, RLS/privilege differences, protected fields, transaction rollback, conversation uniqueness and pooled identity isolation remain covered.

Exact commands/results and evidence are in `artifacts/milestone5/connectivity-investigation-*.log`. Java fixtures include fresh migration history and prepared/reconciled history. This is not hosted authentication/Realtime, Windows suspend/resume, native-device or production network validation. No mobile source changed; mobile/browser/export suites were not rerun for this backend-only investigation.

| Validation | Result |
|---|---|
| Java21 `mvn clean verify`, fresh schema | PASS:82 tests, zero failures/errors/skips |
| `CASTLY_TEST_SCHEMA_PATH=reconciliation mvn clean verify` | PASS:82 tests, zero failures/errors/skips |
| Configuration, diagnostic and real-Hikari synthetic-transport targeted suite | PASS:9 tests |
| Hosted connectivity/acceptance retry | NOT RUN: no hosted write or credentials used |
| Windows DNS/suspend root cause | OPEN: originating-host evidence required |
| Exact hosted42501 guard/RLS/privilege condition | OPEN: fresh diagnostic log required |

Cloud commands used JDK21/Maven3.9.11 and the existing offline Maven cache. Full invocations used `mvn -o -Dmaven.repo.local=/workspace/.m2/repository -f backend/pom.xml clean verify`, then the same command with `CASTLY_TEST_SCHEMA_PATH=reconciliation`. These tests prepare disposable local PostgreSQL; they never use an environment-provided hosted URL. Targeted run used `-Dtest=ConfigurationTest,BookingDiagnosticsTest,ConnectionLifecycleTest test`.
