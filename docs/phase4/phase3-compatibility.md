# Phase 3 compatibility

Phase 4 starts at stable development `b86deb4`, which already contains the reviewed Phase 3 merge (`9bf180e`). The parallel Phase 3 branch and worktree remain unchanged.

The initial full Java run reproduced a pre-existing concurrent verification failure: a transaction that began before the winning grant writer could see its committed grant under READ COMMITTED, but `starts_at <= now()` used the waiting transaction's earlier timestamp. A latch-controlled regression freezes the reader transaction timestamp, commits a later grant, then acquires the account lock. The old timestamp excludes the row; effective membership must include it.

The integrated branch narrowly uses PostgreSQL `statement_timestamp()` for effective grant, compatibility projection, and discovery expiry evaluation. Grant provenance, RLS, write guards, provider verification and transaction identity remain intact. The full 114-test Java suite passed after this correction; subsequent milestone checks will supersede this intermediate count. No code was changed in the separate Phase 3 worktree.

Combined and Phase4-without-Phase3 migration validation, final regressions, and release evidence are pending completion of later milestones.
