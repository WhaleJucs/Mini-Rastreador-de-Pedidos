import assert from 'node:assert/strict';
import test from 'node:test';
import { createOrdersReader } from './ordersReader.js';

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function readerFixture() {
  const requests = [];
  const snapshots = [];
  const reader = createOrdersReader({
    load: ({ signal }) => {
      const request = { ...deferred(), signal };
      requests.push(request);
      return request.promise;
    },
    onChange: state => snapshots.push(state),
  });
  return { reader, requests, snapshots };
}

test('distinguishes initial loading, loaded empty data and a failed read', async () => {
  const { reader, requests, snapshots } = readerFixture();
  const initial = reader.refresh();
  assert.deepEqual(snapshots.at(-1), { orders: [], hasLoaded: false, isLoading: true, error: null });
  requests[0].reject(new Error('offline'));
  await initial;
  assert.equal(snapshots.at(-1).hasLoaded, false);
  assert.equal(snapshots.at(-1).isLoading, false);
  assert.match(snapshots.at(-1).error, /conexão/);

  const retry = reader.refresh();
  assert.equal(snapshots.at(-1).error, null);
  requests[1].resolve([]);
  await retry;
  assert.deepEqual(snapshots.at(-1), { orders: [], hasLoaded: true, isLoading: false, error: null });
  reader.dispose();
});

test('keeps valid data visible during refresh and after a network failure', async () => {
  const { reader, requests, snapshots } = readerFixture();
  const orders = [{ id: 2 }];
  const first = reader.refresh();
  requests[0].resolve(orders);
  await first;

  const refresh = reader.refresh();
  assert.equal(snapshots.at(-1).orders, orders);
  assert.equal(snapshots.at(-1).isLoading, true);
  requests[1].reject({ response: { status: 503 } });
  await refresh;
  assert.equal(snapshots.at(-1).orders, orders);
  assert.equal(snapshots.at(-1).hasLoaded, true);
  assert.equal(snapshots.at(-1).isLoading, false);
  assert.match(snapshots.at(-1).error, /temporariamente ocupado/);
  reader.dispose();
});

test('ignores an older success even when the transport resolves after cancellation', async () => {
  const { reader, requests, snapshots } = readerFixture();
  const first = reader.refresh();
  const second = reader.refresh();
  assert.equal(requests[0].signal.aborted, true);
  requests[1].resolve([{ id: 2 }]);
  await second;
  const currentState = snapshots.at(-1);
  const count = snapshots.length;
  requests[0].resolve([{ id: 1 }]);
  await first;
  assert.equal(snapshots.length, count);
  assert.equal(snapshots.at(-1), currentState);
  assert.deepEqual(currentState.orders, [{ id: 2 }]);
  reader.dispose();
});

test('an obsolete failure cannot clear the active request loading state or set its error', async () => {
  const { reader, requests, snapshots } = readerFixture();
  const first = reader.refresh();
  const second = reader.refresh();
  const count = snapshots.length;
  requests[0].reject(new Error('resposta antiga'));
  await first;
  assert.equal(snapshots.length, count);
  assert.equal(snapshots.at(-1).isLoading, true);
  assert.equal(snapshots.at(-1).error, null);
  requests[1].resolve([]);
  await second;
  assert.equal(snapshots.at(-1).isLoading, false);
  reader.dispose();
});

test('invalidation prevents a read from before a mutation overwriting a newer refresh', async () => {
  const { reader, requests, snapshots } = readerFixture();
  const beforeMutation = reader.refresh();
  reader.invalidate();
  assert.equal(requests[0].signal.aborted, true);
  const afterMutation = reader.refresh();
  requests[1].resolve([{ id: 3, version: 1 }]);
  await afterMutation;
  const count = snapshots.length;
  requests[0].resolve([{ id: 3, version: 0 }]);
  await beforeMutation;
  assert.equal(snapshots.length, count);
  assert.deepEqual(snapshots.at(-1).orders, [{ id: 3, version: 1 }]);
  reader.dispose();
});

test('disposal prevents pending responses and later calls from updating an ended context', async () => {
  const { reader, requests, snapshots } = readerFixture();
  const pending = reader.refresh();
  reader.dispose();
  assert.equal(requests[0].signal.aborted, true);
  const count = snapshots.length;
  requests[0].resolve([{ id: 1 }]);
  await pending;
  reader.invalidate();
  await reader.refresh();
  assert.equal(snapshots.length, count);
  assert.equal(requests.length, 1);
});

test('a recreated reader works after cleanup without accepting a previous session response', async () => {
  const previous = readerFixture();
  const oldRequest = previous.reader.refresh();
  previous.reader.dispose();
  const current = readerFixture();
  const newRequest = current.reader.refresh();
  current.requests[0].resolve([{ id: 2 }]);
  await newRequest;
  previous.requests[0].resolve([{ id: 1 }]);
  await oldRequest;
  assert.deepEqual(current.snapshots.at(-1).orders, [{ id: 2 }]);
  assert.equal(previous.snapshots.length, 1);
  current.reader.dispose();
});
