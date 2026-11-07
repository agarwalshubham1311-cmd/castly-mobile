-- REVIEW REQUIRED: apply to hosted Supabase only after explicit owner approval.
-- Restore the namespace prerequisite for auth.uid() in invoker triggers/policies.
-- Existing function EXECUTE, table/column grants and RLS remain unchanged.
begin;
grant usage on schema auth to castly_api;
commit;
