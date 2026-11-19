-- M7.1 only. Forward-only. Rehearse locally; no bootstrap or hosted execution here.
begin;
create schema admin_private;
revoke all on schema admin_private from public,anon,authenticated;
grant usage on schema admin_private to castly_api;
create table admin_private.role_permissions(role text not null,permission text not null,primary key(role,permission));
insert into admin_private.role_permissions values
('OPERATIONS_ADMIN','users.read'),('OPERATIONS_ADMIN','bookings.read'),('OPERATIONS_ADMIN','bookings.manage'),('OPERATIONS_ADMIN','reports.review'),('OPERATIONS_ADMIN','analytics.read'),
('MODERATOR','users.read'),('MODERATOR','reports.review'),('MODERATOR','moderation.action'),
('SUPPORT_AGENT','users.read'),('SUPPORT_AGENT','bookings.read'),
('FINANCE_ADMIN','subscriptions.read'),('FINANCE_ADMIN','finance.read'),('ANALYST','analytics.read');
insert into admin_private.role_permissions select 'SUPER_ADMIN',p from unnest(array['users.read','users.suspend','users.verify','bookings.read','bookings.manage','reports.review','moderation.action','subscriptions.read','finance.read','analytics.read','admins.manage','audit.read']) p;
create table admin_private.principals(
 user_id uuid primary key references public.users(id), roles text[] not null,
 state text not null check(state in ('active','revoked')), version bigint not null default 1,
 created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
 check(cardinality(roles) between 1 and 6),
 check(roles <@ array['SUPER_ADMIN','OPERATIONS_ADMIN','MODERATOR','SUPPORT_AGENT','FINANCE_ADMIN','ANALYST']::text[]));
create table admin_private.authorizations(user_id uuid primary key references admin_private.principals(user_id),roles text[] not null,state text not null,version bigint not null);
create table admin_private.sessions(
 id uuid primary key default gen_random_uuid(),user_id uuid not null references admin_private.principals(user_id),
 auth_session_id uuid not null,principal_version bigint not null,
 created_at timestamptz not null default now(),expires_at timestamptz not null default now()+interval '1 hour',revoked_at timestamptz,
 ip_fingerprint text not null,agent_family text not null check(agent_family in ('chromium','firefox','safari','other')));
create index on admin_private.sessions(user_id,id);
create index on admin_private.sessions(user_id,created_at desc);
create table admin_private.invitations(
 id uuid primary key default gen_random_uuid(),user_id uuid not null references public.users(id),roles text[] not null,
 token_hash text not null unique check(token_hash ~ '^[0-9a-f]{64}$'),created_by uuid not null,
 created_at timestamptz not null default now(),expires_at timestamptz not null default now()+interval '24 hours',redeemed_at timestamptz,
 check(cardinality(roles) between 1 and 5),
 check(roles <@ array['OPERATIONS_ADMIN','MODERATOR','SUPPORT_AGENT','FINANCE_ADMIN','ANALYST']::text[]));
create index on admin_private.invitations(user_id,expires_at);
create table admin_private.audit(
 id bigint generated always as identity primary key,actor_id uuid,action text not null,target_id uuid,
 occurred_at timestamptz not null default clock_timestamp(),request_id uuid not null,
 reason text not null check(length(reason) between 3 and 500),outcome text not null check(outcome in ('success','denied')),
 before_state jsonb not null default '{}',after_state jsonb not null default '{}',
 ip_fingerprint text not null,agent_family text not null);
create table admin_private.rate_limits(user_id uuid not null,bucket text not null check(bucket in ('login','read','write')),window_start timestamptz not null,attempts int not null,primary key(user_id,bucket));

create function private.admin_mfa_recent() returns boolean language sql stable security invoker set search_path=pg_catalog as $$
 select coalesce(current_setting('request.admin.aal2',true)='true' and nullif(current_setting('request.admin.mfa_at',true),'')::timestamptz between now()-interval '5 minutes' and now()+interval '30 seconds',false)
$$;
create function private.admin_permission(required text) returns boolean language sql stable security invoker set search_path=pg_catalog as $$
 select coalesce(current_setting('request.admin.aal2',true)='true',false) and exists(
 select 1 from admin_private.authorizations a join admin_private.role_permissions p on p.role=any(a.roles)
 join admin_private.sessions s on s.user_id=a.user_id and s.principal_version=a.version
 where a.user_id=private.request_user_id() and a.state='active' and p.permission=required
 and s.id=nullif(current_setting('request.admin.session',true),'')::uuid
 and s.auth_session_id=nullif(current_setting('request.admin.auth_session',true),'')::uuid
 and s.revoked_at is null and s.expires_at>now())
$$;
create function private.admin_invited(target uuid,assigned text[]) returns boolean language sql stable security invoker set search_path=pg_catalog as $$
 select private.admin_mfa_recent() and target=private.request_user_id() and exists(select 1 from admin_private.invitations i
 where i.user_id=target and i.roles=assigned and i.token_hash=current_setting('request.admin.invite_hash',true) and i.redeemed_at is null and i.expires_at>now())
$$;

alter table admin_private.role_permissions enable row level security;
create policy role_definitions on admin_private.role_permissions for select to castly_api using(true);
alter table admin_private.authorizations enable row level security;
create policy own_auth on admin_private.authorizations for select to castly_api using(user_id=private.request_user_id() or pg_trigger_depth()>0);
create policy project_insert on admin_private.authorizations for insert to castly_api with check(pg_trigger_depth()>0);
create policy project_update on admin_private.authorizations for update to castly_api using(pg_trigger_depth()>0) with check(pg_trigger_depth()>0);
alter table admin_private.sessions enable row level security;
-- Actor-only session input avoids recursive RLS authorization. Revoke other accounts through principal version changes.
create policy own_session_read on admin_private.sessions for select to castly_api using(user_id=private.request_user_id());
create policy session_insert on admin_private.sessions for insert to castly_api with check(user_id=private.request_user_id() and private.admin_mfa_recent() and exists(select 1 from admin_private.authorizations a where a.user_id=sessions.user_id and a.state='active' and a.version=sessions.principal_version));
create policy session_revoke on admin_private.sessions for update to castly_api using(user_id=private.request_user_id()) with check(user_id=private.request_user_id());
alter table admin_private.invitations enable row level security;
create policy invitation_read on admin_private.invitations for select to castly_api using(user_id=private.request_user_id() or private.admin_permission('admins.manage'));
create policy invitation_create on admin_private.invitations for insert to castly_api with check(private.admin_permission('admins.manage') and private.admin_mfa_recent() and created_by=private.request_user_id() and user_id<>private.request_user_id());
create policy invitation_redeem on admin_private.invitations for update to castly_api using(user_id=private.request_user_id() and token_hash=current_setting('request.admin.invite_hash',true) and redeemed_at is null and expires_at>now() and private.admin_mfa_recent()) with check(user_id=private.request_user_id());
alter table admin_private.principals enable row level security;
create policy principal_read on admin_private.principals for select to castly_api using(user_id=private.request_user_id() or private.admin_permission('admins.manage'));
create policy principal_create on admin_private.principals for insert to castly_api with check(private.admin_invited(user_id,roles));
create policy principal_manage on admin_private.principals for update to castly_api using(private.admin_permission('admins.manage') and private.admin_mfa_recent() and user_id<>private.request_user_id()) with check(private.admin_permission('admins.manage') and private.admin_mfa_recent() and user_id<>private.request_user_id());
alter table admin_private.audit enable row level security;
create policy audit_read on admin_private.audit for select to castly_api using(private.admin_permission('audit.read'));
create policy audit_append on admin_private.audit for insert to castly_api with check(actor_id=private.request_user_id());
alter table admin_private.rate_limits enable row level security;
create policy rate_read on admin_private.rate_limits for select to castly_api using(user_id=private.request_user_id());
create policy rate_insert on admin_private.rate_limits for insert to castly_api with check(user_id=private.request_user_id());
create policy rate_update on admin_private.rate_limits for update to castly_api using(user_id=private.request_user_id()) with check(user_id=private.request_user_id());

create function private.admin_projection_guard() returns trigger language plpgsql security invoker set search_path=pg_catalog as $$begin
 if pg_trigger_depth()<2 then raise insufficient_privilege using message='admin_projection_private'; end if; return new;
end$$;
create trigger projection_guard before insert or update on admin_private.authorizations for each row execute function private.admin_projection_guard();
create function private.admin_mutation_guard() returns trigger language plpgsql security invoker set search_path=pg_catalog as $$begin
 if tg_table_name='principals' then
  perform pg_advisory_xact_lock(hashtextextended('admin:'||new.user_id,0));
  if current_user='castly_api' and ('SUPER_ADMIN'=any(new.roles) or (tg_op='UPDATE' and 'SUPER_ADMIN'=any(old.roles))) then raise insufficient_privilege using message='admin_super_immutable'; end if;
  if current_user<>'castly_api' and current_setting('castly.admin_bootstrap_reviewed',true) is distinct from 'M7.1-owner-approved' then raise insufficient_privilege using message='bootstrap_review_required'; end if;
  if tg_op='UPDATE' then new.version=old.version+1; new.updated_at=now(); end if;
 elsif tg_table_name='sessions' then
  if tg_op='INSERT' then
   if new.auth_session_id is distinct from nullif(current_setting('request.admin.auth_session',true),'')::uuid then raise insufficient_privilege using message='admin_session_binding'; end if;
   new.created_at=now(); new.expires_at=now()+interval '1 hour';
  else new.revoked_at=now(); end if;
 elsif tg_table_name='invitations' then
  if tg_op='INSERT' then new.created_at=now();new.expires_at=now()+interval '24 hours';
  else new.redeemed_at=now(); end if;
 end if;
 return new;
end$$;
create trigger principal_guard before insert or update on admin_private.principals for each row execute function private.admin_mutation_guard();
create trigger session_guard before insert or update on admin_private.sessions for each row execute function private.admin_mutation_guard();
create trigger invite_guard before insert or update on admin_private.invitations for each row execute function private.admin_mutation_guard();
create function private.admin_audit_guard() returns trigger language plpgsql security invoker set search_path=pg_catalog as $$begin
 if tg_op<>'INSERT' then raise insufficient_privilege using message='admin_audit_append_only'; end if;
 if current_user='castly_api' and (new.actor_id is distinct from private.request_user_id() or (pg_trigger_depth()<2 and (new.outcome<>'denied' or new.action not in ('access.denied','session.denied','rate.denied')))) then raise insufficient_privilege using message='admin_audit_source'; end if;
 if new.before_state - array['roles','state','version','revoked'] <> '{}'::jsonb or new.after_state - array['roles','state','version','revoked'] <> '{}'::jsonb then raise insufficient_privilege using message='admin_audit_metadata'; end if;
 new.occurred_at=clock_timestamp(); return new;
end$$;
create trigger audit_guard before insert or update or delete on admin_private.audit for each row execute function private.admin_audit_guard();
create function private.admin_changed() returns trigger language plpgsql security invoker set search_path=pg_catalog as $$
declare prior jsonb='{}'; following jsonb='{}';target uuid;event text;
begin
 if tg_table_name='principals' then
  insert into admin_private.authorizations values(new.user_id,new.roles,new.state,new.version) on conflict(user_id) do update set roles=excluded.roles,state=excluded.state,version=excluded.version;
  target=new.user_id;event=case when tg_op='INSERT' then 'principal.activated' else 'principal.changed' end;
  following=jsonb_build_object('roles',new.roles,'state',new.state,'version',new.version);
  if tg_op='UPDATE' then prior=jsonb_build_object('roles',old.roles,'state',old.state,'version',old.version); end if;
 elsif tg_table_name='sessions' then
  target=new.id;event=case when tg_op='INSERT' then 'session.created' else 'session.revoked' end;
  following=jsonb_build_object('revoked',new.revoked_at is not null);
 else target=new.id;event=case when tg_op='INSERT' then 'invitation.created' else 'invitation.redeemed' end;
  following=jsonb_build_object('roles',new.roles);
 end if;
 insert into admin_private.audit(actor_id,action,target_id,request_id,reason,outcome,before_state,after_state,ip_fingerprint,agent_family)
 values(private.request_user_id(),event,target,coalesce(nullif(current_setting('request.admin.request_id',true),'')::uuid,gen_random_uuid()),coalesce(nullif(current_setting('request.admin.reason',true),''),'Reviewed owner bootstrap'),'success',prior,following,coalesce(current_setting('request.admin.ip',true),''),coalesce(current_setting('request.admin.agent',true),'other'));
 return new;
end$$;
create trigger principal_changed after insert or update on admin_private.principals for each row execute function private.admin_changed();
create trigger session_changed after insert or update on admin_private.sessions for each row execute function private.admin_changed();
create trigger invitation_changed after insert or update on admin_private.invitations for each row execute function private.admin_changed();

grant select on admin_private.role_permissions,admin_private.authorizations,admin_private.principals,admin_private.sessions,admin_private.audit,admin_private.rate_limits to castly_api;
grant select(id,user_id,roles,created_by,created_at,expires_at,redeemed_at,token_hash) on admin_private.invitations to castly_api;
grant insert(user_id,roles,state) on admin_private.principals to castly_api;
grant update(roles,state) on admin_private.principals to castly_api;
grant insert(user_id,roles,state,version),update(roles,state,version) on admin_private.authorizations to castly_api;
grant insert(user_id,auth_session_id,principal_version,ip_fingerprint,agent_family) on admin_private.sessions to castly_api;
grant update(revoked_at) on admin_private.sessions to castly_api;
grant insert(user_id,roles,token_hash,created_by) on admin_private.invitations to castly_api;
grant update(redeemed_at) on admin_private.invitations to castly_api;
grant insert(actor_id,action,target_id,request_id,reason,outcome,before_state,after_state,ip_fingerprint,agent_family) on admin_private.audit to castly_api;
grant usage on sequence admin_private.audit_id_seq to castly_api;
grant insert(user_id,bucket,window_start,attempts),update(window_start,attempts) on admin_private.rate_limits to castly_api;
revoke all on all functions in schema admin_private from public;
revoke all on function private.admin_permission(text),private.admin_mfa_recent(),private.admin_invited(uuid,text[]),private.admin_mutation_guard(),private.admin_projection_guard(),private.admin_audit_guard(),private.admin_changed() from public;
grant execute on function private.admin_permission(text),private.admin_mfa_recent(),private.admin_invited(uuid,text[]) to castly_api;
commit;
