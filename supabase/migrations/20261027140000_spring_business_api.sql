begin;
-- Forward-only reconciliation: never edit the previously committed RPC migration.
-- Apply only after approval; the mobile fallback targets a separate pre-cutover dev DB.
do $$begin
 if not exists(select 1 from pg_roles where rolname='castly_api') then
  create role castly_api nologin nosuperuser nocreatedb nocreaterole noinherit noreplication nobypassrls;
 end if;
 if exists(select 1 from pg_roles where rolname='castly_api' and (rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication))
 or exists(select 1 from pg_auth_members where member=(select oid from pg_roles where rolname='castly_api'))
 or exists(select 1 from pg_class where relowner=(select oid from pg_roles where rolname='castly_api') and relkind in ('r','p')) then
  raise exception 'castly_api must be a dedicated nonprivileged role with no role memberships';
 end if;
 execute format('grant connect on database %I to castly_api',current_database());
end$$;
grant usage on schema public,auth to castly_api;
grant execute on function auth.uid() to castly_api;
-- Business writes now have exactly one API path. Keep SELECT for Storage/Realtime RLS.
revoke insert,update,delete on public.users,public.talent_profiles,public.portfolio_assets,public.bookings,public.conversations,public.messages,public.saved_talent,public.subscription_entitlements from authenticated,anon;
-- PostgreSQL table REVOKE does not remove existing column grants.
revoke update(display_name) on public.users from authenticated;
revoke insert(id,display_name,category_id,city,day_rate,bio,skills,available,published,portrait_path),update(display_name,category_id,city,day_rate,bio,skills,available,published,portrait_path) on public.talent_profiles from authenticated;
revoke insert(talent_id,path,label,position),update(label,position) on public.portfolio_assets from authenticated;
revoke insert(client_id,talent_id,project_type,proposed_date,location,requirements,indicative_budget) on public.bookings from authenticated;
revoke insert(client_id,talent_id) on public.conversations from authenticated;
revoke insert(conversation_id,sender_id,body) on public.messages from authenticated;
revoke insert(user_id,talent_id) on public.saved_talent from authenticated;
revoke all on function public.respond_to_booking(uuid,text),public.cancel_booking(uuid),public.complete_onboarding(public.account_role,text) from public,anon,authenticated,castly_api;
drop policy if exists participant_message_send on public.messages;
alter table public.bookings add column client_name text not null default '',add column talent_name text not null default '';
update public.bookings b set client_name=u.display_name,talent_name=t.display_name from public.users u,public.talent_profiles t where b.client_id=u.id and b.talent_id=t.id;
grant select on public.users,public.categories,public.talent_profiles,public.portfolio_assets,public.bookings,public.conversations,public.messages,public.saved_talent,public.subscription_entitlements to castly_api;
grant update(display_name,primary_role) on public.users to castly_api;
grant insert(id,display_name,category_id,city,day_rate,bio,skills,available,published,portrait_path),update(display_name,category_id,city,day_rate,bio,skills,available,published,portrait_path) on public.talent_profiles to castly_api;
grant insert(talent_id,path,label,position),update(label,position),delete on public.portfolio_assets to castly_api;
grant insert(client_id,talent_id,project_type,proposed_date,location,requirements,indicative_budget,client_name,talent_name),update(status) on public.bookings to castly_api;
grant insert(client_id,talent_id) on public.conversations to castly_api;
grant insert(conversation_id,sender_id,body) on public.messages to castly_api;
grant insert(user_id,talent_id),delete on public.saved_talent to castly_api;
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
create function public.api_booking_transition_guard() returns trigger language plpgsql set search_path='' as $$begin
 if current_user='castly_api' and new.status is distinct from old.status then
  if (new.status in ('accepted','declined') and old.status='requested' and old.talent_id=auth.uid())
   or (new.status='cancelled' and ((old.status='requested' and old.client_id=auth.uid()) or (old.status='accepted' and (old.client_id=auth.uid() or old.talent_id=auth.uid())))) then return new; end if;
  raise exception 'Invalid booking transition' using errcode='42501';
 end if;
 return new;
end$$;
create trigger api_booking_transition before update on public.bookings for each row execute function public.api_booking_transition_guard();
revoke all on function public.api_booking_transition_guard() from public,anon,authenticated,castly_api;
-- Realtime is read-only transport. API INSERT is the sole message-send path.
do $$begin
 if exists(select 1 from pg_publication where pubname='supabase_realtime') then
  if not exists(select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename='messages') then alter publication supabase_realtime add table public.messages;end if;
  if not exists(select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename='bookings') then alter publication supabase_realtime add table public.bookings;end if;
  if not exists(select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename='conversations') then alter publication supabase_realtime add table public.conversations;end if;
 end if;
end$$;
commit;
