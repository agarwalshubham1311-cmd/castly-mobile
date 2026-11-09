begin;
create table public.promotion_claims (
 id uuid primary key default gen_random_uuid(), user_id uuid not null unique references public.users(id),
 post_url text not null unique check(length(post_url) between 10 and 2048),
 status text not null default 'pending' check(status in ('pending','approved','rejected')),
 reason text check(length(reason) between 3 and 1000), reviewer_id uuid references public.users(id),
 reviewed_at timestamptz, created_at timestamptz not null default now(),
 check((status='pending' and reason is null and reviewer_id is null and reviewed_at is null) or (status<>'pending' and reason is not null and reviewer_id is not null and reviewed_at is not null))
);
create index promotion_claim_review_queue on public.promotion_claims(status,created_at,id);
alter table public.promotion_claims enable row level security;
revoke all on public.promotion_claims from anon,authenticated;
grant select,insert(user_id,post_url),update(status,reason,reviewer_id,reviewed_at) on public.promotion_claims to castly_api;
create policy claim_read on public.promotion_claims for select to castly_api using(user_id=private.request_user_id() or private.is_subscription_admin());
create policy claim_insert on public.promotion_claims for insert to castly_api with check(user_id=private.request_user_id() and exists(select 1 from public.users where id=private.request_user_id() and primary_role='talent'));
create policy claim_review on public.promotion_claims for update to castly_api using(private.is_subscription_admin()) with check(private.is_subscription_admin() and reviewer_id=private.request_user_id() and user_id<>private.request_user_id());
create function private.guard_promotion_review() returns trigger language plpgsql security invoker set search_path='' as $$ begin
 if old.status<>'pending' or new.status='pending' then raise exception 'Promotion decision is final' using errcode='42501'; end if;
 return new;
end $$;
revoke all on function private.guard_promotion_review() from public;
create trigger promotion_review_transition before update on public.promotion_claims for each row execute function private.guard_promotion_review();
create table private.subscription_rate_limits(user_id uuid not null references public.users(id), operation text not null, window_start timestamptz not null, attempts integer not null, primary key(user_id,operation));
alter table private.subscription_rate_limits enable row level security;
grant select,insert,update on private.subscription_rate_limits to castly_api;
create policy subscription_rate_own on private.subscription_rate_limits for all to castly_api using(user_id=private.request_user_id()) with check(user_id=private.request_user_id());
commit;
