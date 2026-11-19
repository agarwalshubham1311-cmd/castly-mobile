begin;
do $$begin
 if (select public from storage.buckets where id='trust-evidence') then raise exception 'Evidence bucket is public';end if;
 if exists(select 1 from trust_private.evidence_policies where approved or accepting_uploads) then raise exception 'Evidence policy was activated by migration';end if;
 if exists(select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='trust_private' and p.prosecdef) then raise exception 'Privileged trust helper found';end if;
 if exists(select 1 from pg_roles where rolname='castly_api' and (rolsuper or rolbypassrls)) then raise exception 'Runtime role bypasses RLS';end if;
 if not exists(select 1 from pg_policies where schemaname='public' and tablename='dispute_evidence' and policyname='evidence_read' and qual like '%30 days%' and qual not like '%FINAL_RESOLVED%') then raise exception 'Reviewer evidence window missing';end if;
 raise notice 'PASS Phase4 private evidence catalog and reviewer window';
end$$;
set local role authenticated;
do $$begin
 begin
  insert into public.booking_disputes(booking_id,owner_id,counterparty_id,statement,consent_version) values(gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),repeat('x',20),'dispute-text-v1');
  raise exception 'Authenticated client inserted a dispute directly';
 exception when insufficient_privilege then raise notice 'PASS authenticated dispute write denied';end;
 begin
  insert into public.dispute_evidence(dispute_id,owner_id,kind,consent_version) values(gen_random_uuid(),gen_random_uuid(),'note','dispute-text-v1');
  raise exception 'Authenticated client inserted evidence directly';
 exception when insufficient_privilege then raise notice 'PASS authenticated evidence write denied';end;
 begin
  insert into trust_private.reviewers(user_id,scope) values(gen_random_uuid(),'disputes');
  raise exception 'Authenticated client provisioned reviewer';
 exception when insufficient_privilege then raise notice 'PASS reviewer provisioning denied';end;
end$$;
rollback;
