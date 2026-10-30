-- Include ONLY inside the approved preparation/cutover transaction, after structural review.
-- The existing stamp trigger would otherwise rewrite historical updated_at values.
-- Transactional DDL restores trigger state on any failure; no other trigger is disabled.
alter table public.bookings disable trigger bookings_stamp;
update public.bookings b
 set client_name=case when b.client_name='' then u.display_name else b.client_name end,
     talent_name=case when b.talent_name='' then t.display_name else b.talent_name end
 from public.users u,public.talent_profiles t
 where b.client_id=u.id and b.talent_id=t.id and (b.client_name='' or b.talent_name='');
alter table public.bookings enable trigger bookings_stamp;
do $$begin
 if exists(select 1 from public.bookings where char_length(trim(client_name))=0 or char_length(trim(talent_name))=0) then
  raise exception 'Unresolved booking display names: review source accounts before preparing/cutting over';
 end if;
end$$;
