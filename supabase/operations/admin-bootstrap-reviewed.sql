-- OWNER-ONLY runbook artifact. Never run from application/CI/migration startup.
-- Requires two-person approval of identity, existing verified TOTP and least privilege.
\set ON_ERROR_STOP on
\if :{?bootstrap_approved}
\else
\echo 'STOP: explicit bootstrap_approved variable is required'
\quit 3
\endif
select :'bootstrap_approved'='M7.1-owner-approved' as approved \gset
\if :approved
\else
\echo 'STOP: approval marker mismatch'
\quit 3
\endif
\if :{?admin_user}
\else
\echo 'STOP: reviewed existing account UUID required'
\quit 3
\endif
begin;
do $$begin
 if current_user is distinct from (select pg_get_userbyid(relowner) from pg_class where oid='admin_private.principals'::regclass) then raise insufficient_privilege using message='Run only as reviewed migration owner';end if;
end$$;
set local castly.admin_bootstrap_reviewed='M7.1-owner-approved';
set local request.admin.reason='Separately approved initial administrator bootstrap';
insert into admin_private.principals(user_id,roles,state) values(:'admin_user'::uuid,array['SUPER_ADMIN'],'active');
-- Review inserted row/audit in this owner transaction before approving execution of this artifact.
select user_id,roles,state,version from admin_private.principals where user_id=:'admin_user'::uuid;
commit;
