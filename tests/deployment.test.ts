import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
const require = createRequire(import.meta.url);
const yaml = require('js-yaml');
const { checkReleaseEnvironment } = require('../scripts/check-release-env.cjs');

test('release client configuration fails closed without disclosing server credential values', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'castly-release-env-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const env = {
    EXPO_PUBLIC_BACKEND_MODE: 'supabase', EXPO_PUBLIC_BUSINESS_API_MODE: 'spring',
    EXPO_PUBLIC_ISOLATED_SUPABASE_FALLBACK: 'false',
    EXPO_PUBLIC_API_URL: 'https://castly-api.onrender.com',
    EXPO_PUBLIC_SUPABASE_URL: 'https://release-fixture.supabase.co',
    EXPO_PUBLIC_SUPABASE_ANON_KEY: 'sb_publishable_release_fixture',
  };
  assert.doesNotThrow(() => checkReleaseEnvironment(env, root));
  for (const change of [
    { EXPO_PUBLIC_API_URL: '' }, { EXPO_PUBLIC_API_URL: 'http://localhost:8080' },
    { EXPO_PUBLIC_API_URL: 'https://castly-api.onrender.com/api/v1' },
    { EXPO_PUBLIC_API_URL: 'https://user:password@castly-api.onrender.com' },
    { EXPO_PUBLIC_API_URL: 'https://YOUR-RENDER-SERVICE.onrender.com' },
    { EXPO_PUBLIC_BACKEND_MODE: 'mock' }, { EXPO_PUBLIC_BUSINESS_API_MODE: 'supabase' },
    { EXPO_PUBLIC_ISOLATED_SUPABASE_FALLBACK: 'true' },
    { EXPO_PUBLIC_SUPABASE_ANON_KEY: 'sb_secret_never-log' },
    { DATABASE_PASSWORD: 'private-value-never-log' },
  ]) assert.throws(() => checkReleaseEnvironment({ ...env, ...change }, root), (e: Error) => {
    assert.ok(!e.message.includes('private-value-never-log'));
    assert.ok(!e.message.includes('sb_secret_never-log'));
    return true;
  });
});

test('Render separates backend secrets and public static configuration and cannot auto-migrate', () => {
  const backend = yaml.load(fs.readFileSync('render.yaml', 'utf8')).services[0];
  assert.equal(backend.runtime, 'docker');
  assert.equal(backend.rootDir, 'backend');
  assert.equal(backend.healthCheckPath, '/actuator/health');
  assert.equal(backend.autoDeployTrigger, 'off');
  assert.equal(backend.preDeployCommand, undefined);
  const settings = new Map(backend.envVars.map((v: any) => [v.key, v]));
  assert.equal((settings.get('CASTLY_NOTIFICATIONS_ENABLED') as any).value, 'false');
  for (const key of ['DATABASE_PASSWORD','DATABASE_URL','DATABASE_USERNAME','SUPABASE_JWT_ISSUER','JWT_SIGNING_MODE'])
    assert.equal((settings.get(key) as any).sync, false);
  const web = yaml.load(fs.readFileSync('render-web.yaml', 'utf8')).services[0];
  assert.equal(web.runtime, 'static');
  assert.equal(web.autoDeployTrigger, 'off');
  assert.ok(web.buildCommand.includes('check:release-env'));
  assert.ok(web.envVars.every((v: any) => !v.key.startsWith('DATABASE_')));
  assert.deepEqual(web.routes, [{ type: 'rewrite', source: '/*', destination: '/index.html' }]);
  const dockerfile = fs.readFileSync('backend/Dockerfile', 'utf8');
  assert.match(dockerfile, /USER 10001:10001/);
  assert.match(dockerfile, /ENTRYPOINT \["java"/);
  assert.doesNotMatch(dockerfile, /db push|flyway|liquibase|DATABASE_PASSWORD|COPY \. \./i);
});

test('EAS preview creates APK and production creates AAB with explicit Spring release configuration', () => {
  const eas = JSON.parse(fs.readFileSync('eas.json', 'utf8'));
  assert.equal(eas.cli.appVersionSource, 'remote');
  assert.equal(eas.build.preview.android.buildType, 'apk');
  assert.equal(eas.build.preview.distribution, 'internal');
  assert.equal(eas.build.production.android.buildType, 'app-bundle');
  assert.equal(eas.build.production.distribution, 'store');
  for (const profile of ['preview','production']) {
    assert.equal(eas.build[profile].environment, profile);
    assert.equal(eas.build[profile].env.CASTLY_RELEASE_CHECK, '1');
    assert.equal(eas.build[profile].env.EXPO_PUBLIC_BUSINESS_API_MODE, 'spring');
    assert.equal(eas.build[profile].env.EXPO_PUBLIC_ISOLATED_SUPABASE_FALLBACK, 'false');
    assert.equal(eas.build[profile].env.EXPO_PUBLIC_API_URL, undefined);
  }
});
