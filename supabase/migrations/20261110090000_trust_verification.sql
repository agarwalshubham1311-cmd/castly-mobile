begin;
-- Phase 4 depends only on the restricted Phase 2 API/identity contract, not Phase 3.
create schema trust_private;
revoke all on schema trust_private from public,anon,authenticated;
grant usage on schema trust_private to castly_api,authenticated;
-- New Storage-specific identity helper; existing private/Auth schema ACLs remain unchanged.
create function trust_private.actor_id() returns uuid language plpgsql stable security invoker set search_path='' as $$
declare a uuid; b uuid; raw text:=nullif(current_setting('request.jwt.claims',true),''); begin
 a:=nullif(current_setting('request.jwt.claim.sub',true),'')::uuid;
 if raw is not null then b:=nullif(raw::jsonb->>'sub','')::uuid; end if;
 if a is not null and b is not null and a<>b then raise exception 'Conflicting identity' using errcode='42501'; end if;
 return coalesce(a,b);
exception when invalid_text_representation then raise exception 'Invalid identity' using errcode='42501'; end $$;
revoke all on function trust_private.actor_id() from public,anon;
grant execute on function trust_private.actor_id() to castly_api,authenticated;
create table trust_private.reviewers(user_id uuid not null references public.users(id),scope text not null check(scope in ('verification','moderation','disputes')),primary key(user_id,scope));
alter table trust_private.reviewers enable row level security;
revoke all on trust_private.reviewers from public,anon,authenticated;
grant select on trust_private.reviewers to castly_api,authenticated;
create policy reviewer_self on trust_private.reviewers for select to castly_api,authenticated using(user_id=trust_private.actor_id());
create function trust_private.is_reviewer(requested_scope text) returns boolean language sql stable security invoker set search_path='' as $$select exists(select 1 from trust_private.reviewers where user_id=trust_private.actor_id() and scope=requested_scope)$$;
revoke all on function trust_private.is_reviewer(text) from public,anon;
grant execute on function trust_private.is_reviewer(text) to castly_api,authenticated;
create table trust_private.rate_limits(user_id uuid not null references public.users(id),operation text not null,window_start timestamptz not null,attempts integer not null check(attempts>0),primary key(user_id,operation));
alter table trust_private.rate_limits enable row level security;
revoke all on trust_private.rate_limits from public,anon,authenticated;
grant select,insert(user_id,operation,window_start,attempts),update(window_start,attempts) on trust_private.rate_limits to castly_api;
create policy rate_own on trust_private.rate_limits for all to castly_api using(user_id=private.request_user_id()) with check(user_id=private.request_user_id());
create table public.trust_audit_logs(id uuid primary key default gen_random_uuid(),actor_id uuid not null references public.users(id),subject_id uuid not null references public.users(id),scope text not null check(scope in ('verification','moderation','disputes','reviews','safety')),action text not null,resource_id uuid,reason text check(length(reason)<=1000),created_at timestamptz not null default now());
alter table public.trust_audit_logs enable row level security;
revoke all on public.trust_audit_logs from public,anon,authenticated;
grant select,insert(actor_id,subject_id,scope,action,resource_id,reason) on public.trust_audit_logs to castly_api;
create policy audit_read on public.trust_audit_logs for select to castly_api using(trust_private.is_reviewer(scope));
create policy audit_insert on public.trust_audit_logs for insert to castly_api with check(actor_id=private.request_user_id() and (subject_id=actor_id or trust_private.is_reviewer(scope)));
create index trust_audit_resource on public.trust_audit_logs(scope,resource_id,created_at desc);
create table public.verification_requests(
 id uuid primary key default gen_random_uuid(),user_id uuid not null unique references public.users(id),
 status text not null default 'PENDING' check(status in ('PENDING','UNDER_REVIEW','VERIFIED','REJECTED','EXPIRED','REVOKED')),
 statement text not null check(length(trim(statement)) between 20 and 2000),revision integer not null default 1 check(revision>0),
 consent_version text not null,submitted_at timestamptz not null default now(),expires_at timestamptz,
 check(status<>'VERIFIED' or expires_at is not null)
);
create index verification_queue on public.verification_requests(status,submitted_at,id);
create table public.verification_evidence(id uuid primary key default gen_random_uuid(),request_id uuid not null references public.verification_requests(id),revision integer not null,owner_id uuid not null references public.users(id),portfolio_asset_id uuid references public.portfolio_assets(id) on delete set null,portfolio_path text not null,created_at timestamptz not null default now(),unique(request_id,revision,portfolio_path));
create table public.verification_decisions(id uuid primary key default gen_random_uuid(),request_id uuid not null references public.verification_requests(id),revision integer not null,reviewer_id uuid not null references public.users(id),decision text not null check(decision in ('UNDER_REVIEW','VERIFIED','REJECTED','REVOKED')),reason text not null check(length(trim(reason)) between 3 and 1000),created_at timestamptz not null default now(),unique(request_id,revision,decision));
create function trust_private.review_facts(p public.talent_profiles) returns jsonb language sql immutable security invoker set search_path='' as $$select jsonb_build_object('display_name',p.display_name,'category_id',p.category_id,'bio',p.bio,'portrait_path',p.portrait_path)$$;
revoke all on function trust_private.review_facts(public.talent_profiles) from public,anon,authenticated;
grant execute on function trust_private.review_facts(public.talent_profiles) to castly_api;
create table public.creator_verification_badges(user_id uuid primary key references public.users(id),verified_until timestamptz not null,portfolio_reviewed boolean not null default false,profile_facts jsonb not null,updated_at timestamptz not null default now());
do $$declare t text;begin foreach t in array array['verification_requests','verification_evidence','verification_decisions','creator_verification_badges']loop execute format('alter table public.%I enable row level security',t);execute format('revoke all on public.%I from public,anon,authenticated',t);execute format('grant select on public.%I to castly_api',t);end loop;end$$;
grant insert(user_id,statement,consent_version),update(statement,consent_version,status,revision,submitted_at,expires_at) on public.verification_requests to castly_api;
create policy verification_read on public.verification_requests for select to castly_api using(user_id=private.request_user_id() or trust_private.is_reviewer('verification'));
create policy verification_insert on public.verification_requests for insert to castly_api with check(user_id=private.request_user_id() and exists(select 1 from public.users where id=private.request_user_id() and primary_role='talent'));
create policy verification_update on public.verification_requests for update to castly_api using(user_id=private.request_user_id() or trust_private.is_reviewer('verification')) with check(user_id=private.request_user_id() or trust_private.is_reviewer('verification'));
create function trust_private.verification_transition() returns trigger language plpgsql security invoker set search_path='' as $$begin
 if new.id<>old.id or new.user_id<>old.user_id then raise exception 'Immutable verification identity' using errcode='42501';end if;
 if new.user_id=private.request_user_id() then
  if new.status<>'PENDING' or new.revision<>old.revision+1 or new.expires_at is not null or not(old.status in ('REJECTED','REVOKED','EXPIRED') or(old.status='VERIFIED' and (old.expires_at<=now() or not exists(select 1 from public.creator_verification_badges v join public.talent_profiles t on t.id=v.user_id where v.user_id=old.user_id and v.profile_facts=trust_private.review_facts(t))))) then raise exception 'Invalid verification submission' using errcode='42501';end if;
 elsif trust_private.is_reviewer('verification') then
  if (to_jsonb(new)-array['status','expires_at']) is distinct from(to_jsonb(old)-array['status','expires_at']) or not((old.status='PENDING' and new.status in ('UNDER_REVIEW','VERIFIED','REJECTED')) or(old.status='UNDER_REVIEW' and new.status in ('VERIFIED','REJECTED')) or(old.status='VERIFIED' and new.status='REVOKED')) then raise exception 'Invalid verification decision' using errcode='42501';end if;
 else raise exception 'Reviewer required' using errcode='42501';end if;return new;end$$;
revoke all on function trust_private.verification_transition() from public,anon,authenticated,castly_api;
create trigger verification_transition before update on public.verification_requests for each row execute function trust_private.verification_transition();
grant insert(request_id,revision,owner_id,portfolio_asset_id,portfolio_path) on public.verification_evidence to castly_api;
create policy verification_evidence_read on public.verification_evidence for select to castly_api using(owner_id=private.request_user_id() or (trust_private.is_reviewer('verification') and exists(select 1 from public.verification_requests r where r.id=request_id and r.revision=verification_evidence.revision and r.status in ('PENDING','UNDER_REVIEW') and r.submitted_at>statement_timestamp()-interval '30 days')));
create policy verification_evidence_insert on public.verification_evidence for insert to castly_api with check(owner_id=private.request_user_id() and exists(select 1 from public.verification_requests r where r.id=request_id and r.user_id=owner_id and r.revision=verification_evidence.revision and r.status='PENDING') and exists(select 1 from public.portfolio_assets a where a.id=portfolio_asset_id and a.talent_id=owner_id and a.path=portfolio_path));
grant insert(request_id,revision,reviewer_id,decision,reason) on public.verification_decisions to castly_api;
create policy decisions_read on public.verification_decisions for select to castly_api using(exists(select 1 from public.verification_requests r where r.id=request_id and (r.user_id=private.request_user_id() or trust_private.is_reviewer('verification'))));
create policy decisions_insert on public.verification_decisions for insert to castly_api with check(reviewer_id=private.request_user_id() and trust_private.is_reviewer('verification') and exists(select 1 from public.verification_requests r where r.id=request_id and r.user_id<>private.request_user_id() and r.revision=verification_decisions.revision and r.status=decision));
grant insert(user_id,verified_until,portfolio_reviewed,profile_facts),update(verified_until,portfolio_reviewed,profile_facts,updated_at) on public.creator_verification_badges to castly_api;
create policy badge_read on public.creator_verification_badges for select to castly_api using(user_id=private.request_user_id() or trust_private.is_reviewer('verification') or exists(select 1 from public.talent_profiles t where t.id=user_id and t.published));
create policy badge_insert on public.creator_verification_badges for insert to castly_api with check(trust_private.is_reviewer('verification') and user_id<>private.request_user_id());
create policy badge_update on public.creator_verification_badges for update to castly_api using(trust_private.is_reviewer('verification') and user_id<>private.request_user_id()) with check(trust_private.is_reviewer('verification') and user_id<>private.request_user_id());
-- A new revision irrevocably invalidates the old public approval projection.
create policy badge_owner_withdraw on public.creator_verification_badges for update to castly_api using(user_id=private.request_user_id()) with check(user_id=private.request_user_id() and not portfolio_reviewed);
create function trust_private.invalidate_verification_revision() returns trigger language plpgsql security invoker set search_path='' as $$begin
 if new.revision<>old.revision then update public.creator_verification_badges set portfolio_reviewed=false where user_id=new.user_id;end if;return new;end$$;
revoke all on function trust_private.invalidate_verification_revision() from public,anon,authenticated,castly_api;
create trigger invalidate_verification_revision after update on public.verification_requests for each row execute function trust_private.invalidate_verification_revision();
-- Reviewer Storage reads are limited to explicitly submitted existing portfolio objects.
grant select on public.verification_evidence to authenticated;
grant select(id,user_id,revision,status,submitted_at) on public.verification_requests to authenticated;
create policy verification_storage_request on public.verification_requests for select to authenticated using(user_id=trust_private.actor_id() or trust_private.is_reviewer('verification'));
create policy verification_storage_metadata on public.verification_evidence for select to authenticated using(owner_id=trust_private.actor_id() or (trust_private.is_reviewer('verification') and exists(select 1 from public.verification_requests r where r.id=request_id and r.revision=verification_evidence.revision and r.status in ('PENDING','UNDER_REVIEW') and r.submitted_at>statement_timestamp()-interval '30 days')));
create policy trust_portfolio_review_read on storage.objects for select to authenticated using(bucket_id='portfolio' and trust_private.is_reviewer('verification') and exists(select 1 from public.verification_evidence e where e.portfolio_path=name));
-- Immutable submission facts for appeal/history; object access is separately time bounded.
create table public.verification_revisions(request_id uuid not null references public.verification_requests(id),revision integer not null,owner_id uuid not null references public.users(id),statement text not null,consent_version text not null,submitted_at timestamptz not null default now(),primary key(request_id,revision));
alter table public.verification_revisions enable row level security;
revoke all on public.verification_revisions from public,anon,authenticated;
grant select,insert(request_id,revision,owner_id,statement,consent_version) on public.verification_revisions to castly_api;
create policy revisions_read on public.verification_revisions for select to castly_api using(owner_id=private.request_user_id() or trust_private.is_reviewer('verification'));
create policy revisions_insert on public.verification_revisions for insert to castly_api with check(owner_id=private.request_user_id() and exists(select 1 from public.verification_requests r where r.id=request_id and r.user_id=owner_id and r.revision=verification_revisions.revision and r.statement=verification_revisions.statement and r.consent_version=verification_revisions.consent_version));
commit;
