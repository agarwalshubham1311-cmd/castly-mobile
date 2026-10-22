const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
// Docker container is isolated/local. With SUPABASE_TEST_DATABASE_URL, use psql against a disposable local Supabase DB instead.
const container = process.env.CASTLY_TEST_DB_CONTAINER || 'castly-phase2-policy-db';
const remote = process.env.SUPABASE_TEST_DATABASE_URL;
function sql(text) {
  const args = remote
    ? [remote, '-X', '-v', 'ON_ERROR_STOP=1']
    : ['exec', '-i', container, 'psql', '-U', 'postgres', '-X', '-v', 'ON_ERROR_STOP=1'];
  const result = spawnSync(remote ? 'psql' : 'docker', args, { input: text, encoding: 'utf8' });
  process.stdout.write(result.stdout || '');
  process.stderr.write(result.stderr || '');
  if (result.status !== 0) process.exit(result.status || 1);
}
if (!remote) {
  sql('drop database if exists castly_policy_test; create database castly_policy_test;');
}
function testSQL(text) {
  if (remote) {
    sql(text);
    return;
  }
  const r = spawnSync(
    'docker',
    [
      'exec',
      '-i',
      container,
      'psql',
      '-U',
      'postgres',
      '-d',
      'castly_policy_test',
      '-X',
      '-v',
      'ON_ERROR_STOP=1',
    ],
    { input: text, encoding: 'utf8' },
  );
  process.stdout.write(r.stdout || '');
  process.stderr.write(r.stderr || '');
  if (r.status !== 0) process.exit(r.status || 1);
}
if (!remote) {
  testSQL(fs.readFileSync(path.join(__dirname, '../supabase/tests/bootstrap-local.sql'), 'utf8'));
  for (const name of fs.readdirSync(path.join(__dirname, '../supabase/migrations')).sort())
    testSQL(fs.readFileSync(path.join(__dirname, '../supabase/migrations', name), 'utf8'));
}
testSQL(fs.readFileSync(path.join(__dirname, '../supabase/tests/policies.sql'), 'utf8'));
console.log('PASS: migrations and RLS policy tests on PostgreSQL. Test fixtures rolled back.');
