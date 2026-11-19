# Phase 5: AI-Powered Discovery & Matching (M5.1–M5.4) — Codex implementation brief

Source: https://github.com/agarwalshubham1311-cmd/castly-mobile/issues/6

## Mission
Implement **Phase 5 — AI-Powered Discovery & Matching** for Castly. This issue is the implementation brief for Codex. Goal: a client can describe a campaign and receive a relevant, explainable shortlist of creators, then generate/edit a booking brief.

## Isolation and source control
- Repository: `agarwalshubham1311-cmd/castly-mobile`.
- Stable production baseline: `feat/milestone5-booking-messaging-notifications` at `b86deb4fac8c44ac28ac2a93d97b38c4ad1c6032`. Phase 3 is deployed and being manually tested.
- Phase 4 is isolated at `feat/phase4-trust-verification-safety`, **not** approved for production. Inspect it read-only for future compatibility, but do not merge, modify or depend on Phase 4 objects without a separately reviewed integration plan.
- Work in a **separate** `feat/phase5-ai-discovery-matching` branch/worktree from the stable baseline. Do not touch the user's existing local checkout, Phase 3/4 worktrees, hosted Supabase, Render, EAS, Google Play, or credentials. No automatic deploys or production migrations.
- Phase 3 hosted migration history differs from repo filenames: `subscription_catalog` 20261111052131, `founding_creator_offer` 20261111052140, `google_play_reconciliation` 20261111052149, `subscription_discovery_projection` 20261111052157. New migrations must be forward-only and designed for reconciliation; do not replay old SQL or blanket `supabase db push`.

## Milestones / acceptance criteria
### M5.1 Advanced search
- Search by location (consent-aware; city/region, no precise location by default), category, budget, skills, availability date/window; combined filters, sort, pagination, empty/loading/error states.
- Use public/published and eligible talent only, existing discovery/entitlement contracts; no private trust/payment/moderation signals. Use scalable indexes and bounded queries. Avoid claiming availability unless derived from actual bookings/availability data.
### M5.2 AI recommendations
- Personalized, explainable creator ranking from client brief, permitted preferences, skills, portfolio metadata, service/category, location, budget and availability. Define a deterministic baseline that works with AI disabled/unavailable.
- Opt-in personalization; no sensitive inference, private messages, private dispute/verification documents, purchase tokens or raw PII sent to an LLM. No paid LLM provider or API key assumptions; use provider abstraction and mock/fake for tests. Add latency/cost budgets, rate limits, caching and audit-safe observability.
- Display concise 'why matched' evidence and clearly distinguish heuristics from AI-generated suggestions. Never fabricate skills, availability, ratings or reviews.
### M5.3 Smart matching
- Parse structured campaign requirements (deliverables, creator category, skills, city/remote, dates, budget, optional audience/format) with strict validation and explicit user confirmation of inferred fields.
- Hard constraints first; soft ranking second. Return scored/explainable shortlist with missing-data indicators and safe fallbacks. Avoid discrimination/protected-attribute targeting and self-reinforcing popularity bias.
### M5.4 AI assistance
- Draft **editable** booking briefs, creator profile-improvement suggestions, shortlist comparisons. All AI text labeled as suggestions; no automatic booking, outbound messages, profile edits or payments.
- Integrate into existing Expo Router Lavender Pearl UI and Java 21 Spring Boot API; authenticated client endpoints, input limits, tenant-scoped access, bounded errors, accessible UX, cancellation/retry.

## Design / security
- Propose contracts and storage schema before implementation; Java 21 Spring Boot 3.5.x, Postgres/Supabase RLS, restricted `castly_api` identity, React Native/Expo SDK 58. Server-side authorization, SQL parameterization, request identity isolation, no client-side privileged database writes.
- Introduce independent `CASTLY_ADVANCED_SEARCH_ENABLED`, `CASTLY_AI_RECOMMENDATIONS_ENABLED`, `CASTLY_SMART_MATCHING_ENABLED`, `CASTLY_AI_ASSISTANCE_ENABLED` defaults **false**. Existing subscriptions/Google checkout/notifications flags remain unchanged/off.
- No unapproved external provider spend; design provider abstraction and deterministic local fallback. Explicit opt-in for AI processing; redact prompt/log PII; protect against prompt injection in user/portfolio text, enforce output schema, prevent unauthorized data retrieval.
- No automatic LLM output persistence without a retention/deletion policy; no sensitive attribute inference. Plan index/migration rollout and backward compatibility with Phase 3 and future Phase 4.

## Validation and deliverables
- Unit, integration, security and SQL tests with disposable PostgreSQL; fresh/reconciliation-shaped schema paths, negative cross-user/RLS cases, adversarial prompts, ranking fairness/quality, deterministic AI-off, latency/pagination, mobile fixture and browser tests.
- Run `npm ci`, `npm run typecheck`, `npm run lint`, `npm test`, `npm run test:components`, `npm run test:sql`, `npm run test:reconciliation`, `mvn -f backend/pom.xml clean verify` (and reconciliation path), relevant web/mobile API tests. Report exact results; do not claim native Android validation without device testing.
- Deliver architecture/ADR, endpoint examples, migration order+hashes, data/privacy design, feature flags, test report, rollout/rollback plan, AI quality/evaluation criteria, and demo flow: 'client describes campaign -> confirmed constraints -> relevant creator shortlist -> editable booking brief'.
- Open a **draft PR** against the stable branch when ready; do not merge/deploy. Explicitly call out any missing Phase 4 integration dependencies and unresolved decisions.
