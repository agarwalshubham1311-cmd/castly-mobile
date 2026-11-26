const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const Module = require('node:module');
const ts = require('typescript');
const React = require('react');
const { create, act } = require('react-test-renderer');
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
let actor = 'account-a',
  reply = async () => ({
    available: true,
    state: 'TEMPORARILY_SUSPENDED',
    explanation: 'Protective hold',
    decisions: [
      {
        id: 'decision-a',
        state: 'TEMPORARILY_SUSPENDED',
        explanation: 'Protective hold',
        notified_at: new Date().toISOString(),
      },
    ],
    appeals: [],
  }),
  commands = [],
  routes = [];
const original = Module._load;
Module._load = function (name, parent, main) {
  if (name === '../backend/errors') return { readableError: (e) => e.message };
  if (name === '../theme')
    return { c: { text: '#241B38', error: '#B23450' }, f: { regular: 'Manrope' } };
  if (name === 'react-native') return { View: 'View', Text: 'Text' };
  if (name === 'expo-crypto') return { randomUUID: () => 'fixture-key' };
  if (name === 'expo-router') return { router: { push: (path) => routes.push(path) } };
  if (name === '../auth/AuthProvider')
    return { useAuth: () => ({ session: { user: { id: actor } } }) };
  if (name === '../backend')
    return {
      services: {
        accountAccess: {
          current: () => reply(),
          appeal: async (command, owner) => commands.push({ command, owner }),
        },
      },
    };
  if (name === '../components/ui')
    return {
      Screen: ({ children }) => React.createElement('Screen', {}, children),
      Heading: ({ children }) => React.createElement('Heading', {}, children),
      Label: ({ children }) => React.createElement('Label', {}, children),
      Field: (p) => React.createElement('Field', p),
      PrimaryButton: (p) => React.createElement('Button', p),
    };
  return original.call(this, name, parent, main);
};
const file = require.resolve('../src/screens/AccountAccess.tsx');
const mod = new Module(file, module);
mod.filename = file;
mod.paths = module.paths;
mod._compile(
  ts.transpile(fs.readFileSync(file, 'utf8'), {
    jsx: ts.JsxEmit.ReactJSX,
    module: ts.ModuleKind.CommonJS,
  }),
  file,
);
const Screen = mod.exports.default;
const output = (t) => JSON.stringify(t.toJSON());
test('limited access preserves obligation, privacy and support navigation and submits an actor-bound appeal', async () => {
  let tree;
  await act(async () => {
    tree = create(React.createElement(Screen));
  });
  assert.match(output(tree), /Protective hold/);
  for (const title of [
    'View existing bookings',
    'Manage membership',
    'Support and disputes',
    'Privacy and security',
  ])
    await act(() => tree.root.findByProps({ title }).props.onPress());
  assert.equal(routes.length, 4);
  await act(() => tree.root.findByProps({ title: 'Choose decision for appeal' }).props.onPress());
  await act(() =>
    tree.root
      .findByProps({ label: 'Appeal statement' })
      .props.onChangeText('Please review this case and my independent evidence.'),
  );
  await act(() =>
    tree.root
      .findByProps({ label: 'Evidence reference (do not paste private documents)' })
      .props.onChangeText('support:case-reference'),
  );
  await act(() => tree.root.findByProps({ title: 'Submit appeal' }).props.onPress());
  assert.equal(commands[0].owner, 'account-a');
  assert.equal(commands[0].command.operation, 'APPEAL_SUBMIT');
  assert.equal(commands[0].command.payload.decision_id, 'decision-a');
  await act(() => tree.unmount());
});
test('delayed access data from a previous account never appears under a new account', async () => {
  let resolve;
  actor = 'account-a';
  reply = () => new Promise((r) => (resolve = r));
  let tree;
  await act(async () => {
    tree = create(React.createElement(Screen));
  });
  actor = 'account-b';
  reply = async () => ({
    available: true,
    state: 'ACTIVE',
    explanation: 'Current account unrestricted',
    decisions: [],
    appeals: [],
  });
  await act(async () => tree.update(React.createElement(Screen)));
  await act(async () =>
    resolve({
      available: true,
      state: 'PERMANENTLY_SUSPENDED',
      explanation: 'Private prior-account decision',
      decisions: [],
      appeals: [],
    }),
  );
  assert.doesNotMatch(output(tree), /Private prior-account/);
  assert.match(output(tree), /Current account unrestricted/);
  await act(() => tree.unmount());
});

test('appeal withdrawal requires explicit confirmation and can be cancelled', async () => {
  actor = 'account-a';
  reply = async () => ({
    available: true,
    state: 'TEMPORARILY_SUSPENDED',
    explanation: 'Protective hold',
    decisions: [],
    appeals: [
      {
        id: 'appeal-a',
        decision_id: 'decision-a',
        version: 1,
        status: 'SUBMITTED',
        due_at: new Date().toISOString(),
        resolution: null,
      },
    ],
  });
  let tree;
  await act(async () => {
    tree = create(React.createElement(Screen));
  });
  const before = commands.length;
  await act(() => tree.root.findByProps({ title: 'Withdraw appeal' }).props.onPress());
  assert.equal(commands.length, before);
  await act(() => tree.root.findByProps({ title: 'Keep my appeal open' }).props.onPress());
  assert.equal(commands.length, before);
  await act(() => tree.root.findByProps({ title: 'Withdraw appeal' }).props.onPress());
  await act(() => tree.root.findByProps({ title: 'Confirm withdrawal' }).props.onPress());
  assert.equal(commands.length, before + 1);
  assert.equal(commands.at(-1).command.operation, 'APPEAL_WITHDRAW');
  await act(() => tree.unmount());
});
