# Phase 3 compatibility

The Phase 4 branch starts at stable `b86deb4`, which already merged Phase 3 PR #5 (`9bf180e`). The parallel Phase 3 worktree remains unchanged. Combined migrations and the pre-Phase-4 compatibility fixture use disposable local PostgreSQL; neither represents a hosted schema inventory.

The integrated branch contains a narrow temporal race fix in Phase 3 entitlement reads: `statement_timestamp()` replaces transaction-frozen `now()` for effective grant, compatibility projection and discovery expiry checks after concurrent account locking. A latch regression freezes the reader transaction's start before a later grant commits; the original read would miss the committed grant. Grant provenance, Auth, TLS, RLS and transaction identity remain unchanged. Any independent Phase 3 deployment needs its own review and backport of this fix; do not treat Phase 4's branch as permission to change the separate Phase 3 branch.

With all Phase 4 flags false, the separate unmigrated schema test exercises booking creation, acceptance, messages, history and cancellation before Phase 4 SQL exists. The release sequence must first apply reviewed schema, restart with flags false, then enable one ready feature at a time. Subscriptions and Google RTDN remain independent; `CASTLY_NOTIFICATIONS_ENABLED=false` and pending notification migrations stay unapplied in hosted environments.
