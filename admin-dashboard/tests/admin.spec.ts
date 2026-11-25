import AxeBuilder from '@axe-core/playwright';
import { test, expect, type Page } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
const u = (n: number) => '00000000-0000-0000-0000-' + String(n).padStart(12, '0');
async function signin(page: Page, email: string) {
  await page.goto('/login');
  await page.getByLabel('Email address').fill(email);
  await page.getByLabel('Password', { exact: true }).fill('local-fixture-only');
  await page.getByRole('button', { name: 'Continue securely' }).click();
  await expect(page.getByRole('heading', { name: 'Verify your access' })).toBeVisible();
  await page.getByLabel('Authenticator code').fill('123456');
  await page.getByRole('button', { name: 'Verify MFA' }).click();
}
test('real MFA/JWT/session, invitation activation, audited change and recipient isolation', async ({
  page,
  browser,
}) => {
  await signin(page, 'super@example.test');
  await expect(page.getByRole('heading', { name: 'Administrator overview' })).toBeVisible();
  expect(
    (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze()).violations,
  ).toEqual([]);
  const cookies = await page.context().cookies();
  expect(
    cookies
      .filter((c) => c.name.startsWith('castly_admin_'))
      .every((c) => c.httpOnly && c.sameSite === 'Strict'),
  ).toBeTruthy();
  expect(await page.evaluate(() => localStorage.length)).toBe(0);
  await page.getByRole('link', { name: 'Administrators', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Administrator directory' })).toBeVisible();
  await page.getByLabel('Approved account UUID').fill(u(40));
  await page.getByLabel('Initial role').selectOption('ANALYST');
  await page.getByLabel('Approval reason').fill('Local browser analyst approval');
  await page.getByRole('button', { name: 'Create invitation' }).click();
  const token = (await page.getByTestId('invitation-secret').textContent())!.trim();
  expect(token).toHaveLength(43);
  await page.getByRole('link', { name: 'Audit logs' }).click();
  await expect(page.getByText('invitation.created', { exact: true })).toBeVisible();
  expect(await page.content()).not.toContain(token);
  const guest = await browser.newContext(),
    invitee = await guest.newPage();
  await signin(invitee, 'invitee@example.test');
  await expect(
    invitee.getByText(
      'MFA verified. If invited, activate your invitation below; otherwise contact operations.',
    ),
  ).toBeVisible();
  await invitee.getByRole('link', { name: /Activate an approved invitation/ }).click();
  await invitee.getByLabel('Invitation secret').fill(token);
  await invitee.getByLabel('Acceptance reason').fill('Approved invitation accepted');
  await invitee.getByRole('button', { name: 'Activate access' }).click();
  await expect(invitee.getByRole('heading', { name: 'Administrator overview' })).toBeVisible();
  await expect(invitee.getByRole('link', { name: 'Administrators', exact: true })).toHaveCount(0);
  const denied = await invitee.request.get('/api/backend/audit');
  expect(denied.status()).toBe(403);
  const csrf = await invitee.request.post('/api/backend/invitations', {
    headers: { Origin: 'https://evil.test' },
    data: {},
  });
  expect(csrf.status()).toBe(403);
  await invitee.getByRole('button', { name: 'Sign out' }).click();
  await expect(invitee.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
  expect((await guest.cookies()).filter((c) => c.name.startsWith('castly_admin_'))).toHaveLength(0);
  await guest.close();
  const dir = path.resolve(process.env.CASTLY_ADMIN_SCREENSHOT_DIR ?? '../artifacts/phase7');
  fs.mkdirSync(dir, { recursive: true });
  await page.goto('/dashboard');
  await expect(page.getByRole('table').getByRole('row').nth(1)).toBeVisible();
  for (const width of [360, 390, 430, 1440]) {
    await page.setViewportSize({ width, height: 932 });
    await expect(page.getByRole('heading', { name: 'Administrator overview' })).toBeVisible();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
    ).toBeTruthy();
    await page.screenshot({ path: path.join(dir, `overview-${width}.png`), fullPage: true });
  }
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto('/settings/admins');
  await expect(page.getByRole('heading', { name: 'Administrator directory' })).toBeVisible();
  await expect(page.getByRole('row').filter({ hasText: u(31) })).toBeVisible();
  await page.screenshot({ path: path.join(dir, 'administrators-1440.png'), fullPage: true });
  expect(
    (await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze()).violations,
  ).toEqual([]);
  const row = page.getByRole('row').filter({ hasText: u(32) });
  await row.getByRole('button', { name: 'Manage access' }).click();
  await page.getByRole('checkbox', { name: 'MODERATOR', exact: true }).uncheck();
  await page.getByRole('checkbox', { name: 'ANALYST', exact: true }).check();
  await page.getByLabel('Reason for change').fill('Local browser role update');
  await page.getByRole('button', { name: 'Confirm access change' }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(row).toContainText('ANALYST');
  await page.goto('/audit');
  await expect(page.getByText('principal.changed', { exact: true })).toBeVisible();
  await page.screenshot({ path: path.join(dir, 'audit-1440.png'), fullPage: true });
  const bad = await page.request.get('/api/backend/account');
  expect(bad.status()).toBe(404);
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
});

test('guest and MFA-authenticated non-admin remain outside protected dashboard and APIs', async ({
  page,
}) => {
  await page.goto('/dashboard');
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
  expect((await page.request.get('/api/backend/audit')).status()).toBe(401);
  const noOrigin = await page.request.post('/api/auth/login', {
    data: { email: 'regular@example.test', password: 'local-fixture-only' },
  });
  expect(noOrigin.status()).toBe(403);
  await signin(page, 'regular@example.test');
  await expect(
    page.getByText(
      'MFA verified. If invited, activate your invitation below; otherwise contact operations.',
    ),
  ).toBeVisible();
  // Without a session cookie, Spring first rejects the missing required header.
  expect((await page.request.get('/api/backend/me')).status()).toBe(400);
  const sessionCookie = (await page.context().cookies()).find(
    (c) => c.name === 'castly_admin_session',
  );
  expect(sessionCookie).toBeUndefined();
  await page.goto('/settings/admins');
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
  // A syntactically valid forged cookie must reach and fail actual server authorization.
  await page.context().addCookies([
    {
      name: 'castly_admin_session',
      value: u(99),
      url: 'http://127.0.0.1:3017',
      httpOnly: true,
      sameSite: 'Strict',
    },
  ]);
  expect((await page.request.get('/api/backend/me')).status()).toBe(403);
  expect((await page.request.get('/api/backend/principals')).status()).toBe(403);
  expect((await page.request.get('/api/backend/audit')).status()).toBe(403);
  expect((await page.request.get('/api/backend/account')).status()).toBe(404);
  await page.goto('/settings/admins');
  await expect(page.getByRole('heading', { name: 'Restricted operations' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Administrator directory' })).toHaveCount(0);
});
