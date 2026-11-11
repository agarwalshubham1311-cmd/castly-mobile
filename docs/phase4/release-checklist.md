# Phase 4 release checklist

Current recommendation: code review and isolated staging preparation. Hosted activation is pending the gates below. No hosted SQL, Render deployment, EAS build, real credentials or feature activation occurred.

- [x] Isolated branch from stable `b86deb4`; parallel Phase 3 and APK worktrees preserved.
- [x] Forward-only SQL, API, mobile UI, RLS/authorization and local regression coverage prepared. All new feature flags and `CASTLY_NOTIFICATIONS_ENABLED` default false.
- [ ] Obtain owner read-only hosted inventory, exact guard-source comparison, backup and isolated-copy rehearsal; review/apply only six listed SQL files in order. Reject unexpected schema differences for a separate migration review. Keep pending notification migrations unapplied.
- [ ] Restart API with all Phase 4 flags false; confirm schema readiness and original booking, messaging, search and subscription flows, then provision narrowly scoped reviewers through the owner workflow.
- [ ] Enable verification first after reviewer and badge/portfolio review policy is staffed; verify trusted Auth contact state and expiry. Then enable reviews only after completion attestation UX and booking transitions pass staging checks.
- [ ] Enable safety reporting/blocking after abuse monitoring, reviewer staffing, appeal response policy and concurrent booking/message checks pass. Enable public trust indicators after counters reconcile; monitor asynchronous refresh. Keep private triage scoped and manual.
- [ ] Approve dispute text policy, case retention, appeals and deletion procedures before enabling disputes. For image evidence separately approve/version the privacy notice, consent, retention days, Storage bucket RLS and deletion operations; provision a dedicated retention scope and job. Set `CASTLY_DISPUTE_EVIDENCE_ENABLED=true` and policy version only after real Supabase Storage/JWT authorization and deletion tests. Consent must be renewed when the policy version changes.
- [ ] Test native Expo Go and Android build on supported SDK/device: navigation, image selection, permission denial, deep links, safe areas, TalkBack, font scaling and account switching. Record build/device/OS. Test hosted Realtime invalidation/recovery, Auth/JWKS/TLS, and actual Storage signed URL expiry/revocation limits.
- [ ] Obtain explicit production approval for hosted SQL, deployment, credentials and feature activation. Keep Google RTDN and subscriptions independent; no booking payment/escrow/refund flow is part of Phase 4.

Disable the affected feature flag first if staging detects a defect, preserve audit and evidence for reviewed cleanup, and do not reverse DDL or erase disputes as a shortcut. For image withdrawal, a previously issued signed URL may remain usable until its short expiry; Storage deletion must be confirmed, with `DELETING` retries monitored.
