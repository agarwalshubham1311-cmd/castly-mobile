-- READ ONLY: current hosted definitions are evidence; repository definitions may differ.
-- No role switches, JWT claim impersonation, UPDATE probes, or hosted DDL.
begin read only;

select t.tgname, t.tgenabled, pg_get_triggerdef(t.oid, true) as trigger_definition,
       p.oid::regprocedure as function_name, pg_get_userbyid(p.proowner) as function_owner,
       case when p.prosecdef then 'DEFINER' else 'INVOKER' end as security_mode,
       p.proconfig as function_settings, p.proacl as execute_acl,
       has_function_privilege('castly_api', p.oid, 'EXECUTE') as api_can_execute,
       pg_get_functiondef(p.oid) as function_definition
from pg_trigger t join pg_proc p on p.oid=t.tgfoid
where t.tgrelid='public.bookings'::regclass and not t.tgisinternal
order by t.tgname;

-- Includes restrictive policies: one permissive participant policy is not the whole decision.
select tablename, policyname, permissive, roles, cmd, qual, with_check
from pg_policies where schemaname='public' and tablename in ('bookings','users')
order by tablename, policyname;

-- The invoker guard reads public.users.primary_role and calls auth.uid().
select has_schema_privilege('castly_api','auth','USAGE') as auth_usage,
       has_function_privilege('castly_api','auth.uid()','EXECUTE') as auth_uid_execute,
       has_column_privilege('castly_api','public.users','id','SELECT') as actor_id_read,
       has_column_privilege('castly_api','public.users','primary_role','SELECT') as actor_role_read,
       has_table_privilege('castly_api','public.bookings','SELECT') as booking_select,
       has_table_privilege('castly_api','public.bookings','UPDATE') as booking_table_update,
       has_column_privilege('castly_api','public.bookings','status','UPDATE') as status_update,
       has_column_privilege('castly_api','public.bookings','updated_at','UPDATE') as timestamp_update;

-- Replica identity can impose additional SELECT requirements, but not table-wide UPDATE.
select c.relrowsecurity, c.relforcerowsecurity, c.relreplident
from pg_class c where c.oid='public.bookings'::regclass;

-- Caller of attached triggers need not have EXECUTE on those trigger functions at runtime.
-- Functions called inside their bodies still need privileges under the effective execution role.
-- PL/pgSQL body dependencies are not comprehensively represented by pg_depend;
-- inspect the full definitions and the correlated PostgreSQL error/CONTEXT for nested SQL.
rollback;
