begin;
alter table public.subscription_plans add column premium_features text[] not null default '{}' check(cardinality(premium_features)<=30);
-- Public eligibility is deliberately separate from private payment/claim/grant details.
create table public.subscription_discovery_eligibility (
 user_id uuid primary key references public.users(id), eligible boolean not null,
 valid_until timestamptz, updated_at timestamptz not null default now()
);
alter table public.subscription_discovery_eligibility enable row level security;
revoke all on public.subscription_discovery_eligibility from public,anon,authenticated;
grant select,insert(user_id,eligible,valid_until),update(eligible,valid_until,updated_at) on public.subscription_discovery_eligibility to castly_api;
create policy discovery_eligibility_read on public.subscription_discovery_eligibility for select to castly_api using(user_id=private.request_user_id() or private.is_subscription_admin() or exists(select 1 from public.talent_profiles where id=user_id and published));
create policy discovery_eligibility_insert on public.subscription_discovery_eligibility for insert to castly_api with check((user_id=private.request_user_id() and current_setting('castly.subscription_verified',true)='yes') or private.is_subscription_admin());
create policy discovery_eligibility_update on public.subscription_discovery_eligibility for update to castly_api using((user_id=private.request_user_id() and current_setting('castly.subscription_verified',true)='yes') or private.is_subscription_admin()) with check((user_id=private.request_user_id() and current_setting('castly.subscription_verified',true)='yes') or private.is_subscription_admin());
insert into public.subscription_discovery_eligibility(user_id,eligible,valid_until)
 select user_id,true,case when bool_or(expires_at is null) then null else max(expires_at) end from public.subscription_grants
 where entitlement='creator_pro' and starts_at<=now() and (expires_at is null or expires_at>now()) and revoked_at is null group by user_id;
commit;
