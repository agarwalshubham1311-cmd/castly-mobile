-- Phase 6. Owner review required; no production execution by the application.
begin;
do $$begin
 if current_user='castly_api' or to_regprocedure('private.discovery_talent_visible(uuid)') is null then raise exception 'Reviewed Phase 4+5 baseline and migration owner required';end if;
 if not exists(select 1 from pg_roles where rolname='castly_engagement_worker') then create role castly_engagement_worker nologin noinherit;end if;
 if exists(select 1 from pg_roles where rolname in('castly_api','castly_engagement_worker') and (rolsuper or rolbypassrls or rolcreatedb or rolcreaterole or rolreplication)) or exists(select 1 from pg_auth_members where member in('castly_api'::regrole,'castly_engagement_worker'::regrole)) then raise exception 'Dedicated unprivileged roles required';end if;
end$$;
create schema engagement_private;
revoke all on schema engagement_private from public,anon,authenticated;
grant usage on schema engagement_private to castly_api,castly_engagement_worker;
create table public.engagement_preferences(
 user_id uuid primary key references public.users(id) on delete cascade,
 timezone text not null default 'UTC' check(length(timezone)<=80),
 quiet_start integer not null default 22 check(quiet_start between 0 and 23),
 quiet_end integer not null default 8 check(quiet_end between 0 and 23),
 push_bookings boolean not null default false,push_messages boolean not null default false,
 email_transactional boolean not null default false,email_optional boolean not null default false,
 reminders boolean not null default false,saved_alerts boolean not null default false,
 reengagement boolean not null default false,analytics boolean not null default false,
 revision bigint not null default 1,last_active_at timestamptz not null default now(),updated_at timestamptz not null default now()
);
create table public.push_devices(
 id uuid primary key default gen_random_uuid(),user_id uuid not null references public.users(id) on delete cascade,
 device_key uuid not null,token text not null unique check(token ~ '^(ExponentPushToken|ExpoPushToken)\[[A-Za-z0-9_-]{10,200}\]$'),
 platform text not null check(platform in('android','ios')),active boolean not null default true,
 expires_at timestamptz not null default(now()+interval '1 day'),updated_at timestamptz not null default now(),unique(user_id,device_key)
);
create table public.saved_searches(
 id uuid primary key default gen_random_uuid(),user_id uuid not null references public.users(id) on delete cascade,
 label text not null check(length(trim(label)) between 1 and 80),filters jsonb not null check(jsonb_typeof(filters)='object' and octet_length(filters::text)<=2000),
 alerts boolean not null default false,created_at timestamptz not null default now(),updated_at timestamptz not null default now()
);
create index saved_searches_owner on public.saved_searches(user_id,created_at,id);
create table engagement_private.contacts(user_id uuid primary key references public.users(id) on delete cascade,email text not null check(length(email)<=254),verified_at timestamptz not null,updated_at timestamptz not null default now());
create table engagement_private.suppressions(email_hash text primary key,reason text not null check(reason in('bounce','complaint')),created_at timestamptz not null default now());
create table engagement_private.analytics_admins(user_id uuid primary key references public.users(id) on delete cascade);
create table engagement_private.events(subject_key text not null check(subject_key ~ '^[a-f0-9]{64}$'),day date not null default current_date,event text not null check(event in('activation','search','profile','save','request','accept','message','complete','notification_delivered','notification_opened')),primary key(subject_key,day,event));
create index phase6_events_day on engagement_private.events(day,event,subject_key);
create table engagement_private.outbox(
 id uuid primary key default gen_random_uuid(),recipient_id uuid not null references public.users(id) on delete cascade,
 event_key text not null check(length(event_key)<=180),kind text not null check(kind in('booking_requested','booking_accepted','booking_declined','booking_cancelled','message_received','booking_reminder','saved_digest','reengagement','account_activity')),
 channel text not null check(channel in('push','email')),device_id uuid references public.push_devices(id) on delete cascade,
 notification_id uuid references public.notifications(id) on delete cascade,booking_id uuid references public.bookings(id) on delete cascade,
 conversation_id uuid references public.conversations(id) on delete cascade,search_id uuid references public.saved_searches(id) on delete cascade,source_actor uuid references public.users(id) on delete cascade,
 state text not null default 'queued' check(state in('queued','sending','ticket','delivered','suppressed','dead','uncertain')),
 attempts integer not null default 0 check(attempts between 0 and 10),next_at timestamptz not null default now(),lease_until timestamptz,
 receipt text check(length(receipt)<=200),error_code text check(length(error_code)<=80),created_at timestamptz not null default now(),dispatched_at timestamptz,delivered_at timestamptz,opened_at timestamptz,
 check((channel='push' and device_id is not null) or(channel='email' and device_id is null)),
 unique nulls not distinct(recipient_id,event_key,channel,device_id)
);
create index phase6_outbox_due on engagement_private.outbox(next_at,id) where state in('queued','ticket','sending');
create index phase6_outbox_dispatch_cap on engagement_private.outbox(recipient_id,dispatched_at) where dispatched_at is not null;
create index phase6_outbox_booking_source on engagement_private.outbox(booking_id) where booking_id is not null;
create index phase6_outbox_conversation_source on engagement_private.outbox(conversation_id) where conversation_id is not null;
create index phase6_outbox_device_source on engagement_private.outbox(device_id) where device_id is not null;
create table engagement_private.budgets(day date not null,channel text not null,dispatches integer not null check(dispatches>=0),primary key(day,channel));
create table engagement_private.scan_state(user_id uuid primary key references public.users(id) on delete cascade,next_at timestamptz not null);
create table engagement_private.webhooks(event_id text primary key check(length(event_id)<=200),created_at timestamptz not null default now());
-- Every table uses RLS, including private worker metadata.
do $$declare n text;begin
 foreach n in array array['public.engagement_preferences','public.push_devices','public.saved_searches','engagement_private.contacts','engagement_private.suppressions','engagement_private.analytics_admins','engagement_private.events','engagement_private.outbox','engagement_private.budgets','engagement_private.webhooks','engagement_private.scan_state'] loop
 execute 'alter table '||n||' enable row level security';execute 'revoke all on '||n||' from public,anon,authenticated,castly_api,castly_engagement_worker';end loop;
end$$;
grant select,delete,insert(user_id,timezone,quiet_start,quiet_end,push_bookings,push_messages,email_transactional,email_optional,reminders,saved_alerts,reengagement,analytics),update(timezone,quiet_start,quiet_end,push_bookings,push_messages,email_transactional,email_optional,reminders,saved_alerts,reengagement,analytics,last_active_at) on public.engagement_preferences to castly_api;
grant select,delete,insert(user_id,device_key,token,platform),update(token,platform,active,expires_at,updated_at) on public.push_devices to castly_api;
grant select,delete,insert(user_id,label,filters,alerts),update(label,filters,alerts,updated_at) on public.saved_searches to castly_api;
grant select,delete,insert(user_id,email,verified_at),update(email,verified_at,updated_at) on engagement_private.contacts to castly_api;
grant select,delete on engagement_private.scan_state to castly_api;
create policy phase6_scan_owner on engagement_private.scan_state for all to castly_api using(user_id=private.request_user_id()) with check(user_id=private.request_user_id());
grant select,insert(user_id,next_at),update(next_at) on engagement_private.scan_state to castly_engagement_worker;
grant select on engagement_private.analytics_admins to castly_api;
create policy phase6_admin_self on engagement_private.analytics_admins for select to castly_api using(user_id=private.request_user_id());
create function engagement_private.is_admin() returns boolean language sql stable security invoker set search_path='' as $$select exists(select 1 from engagement_private.analytics_admins where user_id=private.request_user_id())$$;
revoke all on function engagement_private.is_admin() from public,anon,authenticated;
grant execute on function engagement_private.is_admin() to castly_api;
grant select,insert(subject_key,day,event),delete on engagement_private.events to castly_api;
create policy phase6_event_read on engagement_private.events for select to castly_api using(subject_key=current_setting('castly.phase6.subject',true) or engagement_private.is_admin());
create policy phase6_event_write on engagement_private.events for insert to castly_api with check(subject_key=current_setting('castly.phase6.subject',true) and exists(select 1 from public.engagement_preferences where user_id=private.request_user_id() and analytics));
create policy phase6_event_delete on engagement_private.events for delete to castly_api using(subject_key=current_setting('castly.phase6.subject',true));
do $$declare n text;begin
 foreach n in array array['public.engagement_preferences','public.push_devices','public.saved_searches','engagement_private.contacts'] loop
 execute 'create policy phase6_owner_read on '||n||' for select to castly_api using(user_id=private.request_user_id() or pg_trigger_depth()>0)';
 execute 'create policy phase6_owner_insert on '||n||' for insert to castly_api with check(user_id=private.request_user_id())';
 execute 'create policy phase6_owner_update on '||n||' for update to castly_api using(user_id=private.request_user_id()) with check(user_id=private.request_user_id())';
 execute 'create policy phase6_owner_delete on '||n||' for delete to castly_api using(user_id=private.request_user_id())';end loop;
end$$;
-- Separate guards avoid generic-record field assumptions and protect server-managed leases.
create policy phase6_expired_device_delete on public.push_devices for delete to castly_api
 using(expires_at<statement_timestamp() and token=current_setting('castly.phase6.device_token',true));
create function engagement_private.device_guard() returns trigger language plpgsql security invoker set search_path='' as $$begin
 if current_user='castly_api' then
  delete from public.push_devices where token=new.token and expires_at<statement_timestamp() and token=current_setting('castly.phase6.device_token',true);
  perform pg_advisory_xact_lock(hashtextextended(new.user_id::text,601));
  if TG_OP='INSERT' and not exists(select 1 from public.push_devices where user_id=new.user_id and device_key=new.device_key) and(select count(*) from public.push_devices where user_id=new.user_id)>=10 then raise exception 'Device limit' using errcode='23514';end if;
  if TG_OP='UPDATE' and(new.user_id,new.device_key,new.id) is distinct from(old.user_id,old.device_key,old.id) then raise exception 'Protected device owner' using errcode='42501';end if;
  new.expires_at:=statement_timestamp()+interval '1 day';new.updated_at:=statement_timestamp();
 end if;return new;end$$;
create trigger phase6_device_guard before insert or update on public.push_devices for each row execute function engagement_private.device_guard();
create function engagement_private.search_guard() returns trigger language plpgsql security invoker set search_path='' as $$begin
 if current_user='castly_api' then
  perform pg_advisory_xact_lock(hashtextextended(new.user_id::text,602));
  if TG_OP='INSERT' and(select count(*) from public.saved_searches where user_id=new.user_id)>=20 then raise exception 'Saved search limit' using errcode='23514';end if;
  if TG_OP='UPDATE' and(new.user_id,new.id,new.created_at) is distinct from(old.user_id,old.id,old.created_at) then raise exception 'Protected saved search' using errcode='42501';end if;
  new.updated_at:=statement_timestamp();
 end if;return new;end$$;
create trigger phase6_search_guard before insert or update on public.saved_searches for each row execute function engagement_private.search_guard();
create function engagement_private.preference_guard() returns trigger language plpgsql security invoker set search_path='' as $$begin
 perform timezone(new.timezone,statement_timestamp());
 if TG_OP='UPDATE' then
  if new.user_id is distinct from old.user_id then raise exception 'Protected preference owner' using errcode='42501';end if;
  new.revision:=old.revision+1;
 else new.revision:=1;end if;
 new.updated_at:=statement_timestamp();return new;end$$;
create trigger phase6_preference_guard before insert or update on public.engagement_preferences for each row execute function engagement_private.preference_guard();
create function engagement_private.contact_guard() returns trigger language plpgsql security invoker set search_path='' as $$begin
 if current_user='castly_api' and current_setting('castly.phase6.contact_verified',true) is distinct from 'yes' then raise exception 'Trusted contact verification required' using errcode='42501';end if;return new;end$$;
create trigger phase6_contact_guard before insert or update on engagement_private.contacts for each row execute function engagement_private.contact_guard();
grant select,delete,insert(recipient_id,event_key,kind,channel,device_id,notification_id,booking_id,conversation_id,search_id,source_actor),update(opened_at) on engagement_private.outbox to castly_api;
create policy phase6_outbox_read on engagement_private.outbox for select to castly_api using(recipient_id=private.request_user_id() or pg_trigger_depth()>0);
create policy phase6_outbox_insert on engagement_private.outbox for insert to castly_api with check(pg_trigger_depth()>0 and private.request_user_id() is not null);
create policy phase6_outbox_open on engagement_private.outbox for update to castly_api using(recipient_id=private.request_user_id()) with check(recipient_id=private.request_user_id());
create policy phase6_outbox_delete on engagement_private.outbox for delete to castly_api using(recipient_id=private.request_user_id());
-- Only nested notifications or the recipient's validated scheduler may queue.
create function engagement_private.outbox_guard() returns trigger language plpgsql security invoker set search_path='' as $$begin
 if current_user<>'castly_api' then return new;end if;
 if pg_trigger_depth()<2 then raise exception 'Outbox source trigger required' using errcode='42501';end if;
 if new.notification_id is not null then
  if new.source_actor is distinct from private.request_user_id() or new.recipient_id=new.source_actor then raise exception 'Invalid business event actor' using errcode='42501';end if;
 elsif new.recipient_id is distinct from private.request_user_id() or new.kind not in('booking_reminder','saved_digest','reengagement','account_activity') then raise exception 'Invalid scheduled recipient' using errcode='42501';end if;
 return new;end$$;
create trigger phase6_outbox_guard before insert on engagement_private.outbox for each row execute function engagement_private.outbox_guard();
create function engagement_private.enqueue(recipient uuid,event text,kind text,notice uuid,booking uuid,conversation uuid,search uuid,actor uuid) returns void language plpgsql security invoker set search_path='' as $$declare p public.engagement_preferences;begin
 if current_user='castly_api' and pg_trigger_depth()<1 then raise exception 'Source trigger required' using errcode='42501';end if;
 select * into p from public.engagement_preferences where user_id=recipient;
 if not found then return;end if;
 if current_setting('castly.phase6.push',true)='true' and(case when kind='message_received' then p.push_messages else p.push_bookings end) then
  insert into engagement_private.outbox(recipient_id,event_key,kind,channel,device_id,notification_id,booking_id,conversation_id,search_id,source_actor)
   select recipient,event,kind,'push',d.id,notice,booking,conversation,search,actor from public.push_devices d where d.user_id=recipient and d.active and d.expires_at>statement_timestamp() on conflict do nothing;
 end if;
 if current_setting('castly.phase6.email',true)='true' and(case when kind in('booking_reminder','saved_digest','reengagement') then p.email_optional else p.email_transactional end) and exists(select 1 from engagement_private.contacts where user_id=recipient) then
  insert into engagement_private.outbox(recipient_id,event_key,kind,channel,notification_id,booking_id,conversation_id,search_id,source_actor) values(recipient,event,kind,'email',notice,booking,conversation,search,actor) on conflict do nothing;
 end if;
end$$;
-- enqueue is not callable by API; it is reached through source triggers only.
create function engagement_private.notification_event() returns trigger language plpgsql security invoker set search_path='' as $$begin
 if current_user='castly_api' then perform engagement_private.enqueue(new.recipient_id,new.event_key,new.kind,new.id,new.booking_id,new.conversation_id,null,private.request_user_id());end if;return new;end$$;
create trigger phase6_notification_event after insert on public.notifications for each row execute function engagement_private.notification_event();
-- Self schedules are represented by inserts into a source table, then guarded.
create table engagement_private.schedule_sources(user_id uuid not null references public.users(id) on delete cascade,kind text not null,source_id uuid not null,local_day date not null,event_key text not null,booking_id uuid references public.bookings(id) on delete cascade,search_id uuid references public.saved_searches(id) on delete cascade,primary key(user_id,event_key));
alter table engagement_private.schedule_sources enable row level security;
revoke all on engagement_private.schedule_sources from public,anon,authenticated;
grant select,delete,insert(user_id,kind,source_id,local_day,event_key,booking_id,search_id) on engagement_private.schedule_sources to castly_api;
create policy phase6_schedule_owner on engagement_private.schedule_sources for all to castly_api using(user_id=private.request_user_id()) with check(user_id=private.request_user_id());
create function engagement_private.schedule_guard() returns trigger language plpgsql security invoker set search_path='' as $$declare p public.engagement_preferences;d date;begin
 if current_user<>'castly_api' then return new;end if;
 select * into p from public.engagement_preferences where user_id=private.request_user_id();
 d:=(statement_timestamp() at time zone p.timezone)::date;
 if new.user_id is distinct from p.user_id or new.local_day is distinct from d then raise exception 'Schedule identity required' using errcode='42501';end if;
 if new.kind='booking_reminder' and p.reminders then
  if new.source_id is distinct from new.booking_id or new.search_id is not null or not exists(select 1 from public.bookings b where b.id=new.booking_id and p.user_id in(b.client_id,b.talent_id) and b.status in('requested','accepted') and b.proposed_date between d and d+1 and not trust_private.blocked(b.client_id,b.talent_id)) then raise exception 'Reminder source required' using errcode='42501';end if;
 elsif new.kind='saved_digest' and p.saved_alerts then
  if new.source_id is distinct from new.search_id or new.booking_id is not null or not exists(select 1 from public.saved_searches where id=new.search_id and user_id=p.user_id and alerts) then raise exception 'Owned digest required' using errcode='42501';end if;
 elsif new.kind='reengagement' and p.reengagement and p.last_active_at<statement_timestamp()-interval '14 days' then
  if new.source_id<>p.user_id or new.booking_id is not null or new.search_id is not null then raise exception 'Reengagement identity required' using errcode='42501';end if;
 else raise exception 'Schedule consent required' using errcode='42501';end if;
 if new.event_key is distinct from (new.kind||':'||new.source_id||':'||(case when new.kind='reengagement' then date_trunc('week',d)::date::text else d::text end)) then raise exception 'Deterministic schedule key required' using errcode='42501';end if;
 return new;end$$;
create trigger phase6_schedule_guard before insert on engagement_private.schedule_sources for each row execute function engagement_private.schedule_guard();
create function engagement_private.schedule_event() returns trigger language plpgsql security invoker set search_path='' as $$begin
 if current_user='castly_api' then perform engagement_private.enqueue(new.user_id,new.event_key,new.kind,null,new.booking_id,null,new.search_id,null);end if;return new;end$$;
create trigger phase6_schedule_event after insert on engagement_private.schedule_sources for each row execute function engagement_private.schedule_event();
create function engagement_private.account_event() returns trigger language plpgsql security invoker set search_path='' as $$begin
 if current_user='castly_api' and (to_jsonb(new)-array['revision','last_active_at','updated_at']) is distinct from(to_jsonb(old)-array['revision','last_active_at','updated_at']) then perform engagement_private.enqueue(new.user_id,'account:'||new.user_id||':'||new.revision,'account_activity',null,null,null,null,null);end if;return new;end$$;
create trigger phase6_account_event after update on public.engagement_preferences for each row execute function engagement_private.account_event();
create function engagement_private.record_event(kind text) returns void language plpgsql security invoker set search_path='' as $$begin
 if current_setting('castly.phase6.analytics',true)='true' and current_setting('castly.phase6.subject',true) ~ '^[a-f0-9]{64}$' and exists(select 1 from public.engagement_preferences where user_id=private.request_user_id() and analytics) then
 perform pg_advisory_xact_lock(hashtextextended(current_setting('castly.phase6.subject',true),606));
 if kind in('activation','search','profile','save','request','accept','message','complete') and not exists(select 1 from engagement_private.events where subject_key=current_setting('castly.phase6.subject',true) and event='activation') then
 insert into engagement_private.events(subject_key,day,event) values(current_setting('castly.phase6.subject',true),(statement_timestamp() at time zone 'UTC')::date,'activation') on conflict do nothing;
 end if;
 if kind<>'activation' then insert into engagement_private.events(subject_key,day,event) values(current_setting('castly.phase6.subject',true),(statement_timestamp() at time zone 'UTC')::date,kind) on conflict do nothing;end if;
 end if;
 if current_setting('castly.phase6.engagement',true)='true' then update public.engagement_preferences set last_active_at=statement_timestamp() where user_id=private.request_user_id() and reengagement;end if;
end$$;
grant execute on function engagement_private.record_event(text) to castly_api;
create function engagement_private.business_analytics() returns trigger language plpgsql security invoker set search_path='' as $$declare kind text;begin
 if current_user<>'castly_api' then return new;end if;
 if TG_TABLE_NAME='bookings' then
  if TG_OP='INSERT' then kind:='request';elsif new.status is distinct from old.status and new.status='accepted' then kind:='accept';elsif new.status is distinct from old.status and new.status='completed' then kind:='complete';end if;
 elsif TG_TABLE_NAME='messages' then kind:='message';elsif TG_TABLE_NAME='saved_talent' then kind:='save';end if;
 if kind is not null then perform engagement_private.record_event(kind);end if;return new;end$$;
create trigger phase6_booking_analytics after insert or update of status on public.bookings for each row execute function engagement_private.business_analytics();
create trigger phase6_message_analytics after insert on public.messages for each row execute function engagement_private.business_analytics();
create trigger phase6_saved_analytics after insert on public.saved_talent for each row execute function engagement_private.business_analytics();
-- Dedicated worker purpose ACLs; no auth schema access, no privileged memberships.
grant usage on schema public,trust_private to castly_engagement_worker;
grant execute on function trust_private.blocked(uuid,uuid),trust_private.lock_pair(uuid,uuid) to castly_engagement_worker;
grant select on public.engagement_preferences,public.push_devices,engagement_private.contacts,engagement_private.suppressions,engagement_private.outbox,engagement_private.budgets,engagement_private.webhooks,engagement_private.events to castly_engagement_worker;
grant update(active) on public.push_devices to castly_engagement_worker;
grant update(email_optional,reminders,saved_alerts,reengagement) on public.engagement_preferences to castly_engagement_worker;
grant update(state,attempts,next_at,lease_until,receipt,error_code,dispatched_at,delivered_at) on engagement_private.outbox to castly_engagement_worker;
grant insert(day,channel,dispatches),update(dispatches) on engagement_private.budgets to castly_engagement_worker;
grant insert(event_id) on engagement_private.webhooks to castly_engagement_worker;
grant insert(email_hash,reason) on engagement_private.suppressions to castly_engagement_worker;
grant insert(subject_key,day,event),delete on engagement_private.events to castly_engagement_worker;
grant select(local_day) on engagement_private.schedule_sources to castly_engagement_worker;
grant delete on engagement_private.outbox,engagement_private.webhooks,engagement_private.schedule_sources,engagement_private.budgets to castly_engagement_worker;
do $$declare n text;begin
 foreach n in array array['public.engagement_preferences','public.push_devices','public.saved_searches','engagement_private.contacts','engagement_private.suppressions','engagement_private.outbox','engagement_private.budgets','engagement_private.webhooks','engagement_private.scan_state'] loop
 execute 'create policy phase6_worker on '||n||' for all to castly_engagement_worker using(true) with check(true)';end loop;
end$$;
create policy phase6_worker_event_read on engagement_private.events for select to castly_engagement_worker using(true);
create policy phase6_worker_event_insert on engagement_private.events for insert to castly_engagement_worker with check(true);
create policy phase6_worker_event_delete on engagement_private.events for delete to castly_engagement_worker using(day<current_date-90);
create policy phase6_worker_schedule_read on engagement_private.schedule_sources for select to castly_engagement_worker using(local_day<current_date-30);
create policy phase6_worker_schedule_delete on engagement_private.schedule_sources for delete to castly_engagement_worker using(local_day<current_date-30);
grant select(id,client_id,talent_id,status,proposed_date) on public.bookings to castly_engagement_worker;
grant select(id,client_id,talent_id) on public.conversations to castly_engagement_worker;
grant select(blocker_id,blocked_id) on public.user_blocks to castly_engagement_worker;
create policy phase6_worker_booking_metadata on public.bookings for select to castly_engagement_worker using(exists(select 1 from engagement_private.outbox o where o.booking_id=bookings.id or exists(select 1 from public.conversations c where c.id=o.conversation_id and c.client_id=bookings.client_id and c.talent_id=bookings.talent_id)));
create policy phase6_worker_conversation_metadata on public.conversations for select to castly_engagement_worker using(exists(select 1 from engagement_private.outbox o where o.conversation_id=conversations.id));
create policy phase6_worker_block_metadata on public.user_blocks for select to castly_engagement_worker using(exists(select 1 from public.bookings b where (b.client_id=user_blocks.blocker_id and b.talent_id=user_blocks.blocked_id) or(b.client_id=user_blocks.blocked_id and b.talent_id=user_blocks.blocker_id)) or exists(select 1 from public.conversations c where(c.client_id=user_blocks.blocker_id and c.talent_id=user_blocks.blocked_id) or(c.client_id=user_blocks.blocked_id and c.talent_id=user_blocks.blocker_id)));
alter policy phase6_worker on public.push_devices using(exists(select 1 from engagement_private.outbox o where o.device_id=push_devices.id and o.recipient_id=push_devices.user_id)) with check(exists(select 1 from engagement_private.outbox o where o.device_id=push_devices.id and o.recipient_id=push_devices.user_id));
alter policy phase6_worker on engagement_private.contacts using(exists(select 1 from engagement_private.outbox o where o.channel='email' and o.recipient_id=contacts.user_id)) with check(exists(select 1 from engagement_private.outbox o where o.channel='email' and o.recipient_id=contacts.user_id));
alter policy phase6_worker on public.engagement_preferences using(push_bookings or push_messages or email_transactional or email_optional or reminders or saved_alerts or reengagement or analytics or exists(select 1 from engagement_private.outbox o where o.recipient_id=engagement_preferences.user_id)) with check(true);
-- Default PUBLIC EXECUTE must not expose private queue/trigger helpers.
revoke all on all functions in schema engagement_private from public,anon,authenticated,castly_api,castly_engagement_worker;
grant execute on function engagement_private.is_admin(),engagement_private.record_event(text) to castly_api;
-- Invoker trigger code must be able to call these internal helpers, but direct
-- enqueue cannot pass the outbox trigger-depth guard.
grant execute on function engagement_private.enqueue(uuid,text,text,uuid,uuid,uuid,uuid,uuid) to castly_api;
commit;
