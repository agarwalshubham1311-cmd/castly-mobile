-- M7.2 approved account enforcement. Forward only; local rehearsal before any hosted approval.
begin;
do $$begin
 if to_regprocedure('private.request_user_id()') is null or to_regclass('admin_private.principals') is null or to_regclass('public.talent_availability_days') is null then raise exception 'Reviewed Phase 4, Phase 5 and M7.1 migrations required';end if;
 if exists(select 1 from pg_roles where rolname='castly_api' and (rolsuper or rolbypassrls or rolcreaterole)) or exists(select 1 from pg_auth_members where member=(select oid from pg_roles where rolname='castly_api')) then raise exception 'Restricted business role required';end if;
end$$;
create schema enforcement_private;
revoke all on schema enforcement_private from public;
grant usage on schema enforcement_private to castly_api,anon,authenticated,castly_engagement_worker;
create function enforcement_private.actor() returns uuid language sql stable security invoker set search_path=pg_catalog as $$select coalesce(nullif(current_setting('request.jwt.claim.sub',true),''),nullif(current_setting('request.jwt.claims',true),'')::jsonb->>'sub')::uuid$$;
create function enforcement_private.lock_accounts(ids uuid[],exclusive_mode boolean default false) returns void language plpgsql security invoker set search_path=pg_catalog as $$declare u uuid;begin
 for u in select distinct x from unnest(ids) x where x is not null order by x loop
  if exclusive_mode then perform pg_advisory_xact_lock(hashtextextended('castly-account:'||u,0));else perform pg_advisory_xact_lock_shared(hashtextextended('castly-account:'||u,0));end if;
 end loop;end$$;
create table enforcement_private.capabilities(user_id uuid primary key,denied text[] not null,until_at timestamptz,suspended boolean not null,discovery_until timestamptz not null);
alter table enforcement_private.capabilities enable row level security;
create policy capability_read on enforcement_private.capabilities for select using(true);
create policy capability_write on enforcement_private.capabilities for all to castly_api using(pg_trigger_depth()>0) with check(pg_trigger_depth()>0);
grant select on enforcement_private.capabilities to castly_api,authenticated,castly_engagement_worker;
grant select(user_id,discovery_until) on enforcement_private.capabilities to anon;
grant insert(user_id,denied,until_at,suspended,discovery_until),update(denied,until_at,suspended,discovery_until) on enforcement_private.capabilities to castly_api;
create function enforcement_private.visible(u uuid) returns boolean language sql stable security invoker set search_path=pg_catalog as $$select not exists(select 1 from enforcement_private.capabilities where user_id=u and discovery_until>clock_timestamp())$$;
create function enforcement_private.allowed(u uuid,cap text) returns boolean language sql volatile security invoker set search_path=pg_catalog as $$select not exists(select 1 from enforcement_private.capabilities where user_id=u and cap=any(denied) and (until_at is null or until_at>clock_timestamp()))$$;
create function enforcement_private.not_suspended(u uuid) returns boolean language sql volatile security invoker set search_path=pg_catalog as $$select not exists(select 1 from enforcement_private.capabilities where user_id=u and suspended and (until_at is null or until_at>clock_timestamp()))$$;
create function enforcement_private.require_allowed(u uuid,cap text) returns void language plpgsql security invoker set search_path=pg_catalog as $$begin
 perform enforcement_private.lock_accounts(array[u]);if not enforcement_private.allowed(u,cap) then raise insufficient_privilege using message='Account capability restricted';end if;end$$;
create table enforcement_private.admin_accounts(user_id uuid primary key);
insert into enforcement_private.admin_accounts select user_id from admin_private.principals;
create table enforcement_private.accounts(user_id uuid primary key references public.users(id),version bigint not null default 0,state text not null default 'ACTIVE' check(state in('ACTIVE','RESTRICTED','TEMPORARILY_SUSPENDED','PERMANENTLY_SUSPENDED')),denied text[] not null default '{}',until_at timestamptz,episode_start timestamptz,episode_until timestamptz,decision_id uuid,issuer_id uuid,co_issuer_id uuid,explanation text not null default '',reason_code text,investigation text not null default 'OPEN' check(investigation in('OPEN','UNDER_REVIEW','CLOSED')),updated_at timestamptz not null default clock_timestamp());
create table enforcement_private.decisions(id uuid primary key default gen_random_uuid(),user_id uuid not null references public.users(id),actor_id uuid not null,co_actor_id uuid,state text not null,denied text[] not null,until_at timestamptz,reason_code text not null,explanation text not null,evidence_ref text not null,notified_at timestamptz not null default clock_timestamp());
create table enforcement_private.notes(decision_id uuid primary key,notes text not null);
create table enforcement_private.proposals(id uuid primary key default gen_random_uuid(),user_id uuid not null,proposer_id uuid not null,base_version bigint not null,action text not null check(action in('PERMANENT','REINSTATE','MODIFY')),state text not null default 'PENDING' check(state in('PENDING','APPROVED','CANCELLED')),payload jsonb not null,appeal_id uuid,created_at timestamptz not null default clock_timestamp(),decided_by uuid);
create table enforcement_private.appeals(id uuid primary key default gen_random_uuid(),user_id uuid not null,decision_id uuid not null unique references enforcement_private.decisions(id),status text not null default 'SUBMITTED' check(status in('SUBMITTED','UNDER_REVIEW','UPHELD','MODIFIED','OVERTURNED','WITHDRAWN')),version bigint not null default 1,statement text not null,evidence_ref text not null,reviewer_id uuid,submitted_at timestamptz not null default clock_timestamp(),due_at timestamptz not null default clock_timestamp()+interval '7 days',reopen_evidence text,reopen_statement text,resolution text);
create table enforcement_private.commands(id uuid primary key default gen_random_uuid(),actor_id uuid not null default enforcement_private.actor(),request_key uuid not null,operation text not null,user_id uuid not null,payload jsonb not null,result jsonb,created_at timestamptz not null default clock_timestamp(),unique(actor_id,request_key));
create table enforcement_private.events(id bigint generated always as identity primary key,user_id uuid not null,actor_id uuid not null,operation text not null,resource_id uuid,created_at timestamptz not null default clock_timestamp(),public_data jsonb not null);
create table enforcement_private.notices(id uuid primary key default gen_random_uuid(),user_id uuid not null,event_id bigint not null unique references enforcement_private.events(id),kind text not null,explanation text not null,created_at timestamptz not null default clock_timestamp());
create index enforcement_escalations on enforcement_private.commands(user_id,created_at desc,id) where operation='ESCALATE';
create index enforcement_state on enforcement_private.accounts(state,user_id);
create index enforcement_expiry on enforcement_private.accounts(until_at,user_id);
create index enforcement_history on enforcement_private.decisions(user_id,notified_at desc,id);
create index enforcement_proposal_queue on enforcement_private.proposals(state,id);
create index enforcement_appeal_queue on enforcement_private.appeals(status,due_at,id);
create index enforcement_appeal_pending on enforcement_private.appeals(id) where status in('SUBMITTED','UNDER_REVIEW') or reopen_evidence is not null;
create index enforcement_appeal_owner on enforcement_private.appeals(user_id,id);
create index enforcement_event_owner on enforcement_private.events(user_id,id desc);
create index enforcement_notice_owner on enforcement_private.notices(user_id,created_at desc,id);
insert into admin_private.role_permissions values
 ('SUPER_ADMIN','accounts.read'),('SUPER_ADMIN','accounts.escalate'),('SUPER_ADMIN','accounts.enforce'),('SUPER_ADMIN','accounts.permanent'),('SUPER_ADMIN','accounts.appeals'),
 ('MODERATOR','accounts.read'),('MODERATOR','accounts.escalate'),('MODERATOR','accounts.enforce'),('MODERATOR','accounts.appeals'),
 ('OPERATIONS_ADMIN','accounts.read'),('OPERATIONS_ADMIN','accounts.escalate'),('OPERATIONS_ADMIN','accounts.enforce'),('OPERATIONS_ADMIN','accounts.appeals'),
 ('SUPPORT_AGENT','accounts.read'),('SUPPORT_AGENT','accounts.escalate');
create function enforcement_private.write_guard() returns trigger language plpgsql security invoker set search_path=pg_catalog as $$begin
 if current_user='castly_api' and pg_trigger_depth()<2 then raise insufficient_privilege using message='Enforcement source command required';end if;
 if tg_op='DELETE' then return old;end if;return new;end$$;
do $$declare t text;begin foreach t in array array['admin_accounts','accounts','decisions','notes','proposals','appeals','commands','events','notices'] loop
 execute format('alter table enforcement_private.%I enable row level security',t);
 execute format('grant select on enforcement_private.%I to castly_api',t);
 if t<>'commands' then
  execute format('grant insert on enforcement_private.%I to castly_api',t);
  execute format('create trigger guarded before insert or update or delete on enforcement_private.%I for each row execute function enforcement_private.write_guard()',t);
  execute format('create policy source_insert on enforcement_private.%I for insert to castly_api with check(pg_trigger_depth()>0)',t);
 end if;end loop;end$$;
grant update(version,state,denied,until_at,episode_start,episode_until,decision_id,issuer_id,co_issuer_id,explanation,reason_code,investigation,updated_at) on enforcement_private.accounts to castly_api;
grant update(state,decided_by) on enforcement_private.proposals to castly_api;
grant update(status,version,statement,evidence_ref,reviewer_id,submitted_at,due_at,reopen_evidence,reopen_statement,resolution) on enforcement_private.appeals to castly_api;
grant usage on sequence enforcement_private.events_id_seq to castly_api;
grant insert(request_key,operation,user_id,payload) on enforcement_private.commands to castly_api;
create policy command_own on enforcement_private.commands for select to castly_api using(actor_id=enforcement_private.actor());
create policy command_staff on enforcement_private.commands for select to castly_api using(private.admin_permission('accounts.enforce'));
create policy command_create on enforcement_private.commands for insert to castly_api with check(actor_id=enforcement_private.actor());
create policy own_or_staff on enforcement_private.accounts for select to castly_api using(user_id=enforcement_private.actor() or private.admin_permission('accounts.read') or private.admin_permission('users.read') or pg_trigger_depth()>0);
create policy own_decisions on enforcement_private.decisions for select to castly_api using(user_id=enforcement_private.actor() or private.admin_permission('accounts.read') or pg_trigger_depth()>0);
create policy staff_notes on enforcement_private.notes for select to castly_api using(private.admin_permission('accounts.enforce') or pg_trigger_depth()>0);
create policy staff_proposals on enforcement_private.proposals for select to castly_api using(private.admin_permission('accounts.permanent') or pg_trigger_depth()>0);
create policy own_appeals on enforcement_private.appeals for select to castly_api using(user_id=enforcement_private.actor() or private.admin_permission('accounts.appeals') or pg_trigger_depth()>0);
create policy own_events on enforcement_private.events for select to castly_api using((user_id=enforcement_private.actor() and (public_data->>'explanation' is not null or operation like 'APPEAL_%')) or private.admin_permission('accounts.read') or pg_trigger_depth()>0);
create policy own_notices on enforcement_private.notices for select to castly_api using(user_id=enforcement_private.actor() or pg_trigger_depth()>0);
create policy marker_read on enforcement_private.admin_accounts for select to castly_api using(private.admin_permission('accounts.enforce') or pg_trigger_depth()>0);
do $$declare t text;begin foreach t in array array['accounts','proposals','appeals'] loop execute format('create policy source_update on enforcement_private.%I for update to castly_api using(pg_trigger_depth()>0) with check(pg_trigger_depth()>0)',t);end loop;end$$;
create function enforcement_private.project() returns trigger language plpgsql security invoker set search_path=pg_catalog as $$begin
 insert into enforcement_private.capabilities values(new.user_id,new.denied,new.until_at,new.state in('TEMPORARILY_SUSPENDED','PERMANENTLY_SUSPENDED'),case when 'discovery'=any(new.denied) then coalesce(new.until_at,'infinity') else '-infinity' end) on conflict(user_id) do update set denied=excluded.denied,until_at=excluded.until_at,suspended=excluded.suspended,discovery_until=excluded.discovery_until;return new;end$$;
create trigger projection_guard before insert or update or delete on enforcement_private.capabilities for each row execute function enforcement_private.write_guard();
create trigger projection after insert or update on enforcement_private.accounts for each row execute function enforcement_private.project();
create function enforcement_private.admin_marker() returns trigger language plpgsql security invoker set search_path=pg_catalog as $$begin
 perform enforcement_private.lock_accounts(array[new.user_id],true);
 if not enforcement_private.not_suspended(new.user_id) or exists(select 1 from enforcement_private.capabilities where user_id=new.user_id and cardinality(denied)>0 and (until_at is null or until_at>clock_timestamp())) then raise insufficient_privilege using message='Restricted account cannot become administrator';end if;
 insert into enforcement_private.admin_accounts values(new.user_id) on conflict do nothing;return new;end$$;
create trigger enforcement_marker after insert or update on admin_private.principals for each row execute function enforcement_private.admin_marker();
-- A command is the only writable public application entry point. Its trigger validates the
-- caller, canonical transition, stale version and authority before any projection changes.
create function enforcement_private.process_command() returns trigger language plpgsql security invoker set search_path=pg_catalog as $$
#variable_conflict use_variable
declare
 actor uuid:=enforcement_private.actor(); p jsonb:=new.payload;
 c enforcement_private.accounts%rowtype; prior enforcement_private.commands%rowtype;
 a enforcement_private.appeals%rowtype; d enforcement_private.decisions%rowtype;
 proposal enforcement_private.proposals%rowtype;
 roles text[]; op text:=new.operation; result_id uuid; event_id bigint;
 target_state text; caps text[]; expiry timestamptz; anchor timestamptz;
 n timestamptz:=clock_timestamp(); hours int; max_hours int; apply_decision boolean:=false;
 reason text; explanation text; evidence text; co_actor uuid; approval_appeal uuid;
begin
 if actor is null or actor<>new.actor_id or octet_length(p::text)>16384 then raise insufficient_privilege using message='Invalid enforcement command';end if;
 perform pg_advisory_xact_lock(hashtextextended('enforcement-command:'||actor||':'||new.request_key,0));
 select * into prior from enforcement_private.commands where actor_id=actor and request_key=new.request_key;
 if found then
  if prior.operation<>op or prior.user_id<>new.user_id or prior.payload<>p then raise unique_violation using message='Idempotency key reused for a different command';end if;
  return null;
 end if;
 perform enforcement_private.lock_accounts(array[new.user_id],true);
 select * into c from enforcement_private.accounts where user_id=new.user_id for update;
 n:=clock_timestamp();
 if c.user_id is null then
  insert into enforcement_private.accounts(user_id) values(new.user_id) returning * into c;
 end if;
 if op not in('APPEAL_SUBMIT','APPEAL_WITHDRAW','APPEAL_REOPEN_REQUEST') then
  if not private.admin_mfa_recent() or nullif(current_setting('request.admin.mfa_at',true),'')::timestamptz<clock_timestamp()-interval '5 minutes' then raise insufficient_privilege using message='Recent MFA required';end if;
  if new.user_id=actor or exists(select 1 from enforcement_private.admin_accounts where user_id=new.user_id) then raise insufficient_privilege using message='Privileged or own account requires M7.1 controls';end if;
  select r.roles into roles from admin_private.authorizations r where r.user_id=actor;
  if not private.admin_permission(case when op='ESCALATE' then 'accounts.escalate' when op in('PROPOSE','APPROVE') then 'accounts.permanent' when op like 'APPEAL_%' then 'accounts.appeals' else 'accounts.enforce' end) then raise insufficient_privilege using message='Account authority required';end if;
 else
  if new.user_id<>actor then raise insufficient_privilege using message='Own appeal required';end if;
 end if;
 if op in('ENFORCE','REINSTATE','PROPOSE','INVESTIGATE') and (p->>'version')::bigint is distinct from c.version then raise serialization_failure using message='Account version changed';end if;
 if op in('APPEAL_SUBMIT','APPEAL_WITHDRAW','APPEAL_REOPEN_REQUEST','APPEAL_REOPEN','APPEAL_REVIEW','APPEAL_DECIDE') then
  if op='APPEAL_SUBMIT' then
   select * into d from enforcement_private.decisions where id=(p->>'decision_id')::uuid and user_id=new.user_id;
   if d.id is null or n>d.notified_at+interval '30 days' then raise check_violation using message='Appeal notification window closed';end if;
   if length(trim(coalesce(p->>'statement','')))<20 or length(coalesce(p->>'statement',''))>4000 or length(trim(coalesce(p->>'evidence_ref','')))<3 or length(coalesce(p->>'evidence_ref',''))>500 then raise check_violation using message='Appeal statement and evidence reference required';end if;
   insert into enforcement_private.appeals(user_id,decision_id,statement,evidence_ref) values(new.user_id,d.id,p->>'statement',p->>'evidence_ref') returning id into result_id;
  else
   select * into a from enforcement_private.appeals where id=(p->>'appeal_id')::uuid and user_id=new.user_id for update;
   if a.id is null or (p->>'appeal_version')::bigint is distinct from a.version then raise serialization_failure using message='Appeal version changed';end if;
   select * into d from enforcement_private.decisions where id=a.decision_id;
   result_id:=a.id;
   if op in('APPEAL_REVIEW','APPEAL_DECIDE','APPEAL_REOPEN') and (actor=d.actor_id or actor is not distinct from d.co_actor_id) then raise insufficient_privilege using message='Independent appeal reviewer required';end if;
   if op='APPEAL_WITHDRAW' then
    if a.status not in('SUBMITTED','UNDER_REVIEW') then raise check_violation using message='Appeal is closed';end if;
    update enforcement_private.appeals set status='WITHDRAWN',version=version+1 where id=a.id;
   elsif op='APPEAL_REOPEN_REQUEST' then
    if a.status in('SUBMITTED','UNDER_REVIEW') or length(coalesce(p->>'statement',''))>4000 or length(coalesce(p->>'evidence_ref',''))>500 or length(trim(coalesce(p->>'evidence_ref','')))<3 or p->>'evidence_ref'=a.evidence_ref or length(trim(coalesce(p->>'statement','')))<20 then raise check_violation using message='Significant new evidence request required';end if;
    update enforcement_private.appeals set reopen_evidence=p->>'evidence_ref',reopen_statement=p->>'statement',version=version+1 where id=a.id;
   elsif op='APPEAL_REOPEN' then
    if a.status in('SUBMITTED','UNDER_REVIEW') or a.reopen_evidence is null or p->>'significant' is distinct from 'true' then raise check_violation using message='Independent significant-evidence determination required';end if;
    update enforcement_private.appeals set status='SUBMITTED',reviewer_id=null,evidence_ref=reopen_evidence,statement=reopen_statement,reopen_evidence=null,reopen_statement=null,resolution=null,submitted_at=n,due_at=n+interval '7 days',version=version+1 where id=a.id;
   elsif op='APPEAL_REVIEW' then
    if a.status<>'SUBMITTED' then raise check_violation using message='Appeal is not awaiting review';end if;
    update enforcement_private.appeals set status='UNDER_REVIEW',reviewer_id=actor,version=version+1 where id=a.id;
   elsif op='APPEAL_DECIDE' then
    if a.status<>'UNDER_REVIEW' or a.reviewer_id<>actor or length(trim(coalesce(p->>'resolution','')))<10 or length(coalesce(p->>'resolution',''))>1000 or p->>'outcome' not in('UPHELD','MODIFIED','OVERTURNED') then raise check_violation using message='Reviewed appeal decision required';end if;
    if p->>'outcome'='UPHELD' then
     update enforcement_private.appeals set status='UPHELD',resolution=p->>'resolution',version=version+1 where id=a.id;
    else
     if c.decision_id<>a.decision_id then raise serialization_failure using message='A later enforcement decision exists';end if;
     if not private.admin_permission('accounts.enforce') then raise insufficient_privilege using message='Enforcement permission required';end if;
     target_state:=case when p->>'outcome'='OVERTURNED' then 'ACTIVE' else p->>'state' end;
     if c.state='PERMANENTLY_SUSPENDED' then
      if not private.admin_permission('accounts.permanent') then raise insufficient_privilege using message='Permanent appeal requires super administrators';end if;
      insert into enforcement_private.proposals(user_id,proposer_id,base_version,action,payload,appeal_id) values(new.user_id,actor,c.version,case when target_state='ACTIVE' then 'REINSTATE' else 'MODIFY' end,p||jsonb_build_object('state',target_state),a.id) returning id into result_id;
     else
      apply_decision:=true;
      update enforcement_private.appeals set status=p->>'outcome',resolution=p->>'resolution',version=version+1 where id=a.id;
     end if;
    end if;
   end if;
  end if;
 elsif op='ESCALATE' then
  if length(trim(coalesce(p->>'notes','')))<10 or length(coalesce(p->>'notes',''))>4000 then raise check_violation using message='Escalation details required';end if;
  -- Confidential report stays in the command, never becomes an automatic sanction.
  result_id:=new.id;
 elsif op='INVESTIGATE' then
  if p->>'investigation' not in('OPEN','UNDER_REVIEW','CLOSED') then raise check_violation using message='Invalid investigation state';end if;
  update enforcement_private.accounts set investigation=p->>'investigation',version=version+1,updated_at=n where user_id=new.user_id;
 elsif op='PROPOSE' then
  target_state:=p->>'state';
  if target_state<>'PERMANENTLY_SUSPENDED' and c.state<>'PERMANENTLY_SUSPENDED' then raise check_violation using message='Dual approval is for permanent enforcement';end if;
  insert into enforcement_private.proposals(user_id,proposer_id,base_version,action,payload) values(new.user_id,actor,c.version,case when target_state='PERMANENTLY_SUSPENDED' then 'PERMANENT' when target_state='ACTIVE' then 'REINSTATE' else 'MODIFY' end,p) returning id into result_id;
 elsif op='APPROVE' then
  select * into proposal from enforcement_private.proposals where id=(p->>'proposal_id')::uuid and user_id=new.user_id for update;
  if proposal.id is null or proposal.state<>'PENDING' or proposal.base_version<>c.version then raise serialization_failure using message='Proposal is stale';end if;
  if proposal.proposer_id=actor then raise insufficient_privilege using message='Distinct super administrator required';end if;
  -- Revalidate proposer authority at approval, not only at proposal time.
  if not exists(select 1 from enforcement_private.active_super_admins where user_id=proposal.proposer_id) then raise insufficient_privilege using message='Proposer authority revoked';end if;
  if proposal.appeal_id is not null then
   select * into a from enforcement_private.appeals where id=proposal.appeal_id for update;
   select * into d from enforcement_private.decisions where id=a.decision_id;
   if a.status<>'UNDER_REVIEW' or a.reviewer_id<>proposal.proposer_id or c.decision_id<>a.decision_id or actor=d.actor_id or actor is not distinct from d.co_actor_id then raise insufficient_privilege using message='Independent dual appeal approval required';end if;
   update enforcement_private.appeals set status=proposal.payload->>'outcome',resolution=proposal.payload->>'resolution',version=version+1 where id=a.id;
  end if;
  p:=proposal.payload;co_actor:=proposal.proposer_id;target_state:=p->>'state';apply_decision:=true;
  update enforcement_private.proposals set state='APPROVED',decided_by=actor where id=proposal.id;
 elsif op in('ENFORCE','REINSTATE') then
  if c.state='PERMANENTLY_SUSPENDED' or p->>'state'='PERMANENTLY_SUSPENDED' then raise insufficient_privilege using message='Permanent enforcement requires dual approval';end if;
  target_state:=case when op='REINSTATE' then 'ACTIVE' else p->>'state' end;apply_decision:=true;
 else raise check_violation using message='Unknown enforcement operation';end if;
 if apply_decision and (target_state='PERMANENTLY_SUSPENDED' or c.state='PERMANENTLY_SUSPENDED') and (op<>'APPROVE' or co_actor is null or not private.admin_permission('accounts.permanent')) then raise insufficient_privilege using message='Permanent enforcement requires reviewed dual approval';end if;
 if apply_decision or op='PROPOSE' then
  reason:=p->>'reason_code';explanation:=trim(p->>'explanation');evidence:=trim(p->>'evidence_ref');
  if reason is null or reason not in('SPAM','MISLEADING_PROFILE','IMPERSONATION','VERIFICATION_FAILURE','BOOKING_MISCONDUCT','HARASSMENT','FRAUD','PROHIBITED_CONTENT','SAFETY_VIOLATION','REPEATED_VIOLATIONS','ACCOUNT_COMPROMISE','OTHER') or coalesce(length(explanation),0) not between 10 and 1000 or coalesce(length(evidence),0) not between 3 and 500 or length(coalesce(p->>'notes',''))>4000 then raise check_violation using message='Documented reason, public explanation and evidence reference required';end if;
  if target_state not in('ACTIVE','RESTRICTED','TEMPORARILY_SUSPENDED','PERMANENTLY_SUSPENDED') or target_state is null then raise check_violation using message='Invalid enforcement state';end if;
  caps:=case when target_state='ACTIVE' then array[]::text[] when target_state='RESTRICTED' then array(select distinct jsonb_array_elements_text(p->'denied')) else array['discovery','booking_create','booking_accept','messaging','profile','subscription_purchase'] end;
  if target_state='RESTRICTED' and (cardinality(caps)=0 or not caps<@array['discovery','booking_create','booking_accept','messaging','profile','subscription_purchase']) then raise check_violation using message='Invalid capability restriction';end if;
  expiry:=null;anchor:=c.episode_start;
  if target_state in('RESTRICTED','TEMPORARILY_SUSPENDED') then
   hours:=(p->>'duration_hours')::int;
   max_hours:=case when 'SUPER_ADMIN'=any(roles) or 'OPERATIONS_ADMIN'=any(roles) then 720 else 168 end;
   if hours is null or hours<1 or hours>max_hours then raise check_violation using message='Temporary duration exceeds authority';end if;
   if c.episode_until is null or c.episode_until<=n then anchor:=n;end if;
   expiry:=n+make_interval(hours=>hours);
   if expiry>anchor+make_interval(hours=>max_hours) then raise check_violation using message='Cumulative extension exceeds authority';end if;
  end if;
  if apply_decision then
   insert into enforcement_private.decisions(user_id,actor_id,co_actor_id,state,denied,until_at,reason_code,explanation,evidence_ref,notified_at) values(new.user_id,actor,co_actor,target_state,caps,expiry,reason,explanation,evidence,n) returning id into result_id;
   insert into enforcement_private.notes values(result_id,coalesce(p->>'notes',''));
   update enforcement_private.accounts set version=version+1,state=target_state,denied=caps,until_at=expiry,episode_start=anchor,episode_until=case when expiry is null then episode_until else greatest(expiry,episode_until) end,decision_id=result_id,issuer_id=actor,co_issuer_id=co_actor,explanation=explanation,reason_code=reason,updated_at=n where user_id=new.user_id;
  end if;
 end if;
 insert into enforcement_private.events(user_id,actor_id,operation,resource_id,public_data) values(new.user_id,actor,op,result_id,jsonb_build_object('state',case when apply_decision then target_state else null end,'explanation',case when apply_decision then explanation else null end,'appeal_status',case when op like 'APPEAL_%' then (select status from enforcement_private.appeals where id=coalesce(a.id,result_id)) else null end)) returning id into event_id;
 if apply_decision or op like 'APPEAL_%' then
  insert into enforcement_private.notices(user_id,event_id,kind,explanation,created_at) values(new.user_id,event_id,op,case when apply_decision then explanation else 'Your account appeal has been updated. Open Account access for details.' end,n);
 end if;
 new.result:=jsonb_build_object('resource_id',result_id,'event_id',event_id,'version',(select version from enforcement_private.accounts where user_id=new.user_id));return new;
end$$;
-- A minimized roster projection avoids exposing admin identities/roles to ordinary accounts.
create table enforcement_private.active_super_admins(user_id uuid primary key);
insert into enforcement_private.active_super_admins select user_id from admin_private.principals where state='active' and 'SUPER_ADMIN'=any(roles);
alter table enforcement_private.active_super_admins enable row level security;
grant select,insert,delete on enforcement_private.active_super_admins to castly_api;
create policy roster_read on enforcement_private.active_super_admins for select to castly_api using(pg_trigger_depth()>0);
create policy roster_write on enforcement_private.active_super_admins for all to castly_api using(pg_trigger_depth()>0) with check(pg_trigger_depth()>0);
create trigger roster_guard before insert or update or delete on enforcement_private.active_super_admins for each row execute function enforcement_private.write_guard();
create function enforcement_private.sync_super() returns trigger language plpgsql security invoker set search_path=pg_catalog as $$begin
 delete from enforcement_private.active_super_admins where user_id=new.user_id;
 if new.state='active' and 'SUPER_ADMIN'=any(new.roles) then insert into enforcement_private.active_super_admins values(new.user_id);end if;return new;end$$;
create trigger enforcement_super after insert or update on admin_private.principals for each row execute function enforcement_private.sync_super();
create trigger process before insert on enforcement_private.commands for each row execute function enforcement_private.process_command();
-- Additive RESTRICTIVE policies preserve all pre-existing publication/ownership/reviewer rules.
create policy account_visibility on public.talent_profiles as restrictive for select to anon using(enforcement_private.visible(id));
create policy account_visibility_authenticated on public.talent_profiles as restrictive for select to authenticated using(id=enforcement_private.actor() or enforcement_private.visible(id) or trust_private.is_reviewer('verification'));
create policy account_visibility_api on public.talent_profiles as restrictive for select to castly_api using(id=enforcement_private.actor() or enforcement_private.visible(id) or trust_private.is_reviewer('verification'));
create function enforcement_private.business_guard() returns trigger language plpgsql security invoker set search_path=pg_catalog as $$
declare u uuid; c uuid; t uuid;begin
 if tg_table_name='bookings' then
  perform enforcement_private.lock_accounts(array[new.client_id,new.talent_id]);
  if tg_op='INSERT' then
   perform enforcement_private.require_allowed(new.client_id,'booking_create');
   if not enforcement_private.not_suspended(new.client_id) or not enforcement_private.not_suspended(new.talent_id) or not enforcement_private.visible(new.talent_id) then raise insufficient_privilege using message='Account cannot enter a new booking';end if;
  elsif new.status='accepted' and old.status<>'accepted' then
   perform enforcement_private.require_allowed(new.talent_id,'booking_accept');
   if not enforcement_private.not_suspended(new.client_id) or not enforcement_private.not_suspended(new.talent_id) then raise insufficient_privilege using message='Account cannot accept a new booking';end if;
  end if;
 elsif tg_table_name='conversations' then
  perform enforcement_private.lock_accounts(array[new.client_id,new.talent_id]);
  perform enforcement_private.require_allowed(new.client_id,'messaging');perform enforcement_private.require_allowed(new.talent_id,'messaging');
 elsif tg_table_name='messages' then
  perform enforcement_private.require_allowed(new.sender_id,'messaging');
 elsif tg_table_name='talent_profiles' then
  if tg_op='DELETE' then u:=old.id;else u:=new.id;end if;
  perform enforcement_private.require_allowed(u,'profile');
 elsif tg_table_name in('portfolio_assets','talent_discovery_preferences','talent_availability_days') then
  if tg_op='DELETE' then u:=old.talent_id;else u:=new.talent_id;end if;
  perform enforcement_private.require_allowed(u,'profile');
 elsif tg_table_schema='storage' then
  if tg_op<>'INSERT' and old.bucket_id='portfolio' then perform enforcement_private.require_allowed(split_part(old.name,'/',1)::uuid,'profile');end if;
  if tg_op<>'DELETE' and new.bucket_id='portfolio' then perform enforcement_private.require_allowed(split_part(new.name,'/',1)::uuid,'profile');end if;
 end if;
 if tg_op='DELETE' then return old;end if;return new;end$$;
create trigger account_guard before insert or update on public.bookings for each row execute function enforcement_private.business_guard();
create trigger account_guard before insert on public.conversations for each row execute function enforcement_private.business_guard();
create trigger account_guard before insert on public.messages for each row execute function enforcement_private.business_guard();
create trigger account_guard before insert or update or delete on public.talent_profiles for each row execute function enforcement_private.business_guard();
create trigger account_guard before insert or update or delete on public.portfolio_assets for each row execute function enforcement_private.business_guard();
create trigger account_guard before insert or update or delete on public.talent_discovery_preferences for each row execute function enforcement_private.business_guard();
create trigger account_guard before insert or update or delete on public.talent_availability_days for each row execute function enforcement_private.business_guard();
create trigger account_guard before insert or update or delete on storage.objects for each row execute function enforcement_private.business_guard();
-- Discovery-only helpers must also exclude an owner whose profile is hidden. Own profile
-- editing/inspection RLS is deliberately retained, but is not public discovery eligibility.
create or replace function private.discovery_talent_visible(talent uuid) returns boolean
language sql stable security invoker set search_path=pg_catalog as $$
 select enforcement_private.visible(talent) and exists(select 1 from public.talent_profiles t
 where t.id=talent and t.published and not trust_private.blocked(private.request_user_id(),t.id))
$$;
-- Authenticated clients cannot mutate commands or private cases directly. Existing original
-- Storage/RPC RLS and all booking guards remain in place. No auth schema grants.
revoke all on all functions in schema enforcement_private from public;
grant execute on function enforcement_private.actor(),enforcement_private.visible(uuid) to anon,authenticated,castly_api;
grant execute on function enforcement_private.allowed(uuid,text),enforcement_private.not_suspended(uuid),enforcement_private.lock_accounts(uuid[],boolean),enforcement_private.require_allowed(uuid,text) to authenticated,castly_api,castly_engagement_worker;
grant execute on function enforcement_private.visible(uuid) to castly_engagement_worker;
commit;
