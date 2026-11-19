# Administrative permissions and security model

| Role | Permissions |
| --- | --- |
| SUPER_ADMIN | All below plus admins.manage, audit.read |
| OPERATIONS_ADMIN | users.read, bookings.read, bookings.manage, reports.review, analytics.read |
| MODERATOR | users.read, reports.review, moderation.action |
| SUPPORT_AGENT | users.read, bookings.read |
| FINANCE_ADMIN | subscriptions.read, finance.read |
| ANALYST | analytics.read |

Only SUPER_ADMIN receives users.suspend/users.verify initially; assigning these powerful operations more broadly requires a reviewed migration. Permissions are server-derived, unioned across bounded roles, never accepted from the browser. Every admin requires TOTP AAL2. Super-admin creation/removal and self changes are forbidden through APIs, including invitations. Initial provisioning is an owner-reviewed, separately executed bootstrap transaction. The application cannot promote anyone to SUPER_ADMIN.

A principal is pending/active/revoked via invitation activation and management. Roles/state changes increment a security version; all previous sessions become unusable. A user can revoke their own session. With admins.manage + fresh MFA, changing another principal's roles/state (including reapplying the same values) increments the security version and invalidates all their sessions. Super admins are owner-managed only. Read endpoints still require a valid session. Invitation activation requires the exact pre-existing target account, a single-use 256-bit secret stored only as SHA-256, expiry and fresh MFA. Invitations never send email; an operator must approve out-of-band handoff. Self-invitation/overwrite of existing principals is prohibited. Reasons are required and bounded; do not enter secrets or personal document content.

Audit contains actor, action enum, resource UUID, server timestamp, generated correlation ID, bounded reason, safe before/after role/state/session metadata, outcome and HMAC-pseudonymized remote IP/coarse browser family. Never JWTs, password, factor code, invitation secret/hash or full user agent. No forwarded IP is trusted by default. New admin mutations require triggers to append their audit event transactionally. Application roles cannot update/delete audit rows or edit authorization projection directly. Denials are appended in a separate transaction. Operators should export signed/immutable audit batches to an independently managed sink after separate approval; database-owner tampering remains an explicit threat-model limit.
