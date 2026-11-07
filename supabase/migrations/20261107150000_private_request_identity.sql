-- REVIEW REQUIRED. Forward-only API fallback; no hosted application without approval.
-- Does not grant/revoke privileges on auth, change role memberships or disable RLS.
begin;
set local search_path=pg_catalog;

do $preflight$
declare api oid := (select oid from pg_roles where rolname='castly_api');
begin
 if api is null or exists(select 1 from pg_roles where oid=api and (rolsuper or rolbypassrls))
 or exists(select 1 from pg_auth_members where member=api) then
  raise exception 'Dedicated nonprivileged castly_api role required';
 end if;
 if exists(select 1 from pg_namespace where nspname='private') then
  if exists(select 1 from pg_namespace where nspname='private' and nspowner<>(select oid from pg_roles where rolname=current_user))
  or has_schema_privilege('anon','private','USAGE')
  or has_schema_privilege('authenticated','private','USAGE')
  or has_schema_privilege('castly_api','private','CREATE') then
   raise exception 'Existing private namespace requires separate ownership/ACL review';
  end if;
  if not has_schema_privilege('castly_api','private','USAGE') and (
    exists(select 1 from pg_proc where pronamespace=(select oid from pg_namespace where nspname='private'))
    or exists(select 1 from pg_class where relnamespace=(select oid from pg_namespace where nspname='private'))
    or exists(select 1 from pg_type where typnamespace=(select oid from pg_namespace where nspname='private'))
  ) then
   raise exception 'Granting access to a populated private namespace requires separate review';
  end if;
 else
  create schema private;
 end if;
end $preflight$;
revoke all on schema private from public,anon,authenticated;
grant usage on schema private to castly_api;

-- Owner is the trusted application migration role, never the runtime castly_api login.
-- Settings are supplied by Spring's transaction-local set_config, not by mobile callers.
create function private.request_user_id() returns uuid
language plpgsql stable security invoker set search_path='' as $identity$
declare
 scalar_sub text := nullif(pg_catalog.current_setting('request.jwt.claim.sub',true),'');
 raw_claims text := nullif(pg_catalog.current_setting('request.jwt.claims',true),'');
 claims jsonb; scalar_id uuid; json_id uuid;
begin
 scalar_id := scalar_sub::uuid;
 if raw_claims is not null then
  claims := raw_claims::jsonb;
  if pg_catalog.jsonb_typeof(claims) is distinct from 'object' then
   raise exception 'Invalid request identity context' using errcode='42501';
  end if;
  if claims ? 'sub' and pg_catalog.jsonb_typeof(claims->'sub') not in ('string','null') then
   raise exception 'Invalid request identity context' using errcode='42501';
  end if;
  json_id := nullif(claims->>'sub','')::uuid;
 end if;
 if scalar_id is not null and json_id is not null and scalar_id<>json_id then
  raise exception 'Invalid request identity context' using errcode='42501';
 end if;
 return coalesce(scalar_id,json_id);
exception when invalid_text_representation then
 raise exception 'Invalid request identity context' using errcode='42501';
end $identity$;
revoke all on function private.request_user_id() from public,anon,authenticated;
grant execute on function private.request_user_id() to castly_api;

-- Preserve actual policy names, commands, roles, permissiveness and every predicate.
-- Substitute only the identity call in API-exclusive policies. Shared policies need review.
do $policies$
declare p record; old_using text; old_check text; new_using text; new_check text;
 statement text; literal text[]; api oid := (select oid from pg_roles where rolname='castly_api');
begin
 if not exists(select 1 from pg_policy where polrelid='public.bookings'::regclass and polname='api_booking_update' and polroles=array[api]) then
  raise exception 'Expected API booking policy missing or shared; review required';
 end if;
 for p in select pol.*,n.nspname,c.relname from pg_policy pol
  join pg_class c on c.oid=pol.polrelid join pg_namespace n on n.oid=c.relnamespace
  where n.nspname='public' and c.relname in
   ('users','categories','talent_profiles','portfolio_assets','bookings','conversations','messages','saved_talent','subscription_entitlements','notifications')
   and (api=any(pol.polroles) or 0::oid=any(pol.polroles))
 loop
  old_using := pg_get_expr(p.polqual,p.polrelid); old_check := pg_get_expr(p.polwithcheck,p.polrelid);
  if position('auth.uid()' in coalesce(old_using,'')||coalesce(old_check,''))=0 then continue; end if;
  if p.polroles<>array[api] then raise exception 'Shared identity policy requires separate review'; end if;
  for literal in select regexp_matches(coalesce(old_using,'')||coalesce(old_check,''),$rx$('([^']|'')*')$rx$,'g') loop
   if position('auth.uid()' in literal[1])>0 then raise exception 'Identity text in a policy literal requires review'; end if;
  end loop;
  new_using := replace(old_using,'auth.uid()','private.request_user_id()');
  new_check := replace(old_check,'auth.uid()','private.request_user_id()');
  if position('auth.' in coalesce(new_using,'')||coalesce(new_check,''))>0 then
   raise exception 'Additional auth dependency in API policy requires review';
  end if;
  statement := format('alter policy %I on %I.%I',p.polname,p.nspname,p.relname);
  if new_using is not null then statement := statement||' using ('||new_using||')'; end if;
  if new_check is not null then statement := statement||' with check ('||new_check||')'; end if;
  execute statement;
 end loop;
end $policies$;

-- Preserve existing guard bodies/attributes/ACLs. No definer conversion or transition rewrite.
do $guards$
declare name text; f record; literal text[]; definition text;
begin
 foreach name in array array['api_booking_transition_guard','api_message_send_guard'] loop
  select p.*,l.lanname into f from pg_proc p join pg_namespace n on n.oid=p.pronamespace
   join pg_language l on l.oid=p.prolang
   where n.nspname='public' and p.proname=name and p.pronargs=0;
  if not found or f.prosecdef or f.lanname<>'plpgsql' or f.prorettype<>'trigger'::regtype then
   raise exception 'Expected invoker guard missing or changed; review required';
  end if;
  for literal in select regexp_matches(f.prosrc,$rx$('([^']|'')*')$rx$,'g') loop
   if position('auth.uid()' in literal[1])>0 then raise exception 'Identity text in a guard literal requires review'; end if;
  end loop;
  if position('auth.uid()' in f.prosrc)=0 then raise exception 'Expected guard identity call missing; review required'; end if;
  if position('auth.' in replace(f.prosrc,'auth.uid()','private.request_user_id()'))>0 then
   raise exception 'Additional auth dependency in invoker guard requires review';
  end if;
  definition := replace(pg_get_functiondef(f.oid),'auth.uid()','private.request_user_id()');
  execute definition;
 end loop;
end $guards$;
commit;
