-- READ ONLY. Run only via an approved read-only connection; do not paste credentials into logs.
begin read only;
select policyname,roles,cmd,qual,with_check from pg_policies
 where schemaname='public' and tablename in ('bookings','conversations','users') order by tablename,policyname;
select c.relname,t.tgname,pg_get_triggerdef(t.oid,true) from pg_trigger t join pg_class c on c.oid=t.tgrelid
 where c.oid in ('public.bookings'::regclass,'public.conversations'::regclass) and not t.tgisinternal;
select p.oid::regprocedure,p.prosecdef,p.proconfig,pg_get_functiondef(p.oid) from pg_proc p
 where p.oid in (select tgfoid from pg_trigger where tgrelid='public.bookings'::regclass and not tgisinternal);
select has_table_privilege('castly_api','public.bookings','SELECT') booking_read,
 has_column_privilege('castly_api','public.bookings','status','UPDATE') booking_status_update,
 has_table_privilege('castly_api','public.conversations','SELECT') conversation_read,
 has_column_privilege('castly_api','public.conversations','client_id','INSERT') conversation_client_insert,
 has_column_privilege('castly_api','public.conversations','talent_id','INSERT') conversation_talent_insert;
select a.attname,pg_get_expr(d.adbin,d.adrelid) as default_expression
 from pg_attrdef d join pg_attribute a on a.attrelid=d.adrelid and a.attnum=d.adnum
 where d.adrelid='public.conversations'::regclass;
select p.oid::regprocedure as default_function,has_function_privilege('castly_api',p.oid,'EXECUTE') can_execute,
 has_schema_privilege('castly_api',n.oid,'USAGE') schema_usage
 from pg_attrdef d join pg_depend dep on dep.classid='pg_attrdef'::regclass and dep.objid=d.oid and dep.refclassid='pg_proc'::regclass
 join pg_proc p on p.oid=dep.refobjid join pg_namespace n on n.oid=p.pronamespace
 where d.adrelid='public.conversations'::regclass;
-- No account/message data or tokens returned. Match the server's reference/operation/sqlstate log.
rollback;
