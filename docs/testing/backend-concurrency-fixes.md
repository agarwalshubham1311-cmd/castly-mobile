# Spring concurrency test investigation — 2026-11-19

Fix branch: `fix/backend-concurrency-tests`, based on milestone5 `3c12b515f5ba2e6ef67d0089d0fd49ea6c99a175` (the 160-test baseline in the supplied Windows run). Phase7 work and unrelated worktrees are unchanged. Only test code and this report change; no production implementation, configuration, feature flag, grant, policy, trigger or historical migration changes.

## Findings before the fix

The three original tests passed unchanged on the available Linux/Java21 fixture run. The supplied Windows failure output subsequently confirmed both discovery assertions returning the supposedly removed talent and the image test's gateway-entry latch timing out. Those outputs do not show a production authorization failure in these three cases. The booking/conversation SQLSTATE42501 warnings come from separate negative tests; the image's initial503 (policy unavailable) and counterparty404 are expected checks.

### Public-facts discovery recheck

`BoundedProvider.reorder()` waits at most250ms, cancels a late provider and returns a deterministic fallback. The test signalled provider entry, then launched Docker/psql to unpublish the creator. Docker startup/SQL commit could exceed250ms. Meanwhile the real provider timeout ended, `Phase5Service.computeShortlist()` ran its fresh RLS-filtered query **before** the withdrawal committed, and legitimately retained the then-published talent. The mock's `release.await(1 second)` return was ignored and could also self-resume. The test asserted a happens-before ordering it never established.

### Block discovery recheck

The same250ms-versus-Docker race applied to inserting the talent-to-client block. A committed block is filtered through `private.discovery_talent_visible()` and public-profile RLS, but the old test allowed the HTTP response to finish before that commit. Both tests now commit the mutation before releasing the provider-response boundary. The target must be in the initial candidate snapshot, and its absence in the200 response is still mandatory, for **both successful provider permutations and provider fallback**. A removed recheck would still fail these assertions. The actual provider's250ms deadline, malformed-response protection, cache boundaries and worker behavior remain covered by the existing unchanged `BoundedProviderTest`.

### Private-image evidence test

The gateway callback decoded the sanitized image and ran Storage SQL before signalling `started`. If either background operation failed, the worker Future completed exceptionally (or produced an unexpected HTTP response), but the main test never read it while waiting for `started`; the only reported failure was `assertTrue(started.await(10 seconds))`. Cleanup also closed/joined the executor before the outer finally released the callback. The callback's own ten-second release deadline could expire during slower driver checks.

The supplied log does **not** contain the original inner worker exception, so it cannot establish whether that individual Windows run failed in ImageIO, SQL argument handling/RLS or another pre-signal operation. No particular hosted/SQL privilege error is attributed without that evidence. The fix removes this masking: pending requests assert their HTTP status in the worker, entry waits inspect failed/completed Futures, Storage SQL/assertions run visibly on the driver while upload remains held, and resource cleanup releases before joining. Test image decoding is explicitly memory-only. Fixture SQL is sent as UTF-8 stdin with output drained, removing nested Windows command-line quoting and unread-pipe hazards. Windows execution itself was not available here.

## Safeguards retained and strengthened

- Consent must match an approved active evidence policy; private storage must be configured. Added independent negative checks for mismatched consent and unconfigured storage, with no upload call allowed.
- Storage inserts/deletion checks still execute under the actual `authenticated` role with caller claims, and application checks under restricted `castly_api`. No RLS or authorization check is disabled by this patch.
- Reserved evidence still cannot be deleted or have its immutable hash replaced; readiness, idempotent replay, counterparty denial, reviewer access boundaries, closed/expired denial and retention cleanup/retry assertions remain.
- Production image sanitization, five-minute upload lease, storage policy, privacy retention, feature guards and provider250ms deadline are unchanged.
- `ConcurrentBoundary` is test-only. Its30s watchdog fails the test; it never permits a timed-out callback to count as success. It releases in resource-close order before executor shutdown.

## Changed files

- `backend/src/test/java/com/castly/BusinessApiTest.java`: deterministic provider response seam, explicit upload handoff, foreground Storage RLS checks, memory-only image decode, stdin fixture helper and stronger consent/storage checks.
- `backend/src/test/java/com/castly/ConcurrentBoundary.java`: test event ordering and original worker-failure propagation.
- `backend/src/test/java/com/castly/ConcurrentBoundaryTest.java`: early failure/completion and release-before-executor-shutdown regressions.
- This report.

## Executed results

Java21.0.12.1 / Maven3.9.11 / Docker PostgreSQL17, isolated local fixtures only:

| Command/check | Result |
| --- | --- |
| Three original affected methods, before changes | PASS,3 tests; no stable local failure reproduced |
| Three affected methods, after fix | PASS,5 invocations (one image + two provider modes for each discovery test), zero failures/errors/skips |
| Full `mvn test`, run after targeted success | PASS,165 tests, zero failures/errors/skips |
| `git diff --check` | PASS |

The suite increases from160 to165 because of two additional discovery parameter cases and three handoff regression tests. Existing security assertions and HTTP expectations were not relaxed. Native-agent/Surefire channel warnings were emitted, but there were no skipped tests or test failures. No full-suite run preceded targeted success. No hosted Supabase, Render, provider, database migration or deployment was used.

## Windows PowerShell reproduction

Prerequisites: Java21, Maven, Node24 and Docker Desktop with Linux containers running. Tests prepare/reset only the named disposable local PostgreSQL fixture. Do not substitute production connection settings or run backend browser fixtures concurrently with this suite.

To fetch the isolated fix explicitly (this repository's clone may fetch only main by default), without changing your current branch or local files:

```powershell
git fetch origin 'refs/heads/fix/backend-concurrency-tests:refs/remotes/origin/fix/backend-concurrency-tests'
# Review or switch only when your working tree permits it. Never stash/reset unrelated edits.
git diff HEAD..origin/fix/backend-concurrency-tests -- backend/src/test/java/com/castly docs/testing/backend-concurrency-fixes.md
```

Run on the reviewed fix branch/worktree:

```powershell
$repoPath = git rev-parse --show-toplevel
Push-Location (Join-Path $repoPath 'backend')
try {
    mvn -version
    $affectedTests = 'BusinessApiTest#privateImagesRequireApprovedConsentAndCannotBeReplacedDuringFinalization+phase5RechecksBlockAfterProviderWait+phase5RechecksPublicFactsAfterProviderWait'
    mvn "-Dtest=$affectedTests" test
    if ($LASTEXITCODE -ne 0) { throw 'Affected tests failed; inspect target/surefire-reports before running the full suite.' }
    mvn test
    if ($LASTEXITCODE -ne 0) { throw 'Full backend suite failed; inspect target/surefire-reports.' }
} finally {
    Pop-Location
}
```

All execution evidence here is Linux-based. If the Windows image case still fails, the corrected handoff should now report the actual worker/SQL error rather than only a readiness timeout. **Phase6 worker security remains a separate release gate; these fixes and green local tests do not establish Phase6 production readiness.**
