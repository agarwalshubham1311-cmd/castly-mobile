-- Disposable local fixture only. No hosted URL. Every data/ACL/trigger change rolls back.
begin;
create schema invoker_notice_test;
create function invoker_notice_test.eq(actual bigint,expected bigint,label text) returns void language plpgsql as $$begin
 if actual is distinct from expected then raise exception 'FAIL %',label;end if;raise notice 'PASS %',label;end$$;
create function invoker_notice_test.denied(statement text,label text) returns void language plpgsql as $$begin
 begin execute statement;exception when insufficient_privilege then raise notice 'PASS %',label;return;end;
 raise exception 'FAIL %',label;end$$;
grant usage on schema invoker_notice_test to castly_api,authenticated;
grant execute on all functions in schema invoker_notice_test to castly_api,authenticated;
revoke usage on schema auth from castly_api;
insert into auth.users(id) values('c1000000-0000-0000-0000-000000000001'),('c1000000-0000-0000-0000-000000000002');
update public.users set primary_role='client' where id='c1000000-0000-0000-0000-000000000001';
update public.users set primary_role='talent' where id='c1000000-0000-0000-0000-000000000002';
insert into public.talent_profiles(id,display_name,category_id,city,day_rate,published)
 values('c1000000-0000-0000-0000-000000000002','Test Talent','actors','Mumbai',10000,true);
-- Trusted fixture owner repeats each valid event to exercise dedup under recipient-only SELECT.
create trigger test_repeat_booking_notice after update of status on public.bookings
 for each row execute function public.castly_booking_notification();
create trigger test_repeat_message_notice after insert on public.messages
 for each row execute function public.castly_message_notification();
select invoker_notice_test.eq((select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace
 where n.nspname='public' and p.proname in ('castly_booking_notification','castly_message_notification','castly_notification_insert_guard') and not p.prosecdef),3,'all notification functions are invokers');
set local role castly_api;
select set_config('request.jwt.claim.sub','c1000000-0000-0000-0000-000000000001',true),set_config('request.jwt.claims','{"sub":"c1000000-0000-0000-0000-000000000001"}',true);
select invoker_notice_test.eq(has_schema_privilege(current_user,'auth','USAGE')::int,0,'notification writes require no auth schema access');
insert into public.bookings(client_id,talent_id,project_type,proposed_date,location,requirements,indicative_budget)
 values(private.request_user_id(),'c1000000-0000-0000-0000-000000000002','Shoot',current_date+1,'Mumbai','Detailed project brief',10000) returning id as booking \gset
select invoker_notice_test.eq((select count(*) from public.notifications),0,'sender cannot read recipient notification');
select invoker_notice_test.denied(format($sql$insert into public.notifications(recipient_id,kind,event_key,booking_id)
 select talent_id,'booking_requested','booking:'||id||':booking_requested',id from public.bookings where id=%L$sql$,:'booking'),'direct API insert of a valid-looking event is denied');
select invoker_notice_test.eq(has_table_privilege(current_user,'public.notifications','INSERT')::int,0,'API has no table-wide notification INSERT');
select invoker_notice_test.eq(has_column_privilege(current_user,'public.notifications','id','INSERT')::int,0,'API cannot choose notification IDs');
select invoker_notice_test.eq(has_table_privilege(current_user,'public.bookings','TRIGGER')::int,0,'API cannot install event triggers');
select invoker_notice_test.denied('set local session_replication_role=replica','API cannot disable event validation');
select set_config('request.jwt.claim.sub','c1000000-0000-0000-0000-000000000002',true),set_config('request.jwt.claims','{"sub":"c1000000-0000-0000-0000-000000000002"}',true);
select invoker_notice_test.eq((select count(*) from public.notifications where kind='booking_requested'),1,'talent receives request notice');
update public.bookings set status='accepted' where id=:'booking';
insert into public.conversations(client_id,talent_id) values('c1000000-0000-0000-0000-000000000001',private.request_user_id()) returning id as conversation \gset
select set_config('request.jwt.claim.sub','c1000000-0000-0000-0000-000000000001',true),set_config('request.jwt.claims','{"sub":"c1000000-0000-0000-0000-000000000001"}',true);
select invoker_notice_test.eq((select count(*) from public.notifications where kind='booking_accepted'),1,'repeated trigger produces one accepted notice');
insert into public.messages(conversation_id,sender_id,body) values(:'conversation',private.request_user_id(),'Authorized message');
select invoker_notice_test.eq((select count(*) from public.notifications where kind='message_received'),0,'message sender cannot see recipient notice');
reset role;
set local role authenticated;
select set_config('request.jwt.claim.sub','c1000000-0000-0000-0000-000000000002',true),set_config('request.jwt.claims','{"sub":"c1000000-0000-0000-0000-000000000002"}',true);
select invoker_notice_test.eq((select count(*) from public.notifications where kind='message_received'),1,'Realtime recipient sees one deduplicated message notice');
select invoker_notice_test.eq((select count(*) from public.notifications where kind='booking_accepted'),0,'Realtime cannot read counterparty notices');
select invoker_notice_test.denied('update public.notifications set read_at=now()','ordinary authenticated cannot write read markers directly');
select invoker_notice_test.denied('select private.request_user_id()','Realtime needs no access to private API helper');
select invoker_notice_test.eq((select count(*) from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename='notifications'),1,'notifications are in Realtime publication');
rollback;
