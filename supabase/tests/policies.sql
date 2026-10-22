-- Run after migrations on an isolated local Supabase/PostgreSQL database. All fixtures roll back.
begin;
create schema castly_test;
create function castly_test.eq(actual bigint,expected bigint,label text) returns void language plpgsql as $$begin if actual is distinct from expected then raise exception 'FAIL %: expected %, got %',label,expected,actual;end if;raise notice 'PASS %',label;end$$;
create function castly_test.denied(statement text,label text) returns void language plpgsql as $$begin
 begin execute statement; exception when insufficient_privilege or raise_exception or check_violation then raise notice 'PASS %',label;return;end;
 raise exception 'FAIL %: unauthorized write succeeded',label;
end$$;
create function castly_test.affected(statement text,expected bigint,label text) returns void language plpgsql as $$declare total bigint;begin execute statement;get diagnostics total=row_count;perform castly_test.eq(total,expected,label);end$$;
grant usage on schema castly_test to anon,authenticated;
grant execute on all functions in schema castly_test to anon,authenticated;
insert into auth.users(id,email,raw_user_meta_data) values
 ('00000000-0000-0000-0000-000000000001','client1@example.test','{}'),
 ('00000000-0000-0000-0000-000000000002','client2@example.test','{"primary_role":"talent","role":"admin"}'),
 ('00000000-0000-0000-0000-000000000003','talent1@example.test','{}'),
 ('00000000-0000-0000-0000-000000000004','talent2@example.test','{}'),
 ('00000000-0000-0000-0000-000000000005','new@example.test','{}');
select castly_test.eq((select count(*) from public.users where id='00000000-0000-0000-0000-000000000002' and primary_role is null),1,'signup metadata cannot assign role');
update public.users set primary_role='client' where id in ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002');
update public.users set primary_role='talent' where id in ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000004');
insert into public.talent_profiles(id,display_name,category_id,city,day_rate,published,portrait_path) values
 ('00000000-0000-0000-0000-000000000003','Published talent','actors','Mumbai',18000,true,'00000000-0000-0000-0000-000000000003/portrait.jpg'),
 ('00000000-0000-0000-0000-000000000004','Draft talent','models','Delhi',15000,false,'00000000-0000-0000-0000-000000000004/portrait.jpg');
insert into public.portfolio_assets(talent_id,path,label) values
 ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000003/work.jpg','Published work'),
 ('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000004/work.jpg','Draft work');
insert into public.bookings(id,client_id,talent_id,project_type,proposed_date,location,requirements,indicative_budget) values ('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000003','Editorial',current_date+1,'Mumbai','A sufficiently detailed project',18000);
insert into public.conversations(id,client_id,talent_id) values ('20000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000003');
insert into public.messages(conversation_id,sender_id,body) values ('20000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000003','Server fixture, not live delivery');
insert into public.saved_talent(user_id,talent_id) values ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000003');
insert into public.subscription_entitlements(user_id,entitlement) values ('00000000-0000-0000-0000-000000000001','test-read-only');
insert into storage.objects(bucket_id,name) values
 ('portfolio','00000000-0000-0000-0000-000000000003/portrait.jpg'),('portfolio','00000000-0000-0000-0000-000000000003/work.jpg'),
 ('portfolio','00000000-0000-0000-0000-000000000003/staged.jpg'),('portfolio','00000000-0000-0000-0000-000000000004/portrait.jpg'),
 ('private-media','00000000-0000-0000-0000-000000000001/private.pdf');
select castly_test.eq((select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relname in ('users','categories','talent_profiles','portfolio_assets','bookings','conversations','messages','saved_talent','subscription_entitlements') and c.relrowsecurity),9,'all nine application tables enable RLS');
set local role anon;
select castly_test.eq((select count(*) from public.talent_profiles),1,'anonymous discovery hides drafts');
select castly_test.eq((select count(*) from public.portfolio_assets),1,'anonymous portfolio hides drafts');
select castly_test.eq((select count(*) from public.categories),5,'public categories readable');
select castly_test.denied('select * from public.users','anonymous cannot read private accounts');
select castly_test.denied('select * from public.bookings','anonymous cannot read bookings');
select castly_test.denied('select * from public.messages','anonymous cannot read messages');
select castly_test.denied('select * from public.saved_talent','anonymous cannot read saved talent');
select castly_test.denied('select * from public.subscription_entitlements','anonymous cannot read entitlements');
select castly_test.denied($$select public.complete_onboarding('client','Forged')$$,'anonymous onboarding denied');
select castly_test.eq((select count(*) from storage.objects),2,'only registered published portfolio media accessible anonymously');
reset role;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
set local role authenticated;
select castly_test.eq((select count(*) from public.users),1,'client sees only their own account');
select castly_test.denied('select * from auth.users','client cannot read private Auth user directory');
select castly_test.eq((select count(*) from public.bookings),0,'outsider cannot read booking');
select castly_test.eq((select count(*) from public.conversations),0,'outsider cannot read conversation');
select castly_test.eq((select count(*) from public.messages),0,'outsider cannot read messages');
select castly_test.eq((select count(*) from public.saved_talent),0,'outsider cannot read private shortlist');
select castly_test.eq((select count(*) from public.subscription_entitlements),0,'outsider cannot read entitlement');
select castly_test.denied($$update public.users set primary_role='talent'$$,'role column update denied');
select castly_test.denied($$select public.complete_onboarding('talent','Changed role')$$,'onboarding cannot change an existing role');
select castly_test.affected($$update public.users set display_name='Forged' where id='00000000-0000-0000-0000-000000000001'$$,0,'cannot rename another account');
select castly_test.affected($$update public.talent_profiles set bio='Forged' where id='00000000-0000-0000-0000-000000000003'$$,0,'client cannot edit talent');
select castly_test.denied($$insert into public.talent_profiles(id,display_name,category_id,city,day_rate) values ('00000000-0000-0000-0000-000000000002','Client impersonation','actors','Mumbai',1)$$,'client cannot create talent profile');
select castly_test.denied($$insert into public.saved_talent(user_id,talent_id) values ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000004')$$,'cannot save on behalf of another user');
select castly_test.denied($$insert into public.saved_talent(user_id,talent_id) values ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000004')$$,'cannot save an unpublished profile');
select castly_test.denied($$insert into public.bookings(client_id,talent_id,project_type,proposed_date,location,requirements,indicative_budget) values ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000003','Editorial',current_date+1,'Mumbai','An impersonated project request',1)$$,'cannot impersonate a booking client');
select castly_test.denied($$insert into public.bookings(client_id,talent_id,project_type,proposed_date,location,requirements,indicative_budget,status) values ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000003','Editorial',current_date+1,'Mumbai','A forged confirmed booking',1,'accepted')$$,'cannot inject protected booking status');
select castly_test.denied($$update public.bookings set agreed_rate=1$$,'cannot change agreed rates');
select castly_test.denied($$update public.bookings set client_id='00000000-0000-0000-0000-000000000002'$$,'cannot reassign participants');
select castly_test.denied($$insert into public.conversations(client_id,talent_id) values ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000004')$$,'cannot impersonate a conversation participant');
select castly_test.denied($$insert into public.messages(conversation_id,sender_id,body) values ('20000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000003','Impersonated')$$,'ordinary message sends disabled');
select castly_test.denied($$insert into public.subscription_entitlements(user_id,entitlement) values ('00000000-0000-0000-0000-000000000002','free-admin')$$,'cannot grant entitlement');
select castly_test.denied($$update public.subscription_entitlements set valid_until=now()+interval '100 years'$$,'cannot extend entitlement');
select castly_test.denied($$delete from public.subscription_entitlements$$,'cannot delete entitlement');
select castly_test.denied($$insert into storage.objects(bucket_id,name) values ('portfolio','00000000-0000-0000-0000-000000000003/forged.jpg')$$,'cannot upload to another talent folder');
select castly_test.eq((select count(*) from storage.objects where bucket_id='private-media'),0,'cannot read another private file');
select castly_test.denied($$insert into public.bookings(client_id,talent_id,project_type,proposed_date,location,requirements,indicative_budget) values ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000003','Editorial',current_date-1,'Mumbai','A past-date project request',1)$$,'cannot request a past booking date');
select castly_test.denied($$insert into public.bookings(client_id,talent_id,project_type,proposed_date,location,requirements,indicative_budget) values ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000004','Editorial',current_date+1,'Delhi','An unpublished profile request',1)$$,'cannot request unpublished talent');
select castly_test.affected($$insert into public.bookings(client_id,talent_id,project_type,proposed_date,location,requirements,indicative_budget) values ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000003','Editorial',current_date+1,'Mumbai','A legitimate project request',18000)$$,1,'client can create own published-talent request');
select castly_test.eq((select count(*) from public.bookings where status='requested' and agreed_rate is null),1,'new request receives server-managed defaults');
select castly_test.affected($$insert into public.conversations(client_id,talent_id) values ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000003')$$,1,'client can create own conversation metadata');
select castly_test.affected($$delete from storage.objects where bucket_id='private-media'$$,0,'cannot delete another private file');
reset role;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
set local role authenticated;
select castly_test.eq((select count(*) from public.bookings),1,'booking client can read own request');
select castly_test.eq((select count(*) from public.messages),1,'conversation client can read messages');
select castly_test.eq((select count(*) from public.subscription_entitlements),1,'owner can read entitlement');
select castly_test.eq((select count(*) from public.saved_talent),1,'owner can read shortlist');
select castly_test.eq((select count(*) from storage.objects where bucket_id='private-media'),1,'owner can read private file');
select castly_test.affected($$update public.users set display_name='Updated own account'$$,1,'owner can edit account name');
select castly_test.denied($$update public.bookings set status='accepted'$$,'booking owner still cannot change status');
select castly_test.affected($$delete from public.saved_talent where talent_id='00000000-0000-0000-0000-000000000003'$$,1,'owner can remove saved talent');
reset role;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000003',true);
set local role authenticated;
select castly_test.eq((select count(*) from public.bookings),2,'relevant talent can read booking');
select castly_test.eq((select count(*) from public.messages),1,'conversation talent can read messages');
select castly_test.affected($$update public.portfolio_assets set label='Updated own work' where talent_id='00000000-0000-0000-0000-000000000003'$$,1,'owner can edit own portfolio label');
select castly_test.denied($$update public.portfolio_assets set path='00000000-0000-0000-0000-000000000004/forged.jpg'$$,'portfolio path cannot be reassigned');
select castly_test.affected($$update public.talent_profiles set bio='Updated own biography' where id='00000000-0000-0000-0000-000000000003'$$,1,'talent can edit own profile');
select castly_test.affected($$update public.talent_profiles set bio='Forged' where id='00000000-0000-0000-0000-000000000004'$$,0,'talent cannot edit another profile');
select castly_test.affected($$delete from public.portfolio_assets where talent_id='00000000-0000-0000-0000-000000000004'$$,0,'talent cannot delete another portfolio');
select castly_test.denied($$insert into public.portfolio_assets(talent_id,path,label) values ('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000004/forged.jpg','Forged')$$,'talent cannot insert another portfolio');
select castly_test.denied($$update public.talent_profiles set id='00000000-0000-0000-0000-000000000004'$$,'profile owner ID immutable to client');
select castly_test.affected($$update public.talent_profiles set published=false where id='00000000-0000-0000-0000-000000000003'$$,1,'talent can unpublish own profile');
reset role;
select set_config('request.jwt.claim.sub','',true);
set local role anon;
select castly_test.eq((select count(*) from public.talent_profiles),0,'unpublish removes public discovery');
select castly_test.eq((select count(*) from public.portfolio_assets),0,'unpublish hides public portfolio metadata');
select castly_test.eq((select count(*) from storage.objects),0,'unpublish prevents new public media reads');
reset role;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000004',true);
set local role authenticated;
select castly_test.eq((select count(*) from public.talent_profiles),1,'talent can read own draft');
select castly_test.eq((select count(*) from public.portfolio_assets),1,'talent can read own draft portfolio');
select castly_test.affected($$insert into storage.objects(bucket_id,name) values ('portfolio','00000000-0000-0000-0000-000000000004/own-upload.jpg')$$,1,'talent can upload in own folder');
select castly_test.affected($$update storage.objects set name='00000000-0000-0000-0000-000000000003/forged.jpg'$$,0,'object moves/overwrites denied');
reset role;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000005',true);
set local role authenticated;
select castly_test.eq((select count(*) from public.complete_onboarding('talent','New talent')),1,'authenticated user can onboard once');
select castly_test.affected($$insert into public.talent_profiles(id,display_name,category_id,city,day_rate) values ('00000000-0000-0000-0000-000000000005','New talent','actors','Mumbai',20000)$$,1,'talent role can create own profile');
select castly_test.denied($$select public.complete_onboarding('client','Changed role')$$,'role cannot change after onboarding');
rollback;
