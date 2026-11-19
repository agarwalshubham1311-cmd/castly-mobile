const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const Module = require('node:module');
const ts = require('typescript');
const React = require('react');
const { create, act } = require('react-test-renderer');
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
let actor = 'client-a';
let parse = async () => ({ needs_confirmation: true });
let shortlist = async () => ({ method: 'deterministic', candidates: [] });
const services = { discovery: {
  configuration: async () => ({ advanced_search: true, smart_matching: true,
    ai_recommendations: true, ai_assistance: true, external_provider: false }),
  parse: (...args) => parse(...args), shortlist: (...args) => shortlist(...args),
  advanced: async () => [], brief: async () => ({ draft: '' }), compare: async () => ({ items: [] }),
  forget: async () => {},
} };
const original = Module._load;
Module._load = function (name, parent, main) {
  if (name === 'react-native') return { Pressable: 'Pressable' };
  if (name === 'expo-router') return { router: { push() {} } };
  if (name.endsWith('/AuthProvider')) return { useAuth: () => ({ session: { user: { id: actor } } }) };
  if (name === '../backend') return { services };
  if (name.endsWith('/components/ui')) return {
    Screen: ({ children }) => React.createElement('Screen', null, children),
    Label: ({ children }) => React.createElement('Label', null, children),
    Field: (props) => React.createElement('Field', props),
    PrimaryButton: (props) => React.createElement('Button', props),
  };
  return original.call(this, name, parent, main);
};
for (const ext of ['.ts', '.tsx']) require.extensions[ext] = (module, file) =>
  module._compile(ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.React,
      esModuleInterop: true, target: ts.ScriptTarget.ES2022 },
  }).outputText, file);
const Discovery = require('../src/discovery/DiscoveryScreen.tsx').default;
const text = (tree) => JSON.stringify(tree.toJSON());
const field = (tree, label) => tree.root.findByProps({ label: label });

test('account switch clears private draft and discards delayed prior-account parse', async () => {
  actor = 'client-a';
  let resolve;
  parse = () => new Promise((r) => { resolve = r; });
  let tree;
  await act(async () => { tree = create(React.createElement(Discovery)); });
  await act(() => field(tree, 'Campaign description').props.onChangeText('Private old campaign'));
  await act(() => tree.root.findByProps({ title: 'Parse campaign locally' }).props.onPress());
  actor = 'client-b';
  await act(async () => tree.update(React.createElement(Discovery, { key: actor })));
  await act(async () => resolve({ needs_confirmation: true, city: 'Old account secret city' }));
  assert.doesNotMatch(text(tree), /Private old campaign|Old account secret city/);
  assert.equal(field(tree, 'Campaign description').props.value, '');
  await act(() => tree.unmount());
});

test('changing a confirmed constraint discards delayed shortlist and draft', async () => {
  actor = 'client-a';
  let resolve;
  shortlist = () => new Promise((r) => { resolve = r; });
  let tree;
  await act(async () => { tree = create(React.createElement(Discovery)); });
  const confirms = tree.root.findAllByType('Pressable').filter((node) =>
    node.findAllByType('Label').some((label) => String(label.props.children).includes('I confirm these structured campaign fields')));
  await act(() => confirms[0].props.onPress());
  await act(() => tree.root.findByProps({ title: 'Build confirmed shortlist' }).props.onPress());
  await act(() => field(tree, 'City (public profile label)').props.onChangeText('Pune'));
  await act(async () => resolve({ method: 'deterministic', candidates: [{ id: 'old',
    display_name: 'Old result', category_id: 'actors', city: 'Old city', day_rate: 100,
    skills: [], availability_status: 'unknown' }] }));
  assert.doesNotMatch(text(tree), /Old result/);
  assert.equal(tree.root.findByProps({ title: 'Build confirmed shortlist' }).props.disabled, true);
  await act(() => tree.unmount());
});
