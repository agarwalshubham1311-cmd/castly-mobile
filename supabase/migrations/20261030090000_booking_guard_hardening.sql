begin;
-- Forward-only hardening for databases that already ran the repository's Spring migration.
-- Hosted installations with a different ledger must use reconciliation/issue-1 instead.
create index if not exists castly_message_history_cursor_idx on public.messages(conversation_id,created_at,id);
create or replace function public.api_booking_transition_guard() returns trigger
language plpgsql security invoker set search_path='' as $$
declare me uuid := auth.uid(); actor_role public.account_role;
begin
 if current_user <> 'castly_api' then return new; end if;
 if me is null or (me <> old.client_id and me <> old.talent_id) then
  raise exception 'Booking participant required' using errcode='42501';
 end if;
 -- UPDATE(status) is the only API grant, but defend against future accidental wider grants.
 -- The existing bookings_stamp trigger is allowed to maintain updated_at.
 if (to_jsonb(new)-array['status','updated_at']) is distinct from
    (to_jsonb(old)-array['status','updated_at']) then
  raise exception 'Protected booking fields are immutable' using errcode='42501';
 end if;
 select primary_role into actor_role from public.users where id=me;
 if (me=old.client_id and actor_role is distinct from 'client'::public.account_role)
 or (me=old.talent_id and actor_role is distinct from 'talent'::public.account_role) then
  raise exception 'Booking account role mismatch' using errcode='42501';
 end if;
 if new.status is not distinct from old.status then return new; end if;
 if (new.status in ('accepted','declined') and old.status='requested' and old.talent_id=me)
 or (new.status='cancelled' and ((old.status='requested' and old.client_id=me)
      or (old.status='accepted' and me in (old.client_id,old.talent_id)))) then return new; end if;
 raise exception 'Invalid booking transition' using errcode='42501';
end$$;
revoke all on function public.api_booking_transition_guard() from public,anon,authenticated,castly_api;
-- Make direct API-role SQL obey the same send/cancel lock order as Java.
-- RLS remains the SELECT/delivery boundary; this trigger also prevents snapshot races.
create function public.api_message_send_guard() returns trigger
language plpgsql security invoker set search_path='' as $$
declare c public.conversations; accepted_id uuid; has_accepted boolean := false;
begin
 if current_user <> 'castly_api' then return new; end if;
 select * into c from public.conversations where id=new.conversation_id;
 if not found or auth.uid() is null or new.sender_id is distinct from auth.uid()
    or auth.uid() not in (c.client_id,c.talent_id) then
  raise exception 'Conversation participant required' using errcode='42501';
 end if;
 -- Lock every currently accepted booking in stable UUID order. A concurrent cancellation
 -- commits first (send denied), or waits until this send commits (send precedes cancellation).
 for accepted_id in select id from public.bookings
   where client_id=c.client_id and talent_id=c.talent_id and status='accepted'
   order by id for update loop
  has_accepted := true;
 end loop;
 if has_accepted then return new; end if;
 raise exception 'An accepted booking is required' using errcode='42501';
end$$;
create trigger api_message_send before insert on public.messages
 for each row execute function public.api_message_send_guard();
revoke all on function public.api_message_send_guard() from public,anon,authenticated,castly_api;
commit;
