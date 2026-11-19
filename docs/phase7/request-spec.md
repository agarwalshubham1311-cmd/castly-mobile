# CODEX MASTER PROMPT — CASTLY PHASE 7 & PHASE 8

## ROLE

Act as a Principal Software Architect, Senior Java Spring Boot Engineer, Next.js Engineer, DevSecOps Engineer, QA Lead, and Release Manager.

You are responsible for implementing Phase 7 and Phase 8 of **Castly**, a scalable talent-booking marketplace connecting creators, influencers, models, actors, and clients.

Your objective is to deliver production-grade, secure, maintainable software suitable for scaling to millions of users.

Do not create a disconnected demo application. Work directly within the existing Castly repository and preserve its architecture, conventions, and working features.

## EXISTING TECHNOLOGY

- Mobile: Expo React Native, TypeScript
- Backend: Java 21, Spring Boot
- Database: PostgreSQL hosted through Supabase
- Authentication: Existing Supabase Auth/JWT integration
- Infrastructure: Render, Supabase, Expo EAS
- Payments: Google Play Billing and existing subscription infrastructure
- Admin dashboard: Next.js App Router, React, TypeScript
- Styling: Tailwind CSS and a reusable component library
- Testing: Existing repository test frameworks, supplemented where necessary

Repository: `agarwalshubham1311-cmd/castly-mobile`

Working branch: `feat/milestone5-booking-messaging-notifications`

Before implementation, inspect the actual repository and identify existing modules, API contracts, security policies, migrations, deployment configuration, and tests.

Do not assume Phase 6 is production-ready merely because its local SQL tests pass.

---

# GLOBAL IMPLEMENTATION RULES

1. Never modify production Supabase, Render, Google Play, Apple, email providers, or other external services without explicit approval.
2. Never run `supabase db push`, production migrations, destructive SQL, production deployments, or credential rotations automatically.
3. Preserve all existing booking, messaging, discovery, subscription, notification, and trust workflows.
4. Use Java Spring Boot for all privileged backend operations.
5. Never connect the Next.js dashboard directly to PostgreSQL using privileged credentials.
6. Implement server-side RBAC, permission checks, audit logging, rate limiting, and input validation.
7. Follow forward-only database migration practices.
8. Make migrations safe to rehearse against isolated PostgreSQL before proposing production execution.
9. Do not weaken RLS or grant blanket database permissions.
10. Avoid introducing new services unless their operational benefits justify them.
11. Prefer keyset pagination, bounded queries, and indexed filters.
12. Build responsive, accessible, production-quality interfaces.
13. Never hardcode credentials, service keys, or secrets.
14. Never use production personal data for local tests.
15. Require explicit approval before any externally visible action affecting real users.
16. Provide exact PowerShell-compatible commands for every action I must perform manually.
17. At the end of each milestone, provide changed files, test results, unresolved risks, and next steps.
18. Do not silently skip tests or claim success without executing them.
19. Do not rewrite unrelated files or perform unnecessary rollbacks.
20. Never automatically enable paid infrastructure or third-party providers.

Create a separate feature branch for Phase 7 and Phase 8 work. Do not overwrite existing uncommitted changes.

---

# PHASE 7 — ADMIN DASHBOARD & OPERATIONS

## Goal

Give the Castly operations team secure administrative control over users, talent profiles, bookings, moderation, subscriptions, and marketplace analytics.

Build the dashboard in:

`admin-dashboard/`

Use Next.js, TypeScript, Tailwind CSS, and Spring Boot REST APIs.

## M7.1 — Admin Authentication, RBAC & Audit Logs

### Features

Implement a secure admin authentication system integrated with existing identity infrastructure.

Roles:

- SUPER_ADMIN — platform administration and role management
- OPERATIONS_ADMIN — marketplace operations
- MODERATOR — reports and content moderation
- SUPPORT_AGENT — restricted customer support
- FINANCE_ADMIN — subscription and revenue reporting
- ANALYST — read-only analytics

Define granular permissions rather than relying exclusively on role names.

Examples:

- `users.read`
- `users.suspend`
- `users.verify`
- `bookings.read`
- `bookings.manage`
- `reports.review`
- `moderation.action`
- `subscriptions.read`
- `finance.read`
- `analytics.read`
- `admins.manage`
- `audit.read`

Implement:

- Admin login and logout
- Secure session handling
- MFA enforcement for privileged users
- Server-side permission enforcement
- Admin invitation and activation
- Session expiration and revocation
- Role assignment and removal
- Permission-aware navigation
- Audit logs
- Suspicious-login detection
- Rate limiting
- Reauthentication for sensitive actions

Audit records must capture:

- Actor identity
- Action
- Target resource
- Timestamp
- Request/correlation ID
- Reason for action
- Before/after changes, excluding secrets
- Outcome
- Appropriate IP/device metadata

Audit logs must be append-only from application roles, tamper-resistant, and accessible only to authorized administrators.

Do not allow self-promotion to SUPER_ADMIN.

Initial administrator provisioning must require a documented, separately approved bootstrap procedure.

### Deliverables

- Admin authentication integration
- RBAC schema and authorization services
- Audit logging
- Admin login page
- Admin user/role management
- Authentication and authorization tests
- Permission matrix documentation

### Acceptance criteria

- Non-admin users cannot access admin APIs.
- UI restrictions are not the sole authorization control.
- Unauthorized role escalation fails.
- Sensitive actions are audited.
- Admin access can be revoked.
- Admin sessions and MFA work as designed.

---

## M7.2 — User Management

### Features

Build an operational user management module.

**User directory**

- Search users
- Filter by account type, status, verification state, and creation date
- View user profile summary
- View account history
- View subscription status
- View associated talent profile
- View relevant bookings
- Review suspicious activity

**Account verification**

- Verification review queue
- Evidence/document review where supported
- Approve or reject verification
- Record rejection reasons
- Verification history
- Prevent unauthorized verification changes

**Account suspension**

- Temporary suspension
- Permanent suspension where policy permits
- Reason codes
- Effective date and expiration
- Account reinstatement
- Appeal and review workflow
- Audit trail

Suspension enforcement must occur in backend authorization and business services, not just in the admin UI.

Protect existing bookings and financial records from unintended deletion.

Do not expose unnecessary identity documents or sensitive information to support agents.

### Dashboard pages

- `/users`
- `/users/[id]`
- `/users/verifications`
- `/users/suspensions`

### Acceptance criteria

- Search and pagination work with large datasets.
- Suspension blocks the appropriate protected operations.
- Existing records remain consistent.
- Unauthorized administrators cannot suspend accounts.
- Every action is auditable.

---

## M7.3 — Marketplace Operations

### Booking oversight

Create an admin booking dashboard with:

- Booking search and filtering
- Status and lifecycle visibility
- Client and talent references
- Booking history
- Cancellation and dispute indicators
- Operational exceptions
- Stale booking detection
- Escalation queues

Do not introduce unrestricted admin booking edits.

Any exceptional state transition must follow a defined state machine, require authorization and justification, and preserve historical records.

### Reports and moderation

Implement:

- User reports queue
- Talent profile reports
- Content moderation queue
- Report assignment
- Priority and severity
- Review notes
- Evidence references
- Moderation decisions
- Escalations
- Appeals
- SLA tracking
- Resolution history

Moderation actions:

- Approve content
- Reject content
- Hide content
- Restrict visibility
- Suspend account
- Escalate for review

Use existing trust, safety, and reporting modules where available.

### Marketplace monitoring

Track:

- Booking requests
- Accepted bookings
- Declined bookings
- Cancelled bookings
- Completed bookings
- Booking conversion
- Unresolved reports
- Moderation backlog
- Response delays
- Operational incidents

### Dashboard pages

- `/bookings`
- `/bookings/[id]`
- `/reports`
- `/moderation`
- `/operations`

### Acceptance criteria

- Admin actions respect existing booking rules.
- Reports are processed without losing history.
- Sensitive booking information is permission-restricted.
- Concurrent moderation actions are handled safely.
- Every moderation decision is audited.

---

## M7.4 — Business Analytics

### Goal

Provide a reliable executive and operational view of Castly's marketplace performance.

### Revenue analytics

Implement:

- Active paid subscriptions
- New subscriptions
- Renewals
- Expired subscriptions
- Subscription cancellations
- Revenue trends
- Revenue by plan
- Estimated recurring revenue
- Churn rate
- Subscription conversion

Use only verified payment or entitlement records appropriate to the metric.

Do not treat booking value as Castly revenue.

Do not count unverified billing events as settled revenue.

Clearly label estimated versus verified metrics.

### User growth

Track:

- Total registered users
- New users
- Active users
- Talent registrations
- Client registrations
- Completed onboarding
- Activation rate
- Retention cohorts

### Marketplace KPIs

Track:

- Booking requests
- Booking acceptance rate
- Booking completion rate
- Booking cancellation rate
- Time to first booking
- Active talent
- Active clients
- Supply/demand distribution
- Talent profile engagement
- Search-to-booking conversion where supported

### Dashboard capabilities

- Date-range filters
- Daily, weekly, monthly views
- KPI cards
- Trend charts
- Comparison periods
- Filterable tables
- Authorized CSV export
- Data freshness indicators
- Empty/loading/error states

For analytics at scale, use appropriate indexed aggregation tables or materialized views rather than repeatedly scanning transactional tables.

Clearly document KPI formulas, data provenance, and refresh intervals.

Respect analytics consent, data minimization, and retention rules.

### Dashboard pages

- `/dashboard`
- `/analytics/users`
- `/analytics/marketplace`
- `/analytics/revenue`
- `/analytics/subscriptions`

### Acceptance criteria

- Every KPI has a documented formula.
- Revenue numbers reconcile with the authoritative billing source.
- Date filters produce consistent results.
- Dashboard queries remain bounded.
- Sensitive data is not exposed to unauthorized roles.

---

# ADMIN DASHBOARD DESIGN REQUIREMENTS

Use a professional SaaS operations dashboard style.

Visual direction:

- Clean, modern, premium interface
- Light theme by default
- Lavender/purple accents consistent with Castly branding
- Optional dark theme
- Responsive sidebar navigation
- Compact operational tables
- Clear status indicators
- Accessible contrast and keyboard navigation

Main navigation:

1. Overview
2. Users
3. Verifications
4. Bookings
5. Reports
6. Moderation
7. Subscriptions
8. Analytics
9. Audit Logs
10. Settings

Build reusable components:

- App shell
- Sidebar
- Header
- KPI cards
- Charts
- Data tables
- Search and filter controls
- Pagination
- Status indicators
- Confirmation dialogs
- Audit timeline
- Error and loading states

Do not use mock data as if it were real production analytics.

Mock fixtures may be used only in development and automated tests.

---

# PHASE 8 — TESTING, SECURITY & PRODUCTION LAUNCH

## Goal

Prepare Castly for real users through automated testing, security validation, performance engineering, production infrastructure, and controlled mobile releases.

Phase 8 must not be treated as authorization to deploy or publish.

---

## M8.1 — Automated Testing

Build a comprehensive testing strategy covering mobile, backend, admin dashboard, database, and integrations.

### Backend

- JUnit 5
- Spring Boot integration tests
- Spring Security tests
- API contract tests
- PostgreSQL integration tests
- Migration tests
- Transaction rollback tests
- Concurrency tests
- Negative authorization tests

### Mobile

- Unit tests
- Component tests
- Navigation tests
- Authentication tests
- Booking workflow tests
- Messaging tests
- Subscription tests
- Notification permission tests
- Account-switching isolation tests

### Admin dashboard

- Component tests
- Authentication tests
- RBAC tests
- User management tests
- Moderation workflow tests
- Analytics tests
- Browser E2E tests using Playwright

### Integration testing

Cover:

- Registration to onboarding
- Talent discovery to booking
- Booking acceptance to messaging
- Subscription purchase and entitlement updates
- Report submission to moderation
- Account suspension and reinstatement
- Admin login to audited action
- Notification consent and delivery suppression

Use disposable local databases and mocked external providers.

Make all test runners compatible with Windows, Linux, and CI.

Fix hardcoded `/tmp` and `/usr/bin/chromium` assumptions.

### Acceptance criteria

- Critical workflows pass automated tests.
- All admin authorization boundaries have negative tests.
- Database migrations are reproducible.
- CI fails on test regressions.
- Test coverage reports are generated.
- No tests require production credentials.

---

## M8.2 — Security Testing

Perform security testing across the complete platform.

### Authentication and authorization

- Broken access control
- IDOR/BOLA
- Privilege escalation
- JWT validation
- Admin role bypass
- MFA bypass
- Session fixation
- Token expiration
- Cross-account data access
- Database role escalation

### Application security

- SQL injection
- XSS
- CSRF
- SSRF
- Insecure file uploads
- Malicious URL handling
- Input validation
- Sensitive data exposure
- Unsafe logging
- Security misconfiguration

### Marketplace abuse

- Fake accounts
- Spam booking requests
- Messaging abuse
- Fake reviews
- Verification fraud
- Report abuse
- Notification spam
- Subscription entitlement manipulation
- Replay attacks

### Automated security tools

Integrate suitable tools such as:

- OWASP Dependency-Check or equivalent
- npm audit
- Trivy
- Semgrep
- Secret scanning
- Dependency update monitoring

Generate a vulnerability report categorized by severity.

Do not automatically apply breaking dependency upgrades.

### Acceptance criteria

- No unresolved critical vulnerabilities.
- High-severity issues require documented remediation or explicit risk acceptance.
- Authorization boundaries have automated negative tests.
- Security scan reports are retained as CI artifacts.
- Production secrets never appear in logs or repositories.

---

## M8.3 — Performance Testing

### Target

Design and validate a path to supporting millions of registered users, with capacity driven by measured concurrent usage and traffic patterns.

Do not claim million-user readiness without load-test evidence.

### Test scenarios

- Login and token verification
- Talent discovery and search
- Talent profile retrieval
- Booking creation
- Booking status updates
- Messaging
- Saved talent
- Subscription entitlement reads
- Notification queue processing
- Admin user search
- Analytics dashboard queries

### Load testing

Use k6 or an equivalent framework.

Create reproducible scenarios for:

- Baseline traffic
- Sustained load
- Peak load
- Spike testing
- Soak testing
- Recovery after failure

Measure:

- p50, p95, p99 latency
- Throughput
- Error rate
- Database CPU
- Connection pool usage
- Memory consumption
- Slow queries
- Queue lag
- Worker throughput

Propose initial SLO targets and validate them with the owner before treating them as release gates.

### Optimization

Investigate:

- PostgreSQL indexes
- Query plans
- N+1 queries
- Connection pooling
- Redis caching where justified
- Cache invalidation
- Pagination
- Rate limiting
- Async processing
- Background jobs
- Analytics pre-aggregation

Do not introduce Redis or Kafka solely because the application may eventually scale.

### Acceptance criteria

- Load tests are reproducible.
- Bottlenecks are documented.
- Performance changes are supported by before/after measurements.
- Load tests do not target production without explicit authorization.

---

## M8.4 — Production Infrastructure

Implement infrastructure readiness using the existing Render, Supabase, Expo, and CI/CD stack.

### CI/CD

Create or improve pipelines for:

- Code formatting
- Linting
- Type checking
- Unit tests
- Integration tests
- Security scanning
- Backend builds
- Admin dashboard builds
- Mobile build validation
- Artifact generation

Use protected environments and manual approval for production deployment.

### Infrastructure

Implement or document:

- Development environment
- Staging environment
- Production environment
- Environment-specific configuration
- Secrets management
- Database backup and restore procedures
- Disaster recovery
- Database migration approval process
- Health checks
- Structured logs
- Metrics
- Distributed tracing where appropriate
- Error monitoring
- Alerting
- Deployment rollback
- Incident response runbooks

### Release safeguards

- Feature flags default to disabled for unfinished capabilities.
- Push and email delivery providers remain disabled until approved.
- No production migration runs automatically.
- Database backups are verified before migration.
- Migration rehearsals use isolated databases.
- Rollback plans distinguish reversible application changes from forward-only database changes.
- Production secrets are managed outside Git.
- Release pipelines require manual approval.

### Acceptance criteria

- CI/CD is reproducible.
- Staging deployment can be validated independently.
- Backups have a tested restore procedure.
- Monitoring detects critical failures.
- Secrets are not committed.
- Production deployment requires explicit approval.

---

## M8.5 — Android & iOS App Launch

Prepare the mobile app for store submission.

### Android

- Validate Expo/EAS configuration
- Verify Android package identity
- Configure release signing
- Create production AAB
- Validate Google Play Billing
- Configure privacy policy
- Configure Data Safety disclosures
- Prepare store listing
- Prepare screenshots and icons
- Configure internal testing
- Configure closed testing where required
- Validate subscription products
- Prepare staged rollout
- Document rollback and incident response

### iOS

- Configure Apple Developer project
- Validate bundle identifier
- Configure certificates and provisioning
- Build production IPA
- Configure App Store Connect
- Validate In-App Purchase integration
- Prepare App Privacy disclosures
- Prepare screenshots and metadata
- Configure TestFlight
- Validate iOS permissions
- Prepare App Review submission

Do not assume the current Android billing integration automatically satisfies iOS payment requirements.

### Release readiness

Create a checklist covering:

- Authentication
- Onboarding
- Talent discovery
- Booking
- Messaging
- Subscription billing
- Notifications
- Account deletion
- Privacy controls
- Support contact
- Crash monitoring
- Backend availability
- Database backup
- Admin operations
- Security
- Performance
- Store policy compliance

### Approval gates

Do not automatically:

- Upload production builds
- Submit apps for review
- Publish to Google Play
- Publish to App Store
- Enable paid subscriptions
- Enable push/email delivery
- Deploy production backend changes
- Apply hosted database migrations

Prepare the required artifacts and request explicit approval for each production action.

---

# IMPLEMENTATION ORDER

Execute in this sequence:

**Phase 7**

1. M7.1 — Admin Authentication, RBAC, Audit Logs
2. M7.2 — User Management
3. M7.3 — Marketplace Operations
4. M7.4 — Business Analytics

**Phase 8**

5. M8.1 — Automated Testing
6. M8.2 — Security Testing
7. M8.3 — Performance Testing
8. M8.4 — Production Infrastructure
9. M8.5 — App Launch

Security and automated testing must also be implemented incrementally throughout Phase 7, rather than deferred entirely to Phase 8.

# REQUIRED DELIVERABLES

Create and maintain:

- `docs/phase7-architecture.md`
- `docs/phase7-admin-rbac.md`
- `docs/phase7-api-contracts.md`
- `docs/phase7-milestones.md`
- `docs/phase8-test-strategy.md`
- `docs/phase8-security-checklist.md`
- `docs/phase8-performance-plan.md`
- `docs/phase8-infrastructure.md`
- `docs/phase8-launch-checklist.md`

Adjust paths to existing repository conventions where appropriate.

For every milestone, provide:

1. Implementation summary
2. Architecture decisions
3. Files created or modified
4. Database migrations
5. API endpoints
6. UI pages and components
7. Tests executed and results
8. Security findings
9. Remaining risks
10. Exact next commands

# START NOW

First, inspect the repository and produce:

1. Existing architecture assessment
2. Phase 6 dependency and readiness assessment
3. Proposed Phase 7 architecture
4. Database and RBAC design
5. Admin dashboard sitemap
6. REST API design
7. Milestone implementation plan
8. Risks and dependencies

Then begin implementing **M7.1 — Admin Authentication, RBAC & Audit Logs** on a new feature branch.

Implement production-quality code and tests.

Do not stop after writing a plan.

Continue through M7.1 until the code, local migration rehearsal, UI, and automated tests are complete.

Stop before any production migration, deployment, external notification, privileged administrator provisioning, or other action requiring explicit approval.

Do not begin M7.2 until M7.1 has been reviewed and approved.