# Release readiness — target 2026-11-13

Recommendation: **local implementation is reviewable; live monetization is not ready for activation** until the following external gates pass. No hosted migration, Render deployment, EAS build, secret provisioning or real billing activation occurred.

- [ ] Review the final branch and PR against the development branch, including base-branch divergence from local d065bf5. Never overwrite the parallel APK worktree or force-push the base.
- [ ] Review current official Google/Expo/library compatibility and approve a native custom build; validate Expo 58/RN 0.88 + installed IAP/Nitro on Android devices.
- [ ] Approve actual Google product/base-plan IDs, pricing and offers. Confirm package ownership aligns with APK work.
- [ ] Complete owner inventory, backups and migration review in [migration-review.md](migration-review.md). Rehearse on a production-like isolated copy. Obtain explicit approval before hosted SQL. Do not run historical migrations or blanket `supabase db push`.
- [ ] Confirm restricted castly_api, private transaction identity, JWT and TLS verification. Keep `CASTLY_NOTIFICATIONS_ENABLED=false`; Phase 3 does not require pending notification migrations.
- [ ] Provision reviewers and backend-only Google credentials/account-association key through approved secret workflows. Back up the stable association key and document migration-based rotation.
- [ ] Configure verified authenticated RTDN push, retry/dead-letter monitoring and alerting. Test duplicate, forged and unavailable-provider delivery.
- [ ] Approve any finite portfolio limits and existing-user handling; NULL defaults preserve current behavior. Do not advertise a numerical enhancement before configuring it.
- [ ] Validate real Play purchase/acknowledgement, pending, grace/hold/resume, cancellation/expiry, refund/revocation, restore/account switching and replacement flows using license testers.
- [ ] Review accessibility/visual/native platform behavior on physical devices; local component/browser tests are insufficient for native certification.
- [ ] Approve Render deployment, then deploy with subscription and Google flags off initially. Perform authorized read-only health/auth checks. Separately approve subscription rollout and Google purchase activation after staging evidence.
- [ ] Verify free discovery/bookings/messages/Realtime regressions in the approved hosted environment. No booking commission is introduced.

Rollback: disable Google checkout and subscriptions flags to stop new module activity, while preserving ledger/audit data. Do not drop new tables or roll back historical migrations. The existing compatibility entitlement table is maintained, but activation/deactivation and legacy manual adjustments must be reviewed together; disable native purchase entry points before disabling backend verification to avoid unverified paid purchases. Keep RTDN processing available while paid subscriptions exist, even if new checkout is suspended; the current shared Google enable flag requires an operational decision before disabling it. A separate checkout-only maintenance gate is recommended before live rollout if independent processing suspension is required.
