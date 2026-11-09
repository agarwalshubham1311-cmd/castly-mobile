import assert from 'node:assert/strict';
import test from 'node:test';
import {
  canUseFeature,
  hasActiveEntitlement,
  SUBSCRIPTION_PLANS,
} from '../src/subscriptions/catalog.ts';
const now = new Date('2026-11-09T00:00:00Z');
test('catalog has no purchasable unconfigured product', () => {
  assert.equal(SUBSCRIPTION_PLANS.length, 2);
  assert.equal(
    SUBSCRIPTION_PLANS.every((plan) => !plan.purchasable),
    true,
  );
  assert.equal(
    SUBSCRIPTION_PLANS.find((plan) => plan.code === 'creator_pro')?.googlePlayProductId,
    null,
  );
});
test('free features do not require a grant', () => {
  assert.equal(canUseFeature('marketplace_access', [], now), true);
  assert.equal(canUseFeature('standard_profile', [], now), true);
  assert.equal(canUseFeature('premium_discovery', [], now), false);
});
test('active grant unlocks premium features', () => {
  assert.equal(
    canUseFeature(
      'premium_discovery',
      [{ entitlement: 'creator_pro', valid_until: '2027-11-09T00:00:00Z' }],
      now,
    ),
    true,
  );
});
test('expired, revoked and invalid grants are denied', () => {
  for (const grant of [
    { entitlement: 'creator_pro', valid_until: '2026-11-09T00:00:00Z' },
    { entitlement: 'creator_pro', valid_until: 'invalid' },
    { entitlement: 'creator_pro', valid_until: null, revoked_at: '2026-11-08T00:00:00Z' },
  ])
    assert.equal(hasActiveEntitlement([grant], 'creator_pro', now), false);
});
test('wrong entitlement cannot unlock premium', () => {
  assert.equal(
    canUseFeature('enhanced_portfolio', [{ entitlement: 'other', valid_until: null }], now),
    false,
  );
});
