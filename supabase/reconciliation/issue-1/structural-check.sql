-- Minimum machine-checkable prerequisites. This does NOT replace owner review of 01-inventory.
do $$declare r record; missing text;begin
 for r in select * from (values
 ('users','id','uuid'),('users','display_name','text'),('users','primary_role','account_role'),
 ('users','created_at','timestamptz'),('users','updated_at','timestamptz'),
 ('categories','id','text'),('categories','name','text'),
 ('talent_profiles','id','uuid'),('talent_profiles','display_name','text'),('talent_profiles','category_id','text'),
 ('talent_profiles','city','text'),('talent_profiles','day_rate','int4'),('talent_profiles','bio','text'),
 ('talent_profiles','skills','_text'),('talent_profiles','available','bool'),('talent_profiles','published','bool'),
 ('talent_profiles','portrait_path','text'),('talent_profiles','created_at','timestamptz'),('talent_profiles','updated_at','timestamptz'),
 ('portfolio_assets','id','uuid'),('portfolio_assets','talent_id','uuid'),('portfolio_assets','path','text'),
 ('portfolio_assets','label','text'),('portfolio_assets','position','int4'),('portfolio_assets','media_type','text'),('portfolio_assets','created_at','timestamptz'),
 ('bookings','id','uuid'),('bookings','client_id','uuid'),('bookings','talent_id','uuid'),('bookings','status','booking_status'),
 ('bookings','project_type','text'),('bookings','proposed_date','date'),('bookings','location','text'),('bookings','requirements','text'),
 ('bookings','indicative_budget','int4'),('bookings','agreed_rate','int4'),('bookings','created_at','timestamptz'),('bookings','updated_at','timestamptz'),
 ('conversations','id','uuid'),('conversations','client_id','uuid'),('conversations','talent_id','uuid'),('conversations','created_at','timestamptz'),
 ('messages','id','uuid'),('messages','conversation_id','uuid'),('messages','sender_id','uuid'),('messages','body','text'),('messages','created_at','timestamptz'),
 ('saved_talent','user_id','uuid'),('saved_talent','talent_id','uuid'),('saved_talent','created_at','timestamptz'),
 ('subscription_entitlements','id','uuid'),('subscription_entitlements','user_id','uuid'),('subscription_entitlements','entitlement','text'),
 ('subscription_entitlements','valid_until','timestamptz'),('subscription_entitlements','created_at','timestamptz')
 ) as required(tbl,col,typ) loop
  if not exists(select 1 from pg_attribute a join pg_type t on t.oid=a.atttypid
    where a.attrelid=to_regclass('public.'||r.tbl) and a.attname=r.col and t.typname=r.typ and not a.attisdropped) then
   raise exception 'Structural mismatch: %.% must be %',r.tbl,r.col,r.typ;
  end if;
 end loop;
 for r in select unnest(array['users','categories','talent_profiles','portfolio_assets','bookings','conversations','messages','saved_talent','subscription_entitlements']) tbl loop
  if not (select relrowsecurity from pg_class where oid=to_regclass('public.'||r.tbl)) then raise exception 'RLS required on %',r.tbl;end if;
  if not exists(select 1 from pg_constraint where conrelid=to_regclass('public.'||r.tbl) and contype='p') then raise exception 'Primary key required on %',r.tbl;end if;
  if exists(select 1 from pg_policy where polrelid=to_regclass('public.'||r.tbl) and 0=any(polroles)) then
   raise exception 'Review unexpected PUBLIC policy on % before granting API access',r.tbl;
  end if;
 end loop;
 if (select array_agg(enumlabel::text order by enumsortorder) from pg_enum where enumtypid='public.account_role'::regtype)
   <> array['client','talent'] then raise exception 'Unexpected account_role enum';end if;
 if (select array_agg(enumlabel::text order by enumsortorder) from pg_enum where enumtypid='public.booking_status'::regtype)
   <> array['requested','accepted','declined','cancelled','completed'] then raise exception 'Unexpected booking_status enum';end if;
 if not exists(select 1 from pg_constraint where conrelid='public.conversations'::regclass and contype='u'
   and pg_get_constraintdef(oid)='UNIQUE (client_id, talent_id)') then raise exception 'Conversation pair uniqueness required';end if;
 if not exists(select 1 from pg_trigger where tgrelid='public.bookings'::regclass and tgname='bookings_stamp' and tgenabled='O')
 or not exists(select 1 from pg_trigger where tgrelid='public.users'::regclass and tgname='users_immutable_role' and tgenabled='O')
 or not exists(select 1 from pg_trigger where tgrelid='auth.users'::regclass and tgname='castly_account_created' and tgenabled='O') then
  raise exception 'Required existing timestamp/immutable-role/Auth-bootstrap triggers missing; inspect actual definitions';
 end if;
 if to_regprocedure('auth.uid()') is null then raise exception 'Supabase auth.uid required';end if;
 if exists(select 1 from storage.buckets where id in ('portfolio','private-media') and public)
 or (select count(*) from storage.buckets where id in ('portfolio','private-media'))<>2 then
  raise exception 'Private portfolio/private-media buckets required; reconcile Storage separately';
 end if;
 for r in select attname,atttypid,attnotnull from pg_attribute where attrelid='public.bookings'::regclass
   and attname in ('client_name','talent_name') and not attisdropped loop
  if r.atttypid<>'text'::regtype or not r.attnotnull then raise exception 'Existing snapshot column % must be TEXT NOT NULL',r.attname;end if;
 end loop;
 -- Do not silently replace/add constraints or role-policy semantics on an unknown installation.
 -- Owner must verify all FK/CHECK/defaults, trigger bodies, grants, storage rules and indexes in inventory.
end$$;
