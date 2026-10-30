-- Synthetic legacy data to prove preparation/cutover do not drop marketplace or media rows.
insert into auth.users(id,raw_user_meta_data) values
 ('90000000-0000-0000-0000-000000000001','{"display_name":"Client seed"}'),
 ('90000000-0000-0000-0000-000000000002','{"display_name":"Talent seed"}'),
 ('90000000-0000-0000-0000-000000000003','{"display_name":"Outsider seed"}'),
 ('90000000-0000-0000-0000-000000000004','{"display_name":"Draft seed"}');
update public.users set primary_role='client' where id='90000000-0000-0000-0000-000000000001';
update public.users set primary_role='talent' where id in ('90000000-0000-0000-0000-000000000002','90000000-0000-0000-0000-000000000004');
insert into public.talent_profiles(id,display_name,category_id,city,day_rate,published,portrait_path) values
 ('90000000-0000-0000-0000-000000000002','Talent seed','actors','Mumbai',18000,true,'90000000-0000-0000-0000-000000000002/portrait.jpg'),
 ('90000000-0000-0000-0000-000000000004','Draft seed','models','Delhi',12000,false,'90000000-0000-0000-0000-000000000004/portrait.jpg');
insert into public.portfolio_assets(talent_id,path,label) values
 ('90000000-0000-0000-0000-000000000002','90000000-0000-0000-0000-000000000002/work.jpg','Published work'),
 ('90000000-0000-0000-0000-000000000004','90000000-0000-0000-0000-000000000004/work.jpg','Draft work');
insert into public.bookings(id,client_id,talent_id,project_type,proposed_date,location,requirements,indicative_budget,agreed_rate,status,created_at,updated_at) values
 ('91000000-0000-0000-0000-000000000001','90000000-0000-0000-0000-000000000001','90000000-0000-0000-0000-000000000002','Legacy editorial',current_date+2,'Mumbai','A preserved legacy project',18000,17000,'accepted','2020-01-01T00:00:00Z','2020-01-02T00:00:00Z');
insert into public.conversations(id,client_id,talent_id) values ('92000000-0000-0000-0000-000000000001','90000000-0000-0000-0000-000000000001','90000000-0000-0000-0000-000000000002');
insert into public.messages(conversation_id,sender_id,body) values ('92000000-0000-0000-0000-000000000001','90000000-0000-0000-0000-000000000002','Preserved legacy history');
insert into public.saved_talent(user_id,talent_id) values('90000000-0000-0000-0000-000000000001','90000000-0000-0000-0000-000000000002');
insert into public.subscription_entitlements(user_id,entitlement) values('90000000-0000-0000-0000-000000000001','preserved');
insert into storage.objects(bucket_id,name) values ('portfolio','90000000-0000-0000-0000-000000000002/portrait.jpg'),('portfolio','90000000-0000-0000-0000-000000000002/work.jpg'),('portfolio','90000000-0000-0000-0000-000000000004/portrait.jpg'),('private-media','90000000-0000-0000-0000-000000000001/contract.pdf');
