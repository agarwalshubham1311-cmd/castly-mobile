const { assert, launch, go, ready } = require('./browser-utils.cjs');
const fs = require('node:fs');
const path = require('node:path');
const { Buffer } = require('node:buffer');
const api = 'https://castly-test.supabase.co';
const uid = {
  client: '00000000-0000-0000-0000-000000000001',
  talent: '00000000-0000-0000-0000-000000000003',
  public: '00000000-0000-0000-0000-000000000004',
};
const stamp = '2026-10-22T00:00:00Z';
const accounts = {
  [uid.client]: {
    id: uid.client,
    display_name: 'Test Client',
    primary_role: 'client',
    created_at: stamp,
    updated_at: stamp,
  },
  [uid.talent]: {
    id: uid.talent,
    display_name: 'Test Talent',
    primary_role: null,
    created_at: stamp,
    updated_at: stamp,
  },
};
let profiles = [
  {
    id: uid.public,
    display_name: 'Published Talent',
    category_id: 'actors',
    city: 'Mumbai',
    day_rate: 18000,
    bio: 'A published creative portfolio.',
    skills: ['Acting'],
    available: true,
    published: true,
    portrait_path: null,
    created_at: stamp,
    updated_at: stamp,
  },
];
const saved = {};
let assets = [];
const writes = [];
let offline = false;
let failSave = false;
let failAccount = false;
function user(id) {
  return {
    id,
    aud: 'authenticated',
    role: 'authenticated',
    email: id === uid.client ? 'client@example.test' : 'talent@example.test',
    app_metadata: { provider: 'email', providers: ['email'] },
    user_metadata: {},
    identities: [],
    created_at: stamp,
  };
}
function token(id) {
  return `${Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url')}.${Buffer.from(JSON.stringify({ sub: id, aud: 'authenticated', role: 'authenticated', exp: Math.floor(Date.now() / 1000) + 3600, iat: Math.floor(Date.now() / 1000), iss: api + '/auth/v1' })).toString('base64url')}.dGVzdA`;
}
async function mockApi(route) {
  const request = route.request();
  const url = new URL(request.url());
  if (offline) return route.abort('internetdisconnected');
  const headers = {
    'Content-Type': 'application/json',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': '*',
    'Access-Control-Allow-Methods': 'GET,POST,PATCH,DELETE,OPTIONS',
  };
  const respond = (body, status = 200) =>
    route.fulfill({ status, headers, body: status === 204 ? '' : JSON.stringify(body) });
  if (request.method() === 'OPTIONS') return respond(null, 204);
  let actor;
  try {
    actor = JSON.parse(
      Buffer.from((request.headers().authorization || '').split('.')[1], 'base64url').toString(),
    ).sub;
  } catch {
    /* anon */
  }
  const body = (request.headers()['content-type'] || '').includes('application/json')
    ? request.postDataJSON()
    : null;
  if (url.pathname === '/auth/v1/signup') return respond(user(uid.talent));
  if (url.pathname === '/auth/v1/token') {
    if (body.password === 'wrong')
      return respond({ msg: 'Invalid login credentials', code: 'invalid_credentials' }, 400);
    const id = body.email === 'client@example.test' ? uid.client : uid.talent;
    return respond({
      access_token: token(id),
      refresh_token: 'local-contract-fixture',
      expires_in: 3600,
      token_type: 'bearer',
      user: user(id),
    });
  }
  if (url.pathname === '/auth/v1/logout') return respond(null, 204);
  if (url.pathname === '/auth/v1/user') return respond(user(actor));
  if (url.pathname === '/rest/v1/users') {
    if (failAccount) return respond({ message: 'Account temporarily unavailable' }, 503);
    if (!actor) return respond({ message: 'Unauthorized' }, 401);
    if (request.method() === 'PATCH') {
      assert.deepEqual(Object.keys(body), ['display_name']);
      accounts[actor].display_name = body.display_name;
    }
    return respond(accounts[actor]);
  }
  if (url.pathname === '/rest/v1/rpc/complete_onboarding') {
    assert.equal(actor, uid.talent);
    assert.equal(accounts[actor].primary_role, null);
    accounts[actor] = {
      ...accounts[actor],
      primary_role: body.selected_role,
      display_name: body.account_name,
    };
    return respond(accounts[actor]);
  }
  if (url.pathname === '/rest/v1/talent_profiles') {
    const single = (request.headers().accept || '').includes('vnd.pgrst.object');
    if (request.method() === 'POST') {
      assert.equal(body.id, actor);
      assert.equal(accounts[actor].primary_role, 'talent');
      const row = { ...body, created_at: stamp, updated_at: stamp };
      profiles.push(row);
      writes.push({ kind: 'createProfile', keys: Object.keys(body) });
      return respond(row, 201);
    }
    if (request.method() === 'PATCH') {
      assert.equal('id' in body, false);
      assert.equal('created_at' in body, false);
      assert.equal('updated_at' in body, false);
      const row = profiles.find((p) => p.id === actor);
      Object.assign(row, body);
      writes.push({ kind: 'updateProfile', keys: Object.keys(body) });
      return respond(row);
    }
    let rows = profiles;
    if (url.searchParams.get('published') === 'eq.true') rows = rows.filter((p) => p.published);
    if (url.searchParams.get('id'))
      rows = rows.filter((p) => p.id === url.searchParams.get('id').slice(3));
    return respond(single ? rows[0] : rows);
  }
  if (url.pathname.startsWith('/storage/v1/object/sign/portfolio/')) {
    if (request.method() === 'POST') {
      assert.equal(body.expiresIn, 60);
      return respond({
        signedURL: url.pathname.replace('/storage/v1', '') + '?token=contract-fixture',
      });
    }
    return route.fulfill({
      status: 200,
      contentType: 'image/jpeg',
      body: fs.readFileSync(path.resolve(__dirname, '../assets/talent/portrait-01.jpg')),
    });
  }
  if (url.pathname.startsWith('/storage/v1/object/portfolio/')) {
    assert.equal(request.method(), 'POST');
    assert.ok(url.pathname.includes(actor));
    assert.match(request.headers()['content-type'], /image\/jpeg/);
    writes.push({ kind: 'upload', actor });
    return respond({ Key: url.pathname.split('/object/')[1] }, 200);
  }
  if (url.pathname === '/storage/v1/object/portfolio' && request.method() === 'DELETE') {
    writes.push({ kind: 'deleteObject', actor });
    return respond([]);
  }
  if (url.pathname === '/rest/v1/portfolio_assets') {
    if (request.method() === 'POST') {
      assert.equal(body.talent_id, actor);
      const row = {
        ...body,
        id: '30000000-0000-0000-0000-000000000001',
        media_type: 'image',
        created_at: stamp,
      };
      assets.push(row);
      return respond(row, 201);
    }
    if (request.method() === 'DELETE') {
      assets = assets.filter((asset) => asset.id !== url.searchParams.get('id').slice(3));
      return respond(null, 204);
    }
    return respond(assets);
  }
  if (
    url.pathname === '/rest/v1/bookings' ||
    url.pathname === '/rest/v1/conversations' ||
    url.pathname === '/rest/v1/messages' ||
    url.pathname === '/rest/v1/subscription_entitlements'
  )
    return respond([]);
  if (url.pathname === '/rest/v1/saved_talent') {
    if (!actor) return respond({ message: 'Unauthorized' }, 401);
    if (request.method() === 'POST') {
      assert.equal(body.user_id, actor);
      if (failSave) return respond({ message: 'Save unavailable. Try again.' }, 503);
      saved[actor] = [
        ...(saved[actor] || []),
        { user_id: actor, talent_id: body.talent_id, created_at: stamp },
      ];
      writes.push({ kind: 'save', actor });
      return respond(null, 201);
    }
    if (request.method() === 'DELETE') {
      saved[actor] = [];
      return respond(null, 204);
    }
    return respond(saved[actor] || []);
  }
  throw new Error('Unhandled Supabase contract request: ' + url.pathname);
}
(async () => {
  const browser = await launch();
  const folder = path.resolve(
    process.env.CASTLY_SCREENSHOT_DIR || path.join(__dirname, '../artifacts/spring-api/legacy-m1'),
  );
  fs.mkdirSync(folder, { recursive: true });
  try {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
    for (const pattern of ['**/auth/v1/**', '**/rest/v1/**', '**/storage/v1/**'])
      await context.route(pattern, mockApi);
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    async function screenshot(name) {
      await ready(page);
      await page.screenshot({ path: path.join(folder, name + '.png') });
    }
    async function login(email, password = 'correct-password') {
      await go(page, '/login', 'Welcome back');
      await page.getByRole('textbox', { name: 'Email', exact: true }).fill(email);
      await page.getByLabel('Password', { exact: true }).fill(password);
      await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    }
    await go(page, '/account', 'Welcome back');
    assert.ok(page.url().endsWith('/login'), 'private account requires login');
    await screenshot('login-390x844');
    await go(page, '/search', 'Search talent');
    await page.getByRole('button', { name: 'Save Published Talent', exact: true }).click();
    await page.getByRole('button', { name: 'Sign in', exact: true }).waitFor();
    assert.equal(writes.length, 0, 'anonymous save routes to login without a write');
    await page.getByRole('button', { name: 'New to Castly? Create account', exact: true }).click();
    await page.getByRole('textbox', { name: 'Your name', exact: true }).fill('Test Talent');
    await page.getByRole('textbox', { name: 'Email', exact: true }).fill('talent@example.test');
    await page.getByLabel('Password', { exact: true }).fill('new-password');
    await screenshot('signup-390x844');
    await page.getByRole('button', { name: 'Create account', exact: true }).click();
    await page.getByText('Check your email', { exact: true }).waitFor();
    await login('client@example.test', 'wrong');
    await page
      .getByRole('alert')
      .getByText('Email or password is incorrect.', { exact: true })
      .waitFor();
    await page.getByLabel('Password', { exact: true }).fill('correct-password');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.getByText('Test Client', { exact: true }).waitFor();
    await screenshot('client-account-390x844');
    await page.reload();
    await page.getByText('Test Client', { exact: true }).waitFor();
    assert.ok(page.url().endsWith('/account'), 'session restores after reload');
    await go(page, '/talent-edit', 'Talent account required');
    assert.equal(
      await page.getByRole('button', { name: 'Save talent profile', exact: true }).count(),
      0,
      'client cannot open editor',
    );
    await go(page, '/search', 'Search talent');
    await page.getByRole('button', { name: 'Save Published Talent', exact: true }).click();
    await page.getByRole('button', { name: 'Unsave Published Talent', exact: true }).waitFor();
    await go(page, '/saved', 'Saved talent');
    await page
      .getByRole('button', { name: "View Published Talent's profile", exact: true })
      .waitFor();
    await go(page, '/account', 'Test Client');
    await page.getByRole('button', { name: 'Account settings', exact: true }).click();
    await page.getByRole('textbox', { name: 'Account name', exact: true }).fill('Renamed Client');
    await page.getByRole('button', { name: 'Save account', exact: true }).click();
    await go(page, '/account', 'Renamed Client');
    failAccount = true;
    await page.reload();
    await page.getByText('Unable to load your account', { exact: true }).waitFor();
    failAccount = false;
    await page.getByRole('button', { name: 'Retry account', exact: true }).click();
    await page.getByText('Renamed Client', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await page.getByRole('button', { name: 'Sign in', exact: true }).waitFor();
    await login('talent@example.test');
    await page.getByText('How will you use Castly?', { exact: true }).waitFor();
    await page.getByRole('textbox', { name: 'Account name', exact: true }).fill('Test Talent');
    await page.getByRole('button', { name: 'Talent', exact: true }).click();
    await screenshot('onboarding-390x844');
    await page.getByRole('button', { name: 'Continue', exact: true }).click();
    await page.getByRole('button', { name: 'Manage talent profile', exact: true }).waitFor();
    await go(page, '/saved', 'Your shortlist starts here');
    assert.equal(
      await page.getByRole('button', { name: /View .*profile/ }).count(),
      0,
      'no previous-account shortlist leakage',
    );
    await go(page, '/account', 'Test Talent');
    await page.getByRole('button', { name: 'Manage talent profile', exact: true }).click();
    await page.getByRole('textbox', { name: 'Talent name', exact: true }).waitFor();
    await page.getByRole('textbox', { name: 'Talent name', exact: true }).fill('New Creative');
    await page.getByRole('textbox', { name: 'City', exact: true }).fill('Delhi');
    await page
      .getByRole('textbox', { name: 'Indicative daily rate (INR)', exact: true })
      .fill('15000');
    await page
      .getByRole('textbox', { name: 'Biography', exact: true })
      .fill('A new creative profile for the integration contract test.');
    await page.getByRole('button', { name: 'Save talent profile', exact: true }).click();
    await page
      .getByText('Draft profile saved. It is not visible in discovery.', { exact: true })
      .waitFor();
    assert.equal(profiles.find((p) => p.id === uid.talent).published, false);
    const portraitPicker = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: 'Choose portrait', exact: true }).click();
    await (
      await portraitPicker
    ).setFiles(path.resolve(__dirname, '../assets/talent/portrait-01.jpg'));
    await page
      .getByText('Portrait uploaded. Save the profile to apply it.', { exact: true })
      .waitFor();
    await page.getByRole('button', { name: 'Save talent profile', exact: true }).click();
    await page
      .getByText('Draft profile saved. It is not visible in discovery.', { exact: true })
      .waitFor();
    assert.ok(profiles.find((p) => p.id === uid.talent).portrait_path.startsWith(uid.talent + '/'));
    const portfolioPicker = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: 'Add portfolio photograph', exact: true }).click();
    await (
      await portfolioPicker
    ).setFiles(path.resolve(__dirname, '../assets/talent/portrait-02.jpg'));
    await page.getByText('Portfolio photograph added.', { exact: true }).waitFor();
    assert.equal(assets.length, 1);
    await page.getByRole('button', { name: 'Remove photograph 1', exact: true }).click();
    await page.getByText('No portfolio photographs yet.', { exact: true }).waitFor();
    assert.equal(assets.length, 0);
    assert.ok(writes.some((w) => w.kind === 'deleteObject'));
    await page.getByRole('textbox', { name: 'Talent name', exact: true }).fill('Edited Creative');
    await page.getByRole('switch', { name: 'Published in discovery', exact: true }).click();
    await page.getByRole('button', { name: 'Save talent profile', exact: true }).click();
    await page.getByText('Talent profile published.', { exact: true }).waitFor();
    await screenshot('talent-editor-390x844');
    await go(page, '/search', 'Search talent');
    await page
      .getByRole('button', { name: "View Edited Creative's profile", exact: true })
      .waitFor();
    failSave = true;
    await page.getByRole('button', { name: 'Save Published Talent', exact: true }).click();
    await page.getByText('Save unavailable. Try again.', { exact: true }).waitFor();
    assert.equal(
      await page.getByRole('button', { name: 'Unsave Published Talent', exact: true }).count(),
      0,
      'failed save is not shown as success',
    );
    failSave = false;
    offline = true;
    await go(page, '/search', 'Search talent');
    await page.getByText('Talent unavailable', { exact: true }).waitFor();
    await page
      .getByText(/Cannot reach Castly/)
      .first()
      .waitFor();
    await screenshot('offline-390x844');
    offline = false;
    await page.getByRole('button', { name: 'Retry saved talent', exact: true }).click();
    await page
      .getByRole('button', { name: 'Retry saved talent', exact: true })
      .waitFor({ state: 'hidden' });
    await page.getByRole('button', { name: 'Retry discovery', exact: true }).click();
    await page
      .getByRole('button', { name: "View Edited Creative's profile", exact: true })
      .waitFor();
    await go(page, '/messages', 'Messages are not available yet');
    assert.equal(
      await page.getByRole('textbox', { name: 'Local message', exact: true }).count(),
      0,
      'no live messaging UI',
    );
    const callbackContext = await browser.newContext({ viewport: { width: 390, height: 844 } });
    for (const pattern of ['**/auth/v1/**', '**/rest/v1/**', '**/storage/v1/**'])
      await callbackContext.route(pattern, mockApi);
    const callbackPage = await callbackContext.newPage();
    callbackPage.on('pageerror', (e) => errors.push(e.message));
    await go(callbackPage, '/signup', 'Your next creative chapter');
    await callbackPage.getByRole('textbox', { name: 'Your name', exact: true }).fill('Test Talent');
    await callbackPage
      .getByRole('textbox', { name: 'Email', exact: true })
      .fill('talent@example.test');
    await callbackPage.getByLabel('Password', { exact: true }).fill('new-password');
    await callbackPage.getByRole('button', { name: 'Create account', exact: true }).click();
    await callbackPage.getByText('Check your email', { exact: true }).waitFor();
    await go(
      callbackPage,
      '/auth/callback?error_description=Confirmation%20link%20expired',
      'Confirmation link expired',
    );
    await go(callbackPage, '/auth/callback?code=contract-test-code', 'Test Talent');
    assert.ok(
      callbackPage.url().endsWith('/account'),
      'PKCE code exchange returns to an authenticated account',
    );
    assert.deepEqual(errors, []);
    assert.ok(writes.some((w) => w.kind === 'createProfile'));
    assert.ok(writes.some((w) => w.kind === 'updateProfile'));
    console.log(
      'PASS: mocked Supabase HTTP contract: signup confirmation/PKCE callback, invalid login, session restore/logout, protected/role-aware routes, onboarding, account rename/retry, private shortlist isolation, talent create/edit/publish, web portrait/portfolio upload/removal, failed writes and offline recovery. No hosted Supabase project was contacted.',
    );
  } finally {
    await browser.close();
  }
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
