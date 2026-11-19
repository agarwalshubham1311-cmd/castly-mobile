begin;
create table public.talent_discovery_preferences(
 talent_id uuid primary key references public.talent_profiles(id) on delete cascade,
 region text check(region is null or length(trim(region)) between 2 and 100),
 share_region boolean not null default false,
 updated_at timestamptz not null default now(),
 check(not share_region or region is not null)
);
create table public.talent_availability_days(
 talent_id uuid not null references public.talent_profiles(id) on delete cascade,
 day date not null,
 available boolean not null,
 updated_at timestamptz not null default now(),
 primary key(talent_id,day),
 check(day between date '2020-01-01' and date '2100-12-31')
);
create index talent_discovery_region on public.talent_discovery_preferences(lower(region),talent_id) where share_region;
create index talent_availability_confirmed on public.talent_availability_days(day,talent_id) where available;
create index talent_booking_date_conflicts on public.bookings(talent_id,proposed_date) where status='accepted';
create index talent_search_public on public.talent_profiles(category_id,day_rate,id) where published;
create function public.phase5_normalized_skills(value text[]) returns text[] language sql immutable security invoker set search_path='' as $$select array(select lower(x) from unnest(value) x)$$;
revoke all on function public.phase5_normalized_skills(text[]) from public,anon,authenticated;
grant execute on function public.phase5_normalized_skills(text[]) to castly_api;
create index talent_search_skills on public.talent_profiles using gin(public.phase5_normalized_skills(skills)) where published;
alter table public.talent_discovery_preferences enable row level security;
alter table public.talent_availability_days enable row level security;
revoke all on public.talent_discovery_preferences,public.talent_availability_days from public,anon,authenticated;
grant select,insert(talent_id,region,share_region),update(region,share_region,updated_at) on public.talent_discovery_preferences to castly_api;
grant select,insert(talent_id,day,available),update(available,updated_at),delete on public.talent_availability_days to castly_api;
create policy discovery_preference_read on public.talent_discovery_preferences for select to castly_api using(talent_id=private.request_user_id() or (share_region and exists(select 1 from public.talent_profiles t where t.id=talent_id and t.published)));
create policy discovery_preference_insert on public.talent_discovery_preferences for insert to castly_api with check(talent_id=private.request_user_id() and exists(select 1 from public.talent_profiles t where t.id=talent_id));
create policy discovery_preference_update on public.talent_discovery_preferences for update to castly_api using(talent_id=private.request_user_id()) with check(talent_id=private.request_user_id());
create policy availability_read on public.talent_availability_days for select to castly_api using(talent_id=private.request_user_id() or exists(select 1 from public.talent_profiles t where t.id=talent_id and t.published));
create policy availability_insert on public.talent_availability_days for insert to castly_api with check(talent_id=private.request_user_id() and exists(select 1 from public.talent_profiles t where t.id=talent_id));
create policy availability_update on public.talent_availability_days for update to castly_api using(talent_id=private.request_user_id()) with check(talent_id=private.request_user_id());
create policy availability_delete on public.talent_availability_days for delete to castly_api using(talent_id=private.request_user_id());
create table public.talent_booking_busy_days(
 talent_id uuid not null references public.talent_profiles(id) on delete cascade,
 day date not null,
 booking_count integer not null check(booking_count>=0),
 primary key(talent_id,day)
);
alter table public.talent_booking_busy_days enable row level security;
revoke all on public.talent_booking_busy_days from public,anon,authenticated;
grant select,insert(talent_id,day,booking_count),update(booking_count),delete on public.talent_booking_busy_days to castly_api;
create policy busy_read on public.talent_booking_busy_days for select to castly_api using(pg_trigger_depth()>0 or talent_id=private.request_user_id() or exists(select 1 from public.talent_availability_days a join public.talent_profiles t on t.id=a.talent_id where a.talent_id=talent_booking_busy_days.talent_id and a.day=talent_booking_busy_days.day and a.available and t.published));
create policy busy_write on public.talent_booking_busy_days for all to castly_api using(pg_trigger_depth()>0) with check(pg_trigger_depth()>0);
insert into public.talent_booking_busy_days(talent_id,day,booking_count)
 select talent_id,proposed_date,count(*) from public.bookings where status='accepted' group by talent_id,proposed_date;
create function public.phase5_booking_busy_counter() returns trigger language plpgsql security invoker set search_path='' as $$begin
 if TG_OP in('UPDATE','DELETE') and old.status='accepted' then
  update public.talent_booking_busy_days set booking_count=booking_count-1 where talent_id=old.talent_id and day=old.proposed_date;
  delete from public.talent_booking_busy_days where talent_id=old.talent_id and day=old.proposed_date and booking_count=0;
 end if;
 if TG_OP in('INSERT','UPDATE') and new.status='accepted' then
  insert into public.talent_booking_busy_days(talent_id,day,booking_count) values(new.talent_id,new.proposed_date,1)
  on conflict(talent_id,day) do update set booking_count=talent_booking_busy_days.booking_count+1;
 end if;
 return null;end$$;
revoke all on function public.phase5_booking_busy_counter() from public,anon,authenticated,castly_api;
create trigger phase5_booking_busy after insert or update of status or delete on public.bookings for each row execute function public.phase5_booking_busy_counter();
create table public.phase5_request_limits(
 user_id uuid not null references public.users(id) on delete cascade,
 operation text not null check(length(operation) between 1 and 40),
 window_start timestamptz not null,
 attempts integer not null check(attempts>0),
 primary key(user_id,operation)
);
alter table public.phase5_request_limits enable row level security;
revoke all on public.phase5_request_limits from public,anon,authenticated;
grant select,insert(user_id,operation,window_start,attempts),update(window_start,attempts) on public.phase5_request_limits to castly_api;
create policy phase5_limit_read on public.phase5_request_limits for select to castly_api using(user_id=private.request_user_id());
create policy phase5_limit_insert on public.phase5_request_limits for insert to castly_api with check(user_id=private.request_user_id());
create policy phase5_limit_update on public.phase5_request_limits for update to castly_api using(user_id=private.request_user_id()) with check(user_id=private.request_user_id());
commit;
