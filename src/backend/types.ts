export type Role = 'client' | 'talent';
export type Account = {
  id: string;
  display_name: string;
  primary_role: Role | null;
  created_at: string;
  updated_at: string;
};
export type TalentProfile = {
  id: string;
  display_name: string;
  category_id: string;
  city: string;
  day_rate: number;
  bio: string;
  skills: string[];
  available: boolean;
  published: boolean;
  portrait_path: string | null;
  created_at: string;
  updated_at: string;
};
export type TalentDraft = Pick<
  TalentProfile,
  | 'display_name'
  | 'category_id'
  | 'city'
  | 'day_rate'
  | 'bio'
  | 'skills'
  | 'available'
  | 'published'
  | 'portrait_path'
>;
export type PortfolioAsset = {
  id: string;
  talent_id: string;
  path: string;
  label: string;
  media_type: string;
  position: number;
  created_at: string;
};
export type CategoryRow = { id: string; name: string };
export type BookingRow = {
  id: string;
  client_id: string;
  talent_id: string;
  project_type: string;
  proposed_date: string;
  location: string;
  requirements: string;
  indicative_budget: number;
  status: 'requested' | 'accepted' | 'declined' | 'cancelled' | 'completed';
  agreed_rate: number | null;
  created_at: string;
  updated_at: string;
};
export type ConversationRow = {
  id: string;
  client_id: string;
  talent_id: string;
  created_at: string;
};
export type MessageRow = {
  id: string;
  conversation_id: string;
  sender_id: string;
  body: string;
  created_at: string;
};
export type SavedRow = { user_id: string; talent_id: string; created_at: string };
export type Entitlement = {
  id: string;
  user_id: string;
  entitlement: string;
  valid_until: string | null;
  created_at: string;
};
type Table<Row, Insert = Partial<Row>, Update = Partial<Insert>> = {
  Row: Row;
  Insert: Insert;
  Update: Update;
  Relationships: [];
};
export type Database = {
  public: {
    Tables: {
      users: Table<Account, never, Pick<Account, 'display_name'>>;
      categories: Table<CategoryRow, never, never>;
      talent_profiles: Table<TalentProfile, TalentDraft & { id: string }, Partial<TalentDraft>>;
      portfolio_assets: Table<
        PortfolioAsset,
        Pick<PortfolioAsset, 'talent_id' | 'path' | 'label' | 'position'>,
        Pick<PortfolioAsset, 'label' | 'position'>
      >;
      bookings: Table<
        BookingRow,
        Pick<
          BookingRow,
          | 'client_id'
          | 'talent_id'
          | 'project_type'
          | 'proposed_date'
          | 'location'
          | 'requirements'
          | 'indicative_budget'
        >,
        never
      >;
      conversations: Table<
        ConversationRow,
        Pick<ConversationRow, 'client_id' | 'talent_id'>,
        never
      >;
      messages: Table<MessageRow, never, never>;
      saved_talent: Table<SavedRow, Pick<SavedRow, 'user_id' | 'talent_id'>, never>;
      subscription_entitlements: Table<Entitlement, never, never>;
    };
    Views: Record<string, never>;
    Functions: {
      complete_onboarding: {
        Args: { selected_role: Role; account_name: string };
        Returns: Account;
      };
    };
    Enums: { account_role: Role; booking_status: BookingRow['status'] };
    CompositeTypes: Record<string, never>;
  };
};
