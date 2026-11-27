// The image's initialization server exposes only a Unix socket. Wait for the final
// TCP listener, which is also what the Spring JDBC fixture will use.
function waitForPostgres(run, container, { attempts = 60, pause = pauseSync } = {}) {
  for (let n = 0; n < attempts; n++) {
    try {
      run(['exec', container, 'pg_isready', '-h', '127.0.0.1', '-p', '5432', '-U', 'postgres', '-d', 'postgres']);
      return;
    } catch {
      if (n + 1 < attempts) pause(500);
    }
  }
  throw new Error('Disposable PostgreSQL TCP listener did not become ready');
}
function pauseSync(milliseconds) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds);
}
module.exports = { waitForPostgres };
