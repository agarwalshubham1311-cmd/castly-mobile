const { Buffer } = require('node:buffer');
const { checkClientEnvironment } = require('./check-client-env.cjs');

function checkReleaseEnvironment(env = process.env, root = process.cwd()) {
  checkClientEnvironment(env, root);
  for (const name of ['DATABASE_URL', 'DATABASE_USERNAME', 'DATABASE_PASSWORD', 'SUPABASE_JWT_SECRET', 'SUPABASE_SERVICE_ROLE_KEY'])
    if (env[name]) throw new Error(`Remove server-only ${name} from the mobile/static-site build environment.`);
  if (env.EXPO_PUBLIC_BACKEND_MODE !== 'supabase' || env.EXPO_PUBLIC_BUSINESS_API_MODE !== 'spring'
      || env.EXPO_PUBLIC_ISOLATED_SUPABASE_FALLBACK !== 'false')
    throw new Error('Release builds require supabase Auth, spring business mode and isolated fallback=false.');
  for (const name of ['EXPO_PUBLIC_API_URL', 'EXPO_PUBLIC_SUPABASE_URL']) {
    try {
      const url = new URL(env[name] || '');
      if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash
          || !['','/'].includes(url.pathname)
          || ['localhost','127.0.0.1','10.0.2.2','[::1]'].includes(url.hostname)
          || /your[-_]|replace[-_]|example\.(com|test|invalid)$/.test(url.hostname)) throw new Error();
    } catch { throw new Error(`Set ${name} to the approved HTTPS origin only; placeholders and local URLs are not release targets.`); }
  }
  const key = env.EXPO_PUBLIC_SUPABASE_ANON_KEY || '';
  let clientKey = key.startsWith('sb_publishable_');
  if (!clientKey) try { clientKey = JSON.parse(Buffer.from(key.split('.')[1], 'base64url').toString('utf8')).role === 'anon'; } catch {}
  if (!clientKey || /YOUR_|REPLACE_/i.test(key)) throw new Error('Set EXPO_PUBLIC_SUPABASE_ANON_KEY to a publishable/anon client key only.');
}
module.exports = { checkReleaseEnvironment };
if (require.main === module) {
  try { checkReleaseEnvironment(); console.log('PASS: release client configuration is explicit, public-only and HTTPS.'); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
