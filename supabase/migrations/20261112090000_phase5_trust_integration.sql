-- Issue #10: forward-only Phase 4 / Phase 5 integration. No existing grants,
-- policies, triggers or historical migration bodies are replaced.
begin;
do $$begin
 if current_user='castly_api' or exists(select 1 from pg_roles
   where rolname='castly_api' and (rolsuper or rolbypassrls))
   or exists(select 1 from pg_auth_members where member='castly_api'::regrole) then
  raise exception 'Migration owner and dedicated nonprivileged runtime role required';
 end if;
 if not has_function_privilege('castly_api','trust_private.blocked(uuid,uuid)','EXECUTE')
   or not has_function_privilege('castly_api','private.request_user_id()','EXECUTE')
   or not has_table_privilege('castly_api','public.user_blocks','SELECT') then
  raise exception 'Reviewed Phase 4 block and private identity contracts required';
 end if;
end$$;
-- A single public eligibility predicate for every Phase 5 creator read.
-- Phase 4 has no creator suspension state: profile withdrawal/moderation uses
-- published=false. A warning/report/dispute is NOT an adjudicated profile ban.
-- Free creators remain eligible; premium discovery is not a mandatory paywall.
create function private.discovery_talent_visible(talent uuid) returns boolean
language sql stable security invoker set search_path='' as $$
 select exists(select 1 from public.talent_profiles t
  where t.id=talent and t.published
  and not trust_private.blocked(private.request_user_id(),t.id))
$$;
revoke all on function private.discovery_talent_visible(uuid) from public,anon,authenticated;
grant execute on function private.discovery_talent_visible(uuid) to castly_api;
-- Restrictive read policies compose with, rather than broaden, Phase 5 owner
-- and public-consent policies. Owners retain editing/withdrawal of their data.
create policy discovery_preference_trust_gate on public.talent_discovery_preferences
 as restrictive for select to castly_api
 using(talent_id=private.request_user_id() or private.discovery_talent_visible(talent_id));
create policy availability_trust_gate on public.talent_availability_days
 as restrictive for select to castly_api
 using(talent_id=private.request_user_id() or private.discovery_talent_visible(talent_id));
create policy busy_trust_gate on public.talent_booking_busy_days
 as restrictive for select to castly_api
 using(pg_trigger_depth()>0 or talent_id=private.request_user_id()
  or private.discovery_talent_visible(talent_id));
commit;
