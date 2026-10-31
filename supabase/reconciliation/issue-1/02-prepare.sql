-- PREPARE ONLY: owner review/approval required. Does not revoke mobile writes or onboarding.
-- psql -X -v ON_ERROR_STOP=1 -f 02-prepare.sql; no hosted credentials belong in this file.
begin;
do $$begin
 if current_setting('castly.reconciliation_schema_reviewed',true) is distinct from 'issue-1-reviewed' then
  raise exception 'Review 01-inventory against README structural checklist before setting castly.reconciliation_schema_reviewed=issue-1-reviewed';
 end if;
end$$;
\ir structural-check.sql
do $$begin
 if not exists(select 1 from pg_roles where rolname='castly_api') then
  create role castly_api nologin nosuperuser nocreatedb nocreaterole noinherit noreplication nobypassrls;
 end if;
 if exists(select 1 from pg_roles where rolname='castly_api' and (rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication or rolinherit))
 or exists(
  select 1 from pg_auth_members m
  join pg_roles granted on granted.oid=m.roleid
  join pg_roles member_role on member_role.oid=m.member
  join pg_roles grantor on grantor.oid=m.grantor
  where (granted.rolname='castly_api' or member_role.rolname='castly_api')
    and not (
      granted.rolname='castly_api'
      and member_role.rolname='postgres'
      and grantor.rolname='supabase_admin'
      and m.admin_option
      and not m.inherit_option
      and not m.set_option
    )
 )
 or exists(select 1 from pg_class where relowner=(select oid from pg_roles where rolname='castly_api')) then
  raise exception 'castly_api must be nonprivileged, nonowner, and have no role memberships except the reviewed Supabase admin grant to postgres';
 end if;
 execute format('grant connect on database %I to castly_api',current_database());
end$$;
-- Remove only unauthenticated onboarding execution; preserve the working authenticated RPC.
revoke all on function public.complete_onboarding(public.account_role,text) from public,anon;
grant usage on schema public,auth to castly_api;
grant execute on function auth.uid() to castly_api;
alter table public.bookings add column if not exists client_name text not null default '',add column if not exists talent_name text not null default '';
\ir backfill-booking-names.sql
create index if not exists bookings_client_talent_status_idx on public.bookings(client_id,talent_id,status);
create index if not exists castly_message_history_cursor_idx on public.messages(conversation_id,created_at,id);
grant select on public.users,public.categories,public.talent_profiles,public.portfolio_assets,public.bookings,public.conversations,public.messages,public.saved_talent,public.subscription_entitlements to castly_api;
grant update(display_name,primary_role) on public.users to castly_api;
grant insert(id,display_name,category_id,city,day_rate,bio,skills,available,published,portrait_path),update(display_name,category_id,city,day_rate,bio,skills,available,published,portrait_path) on public.talent_profiles to castly_api;
grant insert(talent_id,path,label,position),update(label,position),delete on public.portfolio_assets to castly_api;
grant insert(client_id,talent_id,project_type,proposed_date,location,requirements,indicative_budget,client_name,talent_name),update(status) on public.bookings to castly_api;
grant insert(client_id,talent_id) on public.conversations to castly_api;
grant insert(conversation_id,sender_id,body) on public.messages to castly_api;
grant insert(user_id,talent_id),delete on public.saved_talent to castly_api;
do $$declare f record;begin
 for f in select p.oid from pg_proc p join pg_namespace n on n.oid=p.pronamespace
  where n.nspname='public' and p.proname in ('respond_to_booking','cancel_booking','complete_onboarding') loop
  if has_function_privilege('castly_api',f.oid,'EXECUTE') then
   raise exception 'API inherits old RPC EXECUTE through PUBLIC or another grant; review ACL before preparation';
  end if;
 end loop;
end$$;
create policy api_account_read on public.users for select to castly_api using(id=(select auth.uid()));
create policy api_account_update on public.users for update to castly_api using(id=(select auth.uid())) with check(id=(select auth.uid()));
create policy api_categories on public.categories for select to castly_api using(true);
create policy api_talent_read on public.talent_profiles for select to castly_api using(published or id=(select auth.uid()));
create policy api_talent_create on public.talent_profiles for insert to castly_api with check(id=(select auth.uid()) and exists(select 1 from public.users where id=(select auth.uid()) and primary_role='talent'));
create policy api_talent_update on public.talent_profiles for update to castly_api using(id=(select auth.uid())) with check(id=(select auth.uid()) and exists(select 1 from public.users where id=(select auth.uid()) and primary_role='talent'));
create policy api_portfolio_read on public.portfolio_assets for select to castly_api using(exists(select 1 from public.talent_profiles t where t.id=talent_id and (t.published or t.id=(select auth.uid()))));
create policy api_portfolio_write on public.portfolio_assets for all to castly_api using(talent_id=(select auth.uid())) with check(talent_id=(select auth.uid()));
create policy api_booking_read on public.bookings for select to castly_api using(client_id=(select auth.uid()) or talent_id=(select auth.uid()));
create policy api_booking_create on public.bookings for insert to castly_api with check(client_id=(select auth.uid()) and proposed_date>=current_date and exists(select 1 from public.users where id=(select auth.uid()) and primary_role='client') and exists(select 1 from public.talent_profiles where id=talent_id and published));
create policy api_booking_update on public.bookings for update to castly_api using(client_id=(select auth.uid()) or talent_id=(select auth.uid())) with check(client_id=(select auth.uid()) or talent_id=(select auth.uid()));
create policy api_conversation_read on public.conversations for select to castly_api using(client_id=(select auth.uid()) or talent_id=(select auth.uid()));
create policy api_conversation_create on public.conversations for insert to castly_api with check((client_id=(select auth.uid()) or talent_id=(select auth.uid())) and exists(select 1 from public.bookings b where b.client_id=conversations.client_id and b.talent_id=conversations.talent_id and b.status='accepted'));
create policy api_message_read on public.messages for select to castly_api using(exists(select 1 from public.conversations c where c.id=conversation_id and (c.client_id=(select auth.uid()) or c.talent_id=(select auth.uid()))));
create policy api_message_send on public.messages for insert to castly_api with check(sender_id=(select auth.uid()) and exists(select 1 from public.conversations c where c.id=conversation_id and (c.client_id=(select auth.uid()) or c.talent_id=(select auth.uid())) and exists(select 1 from public.bookings b where b.client_id=c.client_id and b.talent_id=c.talent_id and b.status='accepted')));
create policy api_saved on public.saved_talent for select to castly_api using(user_id=(select auth.uid()));
create policy api_saved_add on public.saved_talent for insert to castly_api with check(user_id=(select auth.uid()) and exists(select 1 from public.talent_profiles where id=talent_id and published));
create policy api_saved_remove on public.saved_talent for delete to castly_api using(user_id=(select auth.uid()));
create policy api_entitlement_read on public.subscription_entitlements for select to castly_api using(user_id=(select auth.uid()));
create or replace function public.api_booking_transition_guard() returns trigger
language plpgsql security invoker set search_path='' as $$
declare me uuid := auth.uid(); actor_role public.account_role;
begin
 if current_user <> 'castly_api' then return new; end if;
 if me is null or (me <> old.client_id and me <> old.talent_id) then
  raise exception 'Booking participant required' using errcode='42501';
 end if;
 -- UPDATE(status) is the only API grant, but defend against future accidental wider grants.
 -- The existing bookings_stamp trigger is allowed to maintain updated_at.
 if (to_jsonb(new)-array['status','updated_at']) is distinct from
    (to_jsonb(old)-array['status','updated_at']) then
  raise exception 'Protected booking fields are immutable' using errcode='42501';
 end if;
 select primary_role into actor_role from public.users where id=me;
 if (me=old.client_id and actor_role is distinct from 'client'::public.account_role)
 or (me=old.talent_id and actor_role is distinct from 'talent'::public.account_role) then
  raise exception 'Booking account role mismatch' using errcode='42501';
 end if;
 if new.status is not distinct from old.status then return new; end if;
 if (new.status in ('accepted','declined') and old.status='requested' and old.talent_id=me)
 or (new.status='cancelled' and ((old.status='requested' and old.client_id=me)
      or (old.status='accepted' and me in (old.client_id,old.talent_id)))) then return new; end if;
 raise exception 'Invalid booking transition' using errcode='42501';
end$$;
revoke all on function public.api_booking_transition_guard() from public,anon,authenticated,castly_api;
-- Make direct API-role SQL obey the same send/cancel lock order as Java.
-- RLS remains the SELECT/delivery boundary; this trigger also prevents snapshot races.
create function public.api_message_send_guard() returns trigger
language plpgsql security invoker set search_path='' as $$
declare c public.conversations; accepted_id uuid; has_accepted boolean := false;
begin
 if current_user <> 'castly_api' then return new; end if;
 select * into c from public.conversations where id=new.conversation_id;
 if not found or auth.uid() is null or new.sender_id is distinct from auth.uid()
    or auth.uid() not in (c.client_id,c.talent_id) then
  raise exception 'Conversation participant required' using errcode='42501';
 end if;
 -- Lock every currently accepted booking in stable UUID order. A concurrent cancellation
 -- commits first (send denied), or waits until this send commits (send precedes cancellation).
 for accepted_id in select id from public.bookings
   where client_id=c.client_id and talent_id=c.talent_id and status='accepted'
   order by id for update loop
  has_accepted := true;
 end loop;
 if has_accepted then return new; end if;
 raise exception 'An accepted booking is required' using errcode='42501';
end$$;
create trigger api_message_send before insert on public.messages
 for each row execute function public.api_message_send_guard();
revoke all on function public.api_message_send_guard() from public,anon,authenticated,castly_api;
create trigger api_booking_transition before update on public.bookings for each row execute function public.api_booking_transition_guard();
-- Realtime is read-only transport. API INSERT is the sole message-send path.
do $$begin
 if exists(select 1 from pg_publication where pubname='supabase_realtime') then
  if not exists(select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename='messages') then alter publication supabase_realtime add table public.messages;end if;
  if not exists(select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename='bookings') then alter publication supabase_realtime add table public.bookings;end if;
  if not exists(select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename='conversations') then alter publication supabase_realtime add table public.conversations;end if;
 end if;
end$$;
commit;
