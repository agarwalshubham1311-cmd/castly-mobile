begin;
-- REVIEW ONLY for hosted projects. New additive event storage; no historical backfill/cutover.
-- These trigger functions can ONLY insert typed notifications, never change business records.
create table public.notifications (
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
create index notifications_recipient_cursor_idx on public.notifications(recipient_id,created_at desc,id desc);
create index notifications_unread_idx on public.notifications(recipient_id,conversation_id) where read_at is null;
alter table public.notifications enable row level security;
revoke all on public.notifications from public,anon,authenticated,castly_api;
grant select on public.notifications to castly_api,authenticated;
grant update(read_at) on public.notifications to castly_api;
create policy api_notifications_read on public.notifications for select to castly_api using(recipient_id=(select auth.uid()));
create policy api_notifications_seen on public.notifications for update to castly_api using(recipient_id=(select auth.uid())) with check(recipient_id=(select auth.uid()));
create policy notification_realtime_read on public.notifications for select to authenticated using(recipient_id=(select auth.uid()));
create function public.castly_booking_notification() returns trigger
language plpgsql security definer set search_path='' as $$
declare event_kind text; recipient uuid;
begin
 if TG_OP='INSERT' then
  if new.status='requested' and auth.uid()=new.client_id then event_kind:='booking_requested'; recipient:=new.talent_id; end if;
 elsif new.status is distinct from old.status then
  if new.status='accepted' and auth.uid()=new.talent_id then event_kind:='booking_accepted'; recipient:=new.client_id;
  elsif new.status='declined' and auth.uid()=new.talent_id then event_kind:='booking_declined'; recipient:=new.client_id;
  elsif new.status='cancelled' and auth.uid() in (new.client_id,new.talent_id) then
   event_kind:='booking_cancelled'; recipient:=case when auth.uid()=new.client_id then new.talent_id else new.client_id end;
  end if;
 end if;
 if recipient is not null then
  insert into public.notifications(recipient_id,kind,event_key,booking_id)
   values(recipient,event_kind,'booking:'||new.id||':'||event_kind,new.id)
   on conflict(recipient_id,event_key) do nothing;
 end if;
 return new;
end$$;
create trigger castly_booking_notification after insert or update of status on public.bookings
 for each row execute function public.castly_booking_notification();
create function public.castly_message_notification() returns trigger
language plpgsql security definer set search_path='' as $$
declare c public.conversations; recipient uuid;
begin
 select * into c from public.conversations where id=new.conversation_id;
 if not found or new.sender_id not in (c.client_id,c.talent_id) then
  raise exception 'Conversation participant required' using errcode='42501';
 end if;
 if auth.uid() is distinct from new.sender_id then return new; end if;
 recipient:=case when new.sender_id=c.client_id then c.talent_id else c.client_id end;
 insert into public.notifications(recipient_id,kind,event_key,conversation_id,message_id)
  values(recipient,'message_received','message:'||new.id,c.id,new.id)
  on conflict(recipient_id,event_key) do nothing;
 return new;
end$$;
create trigger castly_message_notification after insert on public.messages
 for each row execute function public.castly_message_notification();
revoke all on function public.castly_booking_notification(),public.castly_message_notification() from public,anon,authenticated,castly_api;
do $$begin
 if exists(select 1 from pg_publication where pubname='supabase_realtime') then
  alter publication supabase_realtime add table public.notifications;
 end if;
end$$;
commit;
