process.env.TEST_URL ||= 'http://127.0.0.1:8086';
// Real Spring HTTP + PostgreSQL; only Supabase Auth/JWKS are local test fixtures. No hosted requests.
const { assert, launch, go, ready } = require('./browser-utils.cjs');
const http = require('node:http');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');
const { Buffer } = require('node:buffer');
const client = '00000000-0000-0000-0000-000000000001',
  talent = '00000000-0000-0000-0000-000000000002',
  outsider = '00000000-0000-0000-0000-000000000003';
const issuer = 'http://127.0.0.1:8098/auth/v1',
  api = 'http://127.0.0.1:8080/api/v1';
const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' });
const jwk = { ...publicKey.export({ format: 'jwk' }), kid: 'local-e2e', alg: 'ES256', use: 'sig' };
function token(id) {
  const enc = (data) => Buffer.from(JSON.stringify(data)).toString('base64url');
  const value =
    enc({ alg: 'ES256', typ: 'JWT', kid: jwk.kid }) +
    '.' +
    enc({
      iss: issuer,
      sub: id,
      aud: 'authenticated',
      role: 'authenticated',
      iat: Math.floor(Date.now() / 1000),
      exp: Math.floor(Date.now() / 1000) + 3600,
    });
  return (
    value +
    '.' +
    crypto
      .sign('sha256', Buffer.from(value), { key: privateKey, dsaEncoding: 'ieee-p1363' })
      .toString('base64url')
  );
}
function user(id) {
  return {
    id,
    aud: 'authenticated',
    role: 'authenticated',
    email: id === client ? 'client@example.test' : 'talent@example.test',
    app_metadata: { provider: 'email', providers: ['email'] },
    user_metadata: {},
    identities: [],
    created_at: '2026-10-27T00:00:00Z',
  };
}
const auth = http.createServer(async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
  res.setHeader('Content-Type', 'application/json');
  if (req.method === 'OPTIONS') {
    res.writeHead(204).end();
    return;
  }
  const url = new URL(req.url, issuer);
  let raw = '';
  for await (const chunk of req) raw += chunk;
  const body = raw ? JSON.parse(raw) : {};
  if (url.pathname.endsWith('/.well-known/jwks.json')) {
    res.end(JSON.stringify({ keys: [jwk] }));
    return;
  }
  if (url.pathname.endsWith('/token')) {
    const id = body.email === 'client@example.test' ? client : talent;
    res.end(
      JSON.stringify({
        access_token: token(id),
        refresh_token: 'fixture-' + id,
        expires_in: 3600,
        token_type: 'bearer',
        user: user(id),
      }),
    );
    return;
  }
  if (url.pathname.endsWith('/logout')) {
    res.writeHead(204).end();
    return;
  }
  // No Realtime gateway exists in this fixture. The app must expose refresh/poll recovery.
  res.writeHead(404).end('{}');
});
async function until(url) {
  for (let n = 0; n < 80; n++) {
    try {
      if ((await fetch(url)).ok) return;
    } catch {}
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error('Server did not become ready: ' + url);
}
(async () => {
  let browser;
  let java;
  await new Promise((r) => auth.listen(8098, '127.0.0.1', r));
  const folder = path.resolve(__dirname, '../artifacts/spring-api');
  fs.mkdirSync(folder, { recursive: true });
  const log = fs.openSync('/tmp/castly-spring-e2e-java.log', 'w');
  try {
    const prepare = spawnSync('node', ['backend/scripts/prepare-test-db.cjs'], {
      cwd: path.resolve(__dirname, '..'),
      encoding: 'utf8',
    });
    assert.equal(prepare.status, 0, prepare.stderr);
    java = spawn(
      process.env.CASTLY_JAVA || 'java',
      ['-jar', 'backend/target/castly-api-0.1.0-SNAPSHOT.jar'],
      {
        cwd: path.resolve(__dirname, '..'),
        env: {
          ...process.env,
          DATABASE_URL: 'jdbc:postgresql://127.0.0.1:55432/castly_spring_test',
          DATABASE_USERNAME: 'castly_api',
          DATABASE_PASSWORD: 'castly-api-local-test',
          SUPABASE_JWT_ISSUER: issuer,
          JWT_SIGNING_MODE: 'jwks',
          CORS_ALLOWED_ORIGINS: 'http://127.0.0.1:8086',
          PORT: '8080',
        },
        stdio: ['ignore', log, log],
      },
    );
    await until('http://127.0.0.1:8080/actuator/health');
    browser = await launch();
    const errors = [];
    const contextA = await browser.newContext({ viewport: { width: 390, height: 844 } });
    const contextB = await browser.newContext({ viewport: { width: 390, height: 844 } });
    const a = await contextA.newPage(),
      b = await contextB.newPage();
    for (const page of [a, b]) page.on('pageerror', (e) => errors.push(e.message));
    async function login(page, email) {
      await go(page, '/login', 'Welcome back');
      await page.getByRole('textbox', { name: 'Email', exact: true }).fill(email);
      await page.getByLabel('Password', { exact: true }).fill('local-test-password');
      await page.getByRole('button', { name: 'Sign in', exact: true }).click();
      await page.getByRole('button', { name: 'Account settings', exact: true }).waitFor();
    }
    await login(b, 'talent@example.test');
    await b.getByRole('button', { name: 'Manage talent profile', exact: true }).click();
    await b.getByRole('textbox', { name: 'Talent name', exact: true }).fill('Spring API Talent');
    await b.getByRole('textbox', { name: 'City', exact: true }).fill('Mumbai');
    await b
      .getByRole('textbox', { name: 'Indicative daily rate (INR)', exact: true })
      .fill('18000');
    await b
      .getByRole('textbox', { name: 'Biography', exact: true })
      .fill('A profile created through the real Spring API.');
    await b.getByRole('button', { name: 'Save talent profile', exact: true }).click();
    await b
      .getByText('Draft profile saved. It is not visible in discovery.', { exact: true })
      .waitFor();
    await b.getByRole('switch', { name: 'Published in discovery', exact: true }).click();
    await b.getByRole('button', { name: 'Save talent profile', exact: true }).click();
    await b.getByText('Talent profile published.', { exact: true }).waitFor();
    await login(a, 'client@example.test');
    await go(a, '/search', 'Search talent');
    await a.getByRole('button', { name: 'Save Spring API Talent', exact: true }).click();
    await a.getByRole('button', { name: 'Unsave Spring API Talent', exact: true }).waitFor();
    await go(a, '/booking/' + talent, 'Request booking');
    await a.getByRole('textbox', { name: 'Project type', exact: true }).fill('Real API editorial');
    await a
      .getByLabel('Proposed date', { exact: true })
      .fill(new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10));
    await a.getByRole('textbox', { name: 'Location', exact: true }).fill('Mumbai studio');
    await a
      .getByRole('textbox', { name: 'Project requirements', exact: true })
      .fill('A detailed creative brief for this API integration test.');
    await a.getByRole('textbox', { name: 'Indicative budget (INR)', exact: true }).fill('18000');
    await a.getByRole('button', { name: 'Send booking request', exact: true }).click();
    await a.getByText('Requested', { exact: true }).waitFor();
    const bookingId = new URL(a.url()).pathname.split('/').pop();
    await go(b, '/booking-detail/' + bookingId, 'Booking details');
    await b.getByRole('button', { name: 'Accept booking', exact: true }).click();
    await b.getByRole('button', { name: 'Open conversation', exact: true }).waitFor();
    await ready(b);
    await b.screenshot({ path: path.join(folder, 'accepted-booking-390x844.png') });
    await a.reload();
    await a.getByRole('button', { name: 'Open conversation', exact: true }).click();
    await a
      .getByRole('textbox', { name: 'Message', exact: true })
      .fill('Hello from the client via Spring.');
    await a.getByRole('button', { name: 'Send message', exact: true }).click();
    await a.getByText('Hello from the client via Spring.', { exact: true }).waitFor();
    await b.getByRole('button', { name: 'Open conversation', exact: true }).click();
    await b.getByText('Hello from the client via Spring.', { exact: true }).waitFor();
    await b
      .getByRole('textbox', { name: 'Message', exact: true })
      .fill('Hello from talent via Spring.');
    await b.getByRole('button', { name: 'Send message', exact: true }).click();
    await b.getByText('Hello from talent via Spring.', { exact: true }).waitFor();
    await a.getByRole('button', { name: 'Refresh messages', exact: true }).click();
    await a.getByText('Hello from talent via Spring.', { exact: true }).waitFor();
    await ready(a);
    await a.screenshot({ path: path.join(folder, 'conversation-390x844.png') });
    const denied = await fetch(api + '/bookings/' + bookingId, {
      headers: { Authorization: 'Bearer ' + token(outsider) },
    });
    assert.equal(denied.status, 404);
    await go(a, '/booking-detail/' + bookingId, 'Booking details');
    await a.getByRole('button', { name: 'Cancel booking', exact: true }).click();
    await a.getByText('Cancelled', { exact: true }).waitFor();
    await b.reload();
    await b
      .getByText('Messaging is paused because there is no active accepted booking.', {
        exact: true,
      })
      .waitFor();
    assert.equal(
      await b.getByRole('button', { name: 'Send message', exact: true }).isDisabled(),
      true,
    );
    assert.deepEqual(errors, []);
    console.log(
      'PASS: real Java HTTP/JWT/JWKS + PostgreSQL + browser: talent create/edit/publish, favourites, booking creation/acceptance/cancellation, conversation creation, two-party sending/history, outsider denial, paused sending. Supabase Auth/JWKS are local fixtures; hosted Realtime is not tested.',
    );
  } finally {
    if (browser) await browser.close();
    if (java) java.kill('SIGTERM');
    auth.close();
    fs.closeSync(log);
  }
})().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
