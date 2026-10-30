-- READ ONLY. Run with psql -X -v ON_ERROR_STOP=1; retain output privately for owner review.
-- Do not include auth.users data, passwords, connection strings, tokens or private messages.
begin read only;
select current_database(), version(), current_user;
select schemaname,tablename,tableowner,rowsecurity from pg_tables
 where schemaname in ('public','storage') order by 1,2;
select table_schema,table_name,column_name,data_type,udt_name,is_nullable,column_default
 from information_schema.columns where table_schema in ('public','storage') order by 1,2,ordinal_position;
select n.nspname,c.relname,k.conname,k.contype,pg_get_constraintdef(k.oid,true) definition,k.convalidated
 from pg_constraint k join pg_class c on c.oid=k.conrelid join pg_namespace n on n.oid=c.relnamespace
 where n.nspname in ('public','storage') order by 1,2,3;
select schemaname,tablename,indexname,indexdef from pg_indexes where schemaname in ('public','storage') order by 1,2,3;
select schemaname,tablename,policyname,permissive,roles,cmd,qual,with_check
 from pg_policies where schemaname in ('public','storage') order by 1,2,3;
select n.nspname,c.relname,t.tgname,pg_get_triggerdef(t.oid,true)
 from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace
 where not t.tgisinternal and n.nspname in ('public','auth','storage') order by 1,2,3;
select p.oid::regprocedure function,p.prosecdef security_definer,p.proconfig,p.proacl,pg_get_functiondef(p.oid)
 from pg_proc p join pg_namespace n on n.oid=p.pronamespace
 where n.nspname='public' or p.oid=to_regprocedure('auth.uid()') or p.oid=to_regprocedure('storage.foldername(text)') order by 1::text;
select defaclrole::regrole,defaclnamespace::regnamespace,defaclobjtype,defaclacl from pg_default_acl order by 1::text;
select n.nspname,c.relname,c.relkind,c.relacl,a.attname,a.attacl
 from pg_class c join pg_namespace n on n.oid=c.relnamespace
 left join pg_attribute a on a.attrelid=c.oid and a.attnum>0 and not a.attisdropped
 where n.nspname in ('public','auth','storage') and c.relkind in ('r','p','S') order by 1,2,a.attnum;
select nspname,nspacl from pg_namespace where nspname in ('public','auth','storage');
select rolname,rolcanlogin,rolsuper,rolinherit,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls
 from pg_roles where rolname in ('anon','authenticated','castly_api');
select roleid::regrole,member::regrole,admin_option from pg_auth_members
 where roleid=to_regrole('castly_api') or member=to_regrole('castly_api');
select * from pg_publication;
select pubname,schemaname,tablename from pg_publication_tables order by 1,2,3;
select id,name,public,file_size_limit,allowed_mime_types from storage.buckets order by id;
-- Dynamic ledger read: never fabricate or mark repository history as applied.
select to_regclass('supabase_migrations.schema_migrations') as ledger;
do $$declare r record;begin
 if to_regclass('supabase_migrations.schema_migrations') is not null then
  for r in execute 'select version from supabase_migrations.schema_migrations order by version' loop
   raise notice 'ledger version: %',r.version;
  end loop;
 end if;
end$$;
rollback;
