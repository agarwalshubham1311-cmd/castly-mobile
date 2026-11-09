# Phase 3 implementation sequence

P3-M1: configurable plans, grant ledger, legacy reconciliation, effective memberships, API and mobile catalog. P3-M2: auditable manual founding-offer review and 12-month grants. P3-M3: fail-closed Google verification, native billing adapter and authenticated RTDN. P3-M4: security/regression validation and release assessment.

Work is isolated on `feat/phase3-subscriptions-monetization`, based on d065bf5. Hosted schema changes, Render deployment, EAS builds, Google product activation and secret provisioning remain approval checkpoints. No notification migration is required by Phase 3. The existing notification test fixtures may exercise historical migrations only in disposable local PostgreSQL.

The remote development design/contracts/catalog at commits 768f5ca–4bc4806 were reviewed during reconciliation onto 02361e7. Their Free/Creator Pro feature matrix, creator-only founding offer, no-price/no-activation defaults and server-authority constraints are retained. Original pure catalog/entitlement helper exports remain available for advisory UI compatibility and their five original tests are preserved; they do not authorize server features. The implementation replaces the draft's pending backend/UI work. Appeals and additional subscription tiers remain policy decisions, with no automatic resubmission or client subscription tier enabled.
