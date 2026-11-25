// Local-only integration: real Next/Spring/JWT/JWKS/PostgreSQL; Auth/TOTP are deterministic fixtures.
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { spawn, spawnSync } from 'node:child_process';
const admin = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..'),
  root = path.resolve(admin, '..');
const origin = 'http://127.0.0.1:3017',
  authOrigin = 'http://127.0.0.1:19198',
  apiOrigin = 'http://127.0.0.1:19180';
const uid = (n) => '00000000-0000-0000-0000-' + String(n).padStart(12, '0');
const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' });
const jwk = {
  ...publicKey.export({ format: 'jwk' }),
  kid: 'admin-local',
  alg: 'ES256',
  use: 'sig',
};
function token(id, mfa = false) {
  const enc = (v) => Buffer.from(JSON.stringify(v)).toString('base64url'),
    now = Math.floor(Date.now() / 1000);
  const raw =
    enc({ alg: 'ES256', kid: jwk.kid, typ: 'JWT' }) +
    '.' +
    enc({
      iss: authOrigin + '/auth/v1',
      aud: 'authenticated',
      role: 'authenticated',
      sub: id,
      iat: now,
      exp: now + 3600,
      aal: mfa ? 'aal2' : 'aal1',
      session_id: '10000000-0000-0000-0000-' + id.slice(-12),
      amr: [{ method: mfa ? 'totp' : 'password', timestamp: now }],
    });
  return (
    raw +
    '.' +
    crypto
      .sign('sha256', Buffer.from(raw), { key: privateKey, dsaEncoding: 'ieee-p1363' })
      .toString('base64url')
  );
}
function claims(req) {
  try {
    const raw = (req.headers.authorization ?? '').slice(7),
      parts = raw.split('.');
    if (
      !crypto.verify(
        'sha256',
        Buffer.from(parts[0] + '.' + parts[1]),
        { key: publicKey, dsaEncoding: 'ieee-p1363' },
        Buffer.from(parts[2], 'base64url'),
      )
    )
      throw new Error();
    return JSON.parse(Buffer.from(parts[1], 'base64url').toString());
  } catch {
    return null;
  }
}
const factor = '20000000-0000-0000-0000-000000000001',
  challenge = '30000000-0000-0000-0000-000000000001';
const fixture = http.createServer(async (req, res) => {
  res.setHeader('Content-Type', 'application/json');
  const url = new URL(req.url, authOrigin);
  let raw = '';
  for await (const chunk of req) {
    raw += chunk;
    if (raw.length > 16384) {
      res.writeHead(413).end('{}');
      return;
    }
  }
  const body = raw ? JSON.parse(raw) : {};
  if (url.pathname.endsWith('jwks.json')) return res.end(JSON.stringify({ keys: [jwk] }));
  let id;
  if (url.pathname.endsWith('/token')) {
    if (body.password !== 'local-fixture-only') return res.writeHead(400).end('{}');
    id =
      body.email === 'super@example.test'
        ? uid(31)
        : body.email === 'moderator@example.test'
          ? uid(32)
          : body.email === 'support@example.test'
            ? uid(33)
            : body.email === 'analyst@example.test'
              ? uid(34)
              : body.email === 'invitee@example.test'
                ? uid(40)
                : uid(1);
    return res.end(
      JSON.stringify({ access_token: token(id), refresh_token: 'local-' + id, expires_in: 3600 }),
    );
  }
  if (req.method === 'GET' && url.pathname.startsWith('/storage/v1/object/sign/portfolio/')) {
    try {
      const raw = url.searchParams.get('token') ?? '',
        parts = raw.split('.');
      if (
        !crypto.verify(
          'sha256',
          Buffer.from(parts[0] + '.' + parts[1]),
          { key: publicKey, dsaEncoding: 'ieee-p1363' },
          Buffer.from(parts[2], 'base64url'),
        )
      )
        throw new Error();
      const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString());
      if (
        payload.exp <= Date.now() / 1000 ||
        payload.url !== url.pathname.slice('/storage/v1/object/sign/'.length)
      )
        throw new Error();
      res.setHeader('Content-Type', 'image/png');
      res.setHeader('Cache-Control', 'no-store');
      return res.end(
        Buffer.from(
          'iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAADklEQVR4XmMQMgljgAMABxoAncbTxfwAAAAASUVORK5CYII=',
          'base64',
        ),
      );
    } catch {
      return res.writeHead(403).end('{}');
    }
  }
  const jwt = claims(req);
  if (!jwt) return res.writeHead(401).end('{}');
  if (url.pathname.startsWith('/storage/v1/object/sign/portfolio/')) {
    if (jwt.sub !== uid(31) || body.expiresIn > 60 || body.expiresIn < 1)
      return res.writeHead(403).end('{}');
    const enc = (v) => Buffer.from(JSON.stringify(v)).toString('base64url');
    const raw =
      enc({ alg: 'ES256' }) +
      '.' +
      enc({
        exp: Math.floor(Date.now() / 1000) + body.expiresIn,
        url: url.pathname.slice('/storage/v1/object/sign/'.length),
      });
    const signature = crypto
      .sign('sha256', Buffer.from(raw), { key: privateKey, dsaEncoding: 'ieee-p1363' })
      .toString('base64url');
    return res.end(
      JSON.stringify({
        signedURL: url.pathname.slice('/storage/v1'.length) + '?token=' + raw + '.' + signature,
      }),
    );
  }
  if (url.pathname.endsWith('/user'))
    return res.end(
      JSON.stringify({
        id: jwt.sub,
        factors: [{ id: factor, factor_type: 'totp', status: 'verified' }],
      }),
    );
  if (url.pathname.endsWith('/challenge')) return res.end(JSON.stringify({ id: challenge }));
  if (url.pathname.endsWith('/verify')) {
    if (body.code !== '123456' || body.challenge_id !== challenge)
      return res.writeHead(400).end('{}');
    return res.end(
      JSON.stringify({ access_token: token(jwt.sub, true), refresh_token: 'local-' + jwt.sub }),
    );
  }
  if (url.pathname.endsWith('/logout')) return res.writeHead(204).end();
  res.writeHead(404).end('{}');
});
function run(cmd, args, options = {}) {
  const r = spawnSync(cmd, args, { cwd: root, encoding: 'utf8', ...options });
  if (r.status !== 0) throw new Error(r.stderr || r.stdout || 'Command failed');
  return r.stdout;
}
async function ready(url) {
  for (let n = 0; n < 160; n++) {
    try {
      if ((await fetch(url)).ok) return;
    } catch {}
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error('Local test server did not become ready: ' + url);
}
let java, next;
const logs = [];
try {
  run(process.execPath, ['backend/scripts/prepare-test-db.cjs']);
  run(
    'docker',
    [
      'exec',
      '-i',
      'castly-spring-test-db',
      'psql',
      '-U',
      'postgres',
      '-d',
      'castly_spring_test',
      '-v',
      'ON_ERROR_STOP=1',
    ],
    { input: fs.readFileSync(path.join(root, 'backend/scripts/admin-test-fixtures.sql'), 'utf8') },
  );
  run(
    'docker',
    [
      'exec',
      '-i',
      'castly-spring-test-db',
      'psql',
      '-U',
      'postgres',
      '-d',
      'castly_spring_test',
      '-v',
      'ON_ERROR_STOP=1',
    ],
    { input: fs.readFileSync(path.join(root, 'backend/scripts/m72-browser-fixtures.sql'), 'utf8') },
  );
  await new Promise((r) => fixture.listen(19198, '127.0.0.1', r));
  const javaLog = fs.openSync(path.join(os.tmpdir(), 'castly-admin-e2e-java.log'), 'w');
  logs.push(javaLog);
  java = spawn(
    process.env.CASTLY_JAVA ?? 'java',
    ['-jar', path.join(root, 'backend/target/castly-api-0.1.0-SNAPSHOT.jar')],
    {
      cwd: root,
      env: {
        ...process.env,
        PORT: '19180',
        DATABASE_URL: 'jdbc:postgresql://127.0.0.1:55432/castly_spring_test',
        DATABASE_USERNAME: 'castly_api',
        DATABASE_PASSWORD: 'castly-api-local-test',
        SUPABASE_JWT_ISSUER: authOrigin + '/auth/v1',
        JWT_SIGNING_MODE: 'jwks',
        CASTLY_ADMIN_ENABLED: 'true',
        CASTLY_ADMIN_USERS_ENABLED: 'true',
        CASTLY_VERIFICATION_ENABLED: 'true',
        SUPABASE_PUBLISHABLE_KEY: 'sb_publishable_local_fixture',
        CASTLY_ADMIN_AUDIT_KEY: 'local-fixture-audit-key-32-characters-long',
        CASTLY_SUBSCRIPTIONS_ENABLED: 'false',
        CASTLY_GOOGLE_PLAY_ENABLED: 'false',
        CASTLY_NOTIFICATIONS_ENABLED: 'false',
      },
      stdio: ['ignore', javaLog, javaLog],
    },
  );
  await ready(apiOrigin + '/actuator/health');
  const nextLog = fs.openSync(path.join(os.tmpdir(), 'castly-admin-e2e-next.log'), 'w');
  logs.push(nextLog);
  next = spawn(
    process.execPath,
    [
      path.join(admin, 'node_modules/next/dist/bin/next'),
      'start',
      '--hostname',
      '127.0.0.1',
      '--port',
      '3017',
    ],
    {
      cwd: admin,
      env: {
        ...process.env,
        NEXT_TELEMETRY_DISABLED: '1',
        CASTLY_ADMIN_UI_ENABLED: 'true',
        CASTLY_ADMIN_LOCAL_TEST: 'true',
        ADMIN_ORIGIN: origin,
        CASTLY_API_URL: apiOrigin,
        SUPABASE_URL: authOrigin,
        SUPABASE_PUBLISHABLE_KEY: 'sb_publishable_local_fixture',
      },
      stdio: ['ignore', nextLog, nextLog],
    },
  );
  await ready(origin + '/login');
  const test = spawn(
    process.execPath,
    [path.join(admin, 'node_modules/@playwright/test/cli.js'), 'test'],
    { cwd: admin, env: { ...process.env, CASTLY_ADMIN_TEST_URL: origin }, stdio: 'inherit' },
  );
  process.exitCode = await new Promise((r) => test.on('exit', r));
  if (process.exitCode === 0) {
    const audit = run('docker', [
      'exec',
      'castly-spring-test-db',
      'psql',
      '-X',
      '-At',
      '-U',
      'postgres',
      '-d',
      'castly_spring_test',
      '-c',
      "select count(*) from public.trust_audit_logs where action='portfolio.preview_issued' and resource_id='72800000-0000-0000-0000-000000000002'",
    ]);
    if (audit.trim() !== '1')
      throw new Error('Successful portfolio preview must have exactly one committed source audit');
  }
} finally {
  for (const child of [next, java])
    if (child) {
      child.kill('SIGTERM');
      await new Promise((r) => child.once('exit', r));
    }
  fixture.close();
  for (const log of logs) fs.closeSync(log);
}
