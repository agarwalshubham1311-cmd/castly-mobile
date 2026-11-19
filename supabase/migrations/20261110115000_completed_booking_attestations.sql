begin;
alter policy completion_insert on public.booking_completion_confirmations with check(user_id=private.request_user_id() and exists(select 1 from public.bookings b where b.id=booking_id and b.status in('accepted','completed') and user_id in(b.client_id,b.talent_id)));
create or replace function trust_private.completion_lock() returns trigger language plpgsql security invoker set search_path='' as $$begin
 perform 1 from public.bookings where id=new.booking_id and status in('accepted','completed') for update;
 if not found then raise exception 'Accepted or completed booking required' using errcode='42501';end if;return new;end$$;
revoke all on function trust_private.completion_lock() from public,anon,authenticated,castly_api;
alter policy review_insert on public.user_reviews with check(reviewer_id=private.request_user_id() and exists(select 1 from public.bookings b where b.id=booking_id and b.status='completed' and ((b.client_id=reviewer_id and b.talent_id=target_id) or(b.talent_id=reviewer_id and b.client_id=target_id)) and (select count(*) from public.booking_completion_confirmations c where c.booking_id=b.id and c.user_id in(b.client_id,b.talent_id))=2));
commit;
