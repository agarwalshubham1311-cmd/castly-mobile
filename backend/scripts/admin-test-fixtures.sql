-- Disposable PostgreSQL test data ONLY. No real account is provisioned.
do $$begin if current_database()<>'castly_spring_test' then raise exception 'Only disposable castly_spring_test is permitted';end if;end$$;
truncate admin_private.principals,admin_private.authorizations,admin_private.invitations,admin_private.sessions,admin_private.audit,admin_private.rate_limits restart identity;
begin;
set local castly.admin_bootstrap_reviewed='M7.1-owner-approved';
insert into admin_private.principals(user_id,roles,state) values
('00000000-0000-0000-0000-000000000031',array['SUPER_ADMIN'],'active'),
('00000000-0000-0000-0000-000000000032',array['MODERATOR'],'active'),
('00000000-0000-0000-0000-000000000033',array['SUPPORT_AGENT'],'active'),
('00000000-0000-0000-0000-000000000034',array['ANALYST'],'active'),
('00000000-0000-0000-0000-000000000035',array['FINANCE_ADMIN'],'active'),
('00000000-0000-0000-0000-000000000036',array['OPERATIONS_ADMIN'],'active'),
('00000000-0000-0000-0000-000000000037',array['SUPPORT_AGENT'],'revoked'),
('00000000-0000-0000-0000-000000000039',array['SUPER_ADMIN'],'active');
commit;
