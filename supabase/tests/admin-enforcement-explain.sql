-- Optional, disposable benchmark only. 10,000 synthetic accounts / 1,000 restrictions.
-- Every fixture and statistics change rolls back; never run against hosted services.
begin;
revoke usage on schema auth from castly_api;
insert into auth.users(id,raw_user_meta_data) select md5('m72-benchmark:'||g)::uuid,jsonb_build_object('display_name','Synthetic account '||g) from generate_series(1,10000) g;
insert into enforcement_private.accounts(user_id,state,denied,until_at) select md5('m72-benchmark:'||g)::uuid,'RESTRICTED',array['discovery'],clock_timestamp()+interval '1 day' from generate_series(1,1000) g;
insert into auth.users(id) values(md5('m72-benchmark-admin')::uuid);
set local castly.admin_bootstrap_reviewed='M7.1-owner-approved';
insert into admin_private.principals(user_id,roles,state) values(md5('m72-benchmark-admin')::uuid,array['SUPER_ADMIN'],'active');
analyze admin_private.user_directory;analyze enforcement_private.accounts;analyze enforcement_private.capabilities;
select set_config('request.jwt.claim.sub',md5('m72-benchmark-admin')::uuid::text,true),set_config('request.admin.aal2','true',true),set_config('request.admin.mfa_at',now()::text,true),set_config('request.admin.auth_session','73000000-0000-0000-0000-000000000099',true);
set local role castly_api;
insert into admin_private.sessions(user_id,auth_session_id,principal_version,ip_fingerprint,agent_family) values(private.request_user_id(),'73000000-0000-0000-0000-000000000099',1,'fixture','other') returning id as session_id \gset
select set_config('request.admin.session',:'session_id',true);
explain(analyze,buffers,format json)
select u.user_id,u.display_name from admin_private.user_directory u
where u.user_id>'00000000-0000-0000-0000-000000000000' and exists(
 select 1 from enforcement_private.accounts e where e.user_id=u.user_id and e.state='RESTRICTED' and (e.until_at is null or e.until_at>clock_timestamp()))
order by u.user_id limit 26;
rollback;
