# M7.2 account restrictions, reinstatement and appeals — local review

26 November 2026 (India). Branch `feat/m7.2-user-talent-management`, authorized base `dbf5ab228db2ae1acf263678a0727d73c0ee6022`. This continues the directory, minimized summaries, verification/evidence and account-security work committed through `9256275e51757c248f62b09742924a2c1a40d053`. The owner's subsequently supplied account-enforcement policy resolves the policy blocker recorded in `m72-progress.md`; that document describes the earlier checkpoint.

**Local M7.2 review only.** No push, merge, deployment, hosted SQL, infrastructure change, paid-provider activation, real notification or M7.3 work is authorized or performed. `CASTLY_ADMIN_USERS_ENABLED` and the new `CASTLY_ADMIN_ENFORCEMENT_ENABLED` default to `false`. Phase 6 production workers retain their independent release gate.

## Implementation and milestone matrix

| Area | Review implementation |
| --- | --- |
| Directory and details | Existing bounded prefix/role/date/verification filters, UUID keyset pagination, minimized account/talent metadata, subscription/booking summaries and privacy boundaries retained. Effective restriction-state filter added; no global count or OFFSET in the admin directory. |
| Verification | Existing scoped reviewer roster, recent MFA/RBAC, consent, revision/finalization, short-lived portfolio preview and source rechecks retained. No document grants added. |
| Restrictions | ACTIVE, RESTRICTED, TEMPORARILY_SUSPENDED, PERMANENTLY_SUSPENDED; fixed capabilities/reasons, public explanations separated from confidential notes; database-time expiry and versioned/idempotent commands. |
| Authority | Support escalation only; Moderator temporary authority capped at 168 hours per episode; Operations temporary authority capped at 720 hours. Super administrators use the same conservative temporary maximum and independent dual approval for permanent enforcement/reinstatement. |
| Appeals | Own-account submission, 30-day notification window, seven-calendar-day review target, overdue escalation queue, independent review, reasoned outcomes, withdrawal confirmation, significant-evidence reopening and immutable command/event history. |
| Admin UI | Existing design system extended with filters, effective status, duration/reason/capability controls, confirmation, public/private notes, proposal/appeal queues, review/reinstatement, and stale/conflict responses. |
| Mobile | Existing Lavender Pearl Screen/Field/Heading/button/theme components; account-access explanation, retained booking/membership/support/privacy links, appeals and actor-switch isolation. Existing ten screens are preserved. |
| Persistence and notifications | New private invoker-only schema; protected current-state projections, immutable decisions/commands/events/notes, guarded approval/appeal transitions, durable recipient notices. No external sends are scheduled for these notices. |

See `m72-completion-files.txt` for the complete inventory from the authorized base, including the preceding local M7.2 commits.

## Transition and authority matrix

| Current state / operation | Result | Authority and conditions |
| --- | --- | --- |
| ACTIVE / feature restriction | RESTRICTED | Enforce permission, recent MFA, documented fixed reason, evidence reference, public explanation, nonempty fixed capability subset and bounded expiry. |
| ACTIVE or temporary state / temporary suspension | TEMPORARILY_SUSPENDED | Moderator up to 7 days; Operations/Super up to 30 days. Immediate effect after the transaction commits. |
| Temporary extension / replacement | Updated temporary decision | Expected version and original episode horizon checked. Reinstatement/reapplication before the previous episode expiry does not reset that horizon. A genuinely expired episode can begin a newly documented decision; no invented lifetime penalty/cooldown. |
| Temporary state / database-time expiry | Effective ACTIVE | Queries evaluate database time; no scheduler is needed. Original decision/history remains, and publication/draft flags are not rewritten. |
| Non-permanent / reinstatement | ACTIVE | Enforce permission, recent MFA, reason/evidence, expected version; new immutable decision. |
| Non-permanent / propose permanent | No immediate restriction change | Permanent permission (Super), documented proposal and expected version. |
| Permanent proposal / approve | PERMANENTLY_SUSPENDED | Distinct active Super, recent MFA; proposer still active Super; version still matches. No automatic expiry. |
| Permanent / reinstate or modify | No immediate change until approval | Distinct Super proposal + approval. Ordinary enforcement and appeal paths cannot bypass this requirement. |
| Any administrative account target | Denied | M7.2 cannot suspend/reinstate privileged principal identities, including identities retained in the M7.1 roster after revocation. Use M7.1 governance; no self-enforcement or self-approval. |
| Investigation OPEN/UNDER_REVIEW/CLOSED | Investigation only | Separate from restriction and appeal states; cannot automatically punish an account. |

`ACCOUNT_COMPROMISE` is protective, not a misconduct finding. Fixed capabilities are `discovery`, `booking_create`, `booking_accept`, `messaging`, `profile`, `subscription_purchase`. Fully suspended accounts deny all six. All temporary restrictions require an expiry. Reasons are exactly the owner's twelve approved codes; arbitrary executable rules are not supported.

| Appeal transition | Conditions |
| --- | --- |
| New → SUBMITTED | Owner, existing own decision, within 30 days of durable in-app notification, statement and evidence reference. One appeal per decision. Submission does not lift restrictions. |
| SUBMITTED → UNDER_REVIEW | Recent-MFA reviewer with appeal permission, independent of original issuing/approving administrators. |
| UNDER_REVIEW → UPHELD | Assigned independent reviewer, recent MFA, public resolution. Restriction remains. |
| UNDER_REVIEW → MODIFIED / OVERTURNED | Independent assigned reviewer; enforcement authority, valid new decision, reason/evidence and duration checks. A permanent change requires a distinct independent Super approval. A temporary appeal cannot become a permanent ban through a single-reviewer path. |
| SUBMITTED / UNDER_REVIEW → WITHDRAWN | Owner and expected appeal version; mobile asks for confirmation. |
| Closed → reopening request | Owner supplies a different evidence reference and a substantive new statement. No automatic reopening or deadline rejection. |
| Reopening request → SUBMITTED | Independent recent-MFA reviewer explicitly confirms significant new evidence. A new seven-day review target is recorded; original commands/events remain immutable. |
| Review target overdue | Remains pending/under review; visible in the overdue escalation queue, never automatically rejected. |

Both original signers are excluded from review of a permanent decision. Its overturn therefore needs two additional independent Super administrators. Insufficient independent staffing leaves the case pending; there is no fallback authorization shortcut.

## API contracts and permissions

All business endpoints use existing Spring JWT verification and `ActorDatabase` transaction-local subject binding. All admin endpoints also require the existing versioned `X-Castly-Admin-Session`, verified AAL2/auth session and M7.1 authorization. The BFF retains fixed proxy paths, same-origin JSON mutation checks, secure HttpOnly cookies and no credential forwarding.

| Endpoint | Permission / behavior |
| --- | --- |
| `GET /api/v1/admin/users?state=...&after=UUID&limit=25` | `users.read`; existing filters retained, maximum 50. |
| `GET /api/v1/admin/users/{id}/access` | `accounts.read`; current effective state, bounded public history/appeals, permission-filtered confidential notes/escalations/proposals. |
| `POST /api/v1/admin/users/{id}/access` | Operation-specific permission and recent MFA; all authority/version rules rechecked in SQL. |
| `GET /api/v1/admin/account-queues/appeals` | `accounts.appeals`; pending/reopening queue, bounded UUID cursor. |
| `GET /api/v1/admin/account-queues/overdue` | `accounts.appeals`; overdue escalation queue, no auto-rejection. |
| `GET /api/v1/admin/account-queues/approvals` | `accounts.permanent`; pending proposals whose account version and appeal remain applicable. |
| `GET /api/v1/account/access` | Authenticated owner only; public explanation, safe history, own appeals/notices. No confidential notes, internal evidence references or investigation state. |
| `POST /api/v1/account/appeals` | Authenticated owner; only APPEAL_SUBMIT, APPEAL_WITHDRAW, APPEAL_REOPEN_REQUEST. Administrative actions rejected. |
| `POST /api/v1/subscriptions/purchase-eligibility` | Authenticated caller; fresh eligibility check immediately before client Play initiation. Existing verification/restore/renewal/refund paths remain. |

Mutation envelope:

```json
{
  "request_key": "UUID retained when retrying the same command",
  "operation": "ENFORCE",
  "payload": {
    "version": 0,
    "state": "TEMPORARILY_SUSPENDED",
    "duration_hours": 24,
    "reason_code": "ACCOUNT_COMPROMISE",
    "explanation": "Protective account hold while account security is reviewed.",
    "evidence_ref": "support:case-reference",
    "notes": "Confidential investigation notes"
  }
}
```

`ENFORCE`, `REINSTATE`, `INVESTIGATE`: accounts.enforce. `ESCALATE`: accounts.escalate. `PROPOSE`, `APPROVE`: accounts.permanent. Administrative APPEAL_REVIEW / APPEAL_DECIDE / APPEAL_REOPEN: accounts.appeals, with further enforcement/permanent authority where applicable. SQL independently enforces the rules even if an HTTP caller fabricates payload fields. Capability arrays are validated against the fixed list. Submission JSON is bounded at 16 KiB before deserialization, including unknown-length bodies; payload keys/field lengths are bounded too.

Successful commands return `resource_id`, `event_id`, `version`. Reusing the same actor/key/body returns the same result without another event/notice; conflicting reuse and stale versions yield 409. Missing permission, independent approval, MFA or capability yields 403. Disabled admin modules return 404. Persisted restrictions and owner appeals remain effective if new administrative writes are switched off.

## Enforcement coverage and privacy

| Business path | Enforcement / retained access |
| --- | --- |
| Discovery, shortlist, recommendations, brief/compare and digests | Additive restrictive publication policies, minimized visibility predicate and amended discovery-only helper. Own editing/inspection RLS is retained but does not imply discovery eligibility. Existing block/public-fact checks and after-provider rechecks remain. |
| Booking creation | Sorted shared locks for client/talent, caller booking_create capability, both accounts not suspended, target discoverable; original identity/role/RLS guards retained. |
| Booking acceptance | Shared participant locks and booking_accept check; both accounts not suspended. Original participant, talent-role and status guard retained. |
| Existing booking records, cancellation and completion | Existing records/snapshots preserved for both parties. Cancellation and dual-participant completion rules remain available; suspension never fabricates completion, deletes an obligation or overrides participant attestations. |
| Admin-assisted obligations / disputes | Account booking summaries and confidential escalation provide assistance; existing participant confirmation and dispute routes remain authoritative. No forced admin completion, refund, confiscation or new financial transition is invented. |
| Conversation creation / messages | New conversations check both messaging capabilities; sends check sender capability. Reads and legitimate counterparty access remain. can_send reflects the caller's current capability. |
| Public profile, portfolio metadata, availability and region preferences | Invoker triggers take account shared locks and enforce profile capability, including insert/update/delete and alternative direct paths. |
| Storage | Portfolio writes guarded; existing Storage RLS, immutable names, private-media/evidence ownership/consent/finalization remain. No public bucket or reviewer-roster widening. |
| New subscriptions | Configuration closes checkout, and the app obtains fresh eligibility before native purchase. Verified receipts/renewals/restore/refunds and existing entitlements are preserved. |
| Engagement workers | Final dispatch transaction takes recipient shared lock and suppresses suspended recipients before provider I/O. Existing consent, blocks, budgets, restricted login and disabled-worker guards remain. No enforcement notice is sent externally. |
| Auth, limited account, security/recovery, support, privacy and financial records | No global suspension filter blocks these routes. Owners retain their existing authorization boundaries. |

Only minimal capability/expiry/visibility projections are available to business-policy roles. Anonymous grants expose only the user/visibility columns; authenticated and worker roles have no command/case/note privileges. There is no service-role use, membership in authenticated, BYPASSRLS or SECURITY DEFINER shortcut. Booking table-wide UPDATE remains ungranted; no auth-schema access is introduced.

Commands, decisions, notes and events cannot be directly rewritten/deleted by the application role. Appeal and proposal changes use guarded canonical commands, with original inputs and outcomes retained in immutable commands/events. Confidential escalations are available to enforcement staff; ordinary accounts see only user-visible enforcement and appeal events. Booking counterparties never receive investigation details.

## Migration review and ordering

New forward-only migration: `supabase/migrations/20261126100000_account_enforcement.sql`. SHA-256: `a9408f34233dbc55d3779db2231960f64a5147b0957bf3aef4a98de0a97cb635`. All reviewed hashes are in `artifacts/m72-completion/migration-hashes.json`.

The migration creates an application-owned private schema, eleven RLS tables, fixed RBAC permissions, guarded invoker commands/projections, indexed queues/history/state and additive business/Storage triggers and restrictive visibility policies. It amends only the discovery-only helper to add eligibility to its existing publication/block checks. Historical migration files are unchanged.

Prerequisites: the reviewed existing identity fallback, Phase 3/4/5/6 and M7.1 schema, then the existing M7.2 read-model and security-index migrations (`20261125100000`, `20261125110000`), then `20261126100000`. Fresh and synthetic reconciled fixtures rehearse that order. The intentionally pre-Phase-4 compatibility fixture skips the Phase-4-dependent M7.2 changes while those modules stay off.

**Do not use unrestricted `supabase db push`.** Before any future hosted approval: compare actual schema/ACLs/owners and migration history with the reviewed prerequisites; rehearse backup/restore and DDL locks on a representative clone; obtain approval for the exact hash; apply only the reviewed forward artifact with ON_ERROR_STOP through the approved database-owner migration channel. Deploy compatible Spring/admin/mobile builds with administrative write flags still off, validate authorized staging identities, and obtain a separate enablement checkpoint. No hosted command was run here.

## Security/concurrency review findings

- Permanent enforcement is fenced centrally, including an attempted temporary-appeal → permanent-ban transition. A negative regression rejects it without changing the appeal or account.
- Owner profile inspection must not make a banned creator discoverable through brief/compare helpers. The new SQL regression failed against the earlier helper; the forward helper amendment closes that path.
- Shared business locks and exclusive restriction locks use the same account namespace; participant locks sort/deduplicate UUIDs. Restriction commands do not lock booking/message rows. Principal enrollment uses the account lock and rejects creating a privileged identity for an effectively restricted account.
- Real PostgreSQL waiter tests cover booking creation, acceptance, message sending and public publication versus enforcement. Requests completed before restriction retain legitimate records; subsequent attempts fail. Worker dispatch similarly serializes through the final provider boundary.
- Transaction-local subject binding and existing JWT issuer/audience/signature/expiry checks are unchanged. Recent MFA is rechecked after the target lock wait using database clock time.
- Temporary horizon checks prevent reinstatement/reapplication or cumulative extensions from silently restarting the active episode. No scheduler races determine expiry.
- Rollback and duplicate tests prove invalid decisions do not persist partial appeal transitions, duplicate notices or extra events. Confidential material is not included in SQL-error logs or public 403 responses.
- Validation found a missing dependency in the isolated worker fixture; the fixture now supplies the real enforcement dependency, preserving all original fail-closed assertions. Mobile TS now excludes generated Maven output, preventing clean/coverage artifacts from racing its file scan.

## Scale evidence

`supabase/tests/admin-enforcement-explain.sql` is an optional rollback-only fixture: **10,000 synthetic accounts, 1,000 restrictions**, restricted-role execution, normal RLS and ANALYZE. The captured JSON plan is `artifacts/m72-completion/enforcement-explain.json`. The report's validation results record its measured timing.

The sampled directory query returns 26 rows through the directory primary-key index and indexed state probes, without a directory full scan, global count or OFFSET. History, command idempotency, escalation and queue predicates have purpose indexes. This is architectural and small local evidence, **not** a millions-of-users throughput, p95 latency or production capacity claim. Representative distributions, concurrent load, index maintenance and DDL lock costs remain release gates.

## Validation and screenshots

Exact final counts/results and timings are in `artifacts/m72-completion/validation-results.json`. Source logs are copied to `artifacts/m72-completion/validation/` with trailing whitespace normalized for Git review. Screenshot dimensions/hashes are in `artifacts/m72-completion/screenshots/manifest.json`; PNGs are decoded before packaging. The admin browser test uses actual local signed JWTs, BFF sessions, Spring APIs and restricted PostgreSQL. Mobile includes a deterministic HTTP fixture and separate existing real Spring browser regression; neither is hosted/native-device validation.

| Final local check | Result |
| --- | --- |
| Java 21 Maven fresh `clean verify` | PASS — 220 tests, 0 failures/errors/skips |
| Java 21 Maven reconciled `clean verify` | PASS — 220 tests, 0 failures/errors/skips |
| Fresh PostgreSQL security suite | PASS — 227 passing notices, including 28 new enforcement assertions |
| SQL reconciliation | PASS — 608 assertions/catalog gates across both fixtures |
| Backend Java line coverage | 87.81% (4,551 / 5,183 lines); SQL tested separately |
| Admin typecheck, lint, formatting, production build | PASS |
| Admin unit/coverage | PASS — 10 cases; tested security contract: 100% lines, 97.30% branches |
| Real admin browser integration | PASS — 5 flows, including recipient privacy, independent appeal and Axe checks |
| Mobile typecheck, lint, unit, component | PASS — 76 unit + 20 component cases |
| Connected mobile HTTP fixture and real Spring browser | PASS — including existing booking, messaging, notifications, Phase 4/5, privacy and subscription flows; no sends |
| Mock navigation, UI, PNG and accessibility regressions | PASS — 30 baseline PNGs decoded, three widths and 150% browser text simulation |
| Mock and local-connected Expo web exports | PASS — no hosted credentials or services |
| PNG artifact verification | PASS — 67 PNGs fully decoded, nonzero dimensions and nonblank pixels |
| Historical migration integrity / diff checks | PASS — unchanged historical migrations; no whitespace errors |

[Review screenshot gallery](../../artifacts/m72-completion/review.html) · [Download review ZIP](../../artifacts/m72-completion/review.zip) · [Windows PowerShell commands](m72-windows-validation.md)

The latest rollback-only query measurement was **27.890 ms execution / 0.989 ms planning**, returning 26 rows using the directory primary-key index and state-index probes. This is a local warm-cache sample on 10,000 accounts / 1,000 restrictions, not a production capacity claim. Administrative PNGs are full-page captures; their actual dimensions are recorded in the manifest. Mobile account-access PNGs are exactly 360×800, 390×844 and 430×932.

Frontend coverage currently instruments the tested security contract module, not every React component. Backend JaCoCo covers Java; SQL policy and concurrency evidence is separate. No blanket coverage or production-readiness claim is inferred from counts.

## Remaining release gates / policy boundaries

1. Owner review of this local M7.2 checkpoint, migration hash/ACLs/lock ordering and private-data retention/export/deletion obligations; no deployment approval is implied.
2. Actual hosted schema/ACL equivalence, real Supabase MFA/session recovery/Storage and production cookie/TLS behavior require approved staging validation. Local synthetic reconciliation is not hosted evidence.
3. Existing signed image URLs remain usable until their short TTL expires. Previously issued URLs cannot be atomically revoked by a PostgreSQL visibility change; no bucket/publication security was weakened.
4. Castly blocks new checkout initiation and preserves genuine paid obligations. A checkout already started, or a purchase made outside Castly, cannot be atomically prevented by a database restriction. Receipt reconciliation intentionally does not confiscate payment/entitlements. Any stronger store-side policy needs a separately approved provider capability/policy, not a fabricated refund or grant denial.
5. Support-assisted completion retains existing participant attestations. No authority to impersonate participants, force arbitrary completion or override financial/dispute rules was invented.
6. Sufficient independent Super administrators are needed for permanent appeal overturns. Pending/overdue reviews remain visible rather than being automatically rejected.
7. Real Android/iOS, enlarged native text/screen reader, native billing/restore and production load/provider checks are not claimed. Phase 6 worker production security and send enablement remain separate approvals.

Stop at M7.2 review. Do not publish, merge, enable flags or begin M7.3.
