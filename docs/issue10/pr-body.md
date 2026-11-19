Closes #10. Uses #6 as the Phase 5 feature specification.

Phase 5 was built on the earlier Phase 3 baseline. This branch starts at deployed Phase 4 `23be56e0444d37aa97d9d54fd524b63d84590b07`, cherry-picks the four Phase 5 commits, resolves both phases' configuration/service/test conflicts, and integrates public discovery with Phase 4's symmetric blocking contract. Search, matching, recommendations, briefs and comparisons now omit blocked/unpublished creators without exposing private trust reasons. Restrictive policies also hide blocked region/calendar/busy projection reads. Existing Phase 4 security, private evidence and booking/messaging behavior are retained.

All four Phase 5 flags default false. The AI provider stays disabled; current suggestions are labeled deterministic. Free creator eligibility and consented/unknown availability behavior are preserved. Reports, warnings and APPEALED disputes are not automatic creator bans: the repository has no creator suspension state. Actual hosted catalog comparison is still a release gate; production has not been contacted.

Validation: Java 21 clean verify passes 136 tests on each fresh/reconciliation schema path; TypeScript/lint, 72 Node tests, 13 components, all SQL/RLS suites, 344 reconciliation assertions, both web exports, real Spring/browser block/unblock + Phase 5 + Phase 4 + booking/message/subscription regression flows, mobile HTTP fixture, and mock browser/portraits/UI/screenshots/accessibility pass. No native device, hosted Realtime, live AI or production performance claim.

Reviewed forward migration order after owner-approved hosted Phase 3+4 inventory:
1. `20261111100000_phase5_discovery.sql` — SHA-256 `5060bf600b32375e1292fce54f2dc256fe053dd46969ed7d31d7d1afc5ce223c`
2. `20261112090000_phase5_trust_integration.sql` — SHA-256 `c0295af9c62ee31ef9fcef3422da0875b99f2912117315eb63cd03ed597e4fa4`

Do not replay historical migrations or use unrestricted db push. No deployment/migration/flag activation is authorized by this draft. Roll back behavior by disabling affected Phase 5 flags and restarting, retaining schema and existing trust/business paths. See `docs/issue10/integration-review.md`, `migration-release.md`, `hosted-preflight.sql` and `test-report.md` for exact gates and limitations.
