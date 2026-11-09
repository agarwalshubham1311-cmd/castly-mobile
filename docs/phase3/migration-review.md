# Subscription migration review

Local review date: 2026-11-09. These are forward-only artifacts, **not applied to hosted Supabase**. No historical migration was modified. They depend on the existing restricted Spring business role and `20261107150000_private_request_identity.sql` identity contract, not on the pending notification migrations. Do not replay historical SQL or use blanket `supabase db push` on the reconciled hosted project.

## Apply order and SHA-256

| Artifact | SHA-256 |
| --- | --- |
| `20261109090000_subscription_catalog.sql` | `c6afa667201b79e6960d64e13d8dae93eb2ab7ba339e8e66739c821d54a5419b` |
| `20261109100000_founding_creator_offer.sql` | `db5f67ac7568dfec718089e959bf41a613d0dfabc4a912db1c133106e5c53762` |
| `20261109110000_google_play_reconciliation.sql` | `2ba024e5d621644d010b1b7bb5ecfc7fe61759743a5afce17a4d31ce4091a790` |
| `20261109120000_subscription_discovery_projection.sql` | `8924cba5d861c7a45bf481b0ef63bc99256009cc1a948a0d055b7e5d697b5d71` |

1. Catalog migration creates plans, orders, grant/event/audit ledgers, private admin allowlist and hash-only routing. It copies every legacy entitlement into a legacy-source grant once, preserving unknown identifiers, bounded and perpetual grants. Catalog prices/products are unset and portfolio limits are NULL. The existing entitlement table remains a compatibility projection, not a second source of authority after module activation.
2. Founding migration adds unique creator/post claims, immutable reviewed transitions and database-backed sensitive-operation rate limits. The private reviewer allowlist starts empty. An owner must explicitly provision approved reviewer UUIDs; API/mobile users cannot do so.
3. Google migration adds replacement-token retirement and idempotent RTDN receipts. Only hashes are persisted; no raw purchase token or Google credential is stored in tables.
4. Discovery migration adds public eligibility-only projection and initially-empty future premium feature flags. Private payment/moderation data is never added to discovery responses.

Every migration is transactional. Schema creation takes normal DDL locks; the first migration also reads the existing entitlement table for the one-time import. Assess table size and schedule the approved deployment accordingly. Application startup does not run DDL.

## Required owner preflight (review only until approval)

- Inventory actual hosted schema, grants/default privileges, RLS policies, triggers and applied/reconciliation versions. Confirm `castly_api` is non-owner/non-BYPASSRLS and `private.request_user_id()` exists with the expected transaction identity semantics.
- Inventory `subscription_entitlements` counts, entitlement identifiers, perpetual grants, expiries and user foreign keys. Confirm whether production uses `creator_pro` or another name; no unknown entitlement is automatically renamed or promoted. Review any owner-managed grants and freeze old-table writes during cutover.
- Verify no new object names already exist. Do not run these non-repeatable CREATE migrations twice. Record versions through the project's established reviewed migration workflow.
- Back up schema and subscription rows; rehearse on an isolated copy and obtain explicit hosted-schema approval. A local owner role is used only to provision disposable test fixtures; the application always connects as castly_api.
- Review NULL limits and disabled product IDs/flags. Setting finite limits changes future Free uploads and requires product/rollout approval; existing portfolio rows are never deleted by these migrations.

## Post-apply verification before activation

Keep subscriptions/Google checkout disabled and Castly notifications false. Verify RLS on all seven new public tables, explicit PUBLIC/anon/authenticated revokes, column-scoped API grants, no client/admin-table write grant, empty reviewer list, correct legacy import counts, and no SECURITY DEFINER function introduced by these artifacts. Run negative cross-user/unverified write tests on the isolated deployed copy. Compare source-key/import counts with the backed-up entitlement table. Read effective membership and the compatibility endpoint for representative active/expired/perpetual/unknown entitlement users.

Once activated, write future reviewed membership adjustments to the grant ledger and update the compatibility/discovery projections atomically. Do not independently alter `subscription_entitlements`; stale owner edits there cannot override server entitlement resolution. Feature expiry is evaluated at read time, so it does not depend on a scheduled cleanup. Revocation/overlap reconciliation updates projections transactionally.

## Local evidence and limitations

The full migration sequence is tested against disposable PostgreSQL in both repository-history and reconciliation-shaped Java fixtures. Additional SQL tests assert direct mobile write denial, reviewer-provisioning denial, verified-context isolation, unverified order denial and RLS coverage. Existing notification SQL tests run only as inherited local regression fixtures; this does not authorize/apply pending notification migrations to hosted Supabase.

These tests do not establish actual hosted-schema equivalence. Audit/receipt retention, account deletion/anonymization and operational monitoring need approved production policies. The module keeps audit history and intentionally has no public deletion endpoint. Roll back behavior with feature/checkout gates while retaining data; do not drop ledgers or rewind historical SQL.
