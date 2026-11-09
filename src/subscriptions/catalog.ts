export type Plan = { id: string; name: string; portfolio_limit: number | null; premium_discovery: boolean; google_product_id: string | null; google_base_plan_id: string | null };
export type Membership = { id: string; name: string; portfolio_limit: number | null; premium_discovery: boolean; is_creator_pro: boolean; entitlements: { entitlement: string; valid_until: string | null }[] };
export type PromotionClaim = { id: string; post_url: string; status: 'pending' | 'approved' | 'rejected'; reason: string | null; created_at: string; reviewed_at: string | null };
export type SubscriptionOrder = { id: string; product_id: string; state: string; expires_at: string | null; auto_renewing: boolean };
export function promotionUrl(value: string): string {
  const url = new URL(value.trim());
  if (url.protocol !== 'https:' || url.username || url.password || url.port || !['instagram.com','www.instagram.com','youtube.com','www.youtube.com','youtu.be','tiktok.com','www.tiktok.com','x.com','www.x.com'].includes(url.hostname) || url.pathname === '/') throw new Error('Enter a public HTTPS post URL from Instagram, YouTube, TikTok or X.');
  const host = url.hostname.replace(/^www\./, '');
  if (host === 'youtube.com' && url.pathname === '/watch') {
    const id=url.searchParams.get('v');
    if(!id || !/^[A-Za-z0-9_-]{6,100}$/.test(id)) throw new Error('Enter a valid YouTube video URL.');
    return `https://${host}/watch?v=${id}`;
  }
  return `https://${host}${url.pathname.replace(/\/$/, '')}`;
}
export function membershipExpiry(membership: Membership): string | null | undefined {
  return membership.entitlements.find(x => x.entitlement === 'creator_pro')?.valid_until;
}
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
