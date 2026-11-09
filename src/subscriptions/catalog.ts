/** Phase 3 M1 catalog — no checkout, no payment activation. */
export type SubscriptionPlanCode = 'free' | 'creator_pro';
export type SubscriptionFeature = 'marketplace_access' | 'standard_profile' | 'premium_discovery' | 'enhanced_portfolio';
export type SubscriptionPlan = Readonly<{
  code: SubscriptionPlanCode;
  title: string;
  features: readonly SubscriptionFeature[];
  billing: 'free' | 'google_play';
  googlePlayProductId: string | null;
  priceLabel: string | null;
  purchasable: boolean;
}>;
export const SUBSCRIPTION_PLANS: readonly SubscriptionPlan[] = Object.freeze([
  { code: 'free', title: 'Free', features: ['marketplace_access', 'standard_profile'], billing: 'free', googlePlayProductId: null, priceLabel: 'Free', purchasable: false },
  { code: 'creator_pro', title: 'Creator Pro', features: ['marketplace_access', 'standard_profile', 'premium_discovery', 'enhanced_portfolio'], billing: 'google_play', googlePlayProductId: null, priceLabel: null, purchasable: false },
]);
export type EntitlementGrant = Readonly<{
  entitlement: string;
  valid_until: string | null;
  revoked_at?: string | null;
}>;
export function hasActiveEntitlement(grants: readonly EntitlementGrant[], entitlement: string, now: Date = new Date()): boolean {
  return grants.some(grant => {
    if (grant.entitlement !== entitlement || grant.revoked_at != null) return false;
    if (grant.valid_until === null) return true;
    const expiry = Date.parse(grant.valid_until);
    return Number.isFinite(expiry) && expiry > now.getTime();
  });
}
export function canUseFeature(feature: SubscriptionFeature, grants: readonly EntitlementGrant[], now: Date = new Date()): boolean {
  if (feature === 'marketplace_access' || feature === 'standard_profile') return true;
  return hasActiveEntitlement(grants, 'creator_pro', now);
}
