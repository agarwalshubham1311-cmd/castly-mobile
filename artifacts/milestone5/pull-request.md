## Booking lifecycle, messaging and recipient notifications

Completes existing booking decisions/history and participant messaging with confirmations, scrolling, private unread indicators, shared Realtime invalidation and a recipient-only notification feed. Spring remains the business API boundary. A new additive notification migration is prepared for review only; `CASTLY_NOTIFICATIONS_ENABLED` remains false by default.

Adds safe acceptance database-step diagnostics and reuses existing authorized conversations before INSERT. Regressions demonstrate SELECT FOR UPDATE, UPDATE RETURNING and INSERT RLS can each produce the reported generic 403. **The exact hosted acceptance failure remains unconfirmed:** supplied conversation grants/policy pass locally. No speculative hosted privilege or policy repair is included.

Validation: Java21 fresh and reconciled suites72 each; TypeScript/lint; unit48; native-bridge-mocked component2; SQL policy37; reconciliation286; real local Java/PostgreSQL/browser booking-to-messaging journey; mobile API and legacy fixtures; demo/browser/accessibility/screenshots; Expo all-platform export. Public hosted JWKS check is blocked by HTTP403. Hosted Realtime and native devices were not tested.

See `artifacts/milestone5/report.md`, `docs/milestone5/README.md` and verified eight screenshots. No hosted migration/cutover, deployment, merge, payments or push notifications. Draft pending hosted acceptance evidence, schema review and device checks.
