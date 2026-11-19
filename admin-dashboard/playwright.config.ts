import { defineConfig } from '@playwright/test';
import path from 'node:path';
import os from 'node:os';
export default defineConfig({
  testDir: './tests',
  testMatch: '*.spec.ts',
  workers: 1,
  timeout: 45000,
  reporter: [['list'], ['html', { open: 'never' }]],
  outputDir:
    process.env.CASTLY_ADMIN_TEST_OUTPUT ?? path.join(os.tmpdir(), 'castly-admin-e2e-results'),
  use: {
    baseURL: process.env.CASTLY_ADMIN_TEST_URL ?? 'http://127.0.0.1:3017',
    headless: true,
    launchOptions: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH
      ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH }
      : {},
  },
});
