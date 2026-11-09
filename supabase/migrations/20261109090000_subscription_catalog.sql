begin;
-- Forward only. Apply after private_request_identity; independent of notification migrations.
create table public.subscription_plans (
 id text primary key, name text not null, portfolio_limit integer check(portfolio_limit between 1 and 10000),
 premium_discovery boolean not null default false, enabled boolean not null default true,
 google_product_id text unique, google_base_plan_id text,
 check ((google_product_id is null) = (google_base_plan_id is null))
);
insert into public.subscription_plans(id,name,portfolio_limit,premium_discovery) values
 ('free','Free',null,false),('creator_pro','Creator Pro',null,true);
create table public.subscription_grants (
 id uuid primary key default gen_random_uuid(), user_id uuid not null references public.users(id),
 entitlement text not null, source text not null check(source in ('legacy','promotion','google_play')),
 source_key text not null unique, starts_at timestamptz not null default now(), expires_at timestamptz,
 revoked_at timestamptz, created_at timestamptz not null default now(),
 check(expires_at is null or expires_at > starts_at)
);
-- Preserve every legacy entitlement, including perpetual/unknown feature identifiers.
insert into public.subscription_grants(user_id,entitlement,source,source_key,starts_at,expires_at)
 select user_id,entitlement,'legacy','legacy:'||id,least(created_at,coalesce(valid_until,created_at)-interval '1 microsecond'),valid_until
 from public.subscription_entitlements;
create index subscription_grants_owner on public.subscription_grants(user_id,entitlement,expires_at);
create table public.subscription_orders (
 id uuid primary key default gen_random_uuid(), user_id uuid not null references public.users(id),
 provider text not null default 'google_play' check(provider='google_play'),
 token_hash text not null unique check(length(token_hash)=64), product_id text not null,
 provider_order_id text unique, state text not null check(state in ('pending','active','grace','on_hold','paused','cancelled','expired','revoked')),
 expires_at timestamptz, auto_renewing boolean not null default false, acknowledged boolean not null default false,
 updated_at timestamptz not null default now(), created_at timestamptz not null default now()
);
create index subscription_orders_owner on public.subscription_orders(user_id,created_at desc);
create table public.subscription_events (
 id uuid primary key default gen_random_uuid(), user_id uuid not null references public.users(id),
 event_key text not null unique, order_id uuid references public.subscription_orders(id),
 event_type text not null, created_at timestamptz not null default now()
);
create table public.subscription_audit_logs (
 id uuid primary key default gen_random_uuid(), user_id uuid not null references public.users(id),
 actor_id uuid not null references public.users(id), action text not null, resource_id uuid,
 reason text check(length(reason)<=1000), created_at timestamptz not null default now()
);
create table private.subscription_admins(user_id uuid primary key references public.users(id));
alter table private.subscription_admins enable row level security;
revoke all on private.subscription_admins from public,anon,authenticated;
grant select on private.subscription_admins to castly_api;
create policy subscription_admin_self on private.subscription_admins for select to castly_api using(user_id=private.request_user_id());
create function private.is_subscription_admin() returns boolean language sql stable security invoker set search_path='' as $$
 select exists(select 1 from private.subscription_admins where user_id=private.request_user_id())
$$;
revoke all on function private.is_subscription_admin() from public;
grant execute on function private.is_subscription_admin() to castly_api;
-- Token routing contains hashes only; grants no access to another user's business rows.
create table private.subscription_purchase_owners(token_hash text primary key check(length(token_hash)=64),user_id uuid not null references public.users(id));
revoke all on private.subscription_purchase_owners from public,anon,authenticated;
grant select,insert(token_hash,user_id) on private.subscription_purchase_owners to castly_api;
alter table private.subscription_purchase_owners enable row level security;
create policy purchase_route_read on private.subscription_purchase_owners for select to castly_api using(true);
create policy purchase_route_insert on private.subscription_purchase_owners for insert to castly_api with check(user_id=private.request_user_id());
alter table public.subscription_plans enable row level security;
revoke all on public.subscription_plans from public,anon,authenticated;
grant select on public.subscription_plans to castly_api;
create policy subscription_plans_read on public.subscription_plans for select to castly_api using(enabled);
do $$ declare t text; begin
 foreach t in array array['subscription_grants','subscription_orders','subscription_events','subscription_audit_logs'] loop
  execute format('alter table public.%I enable row level security',t);
  execute format('revoke all on public.%I from public,anon,authenticated',t);
  execute format('grant select on public.%I to castly_api',t);
  execute format('create policy owner_read on public.%I for select to castly_api using(user_id=private.request_user_id() or private.is_subscription_admin())',t);
  execute format('create policy owner_insert on public.%I for insert to castly_api with check((user_id=private.request_user_id() and current_setting(''castly.subscription_verified'',true)=''yes'') or private.is_subscription_admin())',t);
 end loop;
end $$;
grant insert(user_id,entitlement,source,source_key,starts_at,expires_at) on public.subscription_grants to castly_api;
grant insert(user_id,token_hash,product_id,provider_order_id,state,expires_at,auto_renewing,acknowledged) on public.subscription_orders to castly_api;
grant insert(user_id,event_key,order_id,event_type) on public.subscription_events to castly_api;
grant insert(user_id,actor_id,action,resource_id,reason) on public.subscription_audit_logs to castly_api;
grant update(expires_at,revoked_at) on public.subscription_grants to castly_api;
create policy grant_update on public.subscription_grants for update to castly_api using((user_id=private.request_user_id() and current_setting('castly.subscription_verified',true)='yes') or private.is_subscription_admin()) with check((user_id=private.request_user_id() and current_setting('castly.subscription_verified',true)='yes') or private.is_subscription_admin());
grant update(provider_order_id,state,expires_at,auto_renewing,acknowledged,updated_at) on public.subscription_orders to castly_api;
create policy order_update on public.subscription_orders for update to castly_api using(user_id=private.request_user_id() and current_setting('castly.subscription_verified',true)='yes') with check(user_id=private.request_user_id() and current_setting('castly.subscription_verified',true)='yes');
-- Existing table remains the compatibility projection. New code evaluates the ledger at read time.
grant insert(user_id,entitlement,valid_until),update(valid_until) on public.subscription_entitlements to castly_api;
create policy api_entitlement_insert on public.subscription_entitlements for insert to castly_api with check((user_id=private.request_user_id() and current_setting('castly.subscription_verified',true)='yes') or private.is_subscription_admin());
create policy api_entitlement_update on public.subscription_entitlements for update to castly_api using((user_id=private.request_user_id() and current_setting('castly.subscription_verified',true)='yes') or private.is_subscription_admin()) with check((user_id=private.request_user_id() and current_setting('castly.subscription_verified',true)='yes') or private.is_subscription_admin());
create policy api_admin_entitlement_read on public.subscription_entitlements for select to castly_api using(private.is_subscription_admin());
commit;
