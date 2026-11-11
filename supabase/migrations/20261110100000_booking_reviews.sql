begin;
create table public.booking_completion_confirmations(booking_id uuid not null references public.bookings(id),user_id uuid not null references public.users(id),confirmed_at timestamptz not null default now(),primary key(booking_id,user_id));
alter table public.booking_completion_confirmations enable row level security;
revoke all on public.booking_completion_confirmations from public,anon,authenticated;
grant select,insert(booking_id,user_id) on public.booking_completion_confirmations to castly_api;
create policy completion_read on public.booking_completion_confirmations for select to castly_api using(exists(select 1 from public.bookings b where b.id=booking_id and private.request_user_id() in(b.client_id,b.talent_id)));
create policy completion_insert on public.booking_completion_confirmations for insert to castly_api with check(user_id=private.request_user_id() and exists(select 1 from public.bookings b where b.id=booking_id and b.status='accepted' and user_id in(b.client_id,b.talent_id)));
create function trust_private.completion_lock() returns trigger language plpgsql security invoker set search_path='' as $$begin
 perform 1 from public.bookings where id=new.booking_id and status='accepted' for update;
 if not found then raise exception 'Accepted booking required' using errcode='42501';end if;return new;end$$;
revoke all on function trust_private.completion_lock() from public,anon,authenticated,castly_api;
create trigger completion_lock before insert on public.booking_completion_confirmations for each row execute function trust_private.completion_lock();
create or replace function public.api_booking_transition_guard() returns trigger language plpgsql security invoker set search_path='' as $$
declare me uuid:=private.request_user_id();actor_role public.account_role;
begin
 if current_user<>'castly_api' then return new;end if;
 if me is null or me not in(old.client_id,old.talent_id) then raise exception 'Booking participant required' using errcode='42501';end if;
 if(to_jsonb(new)-array['status','updated_at']) is distinct from(to_jsonb(old)-array['status','updated_at']) then raise exception 'Protected booking fields are immutable' using errcode='42501';end if;
 select primary_role into actor_role from public.users where id=me;
 if(me=old.client_id and actor_role is distinct from 'client'::public.account_role) or(me=old.talent_id and actor_role is distinct from 'talent'::public.account_role) then raise exception 'Booking account role mismatch' using errcode='42501';end if;
 if new.status is not distinct from old.status then return new;end if;
 if(new.status in('accepted','declined') and old.status='requested' and old.talent_id=me)
 or(new.status='cancelled' and((old.status='requested' and old.client_id=me) or(old.status='accepted' and me in(old.client_id,old.talent_id)))) then return new;end if;
 if new.status='completed' and old.status='accepted' and (select count(*) from public.booking_completion_confirmations where booking_id=old.id and user_id in(old.client_id,old.talent_id))=2 then return new;end if;
 raise exception 'Invalid booking transition' using errcode='42501';end$$;
revoke all on function public.api_booking_transition_guard() from public,anon,authenticated,castly_api;
-- Preserve accepted conversations after both parties explicitly confirm completion.
alter policy api_conversation_create on public.conversations with check((client_id=private.request_user_id() or talent_id=private.request_user_id()) and exists(select 1 from public.bookings b where b.client_id=conversations.client_id and b.talent_id=conversations.talent_id and b.status in('accepted','completed')));
alter policy api_message_send on public.messages with check(sender_id=private.request_user_id() and exists(select 1 from public.conversations c where c.id=conversation_id and private.request_user_id() in(c.client_id,c.talent_id) and exists(select 1 from public.bookings b where b.client_id=c.client_id and b.talent_id=c.talent_id and b.status in('accepted','completed'))));
do $$declare source text;begin
 select pg_get_functiondef('public.api_message_send_guard()'::regprocedure) into source;
 if position('status=''accepted''' in source)=0 then raise exception 'Unknown message guard requires reconciliation';end if;
 execute replace(source,'status=''accepted''','status in(''accepted'',''completed'')');end$$;
create table public.user_reviews(
 id uuid primary key default gen_random_uuid(),booking_id uuid not null references public.bookings(id),reviewer_id uuid not null references public.users(id),target_id uuid not null references public.users(id),rating integer not null check(rating between 1 and 5),body text not null default '' check(length(body)<=2000),hidden boolean not null default false,created_at timestamptz not null default now(),updated_at timestamptz not null default now(),editable_until timestamptz not null,unique(booking_id,reviewer_id,target_id),check(reviewer_id<>target_id),check(editable_until>=created_at and editable_until<=created_at+interval '30 days'));
create index reviews_target_visible on public.user_reviews(target_id,created_at desc,id) where not hidden;
create index reviews_reviewer on public.user_reviews(reviewer_id,created_at desc,id);
alter table public.user_reviews enable row level security;
revoke all on public.user_reviews from public,anon,authenticated;
grant select,insert(booking_id,reviewer_id,target_id,rating,body,editable_until),update(rating,body,hidden,updated_at) on public.user_reviews to castly_api;
create policy review_read on public.user_reviews for select to castly_api using(reviewer_id=private.request_user_id() or target_id=private.request_user_id() or trust_private.is_reviewer('moderation') or(not hidden and exists(select 1 from public.talent_profiles t where t.id=target_id and t.published)));
create policy review_insert on public.user_reviews for insert to castly_api with check(reviewer_id=private.request_user_id() and exists(select 1 from public.bookings b where b.id=booking_id and b.status='completed' and ((b.client_id=reviewer_id and b.talent_id=target_id) or(b.talent_id=reviewer_id and b.client_id=target_id))));
create policy review_update on public.user_reviews for update to castly_api using(reviewer_id=private.request_user_id() or trust_private.is_reviewer('moderation')) with check(reviewer_id=private.request_user_id() or trust_private.is_reviewer('moderation'));
create function trust_private.review_transition() returns trigger language plpgsql security invoker set search_path='' as $$begin
 if(to_jsonb(new)-array['rating','body','hidden','updated_at']) is distinct from(to_jsonb(old)-array['rating','body','hidden','updated_at']) then raise exception 'Immutable review identity' using errcode='42501';end if;
 if new.hidden is distinct from old.hidden then
  if not trust_private.is_reviewer('moderation') or private.request_user_id() in(old.reviewer_id,old.target_id) or new.rating<>old.rating or new.body<>old.body then raise exception 'Independent moderator required' using errcode='42501';end if;
 elsif new.rating<>old.rating or new.body<>old.body then
  if old.reviewer_id<>private.request_user_id() or old.hidden or old.editable_until<=statement_timestamp() then raise exception 'Review edit window closed' using errcode='42501';end if;
 end if;new.updated_at=statement_timestamp();return new;end$$;
revoke all on function trust_private.review_transition() from public,anon,authenticated,castly_api;
create trigger review_transition before update on public.user_reviews for each row execute function trust_private.review_transition();
commit;
