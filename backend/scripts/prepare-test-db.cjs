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

for (const name of fs.readdirSync(path.join(root, 'supabase/migrations')).sort())
  sql(fs.readFileSync(path.join(root, 'supabase/migrations', name), 'utf8'), 'castly_spring_test');
sql("alter role castly_api login password 'castly-api-local-test';", 'castly_spring_test');
for (let n = 1; n <= 5; n++)
  sql(
    `insert into auth.users(id,email,raw_user_meta_data) values ('00000000-0000-0000-0000-00000000000${n}','user${n}@example.test','{"display_name":"Test user ${n}"}');`,
    'castly_spring_test',
  );
sql(
  "update public.users set primary_role='client' where id='00000000-0000-0000-0000-000000000001'; update public.users set primary_role='talent' where id in ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000005');",
  'castly_spring_test',
);
console.log(
  'Prepared isolated castly_spring_test database. Application tests connect ONLY as castly_api.',
);
