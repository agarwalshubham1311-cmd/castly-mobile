-- M7.2 minimized operational read models. Forward only; apply only after review.
-- No public account, booking, payment or document RLS policy is widened.
begin;
create function private.admin_read_model_guard() returns trigger language plpgsql security invoker set search_path=pg_catalog as $$begin
 if current_user='castly_api' and pg_trigger_depth()<2 then raise insufficient_privilege using message='admin_read_model_source_required';end if;
 if tg_op='DELETE' then return old;end if;return new;
end$$;
revoke all on function private.admin_read_model_guard() from public,anon,authenticated,castly_api;
create table admin_private.user_directory(user_id uuid primary key,display_name text not null,primary_role text,created_at timestamptz not null,updated_at timestamptz not null);
alter table admin_private.user_directory enable row level security;
revoke all on admin_private.user_directory from public,anon,authenticated;
grant select,delete on admin_private.user_directory to castly_api;
grant insert(user_id,display_name,primary_role,created_at,updated_at),update(display_name,primary_role,created_at,updated_at) on admin_private.user_directory to castly_api;
create policy operational_read on admin_private.user_directory for select to castly_api using(private.admin_permission('users.read') or pg_trigger_depth()>0);
create policy projection_insert on admin_private.user_directory for insert to castly_api with check(pg_trigger_depth()>0);
create policy projection_update on admin_private.user_directory for update to castly_api using(pg_trigger_depth()>0) with check(pg_trigger_depth()>0);
create policy projection_delete on admin_private.user_directory for delete to castly_api using(pg_trigger_depth()>0);
create trigger source_only before insert or update or delete on admin_private.user_directory for each row execute function private.admin_read_model_guard();
create function private.admin_project_user_directory() returns trigger language plpgsql security invoker set search_path=pg_catalog as $$begin
 if tg_op='DELETE' then delete from admin_private.user_directory where user_id=old.id;return old;end if;
 insert into admin_private.user_directory values(new.id,new.display_name,new.primary_role::text,new.created_at,new.updated_at) on conflict(user_id) do update set display_name=excluded.display_name,primary_role=excluded.primary_role,created_at=excluded.created_at,updated_at=excluded.updated_at;return new;
end$$;
revoke all on function private.admin_project_user_directory() from public,anon,authenticated,castly_api;
create trigger m72_project after insert or update or delete on public.users for each row execute function private.admin_project_user_directory();
insert into admin_private.user_directory select id,display_name,primary_role::text,created_at,updated_at from public.users t;
create table admin_private.talent_summaries(user_id uuid primary key,display_name text,category_id text,city text,day_rate integer,published boolean,available boolean,facts_digest text);
alter table admin_private.talent_summaries enable row level security;
revoke all on admin_private.talent_summaries from public,anon,authenticated;
grant select,delete on admin_private.talent_summaries to castly_api;
grant insert(user_id,display_name,category_id,city,day_rate,published,available,facts_digest),update(display_name,category_id,city,day_rate,published,available,facts_digest) on admin_private.talent_summaries to castly_api;
create policy operational_read on admin_private.talent_summaries for select to castly_api using(private.admin_permission('users.read') or pg_trigger_depth()>0);
create policy projection_insert on admin_private.talent_summaries for insert to castly_api with check(pg_trigger_depth()>0);
create policy projection_update on admin_private.talent_summaries for update to castly_api using(pg_trigger_depth()>0) with check(pg_trigger_depth()>0);
create policy projection_delete on admin_private.talent_summaries for delete to castly_api using(pg_trigger_depth()>0);
create trigger source_only before insert or update or delete on admin_private.talent_summaries for each row execute function private.admin_read_model_guard();
create function private.admin_project_talent_summaries() returns trigger language plpgsql security invoker set search_path=pg_catalog as $$begin
 if tg_op='DELETE' then delete from admin_private.talent_summaries where user_id=old.id;return old;end if;
 insert into admin_private.talent_summaries values(new.id,new.display_name,new.category_id,new.city,new.day_rate,new.published,new.available,encode(sha256(convert_to(trust_private.review_facts(new)::text,'UTF8')),'hex')) on conflict(user_id) do update set display_name=excluded.display_name,category_id=excluded.category_id,city=excluded.city,day_rate=excluded.day_rate,published=excluded.published,available=excluded.available,facts_digest=excluded.facts_digest;return new;
end$$;
revoke all on function private.admin_project_talent_summaries() from public,anon,authenticated,castly_api;
create trigger m72_project after insert or update or delete on public.talent_profiles for each row execute function private.admin_project_talent_summaries();
insert into admin_private.talent_summaries select id,display_name,category_id,city,day_rate,published,available,encode(sha256(convert_to(trust_private.review_facts(t)::text,'UTF8')),'hex') from public.talent_profiles t;
create table admin_private.verification_summaries(user_id uuid primary key,request_id uuid not null,status text,revision integer,submitted_at timestamptz,expires_at timestamptz);
alter table admin_private.verification_summaries enable row level security;
revoke all on admin_private.verification_summaries from public,anon,authenticated;
grant select,delete on admin_private.verification_summaries to castly_api;
grant insert(user_id,request_id,status,revision,submitted_at,expires_at),update(request_id,status,revision,submitted_at,expires_at) on admin_private.verification_summaries to castly_api;
create policy operational_read on admin_private.verification_summaries for select to castly_api using(private.admin_permission('users.read') or pg_trigger_depth()>0);
create policy projection_insert on admin_private.verification_summaries for insert to castly_api with check(pg_trigger_depth()>0);
create policy projection_update on admin_private.verification_summaries for update to castly_api using(pg_trigger_depth()>0) with check(pg_trigger_depth()>0);
create policy projection_delete on admin_private.verification_summaries for delete to castly_api using(pg_trigger_depth()>0);
create trigger source_only before insert or update or delete on admin_private.verification_summaries for each row execute function private.admin_read_model_guard();
create function private.admin_project_verification_summaries() returns trigger language plpgsql security invoker set search_path=pg_catalog as $$begin
 if tg_op='DELETE' then delete from admin_private.verification_summaries where user_id=old.user_id;return old;end if;
 insert into admin_private.verification_summaries values(new.user_id,new.id,new.status,new.revision,new.submitted_at,new.expires_at) on conflict(user_id) do update set request_id=excluded.request_id,status=excluded.status,revision=excluded.revision,submitted_at=excluded.submitted_at,expires_at=excluded.expires_at;return new;
end$$;
revoke all on function private.admin_project_verification_summaries() from public,anon,authenticated,castly_api;
create trigger m72_project after insert or update or delete on public.verification_requests for each row execute function private.admin_project_verification_summaries();
insert into admin_private.verification_summaries select user_id,id,status,revision,submitted_at,expires_at from public.verification_requests t;
create table admin_private.badge_summaries(user_id uuid primary key,verified_until timestamptz,portfolio_reviewed boolean,facts_digest text);
alter table admin_private.badge_summaries enable row level security;
revoke all on admin_private.badge_summaries from public,anon,authenticated;
grant select,delete on admin_private.badge_summaries to castly_api;
grant insert(user_id,verified_until,portfolio_reviewed,facts_digest),update(verified_until,portfolio_reviewed,facts_digest) on admin_private.badge_summaries to castly_api;
create policy operational_read on admin_private.badge_summaries for select to castly_api using(private.admin_permission('users.read') or pg_trigger_depth()>0);
create policy projection_insert on admin_private.badge_summaries for insert to castly_api with check(pg_trigger_depth()>0);
create policy projection_update on admin_private.badge_summaries for update to castly_api using(pg_trigger_depth()>0) with check(pg_trigger_depth()>0);
create policy projection_delete on admin_private.badge_summaries for delete to castly_api using(pg_trigger_depth()>0);
create trigger source_only before insert or update or delete on admin_private.badge_summaries for each row execute function private.admin_read_model_guard();
create function private.admin_project_badge_summaries() returns trigger language plpgsql security invoker set search_path=pg_catalog as $$begin
 if tg_op='DELETE' then delete from admin_private.badge_summaries where user_id=old.user_id;return old;end if;
 insert into admin_private.badge_summaries values(new.user_id,new.verified_until,new.portfolio_reviewed,encode(sha256(convert_to(new.profile_facts::text,'UTF8')),'hex')) on conflict(user_id) do update set verified_until=excluded.verified_until,portfolio_reviewed=excluded.portfolio_reviewed,facts_digest=excluded.facts_digest;return new;
end$$;
revoke all on function private.admin_project_badge_summaries() from public,anon,authenticated,castly_api;
create trigger m72_project after insert or update or delete on public.creator_verification_badges for each row execute function private.admin_project_badge_summaries();
insert into admin_private.badge_summaries select user_id,verified_until,portfolio_reviewed,encode(sha256(convert_to(profile_facts::text,'UTF8')),'hex') from public.creator_verification_badges t;
create table admin_private.booking_summaries(id uuid primary key,client_id uuid,talent_id uuid,project_type text,proposed_date date,status text,created_at timestamptz);
alter table admin_private.booking_summaries enable row level security;
revoke all on admin_private.booking_summaries from public,anon,authenticated;
grant select,delete on admin_private.booking_summaries to castly_api;
grant insert(id,client_id,talent_id,project_type,proposed_date,status,created_at),update(client_id,talent_id,project_type,proposed_date,status,created_at) on admin_private.booking_summaries to castly_api;
create policy operational_read on admin_private.booking_summaries for select to castly_api using(private.admin_permission('bookings.read') or pg_trigger_depth()>0);
create policy projection_insert on admin_private.booking_summaries for insert to castly_api with check(pg_trigger_depth()>0);
create policy projection_update on admin_private.booking_summaries for update to castly_api using(pg_trigger_depth()>0) with check(pg_trigger_depth()>0);
create policy projection_delete on admin_private.booking_summaries for delete to castly_api using(pg_trigger_depth()>0);
create trigger source_only before insert or update or delete on admin_private.booking_summaries for each row execute function private.admin_read_model_guard();
create function private.admin_project_booking_summaries() returns trigger language plpgsql security invoker set search_path=pg_catalog as $$begin
 if tg_op='DELETE' then delete from admin_private.booking_summaries where id=old.id;return old;end if;
 insert into admin_private.booking_summaries values(new.id,new.client_id,new.talent_id,new.project_type,new.proposed_date,new.status::text,new.created_at) on conflict(id) do update set client_id=excluded.client_id,talent_id=excluded.talent_id,project_type=excluded.project_type,proposed_date=excluded.proposed_date,status=excluded.status,created_at=excluded.created_at;return new;
end$$;
revoke all on function private.admin_project_booking_summaries() from public,anon,authenticated,castly_api;
create trigger m72_project after insert or update or delete on public.bookings for each row execute function private.admin_project_booking_summaries();
insert into admin_private.booking_summaries select id,client_id,talent_id,project_type,proposed_date,status::text,created_at from public.bookings t;
create table admin_private.grant_summaries(id uuid primary key,user_id uuid,entitlement text,source text,starts_at timestamptz,expires_at timestamptz,revoked_at timestamptz);
alter table admin_private.grant_summaries enable row level security;
revoke all on admin_private.grant_summaries from public,anon,authenticated;
grant select,delete on admin_private.grant_summaries to castly_api;
grant insert(id,user_id,entitlement,source,starts_at,expires_at,revoked_at),update(user_id,entitlement,source,starts_at,expires_at,revoked_at) on admin_private.grant_summaries to castly_api;
create policy operational_read on admin_private.grant_summaries for select to castly_api using(private.admin_permission('users.read') or pg_trigger_depth()>0);
create policy projection_insert on admin_private.grant_summaries for insert to castly_api with check(pg_trigger_depth()>0);
create policy projection_update on admin_private.grant_summaries for update to castly_api using(pg_trigger_depth()>0) with check(pg_trigger_depth()>0);
create policy projection_delete on admin_private.grant_summaries for delete to castly_api using(pg_trigger_depth()>0);
create trigger source_only before insert or update or delete on admin_private.grant_summaries for each row execute function private.admin_read_model_guard();
create function private.admin_project_grant_summaries() returns trigger language plpgsql security invoker set search_path=pg_catalog as $$begin
 if tg_op='DELETE' then delete from admin_private.grant_summaries where id=old.id;return old;end if;
 insert into admin_private.grant_summaries values(new.id,new.user_id,new.entitlement,new.source,new.starts_at,new.expires_at,new.revoked_at) on conflict(id) do update set user_id=excluded.user_id,entitlement=excluded.entitlement,source=excluded.source,starts_at=excluded.starts_at,expires_at=excluded.expires_at,revoked_at=excluded.revoked_at;return new;
end$$;
revoke all on function private.admin_project_grant_summaries() from public,anon,authenticated,castly_api;
create trigger m72_project after insert or update or delete on public.subscription_grants for each row execute function private.admin_project_grant_summaries();
insert into admin_private.grant_summaries select id,user_id,entitlement,source,starts_at,expires_at,revoked_at from public.subscription_grants t;
create index admin_directory_role_created on admin_private.user_directory(primary_role,created_at,user_id);
create index admin_directory_created on admin_private.user_directory(created_at,user_id);
create index admin_directory_prefix on admin_private.user_directory(lower(display_name) text_pattern_ops,user_id);
create index admin_verification_state on admin_private.verification_summaries(status,expires_at,user_id);
create index admin_bookings_client on admin_private.booking_summaries(client_id,id);
create index admin_bookings_talent on admin_private.booking_summaries(talent_id,id);
create index admin_grants_user on admin_private.grant_summaries(user_id,id);
create table admin_private.user_history(
 id bigint generated always as identity primary key,user_id uuid not null,actor_id uuid,
 action text not null check(action in('account.created','account.updated','talent.created','talent.updated','talent.deleted')),
 occurred_at timestamptz not null default clock_timestamp());
alter table admin_private.user_history enable row level security;
revoke all on admin_private.user_history from public,anon,authenticated;
grant select on admin_private.user_history to castly_api;
grant insert(user_id,actor_id,action) on admin_private.user_history to castly_api;
grant usage on sequence admin_private.user_history_id_seq to castly_api;
create policy history_read on admin_private.user_history for select to castly_api using(private.admin_permission('users.read'));
create policy source_append on admin_private.user_history for insert to castly_api with check(pg_trigger_depth()>0);
create trigger source_only before insert on admin_private.user_history for each row execute function private.admin_read_model_guard();
create index admin_history_user on admin_private.user_history(user_id,id desc);
create function private.admin_user_changed() returns trigger language plpgsql security invoker set search_path=pg_catalog as $$begin
 insert into admin_private.user_history(user_id,actor_id,action) values(case when tg_op='DELETE' then old.id else new.id end,private.request_user_id(),case when tg_table_name='users' then 'account.' else 'talent.' end||lower(case when tg_op='INSERT' then 'CREATED' when tg_op='UPDATE' then 'UPDATED' else 'DELETED' end));
 if tg_op='DELETE' then return old;end if;return new;
end$$;
revoke all on function private.admin_user_changed() from public,anon,authenticated,castly_api;
create trigger m72_history after insert or update on public.users for each row execute function private.admin_user_changed();
create trigger m72_history after insert or update or delete on public.talent_profiles for each row execute function private.admin_user_changed();
commit;
