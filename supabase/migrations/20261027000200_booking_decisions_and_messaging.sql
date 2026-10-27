begin;
-- Castly Milestone 2: server-controlled booking decisions and participant-only messaging.
create or replace function public.respond_to_booking(p_booking_id uuid, p_decision text)
returns public.bookings
language plpgsql security definer set search_path = ''
as $$
declare v_booking public.bookings;
begin
  if (select auth.uid()) is null then
    raise exception 'Authentication required' using errcode='42501';
  end if;
  if p_decision not in ('accepted','declined') or p_decision is null then
    raise exception 'Invalid booking decision' using errcode='22023';
  end if;
  update public.bookings
     set status = p_decision::public.booking_status
   where id = p_booking_id
     and talent_id = (select auth.uid())
     and status = 'requested'
  returning * into v_booking;
  if not found then
    raise exception 'Booking not found or transition not permitted' using errcode='42501';
  end if;
  return v_booking;
end;
$$;
revoke all on function public.respond_to_booking(uuid,text) from public,anon;
grant execute on function public.respond_to_booking(uuid,text) to authenticated;

create or replace function public.cancel_booking(p_booking_id uuid)
returns public.bookings
language plpgsql security definer set search_path = ''
as $$
declare v_booking public.bookings;
begin
  if (select auth.uid()) is null then
    raise exception 'Authentication required' using errcode='42501';
  end if;
  update public.bookings
     set status = 'cancelled'
   where id = p_booking_id
     and client_id = (select auth.uid())
     and status in ('requested','accepted')
  returning * into v_booking;
  if not found then
    raise exception 'Booking not found or transition not permitted' using errcode='42501';
  end if;
  return v_booking;
end;
$$;
revoke all on function public.cancel_booking(uuid) from public,anon;
grant execute on function public.cancel_booking(uuid) to authenticated;

-- A client or talent can send messages only when they are a participant
-- AND at least one accepted booking exists between the two accounts.
grant insert(conversation_id,sender_id,body) on public.messages to authenticated;
create policy participant_message_send on public.messages
for insert to authenticated with check (
  sender_id = (select auth.uid())
  and exists (
    select 1 from public.conversations c
    where c.id = conversation_id
      and (c.client_id = (select auth.uid()) or c.talent_id = (select auth.uid()))
      and exists (
        select 1 from public.bookings b
        where b.client_id = c.client_id and b.talent_id = c.talent_id
          and b.status = 'accepted'
      )
  )
);

create index if not exists bookings_client_talent_status_idx
on public.bookings(client_id,talent_id,status);
-- Realtime subscriptions must additionally filter by participant;
-- RLS SELECT policies are the final authorization boundary.
commit;