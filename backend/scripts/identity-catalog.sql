-- Disposable-fixture metadata only. Used before/after the forward identity migration.
select jsonb_build_object(
 'policies',(select jsonb_agg(to_jsonb(p) order by schemaname,tablename,policyname)
   from pg_policies p where schemaname in ('public','storage')),
 'guards',(select jsonb_agg(jsonb_build_object('name',p.proname,'body',p.prosrc,
   'definer',p.prosecdef,'owner',pg_get_userbyid(p.proowner),'settings',p.proconfig,
   'acl',p.proacl::text) order by p.proname)
   from pg_proc p join pg_namespace n on n.oid=p.pronamespace
   where n.nspname='public' and p.proname in ('api_booking_transition_guard','api_message_send_guard'))
)::text;
