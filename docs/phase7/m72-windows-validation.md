# M7.2 Windows PowerShell validation

These commands use only disposable local PostgreSQL/HTTP fixtures. Docker Desktop must be running; Node 24, Java 21 and Maven must already be installed. Use `java -version`, `mvn -version`, `node --version`, and `docker info` to confirm them. Set JAVA_HOME to your actual installed Java 21 directory if Maven selects another Java version.

The branch is **local and unpublished**. Do not expect `git fetch origin` to find it. The optional review bundle includes local M7.2 commits relative to authorized base `dbf5ab228db2ae1acf263678a0727d73c0ee6022`. Download it manually before these optional import commands. No push, reset, stash or overwrite is needed:

```powershell
Set-Location 'C:\Users\shubh1504613\castly\castly-milestone5'
git status --short
$bundle = Join-Path $env:USERPROFILE 'Downloads\castly-m72-review.bundle'
git bundle verify $bundle
if ($LASTEXITCODE -ne 0) { throw 'Bundle prerequisites or integrity failed; stop.' }
git fetch $bundle 'refs/heads/feat/m7.2-user-talent-management:refs/heads/review/m72-local'
if ($LASTEXITCODE -ne 0) { throw 'Review branch import failed; do not force it.' }
git worktree add '..\castly-m72-review' 'review/m72-local'
if ($LASTEXITCODE -ne 0) { throw 'Worktree creation failed; preserve existing changes.' }
Set-Location '..\castly-m72-review'
git branch --show-current
git rev-parse HEAD
git status --short
git diff --check
```

If already in the authorized M7.2 worktree at the reported review commit, start below. Run database/browser suites **sequentially**: backend and real-browser fixtures recreate `castly_spring_test`. Do not use a hosted DATABASE_URL or Supabase project for these suites.

```powershell
npm ci
if ($LASTEXITCODE -ne 0) { throw 'Mobile npm ci failed' }
npm --prefix .\admin-dashboard ci
if ($LASTEXITCODE -ne 0) { throw 'Admin npm ci failed' }
Push-Location .\backend
mvn clean verify *> maven-m72-fresh.log
if ($LASTEXITCODE -ne 0) { Get-Content maven-m72-fresh.log -Tail 80; throw 'Fresh backend verification failed' }
Select-String -Path maven-m72-fresh.log -Pattern 'Tests run:|BUILD SUCCESS|BUILD FAILURE'
$env:CASTLY_TEST_SCHEMA_PATH = 'reconciliation'
mvn clean verify *> maven-m72-reconciled.log
if ($LASTEXITCODE -ne 0) { Get-Content maven-m72-reconciled.log -Tail 80; throw 'Reconciled backend verification failed' }
Select-String -Path maven-m72-reconciled.log -Pattern 'Tests run:|BUILD SUCCESS|BUILD FAILURE'
Remove-Item Env:\CASTLY_TEST_SCHEMA_PATH
Pop-Location
Remove-Item Env:\SUPABASE_TEST_DATABASE_URL -ErrorAction SilentlyContinue
$env:CASTLY_TEST_DB_CONTAINER = 'castly-spring-test-db'
npm run test:sql
if ($LASTEXITCODE -ne 0) { throw 'SQL security tests failed' }
npm run test:reconciliation
if ($LASTEXITCODE -ne 0) { throw 'SQL reconciliation tests failed' }
npm run typecheck
if ($LASTEXITCODE -ne 0) { throw 'Mobile typecheck failed' }
npm run lint
if ($LASTEXITCODE -ne 0) { throw 'Mobile lint failed' }
npm test
if ($LASTEXITCODE -ne 0) { throw 'Mobile unit tests failed' }
npm run test:components
if ($LASTEXITCODE -ne 0) { throw 'Mobile component tests failed' }
npm --prefix .\admin-dashboard run typecheck
if ($LASTEXITCODE -ne 0) { throw 'Admin typecheck failed' }
npm --prefix .\admin-dashboard run lint
if ($LASTEXITCODE -ne 0) { throw 'Admin lint failed' }
npm --prefix .\admin-dashboard run format
if ($LASTEXITCODE -ne 0) { throw 'Admin formatting failed' }
npm --prefix .\admin-dashboard run test:coverage
if ($LASTEXITCODE -ne 0) { throw 'Admin unit/coverage tests failed' }
npm --prefix .\admin-dashboard run build
if ($LASTEXITCODE -ne 0) { throw 'Admin production build failed' }
Push-Location .\admin-dashboard
npx playwright install chromium
if ($LASTEXITCODE -ne 0) { throw 'Browser install failed' }
$env:CASTLY_JAVA = Join-Path $env:JAVA_HOME 'bin\java.exe'
$env:CASTLY_ADMIN_SCREENSHOT_DIR = Join-Path (Get-Location).Path '..\artifacts\m72-completion\screenshots'
npm run test:integration
if ($LASTEXITCODE -ne 0) { throw 'Real admin integration failed' }
Pop-Location
git diff --check
```

The admin runner already supports Windows child-process handling. It uses local fixture JWT signing/MFA, real Spring and restricted PostgreSQL; providers/workers are explicitly disabled in its child environment. Run `npm` scripts from their stated package: root has no `format`, `test:coverage`, `build`, or `test:integration` scripts for the admin dashboard.

For the deterministic connected mobile fixture and existing real Spring browser regression, export an isolated client with **local fixture values**, not hosted credentials:

```powershell
$env:EXPO_NO_DOTENV = '1'
$env:EXPO_NO_TELEMETRY = '1'
$env:EXPO_PUBLIC_BACKEND_MODE = 'supabase'
$env:EXPO_PUBLIC_BUSINESS_API_MODE = 'spring'
$env:EXPO_PUBLIC_SUPABASE_URL = 'http://127.0.0.1:8098'
$env:EXPO_PUBLIC_SUPABASE_ANON_KEY = 'sb_publishable_mobile_local_fixture'
$env:EXPO_PUBLIC_API_URL = 'http://127.0.0.1:8080'
$clientExport = Join-Path $env:TEMP 'castly-m72-connected'
npx expo export --platform web --output-dir $clientExport
if ($LASTEXITCODE -ne 0) { throw 'Connected mobile export failed' }
$env:WEB_EXPORT_DIR = $clientExport
$env:PORT = '8086'
node .\scripts\serve-web.cjs
```

Leave that terminal serving the export. In another PowerShell terminal, in the same worktree:

```powershell
$env:TEST_URL = 'http://127.0.0.1:8086'
$env:CASTLY_JAVA = Join-Path $env:JAVA_HOME 'bin\java.exe'
$env:CHROMIUM_PATH = (& node -e "process.stdout.write(require('playwright').chromium.executablePath())")
npm run test:mobile-api
if ($LASTEXITCODE -ne 0) { throw 'Mobile HTTP fixture failed' }
npm run test:backend
if ($LASTEXITCODE -ne 0) { throw 'Real Spring mobile browser regression failed' }
```

For the mock visual regression, use another static export/server, separate from the connected fixture:

```powershell
$env:EXPO_NO_DOTENV = '1'
$env:EXPO_NO_TELEMETRY = '1'
$env:EXPO_PUBLIC_BACKEND_MODE = 'mock'
npm run export:web
if ($LASTEXITCODE -ne 0) { throw 'Mock web export failed' }
$env:WEB_EXPORT_DIR = 'dist-web'
$env:PORT = '8082'
node .\scripts\serve-web.cjs
```

In another terminal in the worktree:

```powershell
$env:TEST_URL = 'http://127.0.0.1:8082'
$env:CHROMIUM_PATH = (& node -e "process.stdout.write(require('playwright').chromium.executablePath())")
$env:CASTLY_SCREENSHOT_DIR = 'artifacts/m72-completion/regression/screenshots'
$env:CASTLY_ACCESSIBILITY_DIR = 'artifacts/m72-completion/regression/accessibility'
npm run test:browser
if ($LASTEXITCODE -ne 0) { throw 'Mock navigation regression failed' }
npm run test:ui
if ($LASTEXITCODE -ne 0) { throw 'Mock UI regression failed' }
npm run test:screenshots
if ($LASTEXITCODE -ne 0) { throw 'PNG validation failed' }
npm run test:accessibility
if ($LASTEXITCODE -ne 0) { throw 'Mobile accessibility regression failed' }
```

These browser/Java checks are not native Android/iOS testing. Do not enable notifications/workers, native checkout, hosted flags or apply hosted SQL as part of validation. New local PNG generation changes only the explicit review artifact directories.
