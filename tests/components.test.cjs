const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const Module = require('node:module');
const ts = require('typescript');
const React = require('react');
const { create, act } = require('react-test-renderer');
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
// Native bridge hosts are mocked; these are actual React Native component trees/handlers.
const original = Module._load;
Module._load = function (name, parent, main) {
  if (name === 'react-native')
    return {
      View: 'View',
      Text: 'Text',
      Modal: 'Modal',
      ScrollView: 'ScrollView',
      Pressable: 'Pressable',
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
const { DecisionDialog } = require('../src/components/DecisionDialog.tsx');
const { BookingStatus } = require('../src/components/BookingStatus.tsx');
test('booking confirmation requires an explicit action, supports cancel and disables both controls while busy', async () => {
  let confirms = 0,
    closes = 0,
    tree;
  await act(() => {
    tree = create(
      React.createElement(DecisionDialog, {
        decision: 'cancel',
        busy: false,
        onConfirm: () => confirms++,
        onClose: () => closes++,
      }),
    );
  });
  assert.equal(confirms, 0);
  const confirm = tree.root.findByProps({ accessibilityLabel: 'Confirm cancel booking' }),
    back = tree.root.findByProps({ accessibilityLabel: 'Go back without changes' });
  assert.equal(confirm.props.style.minHeight, 48);
  await act(() => back.props.onPress());
  assert.equal(closes, 1);
  assert.equal(confirms, 0);
  await act(() => confirm.props.onPress());
  assert.equal(confirms, 1);
  await act(() =>
    tree.update(
      React.createElement(DecisionDialog, {
        decision: 'cancel',
        busy: true,
        error: 'Not permitted. Share reference with support.',
        onConfirm: () => confirms++,
        onClose: () => closes++,
      }),
    ),
  );
  assert.ok(
    tree.root
      .findAllByType('Text')
      .some((node) => node.props.children === 'Not permitted. Share reference with support.'),
  );
  assert.equal(
    tree.root.findByProps({ accessibilityLabel: 'Confirm cancel booking' }).props.disabled,
    true,
  );
  assert.equal(
    tree.root.findByProps({ accessibilityLabel: 'Go back without changes' }).props.disabled,
    true,
  );
  await act(() =>
    tree.update(
      React.createElement(DecisionDialog, {
        decision: null,
        busy: false,
        onConfirm: () => confirms++,
        onClose: () => closes++,
      }),
    ),
  );
  assert.equal(tree.toJSON(), null);
  await act(() => tree.unmount());
});
test('booking status is accessible text for every existing server state, with no completion action', async () => {
  for (const status of ['requested', 'accepted', 'declined', 'cancelled', 'completed']) {
    let tree;
    await act(() => {
      tree = create(React.createElement(BookingStatus, { status }));
    });
    const label = tree.root.findByProps({ accessibilityLabel: 'Booking status: ' + status });
    assert.equal(label.props.children, status[0].toUpperCase() + status.slice(1));
    assert.equal(tree.root.findAllByType('Pressable').length, 0);
    await act(() => tree.unmount());
  }
});
