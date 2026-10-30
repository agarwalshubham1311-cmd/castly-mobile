// Disposable Docker-only tests. No hosted URL, token, migration repair or schema push is accepted.
const { spawnSync } = require('node:child_process');
const path = require('node:path');
const fs = require('node:fs');
let assertions = 0;
const container = 'castly-issue1-reconciliation-db';
const hardening = fs.readFileSync(path.resolve(__dirname, '../supabase/migrations/20261030090000_booking_guard_hardening.sql'), 'utf8');
const preparation = fs.readFileSync(path.resolve(__dirname, '../supabase/reconciliation/issue-1/02-prepare.sql'), 'utf8');
const bodies = hardening.slice(hardening.indexOf('create or replace function'), hardening.lastIndexOf('commit;')).trim();
if (!preparation.includes(bodies)) throw new Error('Fresh-history and reconciliation trigger bodies differ');
function run(args, input, expectedFailure = false) {
  const r = spawnSync('docker', args, { input, encoding: 'utf8' });
  if (expectedFailure) {
    if (r.status === 0) throw new Error('Expected reconciliation approval/drift gate to reject operation');
    console.log('PASS expected rejection: ' + (r.stderr || '').split('\n').find((s) => s.includes('ERROR:')));
  } else if (r.status !== 0) throw new Error(r.stderr || r.stdout);
  if (!expectedFailure) {
    const passed = (r.stderr || '').split('\n').filter((s) => s.includes('PASS '));
    assertions += passed.length;
    if (passed.length) console.log(passed.join('\n'));
  }
  return r.stdout || '';
}
try {
  run(['inspect', container]);
  run(['start', container]);
} catch {
  run(['run', '--name', container, '-e', 'POSTGRES_PASSWORD=disposable-local-only', '-d', 'postgres:17-alpine']);
}
for (let n = 0; ; n++) {
  const r = spawnSync('docker', ['exec', container, 'pg_isready', '-U', 'postgres']);
  if (r.status === 0) break;
  if (n >= 30) throw new Error('Disposable PostgreSQL did not start');
  spawnSync('node', ['-e', 'setTimeout(()=>{},500)']);
}
run(['exec', container, 'mkdir', '-p', '/tmp/castly-sql']);
run(['cp', path.resolve(__dirname, '../supabase') + '/.' , `${container}:/tmp/castly-sql`]);
function sql(db, text, fail = false) {
  return run(['exec', '-i', container, 'psql', '-X', '-U', 'postgres', '-d', db, '-v', 'ON_ERROR_STOP=1'], text, fail);
}
function file(db, name, prefix = '', fail = false) {
  return sql(db, prefix + `\n\\i /tmp/castly-sql/${name}\n`, fail);
}
for (const withOldRpcs of [false, true]) {
  const db = withOldRpcs ? 'castly_reconcile_rpcs' : 'castly_reconcile_baseline';
  sql('postgres', `drop database if exists ${db}; create database ${db};`);
  file(db, 'tests/bootstrap-local.sql');
  file(db, 'migrations/20261022000100_accounts_and_marketplace.sql');
  file(db, 'migrations/20261022000200_media_storage.sql');
  // Approximation of the issue's reported baseline, not a claim to reproduce unseen hosted DDL.
  sql(db, `create schema supabase_migrations;
    create table supabase_migrations.schema_migrations(version text primary key);
    insert into supabase_migrations.schema_migrations values('20261027032615');
    create index local_reported_messages_sender_idx on public.messages(sender_id);
    create index local_reported_talent_category_idx on public.talent_profiles(category_id);`);
  if (withOldRpcs) file(db, 'migrations/20261027120000_booking_decisions_and_messaging.sql');
  file(db, 'reconciliation/issue-1/01-inventory.sql');
  file(db, 'reconciliation/issue-1/02-prepare.sql', '', true);
  // Test the structural gate in a transaction that must roll back, even with approval marker.
  sql(db, `begin; alter table public.bookings rename column indicative_budget to unexpected_budget;
    set castly.reconciliation_schema_reviewed='issue-1-reviewed';
    \\i /tmp/castly-sql/reconciliation/issue-1/structural-check.sql`, true);
  file(db, 'tests/reconciliation-seed.sql');
  if (withOldRpcs) sql(db, "alter table public.bookings add column client_name text not null default ''; alter table public.bookings disable trigger bookings_stamp; update public.bookings set client_name='Archived client'; alter table public.bookings enable trigger bookings_stamp;");
  sql(db, `begin;
    alter table public.bookings add column if not exists client_name text not null default '';
    update public.bookings set client_name='';
    update public.users set display_name='' where id='90000000-0000-0000-0000-000000000001';
    set castly.reconciliation_schema_reviewed='issue-1-reviewed';
    \\i /tmp/castly-sql/reconciliation/issue-1/02-prepare.sql`, true);
  file(db, 'reconciliation/issue-1/02-prepare.sql', "set castly.reconciliation_schema_reviewed='issue-1-reviewed';");
  file(db, 'reconciliation/issue-1/04-verify.sql');
  if (!withOldRpcs && sql(db, "select count(*) from pg_proc where proname in ('respond_to_booking','cancel_booking');").match(/\n\s*0\n/) === null) throw new Error('Preparation introduced old privileged RPCs');
  // Old comprehensive M1 actor suite must still pass after preparation (before cutover).
  const legacy = fs.readFileSync(path.resolve(__dirname, '../supabase/tests/policies.sql'), 'utf8').replace('begin;', 'begin; delete from storage.objects; delete from public.messages; delete from public.conversations; delete from public.bookings; delete from public.saved_talent; delete from public.subscription_entitlements; delete from public.portfolio_assets; delete from public.talent_profiles; delete from auth.users;');
  sql(db,legacy);
  file(db, 'tests/reconciliation-behavior.sql');
  file(db, 'reconciliation/issue-1/03-cutover.sql', '', true);
  // Requests arriving via the working legacy app after preparation need a second backfill.
  sql(db, `begin;
    set local request.jwt.claim.sub='90000000-0000-0000-0000-000000000001';
    set local role authenticated;
    insert into public.bookings(client_id,talent_id,project_type,proposed_date,location,requirements,indicative_budget)
    values(auth.uid(),'90000000-0000-0000-0000-000000000002','Intervening request',current_date+1,'Mumbai','A request created after preparation',18000);
    commit;
    create table castly_cutover_fixture as select id,updated_at from public.bookings where project_type='Intervening request';`);
  // Inject unexpected PUBLIC and column grants: cutover must remove both.
  sql(db, 'grant update(display_name) on public.users to public; grant insert on public.messages to anon; grant update(status) on public.bookings to authenticated;');
  file(db, 'reconciliation/issue-1/03-cutover.sql', "set castly.reconciliation_cutover_approved='issue-1-cutover-approved';");
  file(db, 'reconciliation/issue-1/04-verify.sql', "set castly.reconciliation_verify_cutover='yes';");
  sql(db, `do $$begin
    if (select count(*) from public.bookings where project_type='Intervening request' and client_name='Client seed' and talent_name='Talent seed')<>1 then
      raise exception 'Missing post-preparation booking backfill';end if;
    raise notice 'PASS intervening legacy request gets snapshots at cutover';
    if exists(select 1 from public.bookings b join castly_cutover_fixture f using(id) where b.updated_at<>f.updated_at) then
      raise exception 'Cutover changed legacy request timestamp';end if;
    raise notice 'PASS cutover preserves intervening request timestamp';
    end$$;`);
  file(db, 'tests/reconciliation-behavior.sql');
  // The ledger has not been repaired or populated with fictitious repository history.
  if (sql(db, 'select version from supabase_migrations.schema_migrations;').includes('20261022000100'))
    throw new Error('Historical migration ledger was altered');
  console.log(`PASS ${db}: inventory, refusal gates, preparation preserving legacy M1, API/RLS/Storage actors, cutover, data and ledger preservation`);
}
console.log(`PASS reconciliation scenarios: ${assertions} SQL assertions/catalog gates on disposable PostgreSQL; no hosted database contacted.`);
