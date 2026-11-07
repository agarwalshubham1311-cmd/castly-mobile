-- REVIEW REQUIRED: apply only this artifact after the private identity fallback.
-- No auth privileges, role memberships, elevated functions or business-table grant changes.
begin;
set local search_path=pg_catalog;
do $preflight$
begin
 if to_regprocedure('private.request_user_id()') is null then
  raise exception 'Private request identity migration required';
 end if;
 if not exists(select 1 from pg_publication where pubname='supabase_realtime') then
  raise exception 'Existing Supabase Realtime publication required';
 end if;
 if exists(select 1 from pg_proc where oid='private.request_user_id()'::regprocedure and prosecdef)
 or exists(select 1 from pg_roles where rolname='castly_api' and (rolsuper or rolbypassrls))
 or exists(select 1 from pg_auth_members where member=(select oid from pg_roles where rolname='castly_api')) then
  raise exception 'Restricted invoker identity configuration required';
 end if;
 if to_regclass('public.notifications') is not null then
  -- Only the exact prior proposal is an accepted upgrade; never silently reshape unknown data.
  if (select array_agg(attname::text order by attnum) from pg_attribute
      where attrelid='public.notifications'::regclass and attnum>0 and not attisdropped)
     <>array['id','recipient_id','kind','event_key','booking_id','conversation_id','message_id','created_at','read_at']
  or (select array_agg(atttypid order by attnum) from pg_attribute
      where attrelid='public.notifications'::regclass and attnum>0 and not attisdropped)
     <>array['uuid'::regtype::oid,'uuid'::regtype::oid,'text'::regtype::oid,'text'::regtype::oid,
       'uuid'::regtype::oid,'uuid'::regtype::oid,'uuid'::regtype::oid,'timestamptz'::regtype::oid,'timestamptz'::regtype::oid]
  or exists(select 1 from pg_class where oid='public.notifications'::regclass and relowner=(select oid from pg_roles where rolname='castly_api'))
  or not exists(select 1 from pg_constraint where conrelid='public.notifications'::regclass
      and conname='notifications_recipient_id_event_key_key' and contype='u' and convalidated
      and conkey=array[2,4]::smallint[])
  or exists(select 1 from pg_policies where schemaname='public' and tablename='notifications'
      and policyname not in ('api_notifications_read','api_notifications_seen','notification_realtime_read')) then
   raise exception 'Unexpected notifications baseline requires separate review';
  end if;
 end if;
end $preflight$;

create table if not exists public.notifications (
 id uuid primary key default gen_random_uuid(),
 recipient_id uuid not null references public.users(id) on delete cascade,
 kind text not null check(kind in ('booking_requested','booking_accepted','booking_declined','booking_cancelled','message_received')),
 event_key text not null,
 booking_id uuid references public.bookings(id) on delete cascade,
 conversation_id uuid references public.conversations(id) on delete cascade,
 message_id uuid references public.messages(id) on delete cascade,
 created_at timestamptz not null default now(), read_at timestamptz,
 unique(recipient_id,event_key),
 check((kind='message_received' and message_id is not null and conversation_id is not null and booking_id is null)
    or (kind<>'message_received' and booking_id is not null and message_id is null and conversation_id is null))
);
create index if not exists notifications_recipient_cursor_idx on public.notifications(recipient_id,created_at desc,id desc);
create index if not exists notifications_unread_idx on public.notifications(recipient_id,conversation_id) where read_at is null;
alter table public.notifications enable row level security;
revoke all on public.notifications from public,anon,authenticated,castly_api;
-- Table REVOKE does not remove pre-existing column grants.
revoke insert(id,recipient_id,kind,event_key,booking_id,conversation_id,message_id,created_at,read_at),
 update(id,recipient_id,kind,event_key,booking_id,conversation_id,message_id,created_at,read_at),
 select(id,recipient_id,kind,event_key,booking_id,conversation_id,message_id,created_at,read_at),
 references(id,recipient_id,kind,event_key,booking_id,conversation_id,message_id,created_at,read_at)
 on public.notifications from public,anon,authenticated,castly_api;
grant select on public.notifications to castly_api,authenticated;
grant update(read_at) on public.notifications to castly_api;
-- Needed by invoker AFTER triggers. BEFORE guard + RLS prohibit direct API inserts.
grant insert(recipient_id,kind,event_key,booking_id,conversation_id,message_id) on public.notifications to castly_api;
drop policy if exists api_notifications_read on public.notifications;
drop policy if exists api_notifications_seen on public.notifications;
drop policy if exists notification_realtime_read on public.notifications;
create policy api_notifications_read on public.notifications for select to castly_api
 using(recipient_id=(select private.request_user_id()));
create policy api_notifications_seen on public.notifications for update to castly_api
 using(recipient_id=(select private.request_user_id())) with check(recipient_id=(select private.request_user_id()));
create policy api_notifications_event_insert on public.notifications for insert to castly_api
 with check(pg_trigger_depth()>0 and (select private.request_user_id()) is not null
   and recipient_id<>(select private.request_user_id()));
-- Realtime runs as authenticated, not the API role. Use its verified JWT settings inline;
-- private namespace/helper access and all notification writes remain unavailable to that role.
create policy notification_realtime_read on public.notifications for select to authenticated using(
 recipient_id=(select coalesce(nullif(current_setting('request.jwt.claim.sub',true),''),
   nullif(current_setting('request.jwt.claims',true),'')::jsonb->>'sub')::uuid));

create function public.castly_notification_insert_guard() returns trigger
language plpgsql security invoker set search_path='' as $guard$
declare me uuid; recipient uuid;
begin
 -- Object owners can maintain/backfill data; ordinary users have no INSERT grant.
 if current_user<>'castly_api' then return new; end if;
 me := private.request_user_id();
 if pg_trigger_depth()<2 or me is null or new.read_at is not null then
  raise exception 'Notification event context required' using errcode='42501';
 end if;
 if new.kind='message_received' then
  select case when m.sender_id=c.client_id then c.talent_id else c.client_id end into recipient
   from public.messages m join public.conversations c on c.id=m.conversation_id
   where m.id=new.message_id and m.conversation_id=new.conversation_id and m.sender_id=me
     and me in (c.client_id,c.talent_id) and new.booking_id is null
     and new.event_key='message:'||m.id;
 else
  select case when new.kind='booking_requested' then b.talent_id
    when new.kind in ('booking_accepted','booking_declined') then b.client_id
    when me=b.client_id then b.talent_id else b.client_id end into recipient
   from public.bookings b where b.id=new.booking_id and new.conversation_id is null
    and new.message_id is null and new.event_key='booking:'||b.id||':'||new.kind
    and ((new.kind='booking_requested' and b.status='requested' and me=b.client_id)
      or (new.kind='booking_accepted' and b.status='accepted' and me=b.talent_id)
      or (new.kind='booking_declined' and b.status='declined' and me=b.talent_id)
      or (new.kind='booking_cancelled' and b.status='cancelled' and me in (b.client_id,b.talent_id)));
 end if;
 if recipient is null or new.recipient_id is distinct from recipient or recipient=me then
  raise exception 'Notification event does not match business source' using errcode='42501';
 end if;
 return new;
end $guard$;
create trigger castly_notification_insert_guard before insert on public.notifications
 for each row execute function public.castly_notification_insert_guard();

-- CREATE OR REPLACE deliberately removes the prior local proposal's definer mode on upgrade.
create or replace function public.castly_booking_notification() returns trigger
language plpgsql security invoker set search_path='' as $booking$
declare me uuid; event_kind text; recipient uuid; conflict_name text;
begin
 if current_user<>'castly_api' then return new; end if;
 me := private.request_user_id();
 if TG_OP='INSERT' then
  if new.status='requested' and me=new.client_id then event_kind:='booking_requested'; recipient:=new.talent_id; end if;
 elsif new.status is distinct from old.status then
  if new.status='accepted' and me=new.talent_id then event_kind:='booking_accepted'; recipient:=new.client_id;
  elsif new.status='declined' and me=new.talent_id then event_kind:='booking_declined'; recipient:=new.client_id;
  elsif new.status='cancelled' and me in (new.client_id,new.talent_id) then
   event_kind:='booking_cancelled'; recipient:=case when me=new.client_id then new.talent_id else new.client_id end;
  end if;
 end if;
 if recipient is not null then
  begin
   insert into public.notifications(recipient_id,kind,event_key,booking_id)
    values(recipient,event_kind,'booking:'||new.id||':'||event_kind,new.id);
  exception when unique_violation then
   get stacked diagnostics conflict_name=CONSTRAINT_NAME;
   if conflict_name<>'notifications_recipient_id_event_key_key' then raise; end if;
  end;
 end if;
 return new;
end $booking$;
create or replace function public.castly_message_notification() returns trigger
language plpgsql security invoker set search_path='' as $message$
declare me uuid; c public.conversations; recipient uuid; conflict_name text;
begin
 if current_user<>'castly_api' then return new; end if;
 me := private.request_user_id();
 select * into c from public.conversations where id=new.conversation_id;
 if not found or me is null or new.sender_id is distinct from me or me not in (c.client_id,c.talent_id) then
  raise exception 'Conversation participant required' using errcode='42501';
 end if;
 recipient:=case when me=c.client_id then c.talent_id else c.client_id end;
 begin
  insert into public.notifications(recipient_id,kind,event_key,conversation_id,message_id)
   values(recipient,'message_received','message:'||new.id,c.id,new.id);
 exception when unique_violation then
  get stacked diagnostics conflict_name=CONSTRAINT_NAME;
  if conflict_name<>'notifications_recipient_id_event_key_key' then raise; end if;
 end;
 return new;
end $message$;
drop trigger if exists castly_booking_notification on public.bookings;
drop trigger if exists castly_message_notification on public.messages;
create trigger castly_booking_notification after insert or update of status on public.bookings
 for each row execute function public.castly_booking_notification();
create trigger castly_message_notification after insert on public.messages
 for each row execute function public.castly_message_notification();
revoke all on function public.castly_notification_insert_guard(),public.castly_booking_notification(),
 public.castly_message_notification() from public,anon,authenticated,castly_api;
do $publication$
begin
 if exists(select 1 from pg_publication where pubname='supabase_realtime') and not exists(
   select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename='notifications') then
  alter publication supabase_realtime add table public.notifications;
 end if;
end $publication$;
commit;
