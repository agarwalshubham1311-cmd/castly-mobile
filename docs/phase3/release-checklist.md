# Release readiness — target 2026-11-13

Recommendation: **local implementation is reviewable; live monetization is not ready for activation** until the following external gates pass. No hosted migration, Render deployment, EAS build, secret provisioning or real billing activation occurred.

- [ ] Review the final branch and PR against development 02361e7. Reconciliation is complete; prior local Render preparation d065bf5 was preserved as replayed commit d60aab4. Never overwrite the parallel APK worktree or force-push the base.
- [ ] Review current official Google/Expo/library compatibility and approve a native custom build; validate Expo 58/RN 0.88 + installed Expo IAP module on Android devices.
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

Rollback: first set `CASTLY_GOOGLE_PLAY_CHECKOUT_ENABLED=false` to stop new checkout while keeping provider verification/restore/RTDN available for existing purchases. Preserve grant/audit data. Do not drop tables or reverse historical migrations. Full module deactivation requires an operational review of active memberships and the compatibility projection. Do not disable provider processing while paid subscriptions still require renewals/refund handling.
