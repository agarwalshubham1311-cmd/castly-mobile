import test from 'node:test';
import assert from 'node:assert/strict';
import {
  BillingSession,
  billingFailure,
  type NativeBilling,
} from '../src/subscriptions/billing.ts';
const config = {
  enabled: true,
  package_name: 'com.castly.fixture',
  obfuscated_account_id: 'bound',
  products: { pro: 'annual' },
};
const purchase = {
  productId: 'pro',
  purchaseToken: 'secret-fixture-token',
  purchaseState: 'purchased',
  obfuscatedAccountIdAndroid: 'bound',
  packageNameAndroid: 'com.castly.fixture',
};
function fixture() {
  let actor = 'creator',
    verified = 0,
    finished = 0,
    removed = 0,
    closed = 0;
  const statuses: string[] = [];
  let updated: ((p: unknown) => void) | undefined;
  const native = {
    initConnection: async () => true,
    endConnection: async () => {
      closed++;
      return true;
    },
    fetchProducts: async () => [
      {
        id: 'pro',
        subscriptionOffers: [
          {
            basePlanIdAndroid: 'annual',
            offerTokenAndroid: 'offer',
            displayPrice: 'store price',
            pricingPhasesAndroid: {
              pricingPhaseList: [
                { formattedPrice: 'store price', billingPeriod: 'P1Y', billingCycleCount: 0 },
              ],
            },
          },
        ],
      },
    ],
    getAvailablePurchases: async () => [purchase],
    requestPurchase: async () => null,
    finishTransaction: async () => {
      finished++;
    },
    purchaseUpdatedListener: (fn: typeof updated) => {
      updated = fn;
      return {
        remove() {
          removed++;
        },
      };
    },
    purchaseErrorListener: () => ({
      remove() {
        removed++;
      },
    }),
  } as unknown as NativeBilling;
  const result = {
    id: 'creator_pro',
    name: 'Creator Pro',
    is_creator_pro: true,
    premium_discovery: true,
    portfolio_limit: null,
    entitlements: [],
    purchase_state: 'active',
    purchase_acknowledged: true,
  };
  const session = new BillingSession(
    native,
    config,
    'creator',
    () => actor,
    async () => {
      verified++;
      return result;
    },
    (s) => statuses.push(s),
  );
  return {
    session,
    native,
    statuses,
    result,
    setActor: (a: string) => (actor = a),
    get verified() {
      return verified;
    },
    get finished() {
      return finished;
    },
    get removed() {
      return removed;
    },
    get closed() {
      return closed;
    },
    emit: () => updated?.(purchase),
  };
}
test('billing queries real offers and duplicate callbacks verify and finish once', async () => {
  const f = fixture();
  await f.session.connect();
  assert.equal(f.session.offers[0].label, 'store price');
  await Promise.all([
    f.session.process(purchase as never, false),
    f.session.process(purchase as never, false),
  ]);
  await f.session.process(purchase as never, false);
  assert.equal(f.verified, 1);
  assert.equal(f.finished, 1);
  assert.equal(f.statuses.at(-1), 'confirmed');
  await f.session.close();
  assert.equal(f.removed, 2);
  assert.equal(f.closed, 1);
});
test('pending purchase never verifies or finishes and account switch denies processing', async () => {
  const f = fixture();
  await f.session.process({ ...purchase, purchaseState: 'pending' } as never, false);
  assert.equal(f.verified, 0);
  assert.equal(f.finished, 0);
  assert.equal(f.statuses.at(-1), 'pending');
  f.setActor('another');
  await assert.rejects(() => f.session.process(purchase as never, false), /account changed/);
  assert.equal(f.verified, 0);
});
test('restore reconciles again even after a completed callback', async () => {
  const f = fixture();
  await f.session.process(purchase as never, false);
  await f.session.restore();
  assert.equal(f.verified, 2);
});
test('verification failure never finishes or reports confirmed and safe retry succeeds', async () => {
  const f = fixture();
  let attempt = 0;
  const session = new BillingSession(
    f.native,
    config,
    'creator',
    () => 'creator',
    async () => {
      if (attempt++ === 0) throw new Error('verification failed');
      return f.result;
    },
    () => {},
  );
  await assert.rejects(() => session.process(purchase as never, false));
  assert.equal(f.finished, 0);
  await session.process(purchase as never, false);
  assert.equal(f.finished, 1);
});
test('disabled billing never queries products or attaches native listeners', async () => {
  const f = fixture();
  const session = new BillingSession(
    f.native,
    { ...config, enabled: false },
    'creator',
    () => 'creator',
    async () => f.result,
    (s) => f.statuses.push(s),
  );
  await session.connect();
  assert.equal(session.offers.length, 0);
  assert.equal(f.statuses.at(-1), 'unavailable');
});

test('cancellation is preserved and raw native errors are never displayed', () => {
  assert.equal(
    billingFailure({ code: 'user-cancelled', message: 'secret-token' }).status,
    'cancelled',
  );
  assert.doesNotMatch(billingFailure(new Error('secret-token')).message, /secret-token/);
});
test('closing while connection starts removes listeners and serializes native teardown', async () => {
  const f = fixture();
  let release!: () => void;
  Object.defineProperty(f.native, 'initConnection', {
    value: () =>
      new Promise<boolean>((resolve) => {
        release = () => resolve(true);
      }),
  });
  const connection = f.session.connect();
  await Promise.resolve();
  const close = f.session.close();
  release();
  await Promise.all([connection, close]);
  assert.equal(f.removed, 2);
  assert.equal(f.closed, 1);
  assert.equal(f.session.offers.length, 0);
});
test('account change during verification prevents finishing and success feedback', async () => {
  const f = fixture();
  let release!: (value: typeof f.result) => void;
  let actor = 'creator';
  const states: string[] = [];
  const session = new BillingSession(
    f.native,
    config,
    'creator',
    () => actor,
    () => new Promise((resolve) => (release = resolve)),
    (state) => states.push(state),
  );
  const processing = session.process(purchase as never, false);
  actor = 'other';
  release(f.result);
  await assert.rejects(processing, /account changed/);
  assert.equal(f.finished, 0);
  assert.equal(states.includes('confirmed'), false);
});
