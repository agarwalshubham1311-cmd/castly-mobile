begin;
-- Even published portfolio files use signed URLs so draft/unpublished assets are not world-readable by URL.
insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types) values
 ('portfolio','portfolio',false,5242880,array['image/jpeg','image/png','image/webp']),
 ('private-media','private-media',false,10485760,array['image/jpeg','image/png','application/pdf']);
create policy castly_portfolio_read on storage.objects for select to anon,authenticated using (
 bucket_id='portfolio' and (
 (storage.foldername(name))[1]=(select auth.uid())::text or exists(
  select 1 from public.talent_profiles t where t.id::text=(storage.foldername(name))[1] and t.published
  and (t.portrait_path=name or exists(select 1 from public.portfolio_assets a where a.talent_id=t.id and a.path=name))
 )));
create policy castly_portfolio_upload on storage.objects for insert to authenticated with check(
 bucket_id='portfolio' and (storage.foldername(name))[1]=(select auth.uid())::text
 and exists(select 1 from public.users where id=(select auth.uid()) and primary_role='talent')
 and exists(select 1 from public.talent_profiles where id=(select auth.uid())));
create policy castly_portfolio_delete on storage.objects for delete to authenticated using(bucket_id='portfolio' and (storage.foldername(name))[1]=(select auth.uid())::text);
-- Immutable object names/no overwrite: upload a fresh UUID filename, then change the profile/asset pointer.
create policy castly_private_read on storage.objects for select to authenticated using(bucket_id='private-media' and (storage.foldername(name))[1]=(select auth.uid())::text);
create policy castly_private_upload on storage.objects for insert to authenticated with check(bucket_id='private-media' and (storage.foldername(name))[1]=(select auth.uid())::text);
create policy castly_private_delete on storage.objects for delete to authenticated using(bucket_id='private-media' and (storage.foldername(name))[1]=(select auth.uid())::text);
commit;
