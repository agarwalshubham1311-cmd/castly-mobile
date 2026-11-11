process.env.TEST_URL ||= 'http://127.0.0.1:8086';
// Real Spring HTTP + PostgreSQL; only Supabase Auth/JWKS are local test fixtures. No hosted requests.
const { assert, launch, go, ready, noOverflow } = require('./browser-utils.cjs');
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
    const id = body.refresh_token
      ? body.refresh_token.replace('fixture-', '')
      : body.email === 'client@example.test'
        ? client
        : talent;
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
  const folder = path.resolve(
    process.env.CASTLY_SCREENSHOT_DIR || path.join(__dirname, '../artifacts/spring-api'),
  );
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
          CASTLY_NOTIFICATIONS_ENABLED: 'true',
          CASTLY_SUBSCRIPTIONS_ENABLED: 'true',
          CASTLY_GOOGLE_PLAY_ENABLED: 'false',
          CASTLY_DISPUTES_ENABLED: 'true',
          CASTLY_ADVANCED_SEARCH_ENABLED: 'true',
          CASTLY_AI_RECOMMENDATIONS_ENABLED: 'true',
          CASTLY_SMART_MATCHING_ENABLED: 'true',
          CASTLY_AI_ASSISTANCE_ENABLED: 'true',
          CORS_ALLOWED_ORIGINS: new URL(process.env.TEST_URL).origin,
          PORT: '8080',
        },
        stdio: ['ignore', log, log],
      },
    );
    await until('http://127.0.0.1:8080/actuator/health');
    browser = await launch();
    const errors = [];
    const bypass = [];
    const contextA = await browser.newContext({ viewport: { width: 390, height: 844 } });
    const contextB = await browser.newContext({ viewport: { width: 390, height: 844 } });
    const a = await contextA.newPage(),
      b = await contextB.newPage();
    for (const page of [a, b]) {
      page.on('pageerror', (e) => errors.push(e.message));
      page.on('request', (request) => {
        if (new URL(request.url()).pathname.startsWith('/rest/v1')) bypass.push(true);
      });
    }
    async function responsiveCapture(page, name) {
      for (const [width, height] of [
        [360, 800],
        [390, 844],
        [430, 932],
      ]) {
        await page.setViewportSize({ width, height });
        await ready(page);
        await noOverflow(page);
        if (name === 'booking-confirmation') {
          const button = await page
            .getByRole('button', { name: 'Confirm accept booking', exact: true })
            .boundingBox();
          assert.ok(
            button && button.y >= 0 && button.y + button.height <= height,
            'Confirmation action stays accessible',
          );
        }
        await page.screenshot({
          path: path.join(folder, `${name}-${width}x${height}.png`),
          animations: 'disabled',
        });
      }
      await page.setViewportSize({ width: 390, height: 844 });
    }
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
    await b.getByRole('textbox', { name: 'Skills, separated by commas', exact: true }).fill('video');
    await b.getByRole('button', { name: 'Save talent profile', exact: true }).click();
    await b
      .getByText('Draft profile saved. It is not visible in discovery.', { exact: true })
      .waitFor();
    await b.getByRole('switch', { name: 'Published in discovery', exact: true }).click();
    await b.getByRole('button', { name: 'Save talent profile', exact: true }).click();
    await b.getByText('Talent profile published.', { exact: true }).waitFor();
    const phase5Day = new Date(Date.now() + 5 * 86400000).toISOString().slice(0, 10);
    await go(b, '/discovery/creator', 'Creator discovery controls');
    await b.getByRole('textbox', { name: 'Broad region (no coordinates or address)' }).fill('Maharashtra');
    await b.getByRole('checkbox', { name: 'Share this region in discovery' }).click();
    await b.getByRole('button', { name: 'Save region choice' }).click();
    await b.getByText('Region choice saved.', { exact: true }).waitFor();
    await b.getByRole('textbox', { name: 'Availability day (YYYY-MM-DD)' }).fill(phase5Day);
    await b.getByRole('button', { name: 'Save date declaration' }).click();
    await b.getByText('Date declaration saved. Clients still need direct confirmation.', { exact: true }).waitFor();
    let expiredOnce = false;
    await a.route('**/api/v1/account', async (route) => {
      if (!expiredOnce && route.request().method() === 'GET') {
        expiredOnce = true;
        await route.fulfill({
          status: 401,
          contentType: 'application/problem+json',
          body: '{"status":401}',
        });
      } else await route.continue();
    });
    await login(a, 'client@example.test');
    assert.equal(
      expiredOnce,
      true,
      'Private API 401 must refresh the same Auth user and restore the account',
    );
    await go(a, '/discovery', 'Campaign discovery');
    await a.getByRole('textbox', { name: 'Campaign description' })
      .fill(`Actors with video for a reel in Mumbai under INR 20000 on ${phase5Day}`);
    await a.getByRole('button', { name: 'Parse campaign locally' }).click();
    await a.getByRole('textbox', { name: 'City (public profile label)' }).waitFor();
    await a.getByRole('checkbox', { name: 'Budget is an indicative per-day rate ceiling' }).click();
    await a.getByRole('checkbox', { name: 'I confirm these structured campaign fields' }).click();
    await a.getByRole('button', { name: 'Build confirmed shortlist' }).click();
    await a.getByRole('button', { name: 'Draft brief for Spring API Talent' }).waitFor();
    await a.getByText('Date availability: confirmed', { exact: false }).waitFor();
    await a.getByRole('button', { name: 'Draft brief for Spring API Talent' }).click();
    const editableBrief = a.getByRole('textbox', { name: 'Editable booking brief suggestion (not sent)' });
    await editableBrief.waitFor();
    await editableBrief.fill('Edited by the client before any booking.');
    assert.equal(await editableBrief.inputValue(), 'Edited by the client before any booking.');
    await a.getByRole('checkbox', { name: 'Compare Spring API Talent' }).click();
    await a.getByRole('button', { name: 'Compare selected creators' }).click();
    await a.getByText(/Spring API Talent: actors, Mumbai, INR .*\/day indicative/).first().waitFor();
    console.log('PASS: real browser + Spring + PostgreSQL campaign parse, confirmed constraints, declared-date shortlist, editable brief and factual comparison.');
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
    assert.equal(await a.getByRole('button', { name: 'Accept booking', exact: true }).count(), 0);
    const bookingId = new URL(a.url()).pathname.split('/').pop();
    await go(b, '/notifications', 'Notifications');
    await b.getByText('New booking request', { exact: true }).waitFor();
    await responsiveCapture(b, 'notifications');
    await b.getByRole('button', { name: 'View booking', exact: true }).click();
    assert.equal(await b.getByRole('button', { name: 'Cancel booking', exact: true }).count(), 0);
    await b.getByRole('button', { name: 'Accept booking', exact: true }).click();
    await responsiveCapture(b, 'booking-confirmation');
    await b.getByRole('button', { name: 'Confirm accept booking', exact: true }).click();
    await b.getByRole('button', { name: 'Open conversation', exact: true }).waitFor();
    await ready(b);
    await b.screenshot({
      path: path.join(folder, 'accepted-booking-390x844.png'),
      animations: 'disabled',
    });
    await go(a, '/notifications', 'Notifications');
    await a.getByText('Booking accepted', { exact: true }).waitFor();
    await a.getByRole('button', { name: 'View booking', exact: true }).click();
    await a.getByRole('button', { name: 'Open conversation', exact: true }).click();
    await a
      .getByRole('textbox', { name: 'Message', exact: true })
      .fill('Hello from the client via Spring.');
    let disconnected = false;
    await a.route('**/api/v1/conversations/*/messages', async (route) => {
      if (route.request().method() === 'POST' && !disconnected) {
        disconnected = true;
        await route.abort('internetdisconnected');
      } else await route.continue();
    });
    await a.getByRole('button', { name: 'Send message', exact: true }).click();
    await a
      .getByText(/Cannot reach Castly/)
      .first()
      .waitFor();
    assert.equal(
      await a.getByRole('textbox', { name: 'Message', exact: true }).inputValue(),
      'Hello from the client via Spring.',
    );
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
    await a.screenshot({
      path: path.join(folder, 'conversation-390x844.png'),
      animations: 'disabled',
    });
    const denied = await fetch(api + '/bookings/' + bookingId, {
      headers: { Authorization: 'Bearer ' + token(outsider) },
    });
    assert.equal(denied.status, 404);
    await go(a, '/booking-detail/' + bookingId, 'Booking details');
    await a.getByRole('button', { name: 'Cancel booking', exact: true }).click();
    await a.getByRole('button', { name: 'Confirm cancel booking', exact: true }).click();
    await a.getByText('Cancelled', { exact: true }).waitFor();
    await go(a, '/safety/disputes?booking=' + bookingId, 'Booking disputes');
    await a.getByRole('textbox', { name: 'Describe the booking dispute', exact: true })
      .fill('The cancellation affected our agreed creative project schedule.');
    await a.getByRole('checkbox').click();
    await a.getByRole('button', { name: 'Submit dispute', exact: true }).click();
    await a.getByText('Status: OPEN', { exact: true }).waitFor();
    const disputeId = new URL(a.url()).pathname.split('/').pop();
    const outsiderDispute = await fetch(api + '/disputes/' + disputeId, {
      headers: { Authorization: 'Bearer ' + token(outsider) },
    });
    assert.equal(outsiderDispute.status, 404);
    await a.getByRole('textbox', { name: 'Additional note or appeal explanation', exact: true })
      .fill('The call time and location had already been agreed in messages.');
    await a.getByRole('button', { name: 'Add private note', exact: true }).click();
    await a.getByText('The call time and location had already been agreed in messages.', { exact: true }).waitFor();
    await noOverflow(a);
    console.log('PASS: real browser + Spring + PostgreSQL dispute filing, private note and outsider denial.');
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
    // Additional decisions through the rendered UI; setup requests still use the real API.
    async function request(project) {
      const response = await fetch(api + '/bookings', {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + token(client), 'Content-Type': 'application/json' },
        body: JSON.stringify({
          talent_id: talent,
          project_type: project,
          location: 'Mumbai studio',
          requirements: 'Additional UI decision regression',
          proposed_date: new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10),
          indicative_budget: 18000,
        }),
      });
      assert.equal(response.status, 201);
      return (await response.json()).id;
    }
    const rejected = await request('Rejected editorial');
    await go(b, '/booking-detail/' + rejected, 'Booking details');
    await b.getByRole('button', { name: 'Reject booking', exact: true }).click();
    await b.getByRole('button', { name: 'Confirm reject booking', exact: true }).click();
    await b.getByText('Declined', { exact: true }).waitFor();
    const pending = await request('Cancelled pending editorial');
    await go(a, '/booking-detail/' + pending, 'Booking details');
    await a.getByRole('button', { name: 'Cancel booking', exact: true }).click();
    await a.getByRole('button', { name: 'Confirm cancel booking', exact: true }).click();
    await a.getByText('Cancelled', { exact: true }).waitFor();
    const accepted = await request('Talent cancellation editorial');
    await go(b, '/booking-detail/' + accepted, 'Booking details');
    await b.getByRole('button', { name: 'Accept booking', exact: true }).click();
    await b.getByRole('button', { name: 'Confirm accept booking', exact: true }).click();
    await b.getByRole('button', { name: 'Cancel booking', exact: true }).click();
    await b.getByRole('button', { name: 'Confirm cancel booking', exact: true }).click();
    await b.getByText('Cancelled', { exact: true }).waitFor();
    await go(b, '/subscriptions', 'Membership');
    await b.getByText('Creator Pro', { exact: true }).waitFor();
    assert.equal(
      await b.getByRole('button', { name: 'Purchases unavailable', exact: true }).isDisabled(),
      true,
    );
    await b.getByRole('button', { name: 'Founding Creator Offer', exact: true }).click();
    await b
      .getByRole('textbox', { name: 'Public promotional post URL', exact: true })
      .fill('https://instagram.com/reel/browser-founding-creator');
    await b.getByRole('button', { name: 'Submit for review', exact: true }).click();
    await b.getByText('Status: pending', { exact: true }).waitFor();
    const provision = spawnSync(
      'docker',
      [
        'exec',
        'castly-spring-test-db',
        'psql',
        '-U',
        'postgres',
        '-d',
        'castly_spring_test',
        '-v',
        'ON_ERROR_STOP=1',
        '-c',
        `insert into private.subscription_admins values ('${client}')`,
      ],
      { encoding: 'utf8' },
    );
    assert.equal(provision.status, 0, provision.stderr);
    await go(a, '/subscriptions/review', 'Founding offer review');
    await a
      .getByRole('textbox', { name: 'Review reason and evidence', exact: true })
      .fill('Verified creator identity and public Castly promotional video');
    await a.getByRole('button', { name: 'Approve 12 months', exact: true }).click();
    await a.getByText('No pending claims.', { exact: true }).waitFor();
    await b.getByRole('button', { name: 'Refresh status', exact: true }).click();
    await b.getByText('Status: approved', { exact: true }).waitFor();
    await go(b, '/subscriptions', 'Membership');
    await b.getByText('Your membership: Creator Pro', { exact: true }).waitFor();
    await noOverflow(b);
    const discovery = await fetch(api + '/talents/' + talent);
    assert.equal((await discovery.json()).premium_discovery_eligible, true);
    await b.screenshot({
      path: path.join(folder, 'creator-pro-390x844.png'),
      animations: 'disabled',
    });
    console.log(
      'PASS: subscription catalog, disabled checkout, creator claim submission, trusted admin review, approved status, 12-month membership and public discovery eligibility through real browser + Spring + PostgreSQL.',
    );
    assert.deepEqual(bypass, []);
    assert.deepEqual(errors, []);
    console.log(
      'PASS: real Java HTTP/JWT/JWKS + PostgreSQL + browser: talent create/edit/publish, favourites, booking creation/acceptance/cancellation, conversation creation, two-party sending/history, recipient notifications/navigation, offline draft recovery, outsider denial, paused sending, requested/accepted cancellation and rejection UI, and no direct PostgREST writes. Supabase Auth/JWKS are local fixtures; hosted Realtime is not tested.',
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
