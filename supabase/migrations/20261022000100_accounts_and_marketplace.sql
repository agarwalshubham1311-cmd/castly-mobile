begin;
create type public.account_role as enum ('client', 'talent');
create type public.booking_status as enum ('requested', 'accepted', 'declined', 'cancelled', 'completed');
create table public.users (
 id uuid primary key references auth.users(id) on delete cascade,
 display_name text not null default '' check (char_length(display_name) <= 100),
 primary_role public.account_role,
 created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table public.categories (id text primary key, name text not null unique);
insert into public.categories values ('actors','Actors'),('models','Models'),('creators','Creators'),('musicians','Musicians'),('photographers','Photographers');
create table public.talent_profiles (
 id uuid primary key references public.users(id) on delete cascade,
 display_name text not null check (char_length(trim(display_name)) between 2 and 100),
 category_id text not null references public.categories(id), city text not null check (char_length(trim(city)) between 2 and 100),
 day_rate integer not null check (day_rate between 0 and 10000000), bio text not null default '' check (char_length(bio) <= 2000),
 skills text[] not null default '{}' check (cardinality(skills) <= 20), available boolean not null default true,
 published boolean not null default false, portrait_path text,
 created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
 check (portrait_path is null or portrait_path like id::text || '/%')
);
create table public.portfolio_assets (
 id uuid primary key default gen_random_uuid(), talent_id uuid not null references public.talent_profiles(id) on delete cascade,
 path text not null, label text not null check (char_length(trim(label)) between 1 and 160), media_type text not null default 'image' check (media_type = 'image'),
 position integer not null default 0 check (position >= 0), created_at timestamptz not null default now(),
 unique(talent_id,path), check (path like talent_id::text || '/%')
);
create table public.bookings (
 id uuid primary key default gen_random_uuid(), client_id uuid not null references public.users(id), talent_id uuid not null references public.talent_profiles(id),
 project_type text not null check (char_length(trim(project_type)) between 2 and 100), proposed_date date not null,
 location text not null check (char_length(trim(location)) between 2 and 200), requirements text not null check (char_length(trim(requirements)) between 10 and 4000),
 indicative_budget integer not null check (indicative_budget between 1 and 10000000), status public.booking_status not null default 'requested',
 agreed_rate integer check (agreed_rate >= 0), created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
 check (client_id <> talent_id)
);
create table public.conversations (
 id uuid primary key default gen_random_uuid(), client_id uuid not null references public.users(id), talent_id uuid not null references public.talent_profiles(id),
 created_at timestamptz not null default now(), unique(client_id,talent_id), check(client_id <> talent_id)
);
create table public.messages (
 id uuid primary key default gen_random_uuid(), conversation_id uuid not null references public.conversations(id) on delete cascade,
 sender_id uuid not null references public.users(id), body text not null check (char_length(trim(body)) between 1 and 4000), created_at timestamptz not null default now()
);
create table public.saved_talent (
 user_id uuid not null references public.users(id) on delete cascade, talent_id uuid not null references public.talent_profiles(id) on delete cascade,
 created_at timestamptz not null default now(), primary key(user_id,talent_id)
);
create table public.subscription_entitlements (
 id uuid primary key default gen_random_uuid(), user_id uuid not null references public.users(id) on delete cascade,
 entitlement text not null, valid_until timestamptz, created_at timestamptz not null default now(), unique(user_id,entitlement)
);
create index on public.talent_profiles (published,category_id,city);
create index on public.portfolio_assets (talent_id,position);
create index on public.bookings (client_id,created_at desc);
create index on public.bookings (talent_id,created_at desc);
create index on public.conversations (talent_id);
create index on public.messages (conversation_id,created_at);
create index on public.saved_talent (talent_id);
create index on public.subscription_entitlements (user_id);

create function public.stamp_updated_at() returns trigger language plpgsql set search_path='' as $$ begin new.updated_at=now(); return new; end $$;
create trigger users_stamp before update on public.users for each row execute function public.stamp_updated_at();
create trigger talent_stamp before update on public.talent_profiles for each row execute function public.stamp_updated_at();
create trigger bookings_stamp before update on public.bookings for each row execute function public.stamp_updated_at();
create function public.prevent_role_change() returns trigger language plpgsql set search_path='' as $$
 begin if old.primary_role is not null and new.primary_role is distinct from old.primary_role then raise exception 'Primary role is immutable'; end if; return new; end $$;
create trigger users_immutable_role before update on public.users for each row execute function public.prevent_role_change();
create function public.bootstrap_account() returns trigger language plpgsql security definer set search_path='' as $$
 begin insert into public.users(id,display_name) values(new.id,left(coalesce(new.raw_user_meta_data->>'display_name',''),100)); return new; end $$;
create trigger castly_account_created after insert on auth.users for each row execute function public.bootstrap_account();
-- Backfill pre-existing auth accounts without trusting metadata for authorization.
insert into public.users(id,display_name) select id,left(coalesce(raw_user_meta_data->>'display_name',''),100) from auth.users on conflict(id) do nothing;
create function public.complete_onboarding(selected_role public.account_role, account_name text) returns public.users
language plpgsql security definer set search_path='' as $$
 declare result public.users;
 begin
  if auth.uid() is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if selected_role is null or char_length(trim(account_name)) not between 2 and 100 then raise exception 'Choose a role and a name of 2–100 characters'; end if;
  update public.users set primary_role=selected_role,display_name=trim(account_name) where id=auth.uid() returning * into result;
  if not found then raise exception 'Account unavailable'; end if;
  return result;
 end $$;

alter table public.users enable row level security;
alter table public.categories enable row level security;
alter table public.talent_profiles enable row level security;
alter table public.portfolio_assets enable row level security;
alter table public.bookings enable row level security;
alter table public.conversations enable row level security;
alter table public.messages enable row level security;
alter table public.saved_talent enable row level security;
alter table public.subscription_entitlements enable row level security;
revoke all on public.users, public.categories, public.talent_profiles, public.portfolio_assets, public.bookings, public.conversations, public.messages, public.saved_talent, public.subscription_entitlements from anon, authenticated;
grant usage on schema public to anon, authenticated;
grant select on public.categories,public.talent_profiles,public.portfolio_assets to anon,authenticated;
grant select on public.users,public.bookings,public.conversations,public.messages,public.saved_talent,public.subscription_entitlements to authenticated;
grant update(display_name) on public.users to authenticated;
grant insert(id,display_name,category_id,city,day_rate,bio,skills,available,published,portrait_path), update(display_name,category_id,city,day_rate,bio,skills,available,published,portrait_path) on public.talent_profiles to authenticated;
grant insert(talent_id,path,label,position), update(label,position), delete on public.portfolio_assets to authenticated;
grant insert(client_id,talent_id,project_type,proposed_date,location,requirements,indicative_budget) on public.bookings to authenticated;
grant insert(client_id,talent_id) on public.conversations to authenticated;
grant insert(user_id,talent_id),delete on public.saved_talent to authenticated;
-- No ordinary writes to messages or entitlements. Booking status/agreed_rate/participants are server-managed.
create policy own_account_read on public.users for select to authenticated using(id=(select auth.uid()));
create policy own_account_edit on public.users for update to authenticated using(id=(select auth.uid())) with check(id=(select auth.uid()));
create policy categories_public_read on public.categories for select to anon,authenticated using(true);
create policy published_or_own_talent on public.talent_profiles for select to anon,authenticated using(published or id=(select auth.uid()));
create policy talent_create_own on public.talent_profiles for insert to authenticated with check(id=(select auth.uid()) and exists(select 1 from public.users where id=(select auth.uid()) and primary_role='talent'));
create policy talent_edit_own on public.talent_profiles for update to authenticated using(id=(select auth.uid())) with check(id=(select auth.uid()) and exists(select 1 from public.users where id=(select auth.uid()) and primary_role='talent'));
create policy published_or_own_portfolio on public.portfolio_assets for select to anon,authenticated using(exists(select 1 from public.talent_profiles t where t.id=talent_id and (t.published or t.id=(select auth.uid()))));
create policy portfolio_insert_own on public.portfolio_assets for insert to authenticated with check(talent_id=(select auth.uid()) and exists(select 1 from public.talent_profiles where id=(select auth.uid())));
create policy portfolio_update_own on public.portfolio_assets for update to authenticated using(talent_id=(select auth.uid())) with check(talent_id=(select auth.uid()));
create policy portfolio_delete_own on public.portfolio_assets for delete to authenticated using(talent_id=(select auth.uid()));
create policy participant_booking_read on public.bookings for select to authenticated using(client_id=(select auth.uid()) or talent_id=(select auth.uid()));
create policy client_booking_request on public.bookings for insert to authenticated with check(client_id=(select auth.uid()) and proposed_date>=current_date and exists(select 1 from public.users where id=(select auth.uid()) and primary_role='client') and exists(select 1 from public.talent_profiles where id=talent_id and published));
create policy participant_conversation_read on public.conversations for select to authenticated using(client_id=(select auth.uid()) or talent_id=(select auth.uid()));
create policy client_conversation_create on public.conversations for insert to authenticated with check(client_id=(select auth.uid()) and exists(select 1 from public.users where id=(select auth.uid()) and primary_role='client') and exists(select 1 from public.talent_profiles where id=talent_id and published));
create policy participant_message_read on public.messages for select to authenticated using(exists(select 1 from public.conversations c where c.id=conversation_id and (c.client_id=(select auth.uid()) or c.talent_id=(select auth.uid()))));
create policy own_saved_read on public.saved_talent for select to authenticated using(user_id=(select auth.uid()));
create policy own_saved_add on public.saved_talent for insert to authenticated with check(user_id=(select auth.uid()) and exists(select 1 from public.talent_profiles where id=talent_id and published));
create policy own_saved_delete on public.saved_talent for delete to authenticated using(user_id=(select auth.uid()));
create policy own_entitlements_read on public.subscription_entitlements for select to authenticated using(user_id=(select auth.uid()));
revoke all on function public.complete_onboarding(public.account_role,text) from public,anon;
grant execute on function public.complete_onboarding(public.account_role,text) to authenticated;
revoke all on function public.bootstrap_account(),public.prevent_role_change(),public.stamp_updated_at() from public,anon,authenticated;
commit;
