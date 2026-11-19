# Phase 5 integration gate: reconcile AI discovery branch with deployed Phase 4

## Purpose
Integrate existing Codex Phase 5 AI discovery implementation from `feat/phase5-ai-discovery-matching` with current deployed Phase 4 baseline `feat/milestone5-booking-messaging-notifications` at `23be56e0444d37aa97d9d54fd524b63d84590b07`. **Do not overwrite either branch or deploy automatically.**

## Current evidence (2026-11-12)
- Phase 5 branch: 4 commits ahead / 9 behind production baseline, merge base `b86deb4`; its original issue #6 assumed Phase 4 was not deployed.
- Phase 5 implementation includes advanced search, deterministic recommendations, campaign parser/matching, editable brief, Expo screens, Spring APIs, forward SQL migration `20261111100000_phase5_discovery.sql`.
- Codex-reported local tests: 118 backend tests, 71 npm tests, 286 reconciliation assertions; **not independently rerun in this review**.
- Phase 4 is deployed to Render, and its trust tables and private storage bucket are live. Phase 4 functional tests were user-accepted. Existing `APPEALED` dispute and `OPEN` report are test records, not evidence of closed adjudication.

## Integration work
- [ ] Create a fresh integration branch from deployed Phase 4 baseline. Cherry-pick/rebase Phase 5 commits with conflict review; preserve Phase 4 security, trust visibility, discovery eligibility and booking/messaging contracts.
- [ ] Review Phase 5 SQL against actual hosted Phase 3+4 schema, migration ledger, grants, RLS, trigger ordering and indexes. Rehearse on isolated representative DB; **do not** run production migration yet.
- [ ] Ensure moderation/blocked/unpublished creators are excluded from search and recommendations without leaking private trust signals; preserve authorization and account switching.
- [ ] Review advanced search availability semantics (confirmed/unknown/unavailable), no private booking leaks, opt-in region and date consent, hard constraints, stable pagination, latency.
- [ ] Keep AI provider disabled; deterministic baseline and explicit suggestion labeling; no sensitive input to providers.
- [ ] Run npm typecheck/lint/test/component/SQL/reconciliation, backend Maven verify, integration/security negatives, web/browser/mobile API tests. Report actual results and outstanding Android device checks.
- [ ] Open draft PR to production branch for independent review, with migration hash, rollout/rollback plan and feature flags default off.
- [ ] No changes to Render, Supabase production, EAS, Google Play, or existing checkout/notification flags until separate approval.

Related: #6 Phase 5 implementation, #8 Phase 4 release gates, #9 saved talent error.
