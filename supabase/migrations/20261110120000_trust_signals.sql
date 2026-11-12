begin;
-- Public counters contain no booking IDs, reviewers, moderation notes or safety score.
create table public.trust_signal_snapshots(user_id uuid primary key references public.users(id),completed_bookings bigint not null default 0 check(completed_bookings>=0),review_count bigint not null default 0 check(review_count>=0),rating_total bigint not null default 0 check(rating_total>=0 and rating_total<=review_count*5),policy_version text not null default 'trust-indicators-v1',updated_at timestamptz not null default now());
alter table public.trust_signal_snapshots enable row level security;
revoke all on public.trust_signal_snapshots from public,anon,authenticated;
grant select,insert(user_id,completed_bookings,review_count,rating_total),update(completed_bookings,review_count,rating_total,updated_at) on public.trust_signal_snapshots to castly_api;
create policy signal_read on public.trust_signal_snapshots for select to castly_api using(pg_trigger_depth()>0 or user_id=private.request_user_id() or trust_private.is_reviewer('moderation') or exists(select 1 from public.talent_profiles where id=user_id and published));
create policy signal_insert on public.trust_signal_snapshots for insert to castly_api with check(pg_trigger_depth()>0);
create policy signal_update on public.trust_signal_snapshots for update to castly_api using(pg_trigger_depth()>0) with check(pg_trigger_depth()>0);
create table trust_private.completed_booking_facts(booking_id uuid not null references public.bookings(id),user_id uuid not null references public.users(id),primary key(booking_id,user_id));
alter table trust_private.completed_booking_facts enable row level security;
revoke all on trust_private.completed_booking_facts from public,anon,authenticated;
grant select,insert(booking_id,user_id),delete on trust_private.completed_booking_facts to castly_api;
create policy completed_fact_read on trust_private.completed_booking_facts for select to castly_api using(pg_trigger_depth()>0 or user_id=private.request_user_id());
create policy completed_fact_write on trust_private.completed_booking_facts for all to castly_api using(pg_trigger_depth()>0) with check(pg_trigger_depth()>0);
-- The owner migration backfills only bookings carrying both explicit attestations.
insert into trust_private.completed_booking_facts select b.id,p.user_id from public.bookings b cross join lateral(values(b.client_id),(b.talent_id))p(user_id) where b.status='completed' and (select count(*) from public.booking_completion_confirmations c where c.booking_id=b.id and c.user_id in(b.client_id,b.talent_id))=2;
alter table public.user_reviews add column eligible boolean not null default true;
alter table public.user_reviews disable trigger review_transition;
update public.user_reviews r set eligible=false where not exists(select 1 from public.bookings b where b.id=r.booking_id and b.status='completed' and (select count(*) from public.booking_completion_confirmations c where c.booking_id=b.id and c.user_id in(b.client_id,b.talent_id))=2);
alter table public.user_reviews enable trigger review_transition;
insert into public.trust_signal_snapshots(user_id,completed_bookings,review_count,rating_total)
select u.id,coalesce(b.n,0),coalesce(r.n,0),coalesce(r.total,0) from public.users u left join(select user_id,count(*) n from trust_private.completed_booking_facts group by user_id)b on b.user_id=u.id left join(select target_id,count(*) n,sum(rating) total from public.user_reviews where eligible and not hidden group by target_id)r on r.target_id=u.id where b.n is not null or r.n is not null;
create function trust_private.completed_fact_counter() returns trigger language plpgsql security invoker set search_path='' as $$begin
 if TG_OP='INSERT' then insert into public.trust_signal_snapshots(user_id,completed_bookings) values(new.user_id,1) on conflict(user_id) do update set completed_bookings=trust_signal_snapshots.completed_bookings+1,updated_at=statement_timestamp();return new;
 else update public.trust_signal_snapshots set completed_bookings=completed_bookings-1,updated_at=statement_timestamp() where user_id=old.user_id;return old;end if;end$$;
revoke all on function trust_private.completed_fact_counter() from public,anon,authenticated,castly_api;
create trigger completed_fact_counter after insert or delete on trust_private.completed_booking_facts for each row execute function trust_private.completed_fact_counter();
create function trust_private.record_completed_facts() returns trigger language plpgsql security invoker set search_path='' as $$declare b public.bookings;begin
 if TG_TABLE_NAME='bookings' then b:=new;elsif TG_OP='DELETE' then select * into b from public.bookings where id=old.booking_id;else select * into b from public.bookings where id=new.booking_id;end if;
 if b.status='completed' and (select count(*) from public.booking_completion_confirmations c where c.booking_id=b.id and c.user_id in(b.client_id,b.talent_id))=2 then
 insert into trust_private.completed_booking_facts(booking_id,user_id) values(b.id,b.client_id),(b.id,b.talent_id) on conflict do nothing;
 else delete from trust_private.completed_booking_facts where booking_id=b.id;update public.user_reviews set eligible=false where booking_id=b.id and eligible;end if;if TG_OP='DELETE' then return old;end if;return new;end$$;
revoke all on function trust_private.record_completed_facts() from public,anon,authenticated,castly_api;
create trigger record_completed_booking after update of status on public.bookings for each row execute function trust_private.record_completed_facts();
create trigger record_completed_attestation after insert or delete on public.booking_completion_confirmations for each row execute function trust_private.record_completed_facts();
grant update(eligible) on public.user_reviews to castly_api;
create policy review_eligibility_update on public.user_reviews for update to castly_api using(pg_trigger_depth()>0) with check(pg_trigger_depth()>0);
-- Eligibility can only change through the nested booking transition trigger.
do $$declare source text;begin select pg_get_functiondef('trust_private.review_transition()'::regprocedure) into source;
 source:=replace(source,$old$'rating','body','hidden','updated_at'$old$,$new$'rating','body','hidden','updated_at','eligible'$new$);
 source:=replace(source,'begin',E'begin\n if new.eligible is distinct from old.eligible and pg_trigger_depth()<2 then raise exception ''Protected review eligibility'' using errcode=''42501'';end if;');
 execute source;end$$;
create function trust_private.review_counter() returns trigger language plpgsql security invoker set search_path='' as $$declare old_count bigint:=0;new_count bigint:=0;old_rating bigint:=0;new_rating bigint:=0;target uuid;begin
 if TG_OP='DELETE' then target:=old.target_id;else target:=new.target_id;end if;
 if TG_OP in('UPDATE','DELETE') and old.eligible and not old.hidden then old_count:=1;old_rating:=old.rating;end if;
 if TG_OP<>'DELETE' and new.eligible and not new.hidden then new_count:=1;new_rating:=new.rating;end if;
 if TG_OP='INSERT' then insert into public.trust_signal_snapshots(user_id,review_count,rating_total) values(new.target_id,new_count,new_rating) on conflict(user_id) do update set review_count=trust_signal_snapshots.review_count+excluded.review_count,rating_total=trust_signal_snapshots.rating_total+excluded.rating_total,updated_at=statement_timestamp();
 else update public.trust_signal_snapshots set review_count=review_count+new_count-old_count,rating_total=rating_total+new_rating-old_rating,updated_at=statement_timestamp() where user_id=target;end if;if TG_OP='DELETE' then return old;end if;return new;end$$;
revoke all on function trust_private.review_counter() from public,anon,authenticated,castly_api;
create trigger review_counter after insert or update or delete on public.user_reviews for each row execute function trust_private.review_counter();
-- Internal advisory signals are recalculated asynchronously using authoritative rows.
alter table public.moderation_actions add column active boolean not null default true;
grant update(active) on public.moderation_actions to castly_api;
create policy action_appeal_update on public.moderation_actions for update to castly_api using(pg_trigger_depth()>0 and trust_private.is_reviewer('moderation')) with check(pg_trigger_depth()>0 and trust_private.is_reviewer('moderation'));
create function trust_private.appeal_action_state() returns trigger language plpgsql security invoker set search_path='' as $$begin if new.status='OVERTURNED' then update public.moderation_actions set active=false where id=new.action_id;end if;return new;end$$;
revoke all on function trust_private.appeal_action_state() from public,anon,authenticated,castly_api;
create trigger appeal_action_state after update on public.moderation_appeals for each row execute function trust_private.appeal_action_state();
create index moderation_active_subject on public.moderation_actions(subject_id) where active and decision in('warning','review_hidden');
create table trust_private.policy_action_counts(user_id uuid primary key references public.users(id),active_actions integer not null check(active_actions>=0));
alter table trust_private.policy_action_counts enable row level security;
revoke all on trust_private.policy_action_counts from public,anon,authenticated;
grant select,insert(user_id,active_actions),update(active_actions) on trust_private.policy_action_counts to castly_api;
create policy action_counts_read on trust_private.policy_action_counts for select to castly_api using(pg_trigger_depth()>0 or trust_private.is_reviewer('moderation') or(user_id=private.request_user_id() and current_setting('castly.trust_risk_refresh',true)='true'));
create policy action_counts_write on trust_private.policy_action_counts for all to castly_api using(pg_trigger_depth()>0) with check(pg_trigger_depth()>0);
insert into trust_private.policy_action_counts select subject_id,count(*) from public.moderation_actions where active and decision in('warning','review_hidden') group by subject_id;
create table trust_private.risk_snapshots(user_id uuid primary key references public.users(id),confirmed_policy_actions integer not null default 0,manual_review_recommended boolean not null default false,policy_version text not null default 'manual-review-v1',updated_at timestamptz not null default now());
alter table trust_private.risk_snapshots enable row level security;
revoke all on trust_private.risk_snapshots from public,anon,authenticated;
grant select,insert(user_id),update(updated_at) on trust_private.risk_snapshots to castly_api;
create policy risk_read on trust_private.risk_snapshots for select to castly_api using(trust_private.is_reviewer('moderation') or (user_id=private.request_user_id() and current_setting('castly.trust_risk_refresh',true)='true'));
create policy risk_insert on trust_private.risk_snapshots for insert to castly_api with check(user_id=private.request_user_id() and current_setting('castly.trust_risk_refresh',true)='true');
create policy risk_update on trust_private.risk_snapshots for update to castly_api using((user_id=private.request_user_id() and current_setting('castly.trust_risk_refresh',true)='true') or(pg_trigger_depth()>0 and trust_private.is_reviewer('moderation'))) with check((user_id=private.request_user_id() and current_setting('castly.trust_risk_refresh',true)='true') or(pg_trigger_depth()>0 and trust_private.is_reviewer('moderation')));
create function trust_private.refresh_risk_snapshot() returns trigger language plpgsql security invoker set search_path='' as $$begin
 select coalesce((select active_actions from trust_private.policy_action_counts where user_id=new.user_id),0) into new.confirmed_policy_actions;
 new.manual_review_recommended:=new.confirmed_policy_actions>=2;new.policy_version:='manual-review-v1';new.updated_at:=statement_timestamp();return new;end$$;
revoke all on function trust_private.refresh_risk_snapshot() from public,anon,authenticated,castly_api;
create trigger refresh_risk_snapshot before insert or update on trust_private.risk_snapshots for each row execute function trust_private.refresh_risk_snapshot();
create function trust_private.policy_action_counter() returns trigger language plpgsql security invoker set search_path='' as $$declare delta integer:=0;begin
 if TG_OP='UPDATE' and old.active and old.decision in('warning','review_hidden') then delta:=-1;end if;
 if new.active and new.decision in('warning','review_hidden') then delta:=delta+1;end if;
 if TG_OP='INSERT' then insert into trust_private.policy_action_counts(user_id,active_actions) values(new.subject_id,delta) on conflict(user_id) do update set active_actions=policy_action_counts.active_actions+excluded.active_actions;
 else update trust_private.policy_action_counts set active_actions=active_actions+delta where user_id=new.subject_id;end if;
 update trust_private.risk_snapshots set updated_at=statement_timestamp() where user_id=new.subject_id;return new;end$$;
revoke all on function trust_private.policy_action_counter() from public,anon,authenticated,castly_api;
create trigger policy_action_counter after insert or update of active on public.moderation_actions for each row execute function trust_private.policy_action_counter();
alter policy review_read on public.user_reviews using(reviewer_id=private.request_user_id() or target_id=private.request_user_id() or trust_private.is_reviewer('moderation') or(eligible and not hidden and exists(select 1 from public.talent_profiles t where t.id=target_id and t.published)));
commit;
