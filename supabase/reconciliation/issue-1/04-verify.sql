-- READ ONLY catalog verification after preparation, and again after separately approved cutover.
-- Also run actor regressions on staging; catalog checks are not a substitute for behavior tests.
begin read only;
do $$declare t text; c record; role_name text; cutover boolean;
begin
 cutover := coalesce(current_setting('castly.reconciliation_verify_cutover',true),'')='yes';
 if not exists(select 1 from pg_roles where rolname='castly_api' and not rolsuper and not rolbypassrls
  and not rolcreatedb and not rolcreaterole and not rolreplication and not rolinherit)
 or exists(select 1 from pg_auth_members where member=to_regrole('castly_api') or roleid=to_regrole('castly_api'))
 or exists(select 1 from pg_class where relowner=to_regrole('castly_api'))
 or has_schema_privilege('castly_api','public','CREATE')
 or has_database_privilege('castly_api',current_database(),'CREATE') then raise exception 'Unsafe API role';end if;
 foreach t in array array['users','categories','talent_profiles','portfolio_assets','bookings','conversations','messages','saved_talent','subscription_entitlements'] loop
  if not has_table_privilege('castly_api','public.'||t,'SELECT') then raise exception 'API SELECT missing: %',t;end if;
  if cutover then
   foreach role_name in array array['anon','authenticated'] loop
    if has_table_privilege(role_name,'public.'||t,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') then
     raise exception 'Ordinary table write still granted: % %',role_name,t;
    end if;
    for c in select attname from pg_attribute where attrelid=to_regclass('public.'||t) and attnum>0 and not attisdropped loop
     if has_column_privilege(role_name,'public.'||t,c.attname,'INSERT,UPDATE,REFERENCES') then
      raise exception 'Ordinary column write still granted: % %.%',role_name,t,c.attname;
     end if;
    end loop;
   end loop;
  end if;
 end loop;
 if not has_column_privilege('castly_api','public.bookings','status','UPDATE')
 or has_table_privilege('castly_api','public.bookings','UPDATE')
 or has_column_privilege('castly_api','public.bookings','agreed_rate','UPDATE')
 or has_table_privilege('castly_api','public.subscription_entitlements','INSERT,UPDATE,DELETE')
 or has_table_privilege('castly_api','auth.users','SELECT') then raise exception 'API grants mismatch';end if;
 for c in select p.oid,p.oid::regprocedure signature from pg_proc p join pg_namespace n on n.oid=p.pronamespace
  where n.nspname='public' and p.proname in ('respond_to_booking','cancel_booking','complete_onboarding') loop
  if has_function_privilege('castly_api',c.oid,'EXECUTE') then raise exception 'API can execute old RPC %',c.signature;end if;
  if cutover and (has_function_privilege('authenticated',c.oid,'EXECUTE') or has_function_privilege('anon',c.oid,'EXECUTE')) then
   raise exception 'Ordinary RPC still enabled: %',c.signature;
  end if;
 end loop;
 if exists(select 1 from public.bookings where client_name='' or talent_name='') then raise exception 'Unresolved booking name snapshots';end if;
 if (select count(*) from pg_publication_tables where pubname='supabase_realtime' and schemaname='public'
  and tablename in ('bookings','conversations','messages'))<>3 then raise exception 'Realtime publication membership missing';end if;
 if exists(select 1 from storage.buckets where id in ('portfolio','private-media') and public) then raise exception 'Unexpected public Storage bucket';end if;
 raise notice 'PASS reconciliation catalog verification; cutover=%',cutover;
end$$;
rollback;
