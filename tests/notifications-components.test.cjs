const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const Module = require('node:module');
const ts = require('typescript');
const React = require('react');
const { create, act } = require('react-test-renderer');
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
// Real component/hooks; native bridge/router and HTTP boundary are deterministic fixtures.
let revision = 0;
const reads = [],
  routes = [],
  cursors = [];
let countRefreshes = 0;
let unavailable = false;
const rows = Array.from({ length: 51 }, (_, n) => ({
  id: String(n),
  recipient_id: 'owner',
  kind: 'booking_requested',
  booking_id: 'booking-' + n,
  created_at: new Date(200000 - n * 1000).toISOString(),
  read_at: null,
}));
const original = Module._load;
Module._load = function (name, parent, main) {
  if (name === 'react-native') return { View: 'View' };
  if (name === 'expo-router') return { router: { push: (route) => routes.push(route) } };
  if (name === '../auth/AuthProvider')
    return { useAuth: () => ({ session: { user: { id: 'owner' } } }) };
  if (name === '../backend/NotificationProvider')
    return {
      useNotifications: () => ({
        refresh: async () => {
          countRefreshes++;
        },
      }),
    };
  if (name === '../backend/useLiveRefresh')
    return { useLiveRefresh: () => ({ revision, status: 'Connected' }) };
  if (name === '../backend')
    return {
      services: {
        notifications: {
          list: async (before) => {
          if (unavailable) throw new Error('Offline fixture');
            cursors.push(before);
            return before ? rows.slice(50) : rows.slice(0, 50);
          },
          read: async (id, actor) => {
            reads.push({ id, actor });
          const index = rows.findIndex((r) => r.id === id);
          rows[index] = { ...rows[index], read_at: 'read' };
          },
        },
      },
    };
  if (name === '../components/ui')
    return {
      Screen: ({ children }) => React.createElement('Screen', {}, children),
      Label: ({ children }) => React.createElement('Label', {}, children),
      Heading: ({ children }) => React.createElement('Heading', {}, children),
      PrimaryButton: (props) => React.createElement('Button', props),
      EmptyState: (props) => React.createElement('EmptyState', props),
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
const Feed = require('../src/screens/LiveNotifications.tsx').default;
test('notification feed marks read without navigation, retains history on live refresh and opens booking', async () => {
  let tree;
  await act(async () => {
    tree = create(React.createElement(Feed));
  });
  const buttons = (title) =>
    tree.root.findAllByType('Button').filter((n) => n.props.title === title);
  assert.equal(buttons('View booking').length, 50);
  await act(async () => buttons('Load older notifications')[0].props.onPress());
  assert.equal(buttons('View booking').length, 51);
  assert.ok(cursors.includes('49'));
  revision++;
  await act(async () => tree.update(React.createElement(Feed)));
  assert.equal(buttons('View booking').length, 51);
  await act(async () => buttons('Mark as read')[0].props.onPress());
  assert.deepEqual(reads, [{ id: '0', actor: 'owner' }]);
  assert.equal(countRefreshes, 1);
  assert.equal(buttons('Mark as read').length, 50);
  assert.equal(routes.length, 0);
  await act(async () => buttons('View booking')[0].props.onPress());
  assert.deepEqual(routes, ['/booking-detail/booking-0']);
  unavailable = true;
  revision++;
  await act(async () => tree.update(React.createElement(Feed)));
  assert.equal(buttons('View booking').length, 0);
  assert.equal(tree.root.findByType('EmptyState').props.title, 'Notifications unavailable');
  unavailable = false;
  await act(async () => tree.root.findByType('EmptyState').props.onPress());
  assert.equal(buttons('View booking').length, 51);
  await act(async () => tree.unmount());
});
