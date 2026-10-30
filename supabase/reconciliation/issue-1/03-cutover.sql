-- SEPARATE APPROVAL: API regression + real JWT/pooler + mobile cutover readiness required.
-- Never run during preparation or while production mobile still needs direct business writes.
begin;
do $$begin
 if current_setting('castly.reconciliation_cutover_approved',true) is distinct from 'issue-1-cutover-approved' then
  raise exception 'Separate owner cutover approval required';
 end if;
 if to_regrole('castly_api') is null or to_regprocedure('public.api_message_send_guard()') is null then
  raise exception 'Run reviewed preparation and API regression before cutover';
 end if;
end$$;
-- Capture requests created by the legacy mobile app after preparation; keep timestamps intact.
\ir backfill-booking-names.sql
-- Table REVOKE alone leaves column ACLs active. Revoke every existing column write,
-- including unexpected historical grants, from PUBLIC as well as ordinary roles.
do $$declare t text; c record; f record;begin
 foreach t in array array['users','categories','talent_profiles','portfolio_assets','bookings','conversations','messages','saved_talent','subscription_entitlements'] loop
  execute format('revoke insert,update,delete,truncate,references,trigger on public.%I from public,anon,authenticated',t);
  for c in select attname from pg_attribute where attrelid=to_regclass('public.'||t) and attnum>0 and not attisdropped loop
   execute format('revoke insert(%I),update(%I),references(%I) on public.%I from public,anon,authenticated',c.attname,c.attname,c.attname,t);
  end loop;
 end loop;
 -- Functions may be absent in hosted baseline: never introduce dummy SECURITY DEFINER RPCs.
 for f in select p.oid::regprocedure signature from pg_proc p join pg_namespace n on n.oid=p.pronamespace
  where n.nspname='public' and p.proname in ('complete_onboarding','respond_to_booking','cancel_booking') loop
  execute format('revoke all on function %s from public,anon,authenticated,castly_api',f.signature);
 end loop;
end$$;
drop policy if exists participant_message_send on public.messages;
-- SELECT grants/policies for Realtime and ALL Storage ACLs/policies are intentionally retained.
commit;
