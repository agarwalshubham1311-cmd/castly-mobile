# PR #17 PostgreSQL fixture readiness correction

27 November 2026. Scope: disposable test infrastructure only. No application logic, historical migration, production configuration, activation flag, authorization check or test expectation is weakened.

## Observed failure and reproduction

The public PR check pages report failure in `backend (repository)` and `sql-security`; the latter fails in `node backend/scripts/prepare-test-db.cjs`. The backend reconciliation job was cancelled by matrix fail-fast. Admin and mobile checks passed. Authenticated GitHub API/log access is unavailable in this workspace, so the original private step output could not be inspected.

Both failed jobs execute the same PostgreSQL preparation script. Its readiness probe used `pg_isready` without a host, which accepts the Unix-socket-only server used by the official PostgreSQL image during initialization. That server stops before the final TCP server starts. A successful socket probe therefore does not establish readiness for the migrations or Spring JDBC.

A controlled disposable-container rehearsal added a two-second initialization script and launched the unchanged preparation script while the temporary server was running. It failed at migration preparation with:

```text
psql: error: connection to server on socket "/var/run/postgresql/.s.PGSQL.5432" failed:
FATAL: the database system is shutting down
```

Ordinary unperturbed cold starts can pass, explaining why repeated warm/local validation does not exercise this transition reliably. This is a reproduced defect in the shared failing path, not a claim that inaccessible original CI logs were read.

## Correction

- `backend/scripts/postgres-readiness.cjs` waits for `pg_isready -h 127.0.0.1 -p 5432 -U postgres -d postgres` inside the disposable container. The temporary initialization server has no TCP listener and cannot release preparation early.
- The bounded probe allows 60 attempts with 500 ms between unsuccessful attempts. It fails closed on timeout and avoids spawning another Node process merely to pause.
- Both Spring fixture preparation and the reconciliation fixture use the same helper.
- Backend and SQL CI jobs run the three Node readiness regressions before executing their existing complete test suites.

The Docker password, JDBC configuration, TLS behavior, restricted roles, migration order, SQL/RLS tests and all existing CI jobs remain unchanged.

## Regression evidence

The initialization-socket regression fails with the old probe and passes with the TCP probe. The tests also cover transient startup failures and bounded timeout. The same real-container delayed-initialization rehearsal that failed before the change completes successfully after it.

Portable validation commands from the repository root:

```powershell
node --test .\backend\scripts\postgres-readiness.test.cjs
node .\backend\scripts\prepare-test-db.cjs
Push-Location .\backend
mvn clean verify
$env:CASTLY_TEST_SCHEMA_PATH = 'reconciliation'
mvn clean verify
Remove-Item Env:\CASTLY_TEST_SCHEMA_PATH
Pop-Location
Remove-Item Env:\SUPABASE_TEST_DATABASE_URL -ErrorAction SilentlyContinue
$env:CASTLY_TEST_DB_CONTAINER = 'castly-spring-test-db'
npm run test:sql
npm run test:reconciliation
npm run typecheck
npm run lint
npm test
```

Run the database suites sequentially. They use disposable local databases only. Final execution and GitHub check results are reported with the PR delivery; no hosted Supabase, Render or EAS action is part of this correction.
