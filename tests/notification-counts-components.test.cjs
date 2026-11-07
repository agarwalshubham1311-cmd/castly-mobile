const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const Module = require('node:module');
const ts = require('typescript');
const React = require('react');
const { create, act } = require('react-test-renderer');
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
let complete,
  listener,
  current,
  calls = 0;
const hub = {
  watch: (_topic, _id, callback) => {
    listener = callback;
    return () => {
      listener = undefined;
    };
  },
  enableNotifications: () => {},
  invalidate: () => queueMicrotask(() => listener?.()),
};
const original = Module._load;
Module._load = function (name, parent, main) {
  if (name === '../auth/AuthProvider')
    return { useAuth: () => ({ session: { user: { id: 'owner' } } }) };
  if (name === './LiveProvider') return { useLiveContext: () => ({ hub }) };
  if (name === './index')
    return {
      businessMode: 'spring',
      services: {
        notifications: {
          stats: () => {
            calls++;
            if (calls === 1)
              return new Promise((resolve) => {
                complete = resolve;
              });
            return Promise.resolve({ enabled: true, unread_count: 1, message_unread_count: 1 });
          },
        },
      },
    };
  return original.call(this, name, parent, main);
};
for (const ext of ['.tsx', '.ts'])
  require.extensions[ext] = (module, file) => {
    module._compile(
      ts.transpileModule(fs.readFileSync(file, 'utf8'), {
        compilerOptions: {
          module: ts.ModuleKind.CommonJS,
          jsx: ts.JsxEmit.React,
          esModuleInterop: true,
          target: ts.ScriptTarget.ES2022,
        },
      }).outputText,
      file,
    );
  };
const {
  NotificationProvider,
  useNotifications,
} = require('../src/backend/NotificationProvider.tsx');
function Consumer() {
  const value = useNotifications();
  React.useEffect(() => {
    current = value;
  }, [value]);
  return null;
}
test('count invalidations during an in-flight request schedule one fresh read instead of losing the event', async () => {
  let tree;
  await act(async () => {
    tree = create(React.createElement(NotificationProvider, {}, React.createElement(Consumer)));
  });
  assert.equal(calls, 1);
  void current.refresh();
  void current.refresh();
  assert.equal(calls, 1, 'No parallel count requests');
  await act(async () => {
    complete({ enabled: true, unread_count: 2, message_unread_count: 2 });
  });
  assert.equal(calls, 2, 'One deferred reconciliation after the stale response');
  assert.equal(current.stats.unread_count, 1);
  await act(async () => tree.unmount());
  assert.equal(listener, undefined);
});
