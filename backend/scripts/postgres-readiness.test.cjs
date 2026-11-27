const { test } = require('node:test');
const assert = require('node:assert/strict');
const { waitForPostgres } = require('./postgres-readiness.cjs');

test('a ready initialization socket cannot release migrations while TCP is unavailable', () => {
  let calls = 0;
  const run = (args) => {
    calls++;
    if (args.includes('-h')) throw new Error('Final server not listening');
    return 'Initialization socket accepting connections';
  };
  assert.throws(
    () => waitForPostgres(run, 'castly-fixture-test', { attempts: 3, pause() {} }),
    /TCP listener did not become ready/,
  );
  assert.equal(calls, 3);
});

test('temporary startup failures are retried until the final server accepts connections', () => {
  let calls = 0;
  const waits = [];
  waitForPostgres(
    () => {
      if (++calls < 3) throw new Error('Starting');
      return 'Accepting connections';
    },
    'castly-fixture-test',
    { attempts: 5, pause: (ms) => waits.push(ms) },
  );
  assert.equal(calls, 3);
  assert.deepEqual(waits, [500, 500]);
});

test('an unavailable final server fails closed with a bounded number of probes', () => {
  let calls = 0;
  let waits = 0;
  assert.throws(
    () => waitForPostgres(() => { calls++; throw new Error('Unavailable'); }, 'castly-fixture-test',
      { attempts: 4, pause() { waits++; } }),
    /TCP listener did not become ready/,
  );
  assert.equal(calls, 4);
  assert.equal(waits, 3);
});
