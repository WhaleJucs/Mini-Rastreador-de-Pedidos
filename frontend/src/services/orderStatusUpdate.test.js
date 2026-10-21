import assert from 'node:assert/strict';
import test from 'node:test';
import { allowedOrderStatuses, createOrderStatusUpdater, statusUpdateErrorMessage } from './orderStatusUpdate.js';

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function fixture() {
  const calls = [];
  const changes = [];
  const confirmed = [];
  const reloads = [];
  let starts = 0;
  const updater = createOrderStatusUpdater({
    send: (id, payload, { signal }) => {
      const call = { id, payload, signal, ...deferred() };
      calls.push(call);
      return call.promise;
    },
    reload: () => { const call = deferred(); reloads.push(call); return call.promise; },
    onStart: () => { starts += 1; },
    onConfirmed: order => confirmed.push(order),
    onChange: (id, state) => changes.push({ id, state }),
  });
  return { updater, calls, changes, confirmed, reloads, starts: () => starts };
}

const observed = { id: 1, status: 'RECEBIDO', version: 4 };

test('UI destinations match all five approved edges with no terminal or repetition action', () => {
  assert.deepEqual(allowedOrderStatuses('RECEBIDO'), ['EM_PREPARO', 'CANCELADO']);
  assert.deepEqual(allowedOrderStatuses('EM_PREPARO'), ['SAIU_PARA_ENTREGA', 'CANCELADO']);
  assert.deepEqual(allowedOrderStatuses('SAIU_PARA_ENTREGA'), ['ENTREGUE']);
  for (const status of ['ENTREGUE', 'CANCELADO', 'DONE', 'unknown']) {
    assert.deepEqual(allowedOrderStatuses(status), []);
  }
});

test('sends the observed revision unchanged and announces success only after confirmation', async () => {
  const f = fixture();
  const pending = f.updater.submit(observed, 'EM_PREPARO');
  assert.equal(f.starts(), 1);
  assert.deepEqual(f.calls[0].payload, { status: 'EM_PREPARO', expectedVersion: 4 });
  assert.equal(f.calls[0].id, 1);
  assert.equal(f.changes.at(-1).state.isSubmitting, true);
  assert.deepEqual(f.confirmed, []);
  const result = { ...observed, status: 'EM_PREPARO', version: 5 };
  f.calls[0].resolve(result);
  assert.equal(await pending, result);
  assert.deepEqual(f.confirmed, [result]);
  assert.match(f.changes.at(-1).state.success, /Status confirmado: EM_PREPARO/);
  f.updater.dispose();
});

test('blocks duplicate sends per order while allowing a different order to progress', async () => {
  const f = fixture();
  const first = f.updater.submit(observed, 'EM_PREPARO');
  assert.equal(await f.updater.submit(observed, 'CANCELADO'), null);
  const second = f.updater.submit({ ...observed, id: 2 }, 'CANCELADO');
  assert.equal(f.calls.length, 2);
  f.calls[1].resolve({ id: 2, status: 'CANCELADO', version: 5 });
  await second;
  assert.equal(f.calls[0].signal.aborted, false);
  f.calls[0].resolve({ id: 1, status: 'EM_PREPARO', version: 5 });
  await first;
  assert.equal(f.confirmed.length, 2);
  f.updater.dispose();
});

test('invalid destinations never start a mutation', async () => {
  const f = fixture();
  for (const [status, requested] of [['RECEBIDO', 'ENTREGUE'], ['SAIU_PARA_ENTREGA', 'CANCELADO'],
    ['ENTREGUE', 'RECEBIDO'], ['CANCELADO', 'CANCELADO']]) {
    assert.equal(await f.updater.submit({ ...observed, status }, requested), null);
  }
  assert.equal(f.calls.length, 0);
  assert.equal(f.starts(), 0);
  f.updater.dispose();
});

test('conflict requires successful reload and a new explicit intention with its observed revision', async () => {
  const f = fixture();
  const pending = f.updater.submit(observed, 'EM_PREPARO');
  f.calls[0].reject({ response: { status: 409 } });
  await pending;
  assert.match(f.changes.at(-1).state.error, /outra tela/);
  assert.equal(await f.updater.submit(observed, 'EM_PREPARO'), null);
  assert.equal(f.calls.length, 1);
  const failedReload = f.updater.refresh(1);
  assert.equal(await f.updater.refresh(1), false);
  f.reloads[0].resolve(null);
  assert.equal(await failedReload, false);
  assert.equal(await f.updater.submit(observed, 'EM_PREPARO'), null);
  const reload = f.updater.refresh(1);
  const latest = { id: 1, status: 'EM_PREPARO', version: 5 };
  f.reloads[1].resolve([latest]);
  assert.equal(await reload, true);
  assert.equal(f.calls.length, 1);
  const newIntention = f.updater.submit(latest, 'SAIU_PARA_ENTREGA');
  assert.deepEqual(f.calls[1].payload, { status: 'SAIU_PARA_ENTREGA', expectedVersion: 5 });
  f.calls[1].resolve({ ...latest, status: 'SAIU_PARA_ENTREGA', version: 6 });
  await newIntention;
  f.updater.dispose();
});

for (const [name, error, message] of [
  ['busy', { response: { status: 503 } }, /temporariamente ocupado/],
  ['timeout', { code: 'ECONNABORTED' }, /demorou/],
  ['offline', new Error('private network details'), /conexão/],
  ['server', { response: { status: 500 } }, /confirmar/],
  ['malformed response', { name: 'OrdersResponseError' }, /confirmar/],
]) {
  test(`${name} does not retry or publish success and requires reloading`, async () => {
    const f = fixture();
    const pending = f.updater.submit(observed, 'EM_PREPARO');
    f.calls[0].reject(error);
    await pending;
    assert.match(f.changes.at(-1).state.error, message);
    assert.equal(await f.updater.submit(observed, 'EM_PREPARO'), null);
    assert.equal(f.calls.length, 1);
    assert.deepEqual(f.confirmed, []);
    f.updater.dispose();
  });
}

test('controlled error messages do not expose server details', () => {
  for (const [status, expected] of [[400, /recusada/], [401, /sessão/], [404, /não foi encontrado/], [409, /outra tela/]]) {
    const message = statusUpdateErrorMessage({ response: { status, data: { detail: '<b>SQL segredo</b>' } } });
    assert.match(message, expected);
    assert.doesNotMatch(message, /SQL|segredo|<b>/);
  }
});

test('logout or unmount aborts every pending order and ignores late success and failure', async () => {
  const f = fixture();
  const first = f.updater.submit(observed, 'EM_PREPARO');
  const second = f.updater.submit({ ...observed, id: 2 }, 'CANCELADO');
  const count = f.changes.length;
  f.updater.dispose();
  assert.equal(f.calls[0].signal.aborted, true);
  assert.equal(f.calls[1].signal.aborted, true);
  f.calls[0].resolve({ id: 1, status: 'EM_PREPARO', version: 5 });
  f.calls[1].reject(new Error('late failure'));
  await Promise.all([first, second]);
  assert.equal(f.changes.length, count);
  assert.deepEqual(f.confirmed, []);
  assert.equal(await f.updater.submit(observed, 'CANCELADO'), null);
});

test('cleanup during reload cannot revive a previous session and recreation remains usable', async () => {
  const f = fixture();
  const pending = f.updater.submit(observed, 'EM_PREPARO');
  f.calls[0].reject({ response: { status: 409 } });
  await pending;
  const reloading = f.updater.refresh(1);
  const count = f.changes.length;
  f.updater.dispose();
  f.reloads[0].resolve([{ ...observed, version: 5 }]);
  assert.equal(await reloading, false);
  assert.equal(f.changes.length, count);
  const current = fixture();
  const submit = current.updater.submit(observed, 'CANCELADO');
  current.calls[0].resolve({ ...observed, status: 'CANCELADO', version: 5 });
  await submit;
  assert.equal(current.confirmed.length, 1);
  current.updater.dispose();
});
