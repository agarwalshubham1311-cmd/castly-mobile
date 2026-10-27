const fs = require('node:fs');
const path = require('node:path');
const { parseEnv } = require('node:util');
const { spawnSync } = require('node:child_process');
const local = path.resolve(__dirname, '../../.env.local');
const mobile = fs.existsSync(local) ? parseEnv(fs.readFileSync(local, 'utf8')) : {};
const issuer =
  process.env.SUPABASE_JWT_ISSUER ||
  (mobile.EXPO_PUBLIC_SUPABASE_URL
    ? mobile.EXPO_PUBLIC_SUPABASE_URL.replace(/\/$/, '') + '/auth/v1'
    : '');
if (!issuer || issuer.includes('YOUR_'))
  throw new Error('Configure SUPABASE_JWT_ISSUER or the existing local Expo Supabase project URL.');
const url = new URL(issuer);
if (
  url.protocol !== 'https:' ||
  url.username ||
  url.password ||
  url.search ||
  url.hash ||
  !url.pathname.endsWith('/auth/v1')
)
  throw new Error('Use the exact HTTPS Supabase Auth issuer.');
const r = spawnSync('curl', ['-fsS', '--max-time', '15', issuer + '/.well-known/jwks.json'], {
  encoding: 'utf8',
});
if (r.status !== 0) {
  console.error('Public JWKS verification failed: ' + r.stderr.trim());
  process.exit(1);
}
const jwks = JSON.parse(r.stdout);
const keys = jwks.keys || [];
const algorithms = [...new Set(keys.map((k) => k.alg || k.kty))];
console.log(
  JSON.stringify(
    {
      issuer,
      publicKeyCount: keys.length,
      algorithms,
      recommendedMode: keys.length ? 'jwks' : 'supabase-auth',
      accessTokenVerified: false,
      note: 'Public key compatibility only. Validate a real user access token locally before deployment; no signing secret or service-role key is needed.',
    },
    null,
    2,
  ),
);
