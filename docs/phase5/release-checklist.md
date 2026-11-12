# Integrated Phase 5 release checkpoint

Issue #10 supersedes the original Phase 3-only release plan. Follow `docs/issue10/migration-release.md` for exact order/hashes and `docs/issue10/integration-review.md` for preserved Phase 4 contracts. Do not replay deployed Phase 3/4 migrations.

- All four Phase 5 flags default false; provider stays disabled. Existing flags and hosted configuration are unchanged.
- Review the actual owner-supplied hosted catalog and both forward migrations, then rehearse on an isolated representative restore. Local fixtures are not proof of hosted equivalence.
- Obtain separate migration and deployment approval. Preserve the existing booking, messaging, trust and subscription paths, and enable Phase 5 independently for an internal cohort only after regression smoke tests.
- Disable affected flags to roll back behavior; retain schema and user/audit records. No ledger rewind, automatic provider activation or destructive rollback.
- Native devices, hosted Realtime, live AI providers and production performance still need the checks listed in the integration report.
