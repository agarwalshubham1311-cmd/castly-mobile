const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const Module = require('node:module');
const ts = require('typescript');
const React = require('react');
const { create, act } = require('react-test-renderer');
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
let actor = 'creator-a',
  route = { id: 'booking-a' },
  confirmation = async () => ({
    booking: { status: 'completed' },
    confirmed_by_me: true,
    confirmation_count: 2,
    review_eligible: true,
  }),
  own = async () => [],
  writes = [];
const services = {
  trust: {
    completion: () => confirmation(),
    ownReview: () => own(),
    confirmCompletion: async (id, a) => writes.push(['confirm', id, a]),
    submitReview: async (id, r, b, a) => writes.push(['submit', id, a]),
    editReview: async (id, r, b, a) => writes.push(['edit', id, a]),
  },
};
const original = Module._load;
Module._load = function (name, parent, main) {
  if (name === 'expo-router') return { useLocalSearchParams: () => route };
  if (name.endsWith('/AuthProvider'))
    return { useAuth: () => ({ session: { user: { id: actor } } }) };
  if (name === '../backend') return { services };
  if (name.endsWith('/components/ui'))
    return {
      Screen: ({ children }) => React.createElement('Screen', null, children),
      Label: ({ children }) => React.createElement('Label', null, children),
      Field: (props) => React.createElement('Field', props),
      PrimaryButton: (props) => React.createElement('Button', props),
    };
  return original.call(this, name, parent, main);
};
for (const ext of ['.ts', '.tsx'])
  require.extensions[ext] = (module, file) =>
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
const Booking = require('../src/trust/BookingReviewScreen.tsx').default;
test('legacy completed status without both attestations cannot display review submission', async () => {
  confirmation = async () => ({
    booking: { status: 'completed' },
    confirmed_by_me: false,
    confirmation_count: 0,
    review_eligible: false,
  });
  let tree;
  await act(async () => {
    tree = create(React.createElement(Booking, { key: route.id }));
  });
  assert.equal(tree.root.findAllByProps({ title: 'Submit booking review' }).length, 0);
  assert.equal(
    tree.root.findByProps({ title: 'I confirm this project was completed' }).props.disabled,
    false,
  );
  await act(() => tree.unmount());
});
test('route remount and late previous-resource read cannot edit another booking review', async () => {
  let resolve;
  route = { id: 'booking-a' };
  own = () => new Promise((r) => (resolve = r));
  confirmation = async () => ({
    booking: { status: 'completed' },
    confirmed_by_me: true,
    confirmation_count: 2,
    review_eligible: true,
  });
  let tree;
  await act(async () => {
    tree = create(React.createElement(Booking, { key: route.id }));
  });
  route = { id: 'booking-b' };
  own = async () => [];
  await act(async () => tree.update(React.createElement(Booking, { key: route.id })));
  await act(async () =>
    resolve([
      { id: 'old-review', body: 'Old private draft', rating: 1, editable_until: '2099-01-01' },
    ]),
  );
  assert.doesNotMatch(JSON.stringify(tree.toJSON()), /Old private draft/);
  writes = [];
  await act(async () => tree.root.findByProps({ title: 'Submit booking review' }).props.onPress());
  assert.deepEqual(writes, [['submit', 'booking-b', 'creator-a']]);
  await act(() => tree.unmount());
});
