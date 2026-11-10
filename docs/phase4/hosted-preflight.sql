-- Owner-run read-only catalog inventory. Review output offline; do not run Phase 4 DDL from this file.
select version from supabase_migrations.schema_migrations order by version;
select n.nspname as schema_name,c.relname as object_name,c.relkind,c.relowner::regrole as owner,c.relrowsecurity,c.relforcerowsecurity
from pg_class c join pg_namespace n on n.oid=c.relnamespace
where n.nspname in ('public','private','trust_private','storage','auth')
  and c.relname in ('bookings','messages','conversations','portfolio_assets','users','subscription_grants','subscription_entitlements','verification_requests','verification_evidence','verification_decisions','booking_completion_confirmations','user_reviews','review_reports','user_blocks','safety_reports','moderation_cases','moderation_actions','moderation_appeals','booking_disputes','dispute_evidence','dispute_decisions','trust_signal_snapshots','evidence_policies','reviewers','buckets','objects')
order by 1,2;
select n.nspname,c.relname,t.tgname,pg_get_triggerdef(t.oid) as definition
from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace
where not t.tgisinternal and n.nspname in ('public','private','trust_private','storage')
  and c.relname in ('bookings','messages','conversations','portfolio_assets','user_reviews','moderation_actions','booking_disputes','dispute_evidence')
order by 1,2,3;
select n.nspname,c.relname,k.conname,pg_get_constraintdef(k.oid) as definition
from pg_constraint k join pg_class c on c.oid=k.conrelid join pg_namespace n on n.oid=c.relnamespace
where n.nspname in ('public','private','trust_private','storage')
  and c.relname in ('bookings','messages','conversations','portfolio_assets','user_reviews','moderation_actions','booking_disputes','dispute_evidence')
order by 1,2,3;
select schemaname,tablename,policyname,roles,cmd,qual,with_check from pg_policies
where schemaname in ('public','private','trust_private','storage')
  and tablename in ('bookings','messages','conversations','portfolio_assets','user_reviews','moderation_actions','booking_disputes','dispute_evidence','objects')
order by 1,2,3;
select table_schema,table_name,grantee,privilege_type from information_schema.role_table_grants
where table_schema in ('public','private','trust_private','storage')
  and grantee in ('castly_api','authenticated','anon','PUBLIC') order by 1,2,3,4;
select table_schema,table_name,column_name,grantee,privilege_type from information_schema.column_privileges
where table_schema in ('public','private','trust_private','storage')
  and grantee in ('castly_api','authenticated','anon','PUBLIC') order by 1,2,3,4,5;
select n.nspname,p.proname,pg_get_function_identity_arguments(p.oid) as arguments,p.prosecdef,pg_get_functiondef(p.oid) as source
from pg_proc p join pg_namespace n on n.oid=p.pronamespace
where n.nspname in ('public','private','trust_private') and
 (p.proname like '%booking%' or p.proname like '%message%' or p.proname like '%portfolio%' or p.proname like '%request_user_id%' or p.proname like '%subscription%')
order by 1,2,3;
select rolname,rolsuper,rolbypassrls,rolinherit from pg_roles where rolname in ('castly_api','authenticated','anon');
select bucket_id,name,owner,metadata from storage.objects where bucket_id='trust-evidence' limit 0;
select id,name,public,file_size_limit,allowed_mime_types from storage.buckets where id='trust-evidence';
