-- Synthetic local browser fixtures only. No hosted execution.
do $$begin if current_database()<>'castly_spring_test' then raise exception 'Only disposable castly_spring_test is permitted';end if;end$$;
insert into public.talent_profiles(id,display_name,category_id,city,day_rate,published) values('00000000-0000-0000-0000-000000000088','Lavender fixture talent','actors','Pune',7500,false);
insert into public.portfolio_assets(id,talent_id,path,label) values('72800000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000088','00000000-0000-0000-0000-000000000088/review.jpg','Synthetic review asset');
insert into public.verification_requests(id,user_id,statement,consent_version) values('72800000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000088','Consented portfolio review of the local synthetic fixture.','profile-review-v1');
insert into public.verification_evidence(request_id,revision,owner_id,portfolio_asset_id,portfolio_path) values('72800000-0000-0000-0000-000000000002',1,'00000000-0000-0000-0000-000000000088','72800000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000088/review.jpg');
insert into trust_private.reviewers(user_id,scope) values('00000000-0000-0000-0000-000000000031','verification');
