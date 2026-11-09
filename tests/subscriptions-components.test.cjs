const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const Module = require('node:module');
const ts = require('typescript');
const React = require('react');
const { create, act } = require('react-test-renderer');
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
let actor = 'creator-a',
  current = async () => ({ id: 'free', name: 'Free', is_creator_pro: false, entitlements: [] }),
  claims = async () => [],
  claimCalls = 0;
let billingConfig = { enabled: false, checkout_enabled: false, products: {} };
let nativeModule = null;
const services = {
  subscriptions: {
    plans: async () => [
      { id: 'free', name: 'Free', portfolio_limit: null, premium_discovery: false },
      { id: 'creator_pro', name: 'Creator Pro', portfolio_limit: null, premium_discovery: true },
    ],
    current: () => current(),
    history: async () => [],
    claims: () => claims(),
    claim: async () => {
      claimCalls++;
      return {};
    },
    billingConfiguration: async () => billingConfig,
  },
};
const original = Module._load;
Module._load = function (name, parent, main) {
  if (name === 'react-native')
    return { View: 'View', Text: 'Text', Linking: { openURL: async () => {} } };
  if (name === 'expo-router') return { router: { push() {} } };
  if (name.endsWith('/AuthProvider'))
    return { useAuth: () => ({ session: { user: { id: actor } } }) };
  if (name === '../backend') return { services };
  if (name.endsWith('/components/ui'))
    return {
      Screen: ({ children }) => React.createElement('Screen', null, children),
      Field: (props) => React.createElement('Field', props),
      PrimaryButton: (props) => React.createElement('Button', props),
    };
  if (name === './billingNative') return { loadNativeBilling: async () => nativeModule };
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
const Subscription = require('../src/subscriptions/SubscriptionScreen.tsx').default;
const Founding = require('../src/subscriptions/FoundingOfferScreen.tsx').default;
function text(tree) {
  return JSON.stringify(tree.toJSON());
}
test('plans render without invented prices and unavailable billing remains disabled', async () => {
  let tree;
  await act(async () => {
    tree = create(React.createElement(Subscription));
  });
  assert.match(text(tree), /Creator Pro/);
  assert.match(text(tree), /no booking commission/);
  assert.equal(tree.root.findByProps({ title: 'Purchases unavailable' }).props.disabled, true);
  assert.equal(tree.root.findByProps({ title: 'Restore purchases' }).props.disabled, true);
  await act(() => tree.unmount());
});
test('delayed old-account membership cannot appear after account switch', async () => {
  let resolve, tree;
  actor = 'creator-a';
  current = () => new Promise((r) => (resolve = r));
  await act(async () => {
    tree = create(React.createElement(Subscription));
  });
  actor = 'creator-b';
  current = async () => ({ id: 'free', name: 'Free', is_creator_pro: false, entitlements: [] });
  await act(async () => tree.update(React.createElement(Subscription)));
  await act(async () =>
    resolve({
      id: 'creator_pro',
      name: 'Old account secret membership',
      is_creator_pro: true,
      entitlements: [],
    }),
  );
  assert.doesNotMatch(text(tree), /Old account secret/);
  assert.match(text(tree), /Your membership/);
  await act(() => tree.unmount());
});
test('promotion form rejects untrusted URL and then displays authoritative pending status', async () => {
  actor = 'creator-a';
  claimCalls = 0;
  claims = async () => [];
  let tree;
  await act(async () => (tree = create(React.createElement(Founding))));
  await act(() => tree.root.findByType('Field').props.onChangeText('https://evil.example/post'));
  await act(async () => tree.root.findByProps({ title: 'Submit for review' }).props.onPress());
  assert.equal(claimCalls, 0);
  assert.match(text(tree), /public HTTPS post URL/);
  await act(() =>
    tree.root.findByType('Field').props.onChangeText('https://instagram.com/reel/test-post'),
  );
  claims = async () => [
    { id: 'claim', post_url: 'https://instagram.com/reel/test-post', status: 'pending' },
  ];
  await act(async () => tree.root.findByProps({ title: 'Submit for review' }).props.onPress());
  assert.equal(claimCalls, 1);
  assert.match(text(tree), /pending/);
  await act(() => tree.unmount());
});

test('checkout pause keeps Restore Purchases available in native UI', async () => {
  let restores = 0;
  billingConfig = {
    enabled: true,
    checkout_enabled: false,
    products: { pro: 'annual' },
    package_name: 'com.castly.mobile',
    obfuscated_account_id: 'bound',
  };
  nativeModule = {
    initConnection: async () => true,
    endConnection: async () => true,
    purchaseUpdatedListener: () => ({ remove() {} }),
    purchaseErrorListener: () => ({ remove() {} }),
    fetchProducts: async () => {
      throw new Error('Offers should not load while paused');
    },
    getAvailablePurchases: async () => {
      restores++;
      return [];
    },
    finishTransaction: async () => {},
    requestPurchase: async () => {
      throw new Error('Purchase must remain disabled');
    },
  };
  const { PlayBilling } = require('../src/subscriptions/PlayBilling.tsx');
  let tree;
  await act(async () => {
    tree = create(React.createElement(PlayBilling, { onVerified() {} }));
  });
  assert.equal(tree.root.findByProps({ title: 'Restore purchases' }).props.disabled, false);
  assert.equal(tree.root.findByProps({ title: 'Purchases unavailable' }).props.disabled, true);
  await act(async () => tree.root.findByProps({ title: 'Restore purchases' }).props.onPress());
  assert.equal(restores, 1);
  await act(() => tree.unmount());
  billingConfig = { enabled: false, checkout_enabled: false, products: {} };
  nativeModule = null;
});
