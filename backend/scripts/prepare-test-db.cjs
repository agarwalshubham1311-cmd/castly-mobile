const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const container = 'castly-spring-test-db';
function run(args, input) {
  const r = spawnSync('docker', args, { input, encoding: 'utf8' });
  if (r.status !== 0) throw new Error(r.stderr || r.stdout);
  return r.stdout.trim();
}
try {
  run(['inspect', container]);
  run(['start', container]);
} catch {
  run([
    'run',
    '--name',
    container,
    '-e',
    'POSTGRES_PASSWORD=local-test-only',
    '-p',
    '127.0.0.1:55432:5432',
    '-d',
    'postgres:17-alpine',
  ]);
}
let ready = false;
for (let n = 0; n < 30; n++) {
  try {
    run(['exec', container, 'pg_isready', '-U', 'postgres']);
    ready = true;
    break;
  } catch {
    spawnSync('node', ['-e', 'setTimeout(()=>{},500)']);
  }
}
if (!ready) throw new Error('PostgreSQL did not start');
function sql(text, db = 'postgres') {
  run(['exec', '-i', container, 'psql', '-U', 'postgres', '-d', db, '-v', 'ON_ERROR_STOP=1'], text);
}
sql('drop database if exists castly_spring_test; create database castly_spring_test;');
const root = path.resolve(__dirname, '../..');
sql(
  fs.readFileSync(path.join(root, 'supabase/tests/bootstrap-local.sql'), 'utf8'),
  'castly_spring_test',
);

const schemaPath = process.env.CASTLY_TEST_SCHEMA_PATH || 'repository';
if (!['repository', 'reconciliation'].includes(schemaPath)) throw new Error('Unknown disposable schema path');
if (schemaPath === 'repository') {
  for (const name of fs.readdirSync(path.join(root, 'supabase/migrations')).sort())
    sql(fs.readFileSync(path.join(root, 'supabase/migrations', name), 'utf8'), 'castly_spring_test');
} else {
  for (const name of ['20261022000100_accounts_and_marketplace.sql', '20261022000200_media_storage.sql'])
    sql(fs.readFileSync(path.join(root, 'supabase/migrations', name), 'utf8'), 'castly_spring_test');
  // Synthetic approximation of the reported baseline; never a claim of hosted equivalence.
  sql("create schema supabase_migrations; create table supabase_migrations.schema_migrations(version text primary key); insert into supabase_migrations.schema_migrations values('20261027032615');", 'castly_spring_test');
  run(['exec', container, 'mkdir', '-p', '/tmp/castly-sql']);
  run(['cp', path.join(root, 'supabase') + '/.', container + ':/tmp/castly-sql']);
  sql("set castly.reconciliation_schema_reviewed='issue-1-reviewed';\n\\i /tmp/castly-sql/reconciliation/issue-1/02-prepare.sql\n", 'castly_spring_test');
}
sql("alter role castly_api login password 'castly-api-local-test';", 'castly_spring_test');
for (let n = 1; n <= 100; n++)
  sql(
    `insert into auth.users(id,email,raw_user_meta_data) values ('00000000-0000-0000-0000-${String(n).padStart(12, '0')}','user${n}@example.test','{"display_name":"Test user ${n}"}');`,
    'castly_spring_test',
  );
sql(
  "update public.users set primary_role='client' where id='00000000-0000-0000-0000-000000000001'; update public.users set primary_role='talent' where id in ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000005') or id>='00000000-0000-0000-0000-000000000006';",
  'castly_spring_test',
);
console.log(
  `Prepared isolated castly_spring_test database (${schemaPath}). Application tests connect ONLY as castly_api.`,
);
