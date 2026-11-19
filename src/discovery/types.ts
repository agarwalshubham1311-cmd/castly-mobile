export type CampaignConstraints = {
  confirmed: boolean;
  category?: string | null;
  skills: string[];
  city?: string | null;
  region?: string | null;
  remote: boolean;
  from?: string | null;
  through?: string | null;
  max_budget?: number | null;
  budget_unit?: 'per_day' | 'campaign_total' | null;
  deliverables: string[];
  format?: 'portrait' | 'landscape' | 'square' | 'unspecified' | null;
  audience?: 'general' | 'business' | 'families' | 'unspecified' | null;
  allow_unknown_availability: boolean;
  personalize: boolean;
  allow_external_ai: boolean;
  preferred_skills: string[];
};
export type DiscoveryCandidate = {
  id: string;
  display_name: string;
  category_id: string;
  city: string;
  region?: string | null;
  day_rate: number;
  skills: string[];
  availability_status: 'confirmed' | 'unknown' | 'unavailable';
  score?: number;
  reasons?: string[];
  missing_data?: string[];
};
export type DiscoveryConfiguration = {
  advanced_search: boolean;
  ai_recommendations: boolean;
  smart_matching: boolean;
  ai_assistance: boolean;
  external_provider: boolean;
};
export type Shortlist = {
  method: 'deterministic' | 'provider_reranked';
  provider_available: boolean;
  suggestions_only: boolean;
  candidates: DiscoveryCandidate[];
  total_examined: number;
};
