const { Buffer } = require('node:buffer');
const fs = require('node:fs');
const path = require('node:path');
const { parseEnv } = require('node:util');
function checkClientEnvironment(env = process.env, root = process.cwd()) {
  const sources = [env];
  for (const file of [
    '.env',
    '.env.local',
    '.env.development',
    '.env.development.local',
    '.env.production',
    '.env.production.local',
  ]) {
    const target = path.join(root, file);
    if (fs.existsSync(target)) sources.push(parseEnv(fs.readFileSync(target, 'utf8')));
  }
  for (const source of sources)
    for (const [name, value] of Object.entries(source)) {
      if (!name.startsWith('EXPO_PUBLIC_') || !value) continue;
      let forbidden =
        /SERVICE_ROLE|SECRET_KEY/i.test(name) || String(value).startsWith('sb_secret_');
      try {
        forbidden ||=
          JSON.parse(Buffer.from(String(value).split('.')[1], 'base64url').toString('utf8'))
            .role === 'service_role';
      } catch {
        /* Not a JWT. */
      }
      if (forbidden)
        throw new Error(
          `Refusing to bundle privileged credentials in ${name}. Use only the Supabase publishable/anon key.`,
        );
    }
}
module.exports = { checkClientEnvironment };
if (require.main === module) {
  checkClientEnvironment();
  console.log('PASS: no privileged keys in public client environment.');
}
