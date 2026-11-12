-- OWNER REVIEW ONLY. Read-only catalog inventory, no data rows or migrations.
-- Run with an approved read-only catalog connection; never use app/service credentials in source.
begin transaction read only;
select version,to_jsonb(m)->>'name' as name from supabase_migrations.schema_migrations m order by version;
select n.nspname,c.relname,c.relowner::regrole as owner,c.relrowsecurity,c.relforcerowsecurity
 from pg_class c join pg_namespace n on n.oid=c.relnamespace
 where n.nspname in('public','private','trust_private') and c.relkind in('r','p') order by 1,2;
select schemaname,tablename,policyname,permissive,roles,cmd,qual,with_check
 from pg_policies where schemaname in('public','private','trust_private','storage') order by 1,2,3;
select table_schema,table_name,grantee,privilege_type from information_schema.role_table_grants
 where grantee in('castly_api','authenticated','anon','PUBLIC') order by 1,2,3,4;
select table_schema,table_name,column_name,grantee,privilege_type from information_schema.column_privileges
 where grantee in('castly_api','authenticated','anon','PUBLIC') order by 1,2,3,4,5;
select n.nspname,p.proname,pg_get_function_identity_arguments(p.oid) as arguments,
 p.proowner::regrole as owner,p.prosecdef,p.proconfig,p.proacl,
 encode(sha256(convert_to(pg_get_functiondef(p.oid),'UTF8')),'hex') as source_sha256,
 pg_get_functiondef(p.oid) as definition
 from pg_proc p join pg_namespace n on n.oid=p.pronamespace
 where p.prokind='f' and n.nspname in('private','trust_private','public')
 and (n.nspname in('private','trust_private') or p.proname like '%booking%' or p.proname like '%message%') order by 1,2,3;
select n.nspname,c.relname,t.tgname,pg_get_triggerdef(t.oid) as definition
 from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace
 where not t.tgisinternal and n.nspname in('public','private','trust_private') order by 1,2,3;
select schemaname,tablename,indexname,indexdef from pg_indexes
 where schemaname in('public','private','trust_private') order by 1,2,3;
select rolname,rolsuper,rolbypassrls,rolinherit from pg_roles where rolname='castly_api';
select r.rolname as member,g.rolname as inherited_role from pg_auth_members m
 join pg_roles r on r.oid=m.member join pg_roles g on g.oid=m.roleid where r.rolname='castly_api';
select has_schema_privilege('castly_api','auth','USAGE') as auth_usage,
 has_schema_privilege('castly_api','private','CREATE') as private_create;
select id,public,file_size_limit,allowed_mime_types from storage.buckets where id='trust-evidence';
rollback;
