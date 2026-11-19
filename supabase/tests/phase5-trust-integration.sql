begin;
revoke usage on schema auth from castly_api;
-- Disposable fixture only: no production records, credentials or private facts.
create schema phase5_integration_test;
create function phase5_integration_test.eq(actual bigint,expected bigint,label text)
 returns void language plpgsql as $$begin
 if actual is distinct from expected then raise exception 'FAIL %: got %, expected %',label,actual,expected;end if;
 raise notice 'PASS %',label;
end$$;
grant usage on schema phase5_integration_test to castly_api;
grant execute on all functions in schema phase5_integration_test to castly_api;
insert into auth.users(id,raw_user_meta_data) values
 ('70000000-0000-0000-0000-000000000001','{}'),
 ('70000000-0000-0000-0000-000000000002','{}'),
 ('70000000-0000-0000-0000-000000000003','{}');
insert into public.talent_profiles(id,display_name,category_id,city,day_rate,published) values
 ('70000000-0000-0000-0000-000000000002','Integration creator','actors','Mumbai',1000,true);
insert into public.talent_discovery_preferences(talent_id,region,share_region) values
 ('70000000-0000-0000-0000-000000000002','Maharashtra',true);
insert into public.talent_availability_days(talent_id,day,available) values
 ('70000000-0000-0000-0000-000000000002',current_date+20,true);
insert into public.talent_booking_busy_days(talent_id,day,booking_count) values
 ('70000000-0000-0000-0000-000000000002',current_date+20,1);
select phase5_integration_test.eq((select count(*) from pg_proc where oid='private.discovery_talent_visible(uuid)'::regprocedure and not prosecdef),1,'visibility helper is invoker');
select phase5_integration_test.eq(has_function_privilege('authenticated','private.discovery_talent_visible(uuid)','execute')::int,0,'no direct authenticated visibility helper');
select phase5_integration_test.eq(has_schema_privilege('castly_api','auth','usage')::int,0,'no auth schema privilege required');
select set_config('request.jwt.claim.sub','70000000-0000-0000-0000-000000000001',true);
set local role castly_api;
select phase5_integration_test.eq(private.discovery_talent_visible('70000000-0000-0000-0000-000000000002')::int,1,'published Free creator visible');
insert into public.user_blocks(blocker_id,blocked_id) values(private.request_user_id(),'70000000-0000-0000-0000-000000000002');
select phase5_integration_test.eq(private.discovery_talent_visible('70000000-0000-0000-0000-000000000002')::int,0,'client block removes candidate');
select phase5_integration_test.eq((select count(*) from public.talent_discovery_preferences where talent_id='70000000-0000-0000-0000-000000000002'),0,'blocked region hidden by RLS');
select phase5_integration_test.eq((select count(*) from public.talent_availability_days where talent_id='70000000-0000-0000-0000-000000000002'),0,'blocked availability hidden by RLS');
select phase5_integration_test.eq((select count(*) from public.talent_booking_busy_days where talent_id='70000000-0000-0000-0000-000000000002'),0,'blocked busy projection hidden by RLS');
-- No session-level role/claim carries from one actor to the next.
select set_config('request.jwt.claim.sub','70000000-0000-0000-0000-000000000003',true);
select phase5_integration_test.eq(private.discovery_talent_visible('70000000-0000-0000-0000-000000000002')::int,1,'unrelated actor remains isolated');
select set_config('request.jwt.claim.sub','70000000-0000-0000-0000-000000000002',true);
insert into public.user_blocks(blocker_id,blocked_id) values(private.request_user_id(),'70000000-0000-0000-0000-000000000001');
select phase5_integration_test.eq((select count(*) from public.talent_availability_days where talent_id=private.request_user_id()),1,'blocked creator keeps own declaration');
select set_config('request.jwt.claim.sub','70000000-0000-0000-0000-000000000001',true);
delete from public.user_blocks where blocker_id=private.request_user_id();
select phase5_integration_test.eq(private.discovery_talent_visible('70000000-0000-0000-0000-000000000002')::int,0,'reverse block still excludes candidate');
reset role;
update public.talent_profiles set published=false where id='70000000-0000-0000-0000-000000000002';
set local role castly_api;
select set_config('request.jwt.claim.sub','70000000-0000-0000-0000-000000000003',true);
select phase5_integration_test.eq(private.discovery_talent_visible('70000000-0000-0000-0000-000000000002')::int,0,'unpublished creator excluded for outsider');
select set_config('request.jwt.claim.sub','70000000-0000-0000-0000-000000000002',true);
select phase5_integration_test.eq(private.discovery_talent_visible('70000000-0000-0000-0000-000000000002')::int,0,'owner cannot include unpublished self in discovery');
select phase5_integration_test.eq((select count(*) from public.talent_availability_days where talent_id=private.request_user_id()),1,'owner can still withdraw unpublished declaration');
rollback;
