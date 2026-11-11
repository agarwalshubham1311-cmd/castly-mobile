begin;
create schema phase5_test;
create function phase5_test.denied(statement text,label text) returns void language plpgsql as $$begin
 begin execute statement; exception when insufficient_privilege then raise notice 'PASS %',label;return;end;
 raise exception 'FAIL unauthorized write succeeded: %',label;
end$$;
create function phase5_test.eq(actual bigint,expected bigint,label text) returns void language plpgsql as $$begin
 if actual is distinct from expected then raise exception 'FAIL %: got %, expected %',label,actual,expected;end if;
 raise notice 'PASS %',label;
end$$;
create function phase5_test.delete_other() returns bigint language plpgsql as $$declare removed bigint; begin
 delete from public.talent_availability_days where talent_id='00000000-0000-0000-0000-000000000062';
 get diagnostics removed = row_count;
 return removed;
end$$;
grant usage on schema phase5_test to authenticated,castly_api;
grant execute on all functions in schema phase5_test to authenticated,castly_api;
insert into auth.users(id,raw_user_meta_data) values
 ('00000000-0000-0000-0000-000000000061','{}'),
 ('00000000-0000-0000-0000-000000000062','{}');
update public.users set primary_role='talent' where id in
 ('00000000-0000-0000-0000-000000000061','00000000-0000-0000-0000-000000000062');
insert into public.talent_profiles(id,display_name,category_id,city,day_rate,bio,skills,available,published) values
 ('00000000-0000-0000-0000-000000000061','Shared creator','actors','Pune',1000,'fixture',array['Acting'],true,true),
 ('00000000-0000-0000-0000-000000000062','Private creator','actors','Pune',1000,'fixture',array['Acting'],true,false);
insert into public.talent_availability_days(talent_id,day,available) values
 ('00000000-0000-0000-0000-000000000061',current_date+10,true),
 ('00000000-0000-0000-0000-000000000062',current_date+10,true);
insert into public.talent_booking_busy_days(talent_id,day,booking_count) values
 ('00000000-0000-0000-0000-000000000061',current_date+10,1),
 ('00000000-0000-0000-0000-000000000062',current_date+10,1);
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000061',true);
set local role authenticated;
select phase5_test.denied($$insert into public.talent_availability_days(talent_id,day,available) values(auth.uid(),current_date+11,true)$$,'mobile cannot write availability directly');
select phase5_test.denied($$update public.talent_discovery_preferences set share_region=true$$,'mobile cannot change region consent');
select phase5_test.denied($$select * from public.talent_booking_busy_days$$,'mobile cannot read busy summary');
reset role;
set local role castly_api;
select phase5_test.eq((select count(*) from public.talent_availability_days where talent_id='00000000-0000-0000-0000-000000000062'),0,'other unpublished creator calendar hidden');
select phase5_test.eq((select count(*) from public.talent_booking_busy_days where talent_id='00000000-0000-0000-0000-000000000062'),0,'other unpublished busy summary hidden');
select phase5_test.denied($$insert into public.talent_booking_busy_days(talent_id,day,booking_count) values(private.request_user_id(),current_date+12,1)$$,'API cannot forge busy projection');
select phase5_test.denied($$insert into public.talent_availability_days(talent_id,day,available) values('00000000-0000-0000-0000-000000000062',current_date+11,true)$$,'API cannot write other creator calendar');
select phase5_test.eq(phase5_test.delete_other(),0,'API cannot erase other creator calendar');
select phase5_test.denied($$insert into public.phase5_request_limits(user_id,operation,window_start,attempts) values('00000000-0000-0000-0000-000000000062','search',now(),1)$$,'API cannot write other user rate counter');
reset role;
select phase5_test.eq((select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relname in ('talent_discovery_preferences','talent_availability_days','talent_booking_busy_days','phase5_request_limits') and c.relrowsecurity),4,'all Phase5 tables use RLS');
select phase5_test.eq((select count(*) from pg_indexes where schemaname='public' and indexname='talent_search_skills' and indexdef like '%phase5_normalized_skills%'),1,'normalized skill GIN index exists');
set local enable_seqscan=off;
explain (costs off) select id from public.talent_profiles where published and public.phase5_normalized_skills(skills) @> array['acting'];
rollback;
