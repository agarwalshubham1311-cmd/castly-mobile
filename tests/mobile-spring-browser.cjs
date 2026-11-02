// Deterministic browser-only HTTP fixture. No hosted credentials, DB connection, tokens in logs or screenshots.
process.env.TEST_URL ||= 'http://127.0.0.1:8086';
const { assert, launch, go, ready } = require('./browser-utils.cjs');
const fs = require('node:fs');
const path = require('node:path');
const { Buffer } = require('node:buffer');
const id = (n) => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`;
const stamp = '2026-11-02T00:00:00Z';
const accounts = new Map([
  [
    id(1),
    {
      id: id(1),
      display_name: 'Fixture Client',
      primary_role: 'client',
      created_at: stamp,
      updated_at: stamp,
    },
  ],
  [
    id(2),
    {
      id: id(2),
      display_name: 'Fixture Talent',
      primary_role: 'talent',
      created_at: stamp,
      updated_at: stamp,
    },
  ],
  [
    id(3),
    {
      id: id(3),
      display_name: 'New Client',
      primary_role: null,
      created_at: stamp,
      updated_at: stamp,
    },
  ],
]);
const profiles = Array.from({ length: 201 }, (_, n) => ({
  id: id(n + 10),
  display_name: n === 200 ? 'Far Page Talent' : `Creator ${n}`,
  category_id: 'creators',
  city: 'Mumbai',
  day_rate: 10000,
  bio: 'Fixture public profile',
  skills: ['Video'],
  available: true,
  published: true,
  portrait_path: null,
  created_at: stamp,
  updated_at: stamp,
}));
profiles[0] = { ...profiles[0], id: id(2), display_name: 'Fixture Talent', category_id: 'actors' };
const assets = [
  {
    id: id(600),
    talent_id: id(2),
    path: id(2) + '/photo.jpg',
    label: 'Original caption',
    position: 0,
    media_type: 'image',
    created_at: stamp,
  },
];
const categories = ['actors', 'models', 'creators', 'musicians', 'photographers'].map((id) => ({
  id,
  name: id[0].toUpperCase() + id.slice(1),
}));
const bookings = Array.from({ length: 101 }, (_, n) => ({
  id: id(700 + n),
  client_id: id(1),
  talent_id: id(2),
  project_type: `Fixture booking ${n}`,
  proposed_date: '2026-12-01',
  location: 'Mumbai',
  requirements: 'Fixture project requirements',
  indicative_budget: 1000,
  status: 'requested',
  agreed_rate: null,
  client_name: 'Fixture Client',
  talent_name: 'Fixture Talent',
  created_at: stamp,
  updated_at: stamp,
}));
let failCatalog = false,
  forbiddenBookings = false,
  expireAll = false,
  rejectRefresh = false,
  expireFirst = true,
  refreshes = 0,
  databaseRequests = 0;
const seen = [];
const tokens = new Map();
function session(subject) {
  const user = {
    id: subject,
    email:
      subject === id(1)
        ? 'client@example.test'
        : subject === id(2)
          ? 'talent@example.test'
          : 'new@example.test',
    aud: 'authenticated',
    role: 'authenticated',
    app_metadata: { provider: 'email', providers: ['email'] },
    user_metadata: {},
    identities: [],
    created_at: stamp,
  };
  const encode = (x) => Buffer.from(JSON.stringify(x)).toString('base64url');
  const access =
    encode({ alg: 'HS256', typ: 'JWT' }) +
    '.' +
    encode({
      sub: subject,
      exp: Math.floor(Date.now() / 1000) + 3600,
      aud: 'authenticated',
      role: 'authenticated',
      version: refreshes,
    }) +
    '.fixture';
  tokens.set(access, subject);
  return {
    access_token: access,
    refresh_token: 'fixture-' + subject,
    expires_in: 3600,
    token_type: 'bearer',
    user,
  };
}
(async () => {
  const browser = await launch();
  try {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
    const errors = [];
    const page = await context.newPage();
    page.on('pageerror', (e) => errors.push(e.message));
    for (const pattern of ['**/rest/v1/**', '**/rest/v1/rpc/**'])
      await context.route(pattern, async (route) => {
        databaseRequests++;
        await route.abort();
      });
    await context.route('**/auth/v1/**', async (route) => {
      const request = route.request(),
        url = new URL(request.url());
      const body = request.postDataJSON() || {};
      const finish = (value, status = 200) =>
        route.fulfill({
          status,
          contentType: 'application/json',
          headers: { 'Access-Control-Allow-Origin': '*' },
          body: JSON.stringify(value),
        });
      if (url.pathname.endsWith('/token')) {
        if (url.searchParams.get('grant_type') === 'refresh_token') {
          refreshes++;
          if (rejectRefresh)
            return finish({ code: 'refresh_token_not_found', msg: 'Refresh token expired' }, 400);
          return finish(session(body.refresh_token.replace('fixture-', '')));
        }
        return finish(
          session(
            body.email === 'client@example.test'
              ? id(1)
              : body.email === 'talent@example.test'
                ? id(2)
                : id(3),
          ),
        );
      }
      if (url.pathname.endsWith('/logout')) return route.fulfill({ status: 204 });
      return finish({}, 404);
    });
    await context.route('**/storage/v1/**', async (route) => {
      if (route.request().method() === 'GET')
        return route.fulfill({
          status: 200,
          contentType: 'image/jpeg',
          body: fs.readFileSync(path.join(__dirname, '../assets/talent/portrait-01.jpg')),
        });
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ signedURL: '/object/sign/portfolio/fixture.jpg?fixture=true' }),
      });
    });
    await context.route('**/api/v1/**', async (route) => {
      const request = route.request(),
        url = new URL(request.url());
      const relative = url.pathname.replace('/api/v1/', ''),
        method = request.method();
      const subject = tokens.get((request.headers().authorization || '').replace('Bearer ', ''));
      const body = request.postData() ? request.postDataJSON() : {};
      seen.push({ path: relative, method, body, subject, offset: url.searchParams.get('offset') });
      const finish = (value, status = 200) =>
        route.fulfill({
          status,
          contentType: 'application/json',
          headers: {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Headers': 'Authorization,Content-Type',
            'Access-Control-Allow-Methods': 'GET,POST,PUT,PATCH,DELETE,OPTIONS',
          },
          body: status === 204 ? '' : JSON.stringify(value),
        });
      if (method === 'OPTIONS') return finish({}, 204);
      if (relative === 'categories') return finish(categories);
      if (relative === 'talents' || relative === 'portfolio') {
        if (failCatalog) return finish({ detail: 'Fixture catalog temporarily unavailable' }, 503);
        const rows = relative === 'talents' ? profiles : assets,
          offset = Number(url.searchParams.get('offset') || 0),
          limit = Number(url.searchParams.get('limit') || 200);
        return finish(rows.slice(offset, offset + limit));
      }
      const publicProfile = relative.match(/^talents\/([^/]+)$/),
        portfolio = relative.match(/^talents\/([^/]+)\/portfolio$/);
      if (publicProfile && publicProfile[1] !== 'me')
        return finish(
          profiles.find((p) => p.id === publicProfile[1]) || {},
          profiles.some((p) => p.id === publicProfile[1]) ? 200 : 404,
        );
      if (portfolio) return finish(assets.filter((a) => a.talent_id === portfolio[1]));
      if (!subject || expireAll) return finish({}, 401);
      if (relative === 'account') {
        if (expireFirst) {
          expireFirst = false;
          return finish({}, 401);
        }
        if (method === 'PATCH') accounts.get(subject).display_name = body.display_name;
        return finish(accounts.get(subject));
      }
      if (relative === 'account/onboarding') {
        Object.assign(accounts.get(subject), {
          display_name: body.account_name,
          primary_role: body.selected_role,
        });
        return finish(accounts.get(subject));
      }
      if (relative === 'bookings')
        return forbiddenBookings
          ? finish({ detail: 'You cannot access these bookings.' }, 403)
          : finish(
              bookings
                .filter((row) => row.client_id === subject || row.talent_id === subject)
                .slice(
                  Number(url.searchParams.get('offset') || 0),
                  Number(url.searchParams.get('offset') || 0) + 100,
                ),
            );
      if (
        relative === 'favourites' ||
        relative === 'conversations' ||
        relative === 'account/entitlements'
      )
        return finish([]);
      if (relative === 'talents/me') {
        const profile = profiles.find((p) => p.id === subject);
        if (method === 'PUT' || method === 'POST') Object.assign(profile, body);
        return finish(profile || {}, profile ? 200 : 404);
      }
      if (relative.startsWith('portfolio/') && method === 'PATCH') {
        const asset = assets.find((a) => a.id === relative.split('/')[1]);
        if (!asset || asset.talent_id !== subject) return finish({ detail: 'Not permitted' }, 403);
        Object.assign(asset, body);
        return finish(asset);
      }
      return finish({}, 404);
    });
    async function login(email) {
      await go(page, '/login', 'Welcome back');
      await page.getByRole('textbox', { name: 'Email', exact: true }).fill(email);
      await page.getByLabel('Password', { exact: true }).fill('fixture-password');
      await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    }
    await go(page, '/talent/' + id(210), 'Far Page Talent');
    await page.getByRole('button', { name: 'View gallery', exact: true }).click();
    await page.getByText('Published portfolio', { exact: true }).waitFor();
    await go(page, '/search', 'Search talent');
    await page
      .getByRole('textbox', { name: 'Search talent by name, skill or city', exact: true })
      .fill('Far Page Talent');
    await page
      .getByRole('button', { name: "View Far Page Talent's profile", exact: true })
      .waitFor();
    assert.ok(
      seen.some((r) => r.path === 'talents' && r.offset === '200'),
      'Catalog must include the second page',
    );
    assert.ok(
      seen.some((r) => r.path === 'categories'),
      'Categories come from Spring',
    );
    failCatalog = true;
    await page.reload();
    await page.getByText('Fixture catalog temporarily unavailable', { exact: true }).waitFor();
    failCatalog = false;
    await page.getByRole('button', { name: 'Retry discovery', exact: true }).click();
    await page
      .getByRole('textbox', { name: 'Search talent by name, skill or city', exact: true })
      .fill('Far Page Talent');
    await page
      .getByRole('button', { name: "View Far Page Talent's profile", exact: true })
      .waitFor();
    await login('client@example.test');
    await page.getByRole('button', { name: 'Account settings', exact: true }).waitFor();
    assert.equal(refreshes, 1, 'A private 401 refreshes the same account exactly once');
    forbiddenBookings = true;
    await go(page, '/bookings', 'Bookings');
    await page.getByText('You cannot access these bookings.', { exact: true }).waitFor();
    forbiddenBookings = false;
    await page.getByRole('button', { name: 'Retry', exact: true }).click();
    await page.getByRole('button', { name: 'Load more bookings', exact: true }).click();
    await page
      .getByRole('button', { name: 'View Fixture booking 100 booking', exact: true })
      .waitFor();
    bookings[0].status = 'cancelled';
    await page.getByRole('button', { name: 'Refresh bookings', exact: true }).click();
    await page.getByRole('button', { name: 'Cancelled', exact: true }).click();
    await page
      .getByRole('button', { name: 'View Fixture booking 0 booking', exact: true })
      .waitFor();
    await go(page, '/account', 'Profile');
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await login('new@example.test');
    await page.getByRole('button', { name: 'Client', exact: true }).click();
    await page.getByRole('textbox', { name: 'Account name', exact: true }).fill('Onboarded Client');
    await page.getByRole('button', { name: 'Continue', exact: true }).click();
    await page.getByRole('button', { name: 'Account settings', exact: true }).click();
    await page.getByRole('textbox', { name: 'Account name', exact: true }).fill('Renamed Client');
    const renamed = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/v1/account') && response.request().method() === 'PATCH',
    );
    await page.getByRole('button', { name: 'Save account', exact: true }).click();
    await renamed;
    await go(page, '/account', 'Profile');
    await page.getByRole('button', { name: 'Account settings', exact: true }).waitFor();
    assert.equal(accounts.get(id(3)).display_name, 'Renamed Client');
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await login('talent@example.test');
    await page.getByRole('button', { name: 'Manage talent profile', exact: true }).click();
    await page
      .getByRole('textbox', { name: 'Photograph caption 1', exact: true })
      .fill('Updated editorial caption');
    await page.getByRole('button', { name: 'Save photograph details 1', exact: true }).click();
    await page.getByText('Portfolio details saved.', { exact: true }).waitFor();
    assert.equal(assets[0].label, 'Updated editorial caption');
    assert.ok(
      seen.some(
        (r) => r.path === 'portfolio/' + id(600) && r.method === 'PATCH' && r.subject === id(2),
      ),
    );
    expireAll = true;
    rejectRefresh = true;
    await go(page, '/account');
    await page.getByRole('button', { name: 'Sign in', exact: true }).waitFor();
    await page.getByText('Your session has expired. Sign in again.', { exact: true }).waitFor();
    await ready(page);
    assert.equal(databaseRequests, 0, 'Spring mode cannot use PostgREST/RPC');
    assert.deepEqual(errors, []);
    console.log(
      'PASS deterministic mobile HTTP fixture: 201-profile pagination, categories, deep links/gallery, retry, same-user refresh, 403, onboarding/account, portfolio metadata editing, terminal expiry, and zero direct Supabase business requests. No hosted requests.',
    );
  } finally {
    await browser.close();
  }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
